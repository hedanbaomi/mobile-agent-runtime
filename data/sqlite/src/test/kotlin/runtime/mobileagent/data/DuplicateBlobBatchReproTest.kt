// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VisionSuccess

/**
 * Regression for the `t-dupimg` acceptance finding: a batch of two byte-identical members
 * (`a.jpg`, `b.jpg`) reported "Completed 2/2" while the second member silently duplicated the
 * whole pipeline - a second vision upload, a second document version and duplicate chunks on
 * the same document.  Both members must still report PUBLISHED (the content is genuinely in
 * the knowledge base), but the pipeline work may only happen once.
 */
class DuplicateBlobBatchReproTest {
    @Test
    fun byteIdenticalMembersReusePublishedDocument() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val visionCalls = AtomicInteger()
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            visionModelFingerprint = "vision-test",
            vision = VisionBackend { input ->
                visionCalls.incrementAndGet()
                VisionOutcome.Success(
                    VisionSuccess("ocr-${input.assetHash.take(6)}", "vision-marker-${input.assetHash.take(6)}"),
                )
            },
        )
        val kb = repo.ensureDefaultBase()
        val batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "t-dupimg")
        val payload = "identical-image-bytes".toByteArray()
        listOf("a.jpg", "b.jpg").forEach { name ->
            val job = repo.importBytes(
                displayName = name,
                mediaType = "image/jpeg",
                bytes = payload,
                visionConfigured = false,
                knowledgeBaseId = kb,
                pauseAt = ImportStage.COPYING,
            )
            repo.bindJobToBatch(batchId, job, name)
        }
        repo.authorizeBatchVision(batchId, "vision-test")
        repo.processBatch(batchId, visionConfigured = false)

        val batch = repo.findBatch(batchId)!!
        assertEquals(ImportBatchState.COMPLETED, batch.state, batch.error)
        val progress = repo.batchProgress(batchId)
        assertEquals(2, progress.total)
        assertEquals(2, progress.published, "both members are published; the duplicate shares the document")

        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM documents", emptyList()).single().long("n").toInt(),
            "identical members must collapse to one document")
        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM document_versions", emptyList()).single().long("n").toInt(),
            "the duplicate member must not publish a second version")
        assertEquals(1, visionCalls.get(), "the duplicate member must not upload to vision again")
        assertEquals(2, db.query("SELECT COUNT(*) AS n FROM chunks", emptyList()).single().long("n").toInt(),
            "one pipeline run produces exactly its own chunks")
        assertTrue(repo.search("vision-marker").isNotEmpty(), "published content stays searchable")
    }
}
