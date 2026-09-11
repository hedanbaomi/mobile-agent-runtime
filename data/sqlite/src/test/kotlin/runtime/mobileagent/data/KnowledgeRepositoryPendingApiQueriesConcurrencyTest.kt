// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VisionSuccess

class KnowledgeRepositoryPendingApiQueriesConcurrencyTest {
    @Test
    fun pendingApiQueriesDoesNotWaitForLocalVisionProvider() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val enteredVision = CountDownLatch(1)
        val releaseVision = CountDownLatch(1)
        val vision = VisionBackend {
            enteredVision.countDown()
            check(releaseVision.await(5, TimeUnit.SECONDS)) { "slow Vision fixture was not released" }
            VisionOutcome.Success(VisionSuccess("ocr", "slow vision"))
        }
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            vision = vision,
            visionModelFingerprint = "vision-concurrency-test",
        )
        val knowledgeBaseId = repo.ensureDefaultBase()
        val queryHash = "a".repeat(64)
        db.execute(
            "INSERT INTO embedding_query_attempts(kb_id,space_id,query_hash,error,updated_at) VALUES(?,?,?,?,?)",
            listOf(knowledgeBaseId, "api-space", queryHash, "UNKNOWN_OUTCOME", "2026-09-10T00:00:00Z"),
        )

        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + ByteArray(16)
        val awaiting = repo.importBytes(
            "scan.png",
            "image/png",
            png,
            visionConfigured = true,
            knowledgeBaseId = knowledgeBaseId,
            visionConsent = false,
        )
        assertEquals(ImportStage.AWAITING_UPLOAD_CONSENT, awaiting.stage)

        val visionExecutor = Executors.newSingleThreadExecutor()
        val pendingExecutor = Executors.newSingleThreadExecutor()
        try {
            val visionFuture = visionExecutor.submit<runtime.mobileagent.knowledge.ImportJob> {
                repo.grantVisionConsent(awaiting.id)
            }
            check(enteredVision.await(5, TimeUnit.SECONDS)) { "Vision fixture was not entered" }

            val pending = pendingExecutor.submit<List<runtime.mobileagent.knowledge.ApiQueryAttempt>> {
                repo.pendingApiQueries(knowledgeBaseId)
            }.get(1, TimeUnit.SECONDS)
            assertEquals(queryHash, pending.single().queryHash)

            releaseVision.countDown()
            assertEquals(ImportStage.READY, visionFuture.get(5, TimeUnit.SECONDS).stage)
        } finally {
            releaseVision.countDown()
            visionExecutor.shutdownNow()
            pendingExecutor.shutdownNow()
            check(visionExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "Vision executor did not terminate" }
            check(pendingExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "pending query executor did not terminate" }
            db.close()
        }
    }
}
