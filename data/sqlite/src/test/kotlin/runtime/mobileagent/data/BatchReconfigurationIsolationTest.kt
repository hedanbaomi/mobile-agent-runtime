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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.PipelinePolicy
import runtime.mobileagent.knowledge.sha256Hex

/**
 * F07: a reviewed batch reconfiguration must not read CAS sources, re-parse documents or recompute
 * the plan while it holds the repository lock or an open SQLite write transaction.
 *
 * The harness does not use elapsed-time thresholds as proof. It parks a real CAS read on a latch so
 * the concurrent work is provably ordered around the critical section, and it counts source reads
 * that happen while a writer transaction is open.
 */
class BatchReconfigurationIsolationTest {

    // ---------------------------------------------------------------- harness

    /** Records SQL transaction boundaries so the test can prove what runs inside a write transaction. */
    private class ObservedConnection(private val delegate: SqlConnection) : SqlConnection {
        private val open = AtomicInteger()
        val casReadsInsideTransaction = AtomicInteger()
        val maxTransactionHoldNanos = AtomicLong()
        val transactionCount = AtomicInteger()
        val transactionOpen: Boolean get() = open.get() > 0

        override fun execute(sql: String, args: List<Any?>) = delegate.execute(sql, args)
        override fun query(sql: String, args: List<Any?>) = delegate.query(sql, args)
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

    /** A latch the parked CAS read waits on, plus the facts observed at that moment. */
    private class ParkedRead {
        private val parked = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        val parkedNow = AtomicBoolean(false)
        val parkedInsideTransaction = AtomicBoolean(false)

        fun awaitParked(seconds: Long = 30): Boolean = parked.await(seconds, TimeUnit.SECONDS)
        fun open() = gate.countDown()
        fun park(insideTransaction: Boolean) {
            parkedInsideTransaction.set(insideTransaction)
            parkedNow.set(true)
            parked.countDown()
            try {
                assertTrue(gate.await(120, TimeUnit.SECONDS)) { "the parked CAS read was never released" }
            } finally {
                parkedNow.set(false)
            }
        }
    }

    /** Counts CAS reads and parks one of them so concurrent work can be ordered around it. */
    private class GatedBlobSink(
        private val delegate: BlobSink,
        private val observed: ObservedConnection,
        private val parked: ParkedRead,
    ) : BlobSink by delegate {
        val reads = AtomicInteger()
        val freeReads = AtomicInteger(Int.MAX_VALUE)
        override fun get(sha256: String): ByteArray? {
            reads.incrementAndGet()
            val inside = observed.transactionOpen
            if (inside) observed.casReadsInsideTransaction.incrementAndGet()
            val remaining = freeReads.getAndUpdate { if (it > 0) it - 1 else 0 }
            if (remaining <= 0) parked.park(inside)
            return delegate.get(sha256)
        }
    }

    /** Real conversation writes through ConversationRepository; records how long each one waited. */
    private class SessionWriter(private val db: SqlConnection, private val conversation: String) {
        private val conversations = ConversationRepository(db)
        val writes = AtomicInteger()
        val maxWaitNanos = AtomicLong()
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        fun writeOnce(index: Int): Long {
            val started = System.nanoTime()
            try {
                conversations.append(conversation, MessageRole.USER, "session write $index", messageId = "f07-msg-$index")
                writes.incrementAndGet()
            } catch (failure: Throwable) {
                failures += failure
            }
            val waited = System.nanoTime() - started
            maxWaitNanos.updateAndGet { maxOf(it, waited) }
            return waited
        }
    }

    private class Fixture(
        val raw: JdbcSqlConnection,
        val observed: ObservedConnection,
        val blobs: BlobSink,
        val parked: ParkedRead,
        val batch: String,
        val preview: runtime.mobileagent.knowledge.PipelineReuseSummary,
    ) : AutoCloseable {
        override fun close() = raw.close()
    }

    private fun fixture(jobs: Int = JOBS, chunkVersion: String = "chunks-v1", seedSession: Boolean = false): Fixture {
        val raw = JdbcSqlConnection()
        Migrations.apply(raw)
        val observed = ObservedConnection(raw)
        val parked = ParkedRead()
        val sink = GatedBlobSink(MemoryBlobSink(), observed, parked)
        val repo = repository(observed, sink, chunkVersion)
        val batch = stageTextBatch(repo, jobs)
        val preview = repo.batchReuseSummary(batch, "target")
        if (seedSession) seedConversation(raw, "f07-session-a")
        return Fixture(raw, observed, sink, parked, batch, preview)
    }

