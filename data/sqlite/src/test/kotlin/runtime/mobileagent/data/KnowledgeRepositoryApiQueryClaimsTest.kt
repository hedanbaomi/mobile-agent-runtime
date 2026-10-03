// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.knowledge.ApiEmbeddingBinding
import runtime.mobileagent.knowledge.ApiQueryUnknownOutcomeException
import runtime.mobileagent.knowledge.EmbeddingUnknownOutcomeException
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.sha256Hex

class KnowledgeRepositoryApiQueryClaimsTest {
    @Test fun definiteFailuresReleaseOnlyTheirClaimAndAllowNormalRetry() {
        for (code in listOf(ErrorCode.SECRET_UNAVAILABLE, ErrorCode.PROVIDER_UNAUTHORIZED, ErrorCode.RATE_LIMITED)) {
            fixture().use { f ->
                f.api.action = { throw AppError(code, "synthetic rejection", RetryClass.USER_ACTION, "embedding", "fixture").asException() }
                assertThrows(runtime.mobileagent.domain.AppException::class.java) { f.retrieve() }
                assertTrue(f.repo.pendingApiQueries(f.kb).isEmpty())
                f.api.action = { vector() }
                assertTrue(f.retrieve().hits.isNotEmpty())
                assertEquals(2, f.api.queryCalls.get())
            }
        }
    }

    @Test fun cancellationAndUnknownRemainGatedWithoutAnotherProviderCall() {
        for (failure in listOf(EmbeddingUnknownOutcomeException(IllegalStateException("synthetic unknown")),
            java.util.concurrent.CancellationException("synthetic cancellation"), InterruptedException("interrupted"))) {
            fixture().use { f ->
                f.api.action = { throw failure }
                assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
                assertFalse(f.repo.pendingApiQueries(f.kb).single().retryAuthorized)
                assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
                assertEquals(1, f.api.queryCalls.get())
            }
        }
    }

