// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.knowledge.FileBlobSink
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.sha256Hex

/**
 * F07 scale evidence: 50/300/500 locally synthesized text documents and one Office tier.
 *
 * These are local synthetic tiers, not a device capacity promise. Every tier records the longest
 * SQLite transaction hold, the concurrent conversation-write wait and the peak heap of the
 * reconfiguration itself. The pass/fail fact is structural: no CAS source read may happen while a
 * writer transaction is open. Timings are reported as measurements, not as the proof.
 */
class BatchReconfigurationScaleTest {

    private class ObservedConnection(private val delegate: SqlConnection) : SqlConnection {
        private val open = AtomicInteger()
        val casReadsInsideTransaction = AtomicInteger()
        val maxTransactionHoldNanos = AtomicLong()
        val transactionCount = AtomicInteger()
        val queryPlans = mutableListOf<String>()
        val transactionOpen: Boolean get() = open.get() > 0

        override fun execute(sql: String, args: List<Any?>) = delegate.execute(sql, args)
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            if (sql.contains("AS plan_fingerprint")) queryPlans += delegate.query("EXPLAIN QUERY PLAN $sql", args).map { it.string("detail") }
            return delegate.query(sql, args)
        }
        override fun <T> transaction(block: () -> T): T {
            val started = System.nanoTime()
            open.incrementAndGet()
            transactionCount.incrementAndGet()
            try {
                return delegate.transaction(block)
            } finally {
                open.decrementAndGet()
                maxTransactionHoldNanos.updateAndGet { maxOf(it, System.nanoTime() - started) }
            }
        }
    }

    private class ObservedBlobSink(private val delegate: FileBlobSink, private val observed: ObservedConnection) : runtime.mobileagent.knowledge.BlobSink by delegate {
        override fun get(sha256: String): ByteArray? {
            if (observed.transactionOpen) observed.casReadsInsideTransaction.incrementAndGet()
            return delegate.get(sha256)
        }
    }

    /** Peak used-heap sampler; sampling is a measurement, never a synchronisation primitive. */
    private class HeapSampler : AutoCloseable {
        private val stop = AtomicBoolean(false)
        private val thread = Thread {
            while (!stop.get()) {
                peak.updateAndGet { maxOf(it, used()) }
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; name = "f07-heap-sampler" }

        val peak = AtomicLong(0)
        val baseline = AtomicLong(0)

        private fun used(): Long = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

        fun start() {
            settle()
            baseline.set(used())
            peak.set(baseline.get())
            thread.start()
        }

        fun settle() {
            System.gc()
            Thread.sleep(60)
            System.gc()
            Thread.sleep(60)
        }

        /** Peak heap observed above the settled baseline. */
        fun peakDeltaBytes(): Long = (peak.get() - baseline.get()).coerceAtLeast(0)

        override fun close() {
            stop.set(true)
            thread.join(5_000)
        }
    }

    /** Concurrent conversation writes through the real repository; records the longest wait. */
    private class SessionWriter(private val db: SqlConnection, private val conversation: String) : AutoCloseable {
        private val conversations = ConversationRepository(db)
        private val stop = AtomicBoolean(false)
        private val started = CountDownLatch(1)
        val writes = AtomicInteger()
        val maxWaitNanos = AtomicLong()
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        private val thread = Thread {
            started.countDown()
            var index = 0
            while (!stop.get() && index < 400) {
                index++
                val start = System.nanoTime()
                try {
                    conversations.append(conversation, MessageRole.USER, "scale write $index", messageId = "f07-scale-$conversation-$index")
                    writes.incrementAndGet()
                } catch (failure: Throwable) {
                    failures += failure
                }
                maxWaitNanos.updateAndGet { maxOf(it, System.nanoTime() - start) }
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; name = "f07-session-writer" }

        fun start() {
            thread.start()
            started.await(5, TimeUnit.SECONDS)
        }

        override fun close() {
            stop.set(true)
            thread.join(60_000)
        }
    }

    private fun metric(name: String, value: Any) = println("F07METRIC $name=$value")

    /** Text-only tier: N synthetic UTF-8 text documents. */
    @Test
    fun syntheticTextTiersKeepSourceReadsOutOfTheWriterTransaction() {
        listOf(50, 300, 500).forEach { jobs -> tier("text-$jobs", jobs, office = false) }
    }

    /** Office tier: the same shape through a locally synthesized DOCX package. */
    @Test
    fun syntheticOfficeTierKeepsSourceReadsOutOfTheWriterTransaction() {
        tier("office-50", 50, office = true)
    }

    @Test fun aSmallBatchUsesIndexedQueriesWithALargeUnrelatedHistory() {
        tier("text-1-history-10000", 1, office = false, historicalUnits = 10_000)
    }

    private fun tier(name: String, jobs: Int, office: Boolean, historicalUnits: Int = 0) {
        val root = java.nio.file.Files.createTempDirectory("f07-cas-$name").toFile()
        val dbFile = java.nio.file.Files.createTempFile("f07-$name-", ".sqlite").toFile()
        val raw = JdbcSqlConnection("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            Migrations.apply(raw)
            val observed = ObservedConnection(raw)
            val sink = ObservedBlobSink(FileBlobSink(root), observed)
            val repo = KnowledgeRepository(
                db = observed,
                blobs = sink,
                visionModelFingerprint = "target",
                chunkVersion = "chunks-v1",
                automaticStorageMaintenance = false,
            )
            val kb = repo.ensureDefaultBase()
            val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "f07 scale $name")
            repeat(jobs) { index ->
                val bytes = if (office) docx(index) else syntheticText(index)
                val mediaType = if (office) "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "text/plain"
                val displayName = if (office) "office-$index.docx" else "notes-$index.txt"
                val job = repo.importBytes(displayName, mediaType, bytes, false, kb, pauseAt = ImportStage.COPYING)
                repo.bindJobToBatch(batch, job, displayName)
            }
            if (historicalUnits > 0) raw.transaction {
                val old = repo.importBytes("history.txt", "text/plain", "unrelated history".toByteArray(), false, kb, pauseAt = ImportStage.COPYING)
                repeat(historicalUnits) { index ->
                    raw.execute("INSERT INTO pipeline_units(job_id,unit_id,planner_version,page,requires_vision,unit_json,state) VALUES(?,?,?,?,?,?,?)",
                        listOf(old.id, "history-$index", "synthetic", 1, 0, "{}", "SUCCEEDED"))
                    raw.execute("INSERT INTO pipeline_results(job_id,unit_id,target,result_version,cache_key,asset_id,ocr,description,table_markdown,result_type) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        listOf(old.id, "history-$index", "historical", "synthetic", "cache-$index", "asset-$index", "", "", "", "IMAGE"))
                }
            }
            seedConversation(raw, "f07-scale-$name")
            val preview = repo.batchReuseSummary(batch, "target")
            assertEquals(0, preview.newRequests, "$name must plan without provider requests")
            assertEquals(0, preview.unknown, "$name must plan without unknown outcomes")
            assertTrue(preview.localRebuild >= jobs, "$name must plan at least one local unit per document")

            val sampler = HeapSampler()
            val writer = SessionWriter(raw, "f07-scale-$name")
            val failure = AtomicReference<Throwable?>(null)
            val startedAt = System.nanoTime()
            observed.maxTransactionHoldNanos.set(0)
            observed.transactionCount.set(0)
            sampler.start()
            writer.start()
            try {
                runCatching { repo.reconfigureBatchPipeline(batch, "target", preview) }.onFailure { failure.set(it) }
            } finally {
                val elapsed = System.nanoTime() - startedAt
                writer.close()
                sampler.settle()
                val peakDelta = sampler.peakDeltaBytes()
                sampler.close()
                metric("$name.jobs", jobs)
                metric("$name.unrelated_units", historicalUnits)
                metric("$name.reconfigure_ms", elapsed / 1_000_000)
                metric("$name.max_transaction_hold_ms", observed.maxTransactionHoldNanos.get() / 1_000_000)
                metric("$name.transactions", observed.transactionCount.get())
                metric("$name.cas_reads_inside_transaction", observed.casReadsInsideTransaction.get())
                metric("$name.session_writes", writer.writes.get())
                metric("$name.session_write_max_wait_ms", writer.maxWaitNanos.get() / 1_000_000)
                metric("$name.session_write_failures", writer.failures.size)
                metric("$name.peak_heap_delta_bytes", peakDelta)
                metric("$name.db_bytes", dbFile.length())
                metric("$name.cas_bytes", root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            }
            assertNull(failure.get(), "$name reconfiguration must succeed: ${failure.get()}")
            assertEquals(0, observed.casReadsInsideTransaction.get(), "$name must not read sources inside a writer transaction")
            assertTrue(writer.failures.isEmpty(), "$name conversation writes must not fail: ${writer.failures}")
            assertTrue(writer.writes.get() > 0, "$name must overlap at least one conversation write")
            assertEquals(jobs, raw.query("SELECT id FROM import_jobs WHERE batch_id=? AND stage=?", listOf(batch, ImportStage.COPYING.name)).size)
            assertEquals("PROCESSING", raw.query("SELECT state FROM import_batches WHERE id=?", listOf(batch)).single().string("state"))
            assertFalse(observed.maxTransactionHoldNanos.get() <= 0)
            assertTrue(observed.queryPlans.any { it.contains("SEARCH pipeline_units") }, observed.queryPlans.toString())
            assertTrue(observed.queryPlans.any { it.contains("SEARCH pipeline_results") }, observed.queryPlans.toString())
            assertFalse(observed.queryPlans.any { it.contains("SCAN pipeline_units") || it.contains("SCAN pipeline_results") }, observed.queryPlans.toString())
        } finally {
            raw.close()
            dbFile.delete()
            root.deleteRecursively()
        }
    }

    private fun syntheticText(index: Int, size: Int = 4096): ByteArray {
        val unique = "document-$index " + sha256Hex("f07-scale-$index".toByteArray()).repeat(2)
        val filler = "local store citation evidence ".repeat((size / 30).coerceAtLeast(1))
        return (unique + "\n" + filler + "\n").toByteArray()
    }

    /** Minimal namespace-shaped DOCX with text and no embedded media. */
    private fun docx(index: Int): ByteArray {
        val text = ("Office paragraph $index " + "retained native context ".repeat(64)).trim()
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>").toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun seedConversation(db: SqlConnection, conversation: String) {
        db.execute(
            "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf("f07-scale-snapshot", 1, "f07-agent", "prompt", "model", 1, "[]", "[]", "2026-10-13T00:00:00Z"),
        )
        ConversationRepository(db).create("f07-scale-snapshot", "F07 scale", conversation)
    }
}