    private fun repository(db: SqlConnection, blobs: BlobSink, chunkVersion: String = "chunks-v1"): KnowledgeRepository =
        KnowledgeRepository(
            db = db,
            blobs = blobs,
            visionModelFingerprint = "target",
            chunkVersion = chunkVersion,
            automaticStorageMaintenance = false,
        )

    private fun syntheticText(index: Int, size: Int = 2048): ByteArray {
        val unique = "document-$index " + sha256Hex("f07-$index".toByteArray()).repeat(2)
        val filler = "local store citation evidence ".repeat((size / 30).coerceAtLeast(1))
        return (unique + "\n" + filler + "\n").toByteArray()
    }

    private fun stageTextBatch(repo: KnowledgeRepository, count: Int): String {
        val kb = repo.ensureDefaultBase()
        val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "f07 batch")
        repeat(count) { index ->
            val job = repo.importBytes("notes-$index.txt", "text/plain", syntheticText(index), false, kb, pauseAt = ImportStage.COPYING)
            repo.bindJobToBatch(batch, job, "notes-$index.txt")
        }
        return batch
    }

    private fun seedConversation(db: SqlConnection, conversation: String) {
        db.execute(
            "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf("f07-snapshot", 1, "f07-agent", "prompt", "model", 1, "[]", "[]", "2026-10-13T00:00:00Z"),
        )
        ConversationRepository(db).create("f07-snapshot", "F07 session", conversation)
    }

    private fun metric(name: String, value: Any) = println("F07METRIC $name=$value")

    private fun awaitTrue(deadlineMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
        while (!condition()) {
            if (System.nanoTime() > deadline) return condition()
            Thread.sleep(5)
        }
        return true
    }

    /**
     * The durable marks only the reconfigure writes: staging leaves every one of them unset, so a
     * rejected reconfiguration must leave all three at zero.
     */
    private fun publishedMarks(db: SqlConnection, batch: String): Triple<Int, Int, Int> = Triple(
        db.query("SELECT id FROM import_jobs WHERE batch_id=? AND vision_consent=1", listOf(batch)).size,
        db.query("SELECT id FROM import_items WHERE batch_id=? AND state='QUEUED'", listOf(batch)).size,
        db.query("SELECT p.job_id FROM pipeline_plans p JOIN import_jobs j ON j.id=p.job_id WHERE j.batch_id=?", listOf(batch)).size,
    )

    private fun assertNotReconfigured(db: SqlConnection, batch: String) {
        assertEquals(Triple(0, 0, 0), publishedMarks(db, batch), "a rejected reconfiguration must leave no durable mark")
    }

    /**
     * Runs a reconfiguration with its first CAS read parked, applies [mutate] inside that window and
     * only then releases the read. Returns the failure the caller observed plus the batch id.
     */
    private fun reconfigureAroundMutation(
        chunkVersion: String = "chunks-v1",
        mutate: (SqlConnection, String) -> Unit,
    ): Triple<Throwable?, JdbcSqlConnection, String> {
        val harness = fixture(chunkVersion = chunkVersion)
        val sink = harness.blobs as GatedBlobSink
        val repo = repository(harness.observed, sink, chunkVersion)
        sink.freeReads.set(0)
        val failure = AtomicReference<Throwable?>(null)
        val run = Thread(
            { runCatching { repo.reconfigureBatchPipeline(harness.batch, "target", harness.preview) }.onFailure { failure.set(it) } },
            "f07-reconfigure",
        )
        run.start()
        assertTrue(harness.parked.awaitParked(), "a real CAS read must be parked so the mutation lands inside the window")
        mutate(harness.raw, harness.batch)
        harness.parked.open()
        run.join(60_000)
        assertFalse(run.isAlive, "reconfiguration must not outlive the released read")
        return Triple(failure.get(), harness.raw, harness.batch)
    }

    // ------------------------------------------------- the two RED/GREEN facts

