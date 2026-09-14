// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.PdfPageRasterizer
import runtime.mobileagent.knowledge.PdfParser
import runtime.mobileagent.knowledge.RenderedPdfPage
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionDiagnosticMetadata
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VisionSuccess

/**
 * P1 recovery regressions for the two things a Vision retry must never do: replay an uncertain
 * provider call, and release a cache barrier that belongs to a different destination or to a
 * different image context.
 *
 * Every scenario runs against the real schema in [JdbcSqlConnection] and a real in-process import
 * with [MemoryBlobSink]; no user database is touched and no provider is contacted.
 */
class KnowledgeVisionRecoveryIsolationTest {

    /**
     * An explicit retry of one job may only release the barrier of its own (destination, asset,
     * context) cache identity.  The same image bytes used elsewhere stay gated.
     */
    @Test
    fun retryUnknownVisionReleasesOnlyItsOwnDestinationAssetAndContextBarrier() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        var calls = 0
        val backend = VisionBackend { _ ->
            calls += 1
            if (calls == 1) VisionOutcome.UnknownOutcome
            else VisionOutcome.Success(VisionSuccess("recovered ocr", "recovered visual"))
        }
        val repo = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-target-a")
        val kb = repo.ensureDefaultBase()
        val batch = stagedBatch(
            repo,
            kb,
            "unknown isolation",
            listOf(Triple("figure.pdf", "application/pdf", visionPdf("isolated"))),
        )
        repo.authorizeBatchVision(batch, "vision-target-a")
        repo.processBatch(batch, false)

        val jobId = repo.listBatchItemViews(batch).single().jobId!!
        assertEquals(1, repo.batchProgress(batch).unknown)
        val own = db.query("SELECT cache_key, asset_hash, context_hash FROM vision_results WHERE status = 'UNKNOWN_OUTCOME'").single()

        // Same asset bytes, another context (for example the same logo used by another document).
        insertVisionRow(db, "other-context-cache-key", own.string("asset_hash"), "context-of-another-place", "vision-target-a")
        // Same asset and context, another destination identity.
        insertVisionRow(db, "other-target-cache-key", own.string("asset_hash"), own.string("context_hash"), "vision-target-b")
        assertEquals(3, count(db, "vision_results"))