    @Test fun oldAttemptCannotClearOrOverwriteNewOwnerOrUnconsumedRetryGrant() {
        for (oldOutcome in listOf("known", "unknown", "success")) {
            for (consumeGrant in listOf(false, true)) fixture().use { f ->
                val enteredA = CountDownLatch(1)
                val releaseA = CountDownLatch(1)
                val enteredB = CountDownLatch(1)
                val releaseB = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                f.api.action = { attempt ->
                    if (attempt == 1) {
                        enteredA.countDown(); check(releaseA.await(5, TimeUnit.SECONDS))
                        when (oldOutcome) {
                            "known" -> error("synthetic preflight failure")
                            "unknown" -> throw EmbeddingUnknownOutcomeException(IllegalStateException("synthetic unknown"))
                            else -> vector()
                        }
                    } else {
                        enteredB.countDown(); check(releaseB.await(5, TimeUnit.SECONDS))
                        throw EmbeddingUnknownOutcomeException(IllegalStateException("new owner unknown"))
                    }
                }
                try {
                    val a = pool.submit { runCatching { f.retrieve() } }
                    check(enteredA.await(5, TimeUnit.SECONDS))
                    f.repo.authorizeApiQueryRetry(f.kb, f.api.spaceId, HASH, acknowledgeDuplicateCharge = true)
                    val b = if (consumeGrant) pool.submit { runCatching { f.retrieve() } } else null
                    if (consumeGrant) check(enteredB.await(5, TimeUnit.SECONDS))
                    val before = f.row()
                    releaseA.countDown(); a.get(5, TimeUnit.SECONDS)
                    assertEquals(before.string("error"), f.row().string("error"), oldOutcome)
                    assertEquals(before.long("retry_authorized"), f.row().long("retry_authorized"))
                    assertFalse(f.repo.pendingApiQueries(f.kb).single().error.contains("owner="))
                    if (b != null) { releaseB.countDown(); b.get(5, TimeUnit.SECONDS) }
                    if (oldOutcome == "success") {
                        assertEquals(1, f.db.query("SELECT COUNT(*) AS n FROM embedding_query_vectors").single().long("n"))
                        // A valid cache may complete an explicitly authorized pending attempt with zero re-billing.
                        if (consumeGrant) f.repo.authorizeApiQueryRetry(f.kb, f.api.spaceId, HASH, acknowledgeDuplicateCharge = true)
                        val beforeCalls = f.api.queryCalls.get()
                        assertTrue(f.retrieve().hits.isNotEmpty())
                        assertEquals(beforeCalls, f.api.queryCalls.get())
                        assertTrue(f.repo.pendingApiQueries(f.kb).isEmpty())
                    }
                } finally {
                    releaseA.countDown(); releaseB.countDown(); pool.shutdownNow()
                    check(pool.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun returnedButInvalidVectorRemainsUnknownInsteadOfAutomaticallyRebilling() {
        fixture().use { f ->
            f.api.action = { FloatArray(7) }
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertFalse(f.repo.pendingApiQueries(f.kb).single().retryAuthorized)
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun cacheWriteFailureAfterProviderReturnRemainsGated() {
        fixture(failQueryCacheWrite = true).use { f ->
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertFalse(f.repo.pendingApiQueries(f.kb).single().retryAuthorized)
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun legacyPendingRowsStayBlockedUntilExplicitGrantIsConsumed() {
        fixture().use { f ->
            f.db.execute("INSERT INTO embedding_query_attempts(kb_id,space_id,query_hash,error,updated_at) VALUES(?,?,?,?,?)",
                listOf(f.kb, f.api.spaceId, HASH, "API_QUERY_PENDING: explicit retry authorization is required", "2026-10-03T00:00:00Z"))
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertEquals(0, f.api.queryCalls.get())
            f.repo.authorizeApiQueryRetry(f.kb, f.api.spaceId, HASH, acknowledgeDuplicateCharge = true)
            assertTrue(f.retrieve().hits.isNotEmpty())
            assertTrue(f.repo.pendingApiQueries(f.kb).isEmpty())
        }
    }

    private fun fixture(failQueryCacheWrite: Boolean = false): Fixture {
        val db = JdbcSqlConnection(); Migrations.apply(db)
        val binding = ApiEmbeddingBinding("fixture", "https://example.invalid/v1/embeddings", 1, "fixture", 1, 8, "retrieval")
        val api = Api(binding.spaceId)
        val connection = if (!failQueryCacheWrite) db else object : SqlConnection by db {
            override fun execute(sql: String, args: List<Any?>) {
                if (sql.startsWith("INSERT INTO embedding_query_vectors")) error("synthetic cache write failure")
                db.execute(sql, args)
            }
        }
        val repo = KnowledgeRepository(connection, MemoryBlobSink(), apiEmbedder = api)
        val kb = repo.createApiKnowledgeBase("API fixture", binding)
        assertEquals(ImportStage.READY, repo.importBytes("source.txt", "text/plain", "stable query source".toByteArray(),
            false, knowledgeBaseId = kb, embeddingIsApi = true, embeddingConsent = true).stage)
        return Fixture(db, api, repo, kb)
    }

    private class Fixture(val db: JdbcSqlConnection, val api: Api, val repo: KnowledgeRepository, val kb: String) : AutoCloseable {
        fun retrieve() = repo.retrieve("query-fixture", QUERY, knowledgeBaseIds = listOf(kb))
        fun row() = db.query("SELECT error,retry_authorized FROM embedding_query_attempts WHERE kb_id=? AND space_id=? AND query_hash=?",
            listOf(kb, api.spaceId, HASH)).single()
        override fun close() = db.close()
    }
    private class Api(override val spaceId: String) : TextEmbedder {
        override val dimension = 8
        val queryCalls = AtomicInteger()
        @Volatile var action: (Int) -> FloatArray = { vector() }
        override fun embed(text: String): FloatArray = if (text == QUERY) action(queryCalls.incrementAndGet()) else vector()
    }
    private companion object {
        const val QUERY = "ownership query fixture"
        val HASH = sha256Hex(QUERY.toByteArray())
        fun vector() = FloatArray(8) { if (it == 0) 1f else 0f }
    }
}