    @Test
    fun planningHoldsNeitherTheRepositoryLockNorTheWriterTransaction() {
        val harness = fixture(seedSession = true)
        harness.use {
            val sink = harness.blobs as GatedBlobSink
            val repo = repository(harness.observed, sink)
            val otherKb = repo.createKnowledgeBase("F07 other")
            val otherBatch = repo.beginBatch(otherKb, ImportBatchKind.FILES, "F07 other batch")
            sink.freeReads.set(0)

            val failure = AtomicReference<Throwable?>(null)
            val run = Thread(
                { runCatching { repo.reconfigureBatchPipeline(harness.batch, "target", harness.preview) }.onFailure { failure.set(it) } },
                "f07-reconfigure",
            )
            run.start()
            assertTrue(harness.parked.awaitParked(), "a real CAS read must be parked so concurrency is ordered, not timed")
            assertFalse(harness.observed.transactionOpen, "the parked read must be outside the writer transaction")

            val executor = Executors.newFixedThreadPool(4)
            val writer = SessionWriter(harness.raw, "f07-session-a")
            try {
                val write = executor.submit<Long> { writer.writeOnce(1) }
                val pause = executor.submit<Long> { timed { repo.pauseBatch(otherBatch) } }
                val delete = executor.submit<Long> { timed { repo.deleteKnowledgeBase(otherKb) } }
                val progress = executor.submit<Long> { timed { repo.batchProgress(harness.batch) } }
                val waits = listOf(write, pause, delete, progress).map { it.get(20, TimeUnit.SECONDS) }
                assertTrue(harness.parked.parkedNow.get(), "the concurrent work must finish while the CAS read is still parked")
                assertEquals(0, writer.failures.size, "the session write must not fail: ${writer.failures}")
                metric("isolation.session_write_ms", waits[0] / 1_000_000)
                metric("isolation.pause_batch_ms", waits[1] / 1_000_000)
                metric("isolation.delete_kb_ms", waits[2] / 1_000_000)
                metric("isolation.batch_progress_ms", waits[3] / 1_000_000)
            } finally {
                harness.parked.open()
                run.join(60_000)
                executor.shutdownNow()
                executor.awaitTermination(30, TimeUnit.SECONDS)
            }
            assertFalse(run.isAlive, "reconfiguration must finish once the parked read is released")
            assertNull(failure.get(), "reconfiguration must succeed: ${failure.get()}")
            assertEquals(0, harness.observed.casReadsInsideTransaction.get(), "no CAS read may happen inside a write transaction")
            assertEquals("PROCESSING", harness.raw.query("SELECT state FROM import_batches WHERE id=?", listOf(harness.batch)).single().string("state"))
            assertEquals(JOBS, publishedMarks(harness.raw, harness.batch).second, "every member item returns to the queue")
        }
    }

    @Test
    fun sourceReadsAndPlanMappingNeverRunInsideTheWriterTransaction() {
        val harness = fixture()
        harness.use {
            val sink = harness.blobs as GatedBlobSink
            val repo = repository(harness.observed, sink)
            // The reviewed preview that produced `harness.preview` is allowed to read sources; the
            // reconfiguration itself must not read any source inside a writer transaction.
            sink.freeReads.set(JOBS)
            val failure = AtomicReference<Throwable?>(null)
            val run = Thread(
                { runCatching { repo.reconfigureBatchPipeline(harness.batch, "target", harness.preview) }.onFailure { failure.set(it) } },
                "f07-reconfigure",
            )
            run.start()
            awaitTrue(60_000) { harness.observed.casReadsInsideTransaction.get() > 0 || !run.isAlive }
            metric("isolation.cas_reads_inside_transaction", harness.observed.casReadsInsideTransaction.get())
            metric("isolation.parked_inside_transaction", harness.parked.parkedInsideTransaction.get())
            metric("isolation.max_transaction_hold_ms", harness.observed.maxTransactionHoldNanos.get() / 1_000_000)
            harness.parked.open()
            run.join(60_000)
            assertNull(failure.get(), "reconfiguration must succeed: ${failure.get()}")
            assertEquals(
                0,
                harness.observed.casReadsInsideTransaction.get(),
                "CAS source reads must not run inside the writer transaction (observed " +
                    "${harness.observed.casReadsInsideTransaction.get()} read(s) inside, max transaction hold " +
                    "${harness.observed.maxTransactionHoldNanos.get() / 1_000_000} ms)",
            )
        }
    }

    // ------------------------------------------- commit-time re-verification