        val retried = repo.retryUnknownVision(jobId, true, "vision-target-a")
        assertEquals(ImportStage.READY, retried.stage, retried.error)
        assertEquals(2, calls, "only this job's own unknown page may be dispatched by an explicit retry")
        assertEquals(
            "SUCCESS",
            db.query("SELECT status FROM vision_results WHERE cache_key = ?", listOf(own.string("cache_key"))).single().string("status"),
        )
        val siblings = db.query(
            "SELECT cache_key, status FROM vision_results WHERE cache_key IN (?, ?) ORDER BY cache_key",
            listOf("other-context-cache-key", "other-target-cache-key"),
        )
        assertEquals(2, siblings.size)
        for (row in siblings) {
            assertEquals("UNKNOWN_OUTCOME", row.string("status"), "cache barrier ${row.string("cache_key")} must survive another job's retry")
        }
        assertEquals(1, repo.batchProgress(batch).published)
        assertEquals(0, repo.batchProgress(batch).unknown)
    }

    /**
     * A destination change is adopted from the legacy fingerprint only when the mapping is
     * unambiguous.  Successful pages are reused with zero transport, an adopted UNKNOWN keeps its
     * manual gate, and only the unknown page may be re-sent after an explicit retry.
     */
    @Test
    fun fullFingerprintAdoptsLegacyCheckpointsWithoutReplayAndResendsOnlyTheUnknownPage() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val rasterizer = pageRasterizer()
        val pdf = threePagePdf()
        val legacyTarget = "legacy-vision-target"
        val fullTarget = "vision-target-full|profile:model|config:hash"
        val legacyCalls = mutableListOf<Int>()
        val legacyBackend = VisionBackend { input ->
            legacyCalls += input.page ?: -1
            if (input.page == 3) VisionOutcome.UnknownOutcome
            else VisionOutcome.Success(VisionSuccess("page ${input.page}", "legacy visual ${input.page}"))
        }
        val legacyRepo = KnowledgeRepository(
            db,
            blobs,
            vision = legacyBackend,
            visionModelFingerprint = legacyTarget,
            pdfRasterizer = rasterizer,
        )
        val sourceKb = legacyRepo.ensureDefaultBase()
        val sourceBatch = stagedBatch(
            legacyRepo,
            sourceKb,
            "legacy source",
            listOf(Triple("three.pdf", "application/pdf", pdf)),
        )
        legacyRepo.authorizeBatchVision(sourceBatch, legacyTarget)
        legacyRepo.processBatch(sourceBatch, false)
        assertEquals(listOf(1, 2, 3), legacyCalls)
        assertEquals(2, countWhere(db, "vision_results", "model_fingerprint = '$legacyTarget' AND status = 'SUCCESS'"))
        assertEquals(1, countWhere(db, "vision_results", "model_fingerprint = '$legacyTarget' AND status = 'UNKNOWN_OUTCOME'"))

        val newCalls = mutableListOf<Int>()
        val newBackend = VisionBackend { input ->
            newCalls += input.page ?: -1
            VisionOutcome.Success(VisionSuccess("page ${input.page}", "new visual ${input.page}"))
        }
        fun repository() = KnowledgeRepository(
            db,
            blobs,
            vision = newBackend,
            visionModelFingerprint = fullTarget,
            pdfRasterizer = rasterizer,
            legacyVisionCacheTarget = { target ->
                if (target == fullTarget) LegacyVisionCacheTarget(legacyTarget, unambiguous = true) else null
            },
        )
        val targetKb = repository().createKnowledgeBase("legacy adoption")
        val adoptedBatch = stagedBatch(
            repository(),
            targetKb,
            "legacy adoption",
            listOf(Triple("three.pdf", "application/pdf", pdf)),
        )
        repository().authorizeBatchVision(adoptedBatch, fullTarget)
        repository().processBatch(adoptedBatch, false)

        // Same material under the full new identity: every page is adopted from the legacy
        // checkpoint, including the page whose legacy outcome is still unknown.  Zero transport.
        assertEquals(emptyList<Int>(), newCalls, "adopting legacy checkpoints must not dispatch")
        // Publication counts documents, not cached pages: this one document is still incomplete.
        assertEquals(0, repository().batchProgress(adoptedBatch).published)
        assertEquals(1, repository().batchProgress(adoptedBatch).unknown)
        val adoptedJob = repository().listBatchItemViews(adoptedBatch).single().jobId!!
        val adoptedRows = db.query("SELECT status FROM vision_results WHERE model_fingerprint = ?", listOf(fullTarget))
        assertEquals(3, adoptedRows.size)
        assertEquals(2, adoptedRows.count { it.string("status") == "SUCCESS" })
        assertEquals(1, adoptedRows.count { it.string("status") == "UNKNOWN_OUTCOME" })

        // A repeated delivery and a process restart must not replay the adopted uncertain page.
        repository().processBatch(adoptedBatch, false)
        assertEquals(emptyList<Int>(), newCalls)
        db.execute("UPDATE import_jobs SET stage = 'VISION_PROCESSING', error = NULL WHERE batch_id = ?", listOf(adoptedBatch))
        db.execute("UPDATE import_items SET state = 'PROCESSING', error = NULL WHERE batch_id = ?", listOf(adoptedBatch))
        db.execute("UPDATE import_batches SET state = 'PROCESSING', error = NULL WHERE id = ?", listOf(adoptedBatch))
        val restarted = repository()
        restarted.recoverableBatchIds().forEach { restarted.processBatch(it, false) }
        assertEquals(emptyList<Int>(), newCalls, "an adopted legacy UNKNOWN must never be replayed automatically")

        // Only an explicit retry may re-send the unknown page, and it re-sends nothing else.
        val retried = repository().retryUnknownVision(adoptedJob, true, fullTarget)
        assertEquals(ImportStage.READY, retried.stage, retried.error)
        assertEquals(listOf(3), newCalls)
        assertEquals(1, repository().batchProgress(adoptedBatch).published)
        assertEquals(0, repository().batchProgress(adoptedBatch).unknown)
    }

    /**
     * A legacy destination fingerprint that maps to more than one current configuration is not an
     * authority: the item fails closed, nothing is dispatched and every existing row is preserved.
     */
    @Test
    fun ambiguousLegacyDestinationFailsClosedWithoutDispatchAndKeepsExistingRows() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val legacyTarget = "legacy-vision-target"
        val fullTarget = "vision-target-full|profile:model|config:hash"
        val legacyRepo = KnowledgeRepository(
            db,
            blobs,
            vision = VisionBackend { VisionOutcome.UnknownOutcome },
            visionModelFingerprint = legacyTarget,
        )
        val sourceKb = legacyRepo.ensureDefaultBase()
        val sourceBatch = stagedBatch(
            legacyRepo,
            sourceKb,
            "ambiguous source",
            listOf(Triple("figure.pdf", "application/pdf", visionPdf("ambiguous"))),
        )
        legacyRepo.authorizeBatchVision(sourceBatch, legacyTarget)
        legacyRepo.processBatch(sourceBatch, false)
        val legacyRow = db.query("SELECT cache_key, status FROM vision_results").single()
        assertEquals("UNKNOWN_OUTCOME", legacyRow.string("status"))

        val dispatches = AtomicInteger()
        val repo = KnowledgeRepository(
            db,
            blobs,
            vision = VisionBackend { dispatches.incrementAndGet(); VisionOutcome.Success(VisionSuccess("new", "new")) },
            visionModelFingerprint = fullTarget,
            legacyVisionCacheTarget = { target ->
                if (target == fullTarget) LegacyVisionCacheTarget(legacyTarget, unambiguous = false) else null
            },
        )
        val kb = repo.createKnowledgeBase("ambiguous destination")
        val batch = stagedBatch(
            repo,
            kb,
            "ambiguous destination",
            listOf(Triple("figure.pdf", "application/pdf", visionPdf("ambiguous"))),
        )
        repo.authorizeBatchVision(batch, fullTarget)
        repo.processBatch(batch, false)

        assertEquals(0, dispatches.get(), "an ambiguous legacy mapping must dispatch nothing")
        val item = repo.listBatchItemViews(batch).single()
        assertTrue(
            item.error.orEmpty().contains("LEGACY_VISION_TARGET_AMBIGUOUS"),
            "expected the ambiguous legacy reason, got: ${item.error}",
        )
        assertEquals(
            "UNKNOWN_OUTCOME",
            db.query("SELECT status FROM vision_results WHERE cache_key = ?", listOf(legacyRow.string("cache_key"))).single().string("status"),
        )
        assertEquals(0, countWhere(db, "vision_results", "model_fingerprint = '$fullTarget'"), "no new cache row may be written")
    }

    /**
     * The pre-dispatch gate must cancel locally without entering the transport and without leaving
     * an UNKNOWN barrier behind; only an explicit resume may continue the member.
     */
    @Test
    fun pauseDuringBackendPreparationCancelsLocallyWithoutAnUnknownBarrierAndResumeContinues() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val transports = AtomicInteger()
        var pauseOnce = true
        var firstGateRejected: Boolean? = null
        lateinit var repo: KnowledgeRepository
        lateinit var batch: String
        val backend = VisionBackend { input ->
            if (pauseOnce) {
                pauseOnce = false
                assertNotNull(repo.pauseBatch(batch), "the pause must persist before the gate is consulted")
            }
            val allowed = input.beforeDispatch()
            if (firstGateRejected == null) firstGateRejected = !allowed
            if (!allowed) {
                VisionOutcome.Failed(
                    "REQUEST_CANCELLED",
                    VisionDiagnosticMetadata(errorCode = "REQUEST_CANCELLED", dispatched = false),
                )
            } else {
                transports.incrementAndGet()
                VisionOutcome.Success(VisionSuccess("resumed ocr", "resumed visual"))
            }
        }
        repo = KnowledgeRepository(db, blobs, vision = backend, visionModelFingerprint = "vision-test")
        batch = stagedBatch(
            repo,
            repo.ensureDefaultBase(),
            "pre-dispatch pause",
            listOf(Triple("figure.pdf", "application/pdf", visionPdf("gate"))),
        )
        repo.authorizeBatchVision(batch, "vision-test")
        repo.processBatch(batch, false)

        assertTrue(firstGateRejected == true, "the pre-dispatch gate must observe the persisted pause")
        assertEquals(ImportBatchState.PAUSED, repo.findBatch(batch)!!.state)
        assertEquals(0, transports.get(), "a rejected pre-dispatch gate must not enter the transport")
        val cacheStatus = db.query("SELECT status FROM vision_results").single().string("status")
        assertEquals("FAILED", cacheStatus, "a local cancel must not leave an UNKNOWN no-replay barrier")
        assertTrue(cacheStatus != "UNKNOWN_OUTCOME")

        // A failed resume must not strand a PROCESSING batch with a still-PAUSED job.
        db.execute("CREATE TRIGGER fail_paused_rearm BEFORE UPDATE OF stage ON import_jobs " +
            "WHEN OLD.stage = 'PAUSED' AND NEW.stage = 'COPYING' " +
            "BEGIN SELECT RAISE(ABORT, 'fixture resume interruption'); END")
        assertTrue(runCatching { repo.resumeBatch(batch) }.isFailure)
        assertEquals(ImportBatchState.PAUSED, repo.findBatch(batch)!!.state)
        assertEquals("PAUSED", db.query("SELECT stage FROM import_jobs WHERE batch_id = ?", listOf(batch)).single().string("stage"))
        db.execute("DROP TRIGGER fail_paused_rearm")

        // Only an explicit resume re-arms the member.
        assertNotNull(repo.resumeBatch(batch), "a paused batch must be resumable")
        repo.processBatch(batch, false)
        assertEquals(1, transports.get(), "an explicit resume must re-arm the locally cancelled member")
        assertEquals(1, repo.batchProgress(batch).published)
    }

    private fun threePagePdf(): ByteArray {
        val drawing = "0 0 100 100 re f\n"
        return buildString {
            append("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
            append("2 0 obj << /Type /Pages /Count 3 /Kids [3 0 R 4 0 R 5 0 R] >> endobj\n")
            for (page in 1..3) {
                append("${page + 2} 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Contents ${page + 5} 0 R >> endobj\n")
                append("${page + 5} 0 obj << /Length ${drawing.length} >>\nstream\n${drawing}endstream\nendobj\n")
            }
            append("trailer << /Root 1 0 R >>\n%%EOF")
        }.toByteArray()
    }

    private fun pageRasterizer(): PdfPageRasterizer = PdfPageRasterizer { _, pages ->
        pages.map { page -> RenderedPdfPage(page, byteArrayOf(page.toByte()), "image/png", 1, 1) }
    }

    private fun visionPdf(marker: String): ByteArray = PdfParser.writePdfWithImageXObject(marker)

    /** Stage one durable batch whose members are already copied into the local CAS. */
    private fun stagedBatch(
        repo: KnowledgeRepository,
        knowledgeBaseId: String,
        label: String,
        entries: List<Triple<String, String, ByteArray>>,
    ): String {
        val batchId = repo.beginBatch(knowledgeBaseId, ImportBatchKind.FILES, label)
        entries.forEach { (name, mime, bytes) ->
            val job = repo.importBytes(
                displayName = name,
                mediaType = mime,
                bytes = bytes,
                visionConfigured = false,
                knowledgeBaseId = knowledgeBaseId,
                pauseAt = ImportStage.COPYING,
            )
            repo.bindJobToBatch(batchId, job, name)
        }
        return batchId
    }

    private fun insertVisionRow(
        db: JdbcSqlConnection,
        cacheKey: String,
        assetHash: String,
        contextHash: String,
        target: String,
    ) {
        db.execute(
            "INSERT OR REPLACE INTO vision_results(cache_key,asset_hash,context_hash,model_fingerprint," +
                "prompt_version,schema_version,status,ocr_text,description,table_markdown,result_type,processed_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                cacheKey, assetHash, contextHash, target,
                "vision-prompt-v1", "vision-result-v2", "UNKNOWN_OUTCOME", "", "", "", "", "2026-09-14T00:00:00Z",
            ),
        )
    }

    private fun count(db: JdbcSqlConnection, table: String): Int =
        db.query("SELECT COUNT(*) AS n FROM $table").single().long("n").toInt()

    private fun countWhere(db: JdbcSqlConnection, table: String, where: String): Int =
        db.query("SELECT COUNT(*) AS n FROM $table WHERE $where").single().long("n").toInt()
}