    @Test
    fun cancelledBatchIsNotRestartedByALateReconfiguration() {
        val (failure, raw, batch) = reconfigureAroundMutation { db, id ->
            db.execute("UPDATE import_batches SET state=?, error=? WHERE id=?", listOf("CANCELLED", "Cancelled by user", id))
            db.execute("UPDATE import_jobs SET stage=? WHERE batch_id=?", listOf(ImportStage.CANCELLED.name, id))
        }
        raw.use { db ->
            assertNotNull(failure, "a batch cancelled during planning must not be reconfigured")
            assertEquals("CANCELLED", db.query("SELECT state FROM import_batches WHERE id=?", listOf(batch)).single().string("state"))
            assertNotReconfigured(db, batch)
        }
    }

    @Test
    fun deletedKnowledgeBaseIsNotRepublished() {
        val (failure, raw, batch) = reconfigureAroundMutation { db, _ ->
            db.execute("UPDATE knowledge_bases SET deleted_at=?", listOf("2026-10-13T00:00:00Z"))
        }
        raw.use { db ->
            assertTrue(db.query("SELECT deleted_at FROM knowledge_bases").all { it.string("deleted_at").isNotBlank() })
            assertNotNull(failure, "a knowledge base deleted during planning must not be reconfigured (batch state " +
                "${db.query("SELECT state FROM import_batches WHERE id=?", listOf(batch)).single().string("state")})")
            assertNotReconfigured(db, batch)
        }
    }

    @Test
    fun changedDocumentRevisionIsNotRepublishedUnderAnOldPreview() {
        // The same blob hash can be re-published under a new revision; the reviewed plan must not be
        // committed against a revision the user never reviewed.
        val (failure, raw, batch) = reconfigureAroundMutation { db, _ ->
            db.query("SELECT id FROM documents").forEach { row ->
                val next = "f07-revision-" + row.string("id")
                db.execute(
                    "INSERT OR IGNORE INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES(?,?,?,?,?,?)",
                    listOf(next, row.string("id"), "text-utf8-v1", sha256Hex(next.toByteArray()), "READY", "2026-10-13T00:00:00Z"),
                )
                db.execute("UPDATE documents SET active_version_id=? WHERE id=?", listOf(next, row.string("id")))
            }
        }
        raw.use { db ->
            assertNotNull(failure, "a document revision change during planning must reject the reconfiguration")
            assertNotReconfigured(db, batch)
            assertTrue(db.query("SELECT active_version_id FROM documents").all { it.string("active_version_id").startsWith("f07-revision-") })
        }
    }

    @Test
    fun changedGenerationIsNotRepublishedUnderAnOldPreview() {
        val (failure, raw, batch) = reconfigureAroundMutation { db, _ ->
            db.query("SELECT id FROM knowledge_bases").forEach { row ->
                db.execute("UPDATE knowledge_bases SET active_generation_id=? WHERE id=?", listOf("f07-generation-next", row.string("id")))
            }
        }
        raw.use { db ->
            assertNotNull(failure, "a generation change during planning must reject the reconfiguration")
            assertNotReconfigured(db, batch)
            assertTrue(db.query("SELECT active_generation_id FROM knowledge_bases").all { it.string("active_generation_id") == "f07-generation-next" })
        }
    }

    @Test
    fun changedPipelinePlanFingerprintIsNotRepublishedUnderAnOldPreview() {
        val (failure, raw, batch) = reconfigureAroundMutation { db, _ ->
            db.query("SELECT id FROM import_jobs").forEach { row ->
                db.execute(
                    "INSERT OR REPLACE INTO pipeline_plans(job_id,content_hash,planner_version,result_version,plan_hash) VALUES(?,?,?,?,?)",
                    listOf(row.string("id"), "f07-concurrent", "planner-concurrent", "result-concurrent", "plan-concurrent"),
                )
            }
        }
        raw.use { db ->
            assertNotNull(failure, "a pipeline plan change during planning must reject the reconfiguration")
            assertEquals(Triple(0, 0, JOBS), publishedMarks(db, batch), "concurrent plans survive; no consent or queue publication occurs")
            assertTrue(db.query("SELECT planner_version FROM pipeline_plans").all { it.string("planner_version") == "planner-concurrent" })
        }
    }

    // ------------------------------------------------------ kept contracts

    /** A parked reconfiguration released against unchanged durable state still publishes. */
    @Test
    fun unchangedDurableStateStillPublishesTheReviewedPlan() {
        val harness = fixture()
        harness.use {
            val sink = harness.blobs as GatedBlobSink
            val repo = repository(harness.observed, sink)
            sink.freeReads.set(0)
            val failure = AtomicReference<Throwable?>(null)
            val run = Thread(
                { runCatching { repo.reconfigureBatchPipeline(harness.batch, "target", harness.preview) }.onFailure { failure.set(it) } },
                "f07-reconfigure",
            )
            run.start()
            assertTrue(harness.parked.awaitParked())
            harness.parked.open()
            run.join(60_000)
            assertNull(failure.get(), "reconfiguration must publish when nothing changed: ${failure.get()}")
            assertEquals("PROCESSING", harness.raw.query("SELECT state FROM import_batches WHERE id=?", listOf(harness.batch)).single().string("state"))
            assertEquals(Triple(JOBS, JOBS, JOBS), publishedMarks(harness.raw, harness.batch))
            assertEquals(JOBS, harness.raw.query("SELECT job_id FROM pipeline_plans").size, "the reviewed plan is materialized")
            assertEquals(
                "target",
                harness.raw.query("SELECT vision_target FROM import_batches WHERE id=?", listOf(harness.batch)).single().string("vision_target"),
                "the reviewed destination is durable",
            )
            assertEquals(0, harness.raw.query("SELECT request_id FROM pipeline_attempts").size, "reconfiguration itself never dispatches")
            assertEquals(0, harness.raw.query("SELECT cache_key FROM pipeline_results").size)
        }
    }

    /** A failure inside the publish transaction must leave no partially reconfigured batch. */
    @Test
    fun failedPublishLeavesNoPartialReconfiguration() {
        val harness = fixture()
        harness.use {
            val sink = harness.blobs as GatedBlobSink
            var plans = 0
            val failing = object : SqlConnection by harness.raw {
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql.startsWith("INSERT OR REPLACE INTO pipeline_plans") && ++plans == 3) error("F07 injected publish failure")
                    harness.raw.execute(sql, args)
                }
            }
            val repo = repository(failing, sink)
            assertThrows(IllegalStateException::class.java) {
                repo.reconfigureBatchPipeline(harness.batch, "target", harness.preview)
            }
            assertNotReconfigured(harness.raw, harness.batch)
            assertTrue(
                harness.raw.query("SELECT vision_target FROM import_batches WHERE id=?", listOf(harness.batch)).single().string("vision_target").isBlank(),
            )
        }
    }

    /** Reconfiguration must not replay provider work or drop successful/unknown receipts. */
    @Test
    fun reconfigurePreservesProviderReceiptsAndUnknownGates() {
        val harness = fixture()
        harness.use {
            val repo = repository(harness.observed, harness.blobs)
            harness.raw.execute(
                "INSERT INTO vision_results(cache_key,asset_hash,context_hash,model_fingerprint,prompt_version,schema_version,status,ocr_text,description,table_markdown,result_type,processed_at) VALUES('f07-cache','f07-asset','f07-context','target','p1','s1','UNKNOWN_OUTCOME','','','','','2026-10-13T00:00:00Z')",
            )
            val resultsBefore = harness.raw.query("SELECT cache_key,status FROM vision_results ORDER BY cache_key")
                .map { it.string("cache_key") to it.string("status") }
            val attemptsBefore = harness.raw.query("SELECT COUNT(*) AS n FROM pipeline_attempts").single().long("n")
            repo.configureBatchPipeline(harness.batch, PipelinePolicy(maxConcurrency = 2))
            repo.reconfigureBatchPipeline(harness.batch, "target", harness.preview)
            assertEquals(
                resultsBefore,
                harness.raw.query("SELECT cache_key,status FROM vision_results ORDER BY cache_key").map { it.string("cache_key") to it.string("status") },
            )
            assertEquals(attemptsBefore, harness.raw.query("SELECT COUNT(*) AS n FROM pipeline_attempts").single().long("n"))
            assertEquals(1, harness.raw.query("SELECT cache_key FROM vision_results WHERE status='UNKNOWN_OUTCOME'").size, "an unknown receipt survives reconfiguration")
            assertEquals(2, repo.batchPipelinePolicy(harness.batch).maxConcurrency, "the reviewed dispatch policy survives reconfiguration")
        }
    }

    private fun timed(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }

    private companion object {
        const val JOBS = 6
    }
}
