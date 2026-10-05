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

    @Test fun committedCacheRecoversLegacyAndUnknownWithoutProviderOrRetryGrant() {
        for (error in listOf("API_QUERY_PENDING: explicit retry authorization is required",
            "API_QUERY_UNKNOWN: synthetic unknown", "API_QUERY_PENDING: explicit retry authorization is required; owner=old-process")) {
            fixture().use { f ->
                assertTrue(f.retrieve().hits.isNotEmpty())
                f.pending(error)
                val restarted = KnowledgeRepository(f.db, MemoryBlobSink())
                assertTrue(restarted.retrieve("restart", QUERY, knowledgeBaseIds = listOf(f.kb)).hits.isNotEmpty())
                assertEquals(1, f.api.queryCalls.get())
                // A token may still belong to a concurrent caller. Do not clean it on its behalf.
                assertEquals(if (error.contains("owner=")) 1 else 0, restarted.pendingApiQueries(f.kb).size)
            }
        }
    }

    @Test fun cacheAndClaimCleanupRollbackTogetherOnInsertInterruptionOrDeleteFailure() {
        for (mode in listOf("interrupt-after-insert", "delete")) fixture(failureMode = mode).use { f ->
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertEquals(0, f.db.query("SELECT COUNT(*) AS n FROM embedding_query_vectors").single().long("n"))
            assertFalse(f.repo.pendingApiQueries(f.kb).single().retryAuthorized)
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun recoveryCleanupFailureKeepsSuccessAndRetriesLocally() {
        fixture(failureMode = "recover-delete").use { f ->
            f.retrieve(); f.pending("API_QUERY_UNKNOWN: synthetic unknown")
            assertThrows(IllegalStateException::class.java) { f.retrieve() }
            assertEquals(1, f.repo.pendingApiQueries(f.kb).size)
            assertEquals(1, f.db.query("SELECT COUNT(*) AS n FROM embedding_query_vectors").single().long("n"))
            assertTrue(f.retrieve().hits.isNotEmpty())
            assertTrue(f.repo.pendingApiQueries(f.kb).isEmpty())
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun interruptedCommitAcknowledgmentRecoversCommittedSuccessWithoutRebilling() {
        fixture(failureMode = "commit-ack").use { f ->
            assertThrows(ApiQueryUnknownOutcomeException::class.java) { f.retrieve() }
            assertEquals(1, f.db.query("SELECT COUNT(*) AS n FROM embedding_query_vectors").single().long("n"))
            assertTrue(f.repo.pendingApiQueries(f.kb).isEmpty())
            assertTrue(f.retrieve().hits.isNotEmpty())
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun recoverySnapshotCannotDeleteReplacementLiveOwner() {
        fixture(failureMode = "replace-recovery-owner").use { f ->
            f.retrieve(); f.pending("API_QUERY_UNKNOWN: synthetic unknown")
            assertTrue(f.retrieve().hits.isNotEmpty())
            assertTrue(f.row().string("error").endsWith("owner=replacement"))
            assertEquals(0, f.row().long("retry_authorized"))
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun corruptedOrMismatchedSuccessNeverClearsPendingOrRebills() {
        for (mode in listOf("dimension", "overflow-dimension", "bytes", "nonfinite", "space", "query")) fixture().use { f ->
            f.retrieve()
            f.pending("API_QUERY_UNKNOWN: synthetic unknown")
            when (mode) {
                "dimension" -> f.db.execute("UPDATE embedding_query_vectors SET dimension=7")
                "overflow-dimension" -> f.db.execute("UPDATE embedding_query_vectors SET dimension=4294967304")
                "bytes" -> f.db.execute("UPDATE embedding_query_vectors SET vector_blob=?", listOf(ByteArray(1)))
                "nonfinite" -> f.db.execute("UPDATE embedding_query_vectors SET vector_blob=?", listOf(java.nio.ByteBuffer.allocate(32)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()))
                "space" -> f.db.execute("UPDATE embedding_query_vectors SET space_id='wrong-space'")
                "query" -> f.db.execute("UPDATE embedding_query_vectors SET query_hash=?", listOf("0".repeat(64)))
            }
            assertThrows(Exception::class.java) { f.retrieve() }
            assertEquals(1, f.repo.pendingApiQueries(f.kb).size)
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun cachedSuccessStillRequiresPersistedConsent() {
        fixture().use { f ->
            f.retrieve(); f.pending("API_QUERY_UNKNOWN: synthetic unknown")
            f.db.execute("UPDATE embedding_operations SET consent_fingerprint=''")
            f.db.execute("UPDATE import_jobs SET embedding_consent=0")
            val result = f.retrieve()
            assertTrue(result.hits.isEmpty())
            assertEquals(1, f.repo.pendingApiQueries(f.kb).size)
            assertEquals(1, f.api.queryCalls.get())
        }
    }

    @Test fun twoKnowledgeBasesInSameSpaceCanFinishConcurrentClaimsAndReuseSuccess() {
        fixture().use { f ->
            val binding = ApiEmbeddingBinding("fixture", "https://example.invalid/v1/embeddings", 1, "fixture", 1, 8, "retrieval")
            val kb2 = f.repo.createApiKnowledgeBase("second", binding)
            assertEquals(ImportStage.READY, f.repo.importBytes("second.txt", "text/plain", "second source".toByteArray(),
                false, knowledgeBaseId = kb2, embeddingIsApi = true, embeddingConsent = true).stage)
            val entered = CountDownLatch(2); val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            f.api.action = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); vector() }
            try {
                val a = pool.submit<runtime.mobileagent.knowledge.RetrievalResult> { f.retrieve() }
                val b = pool.submit<runtime.mobileagent.knowledge.RetrievalResult> {
                    f.repo.retrieve("second", QUERY, knowledgeBaseIds = listOf(kb2))
                }
                check(entered.await(5, TimeUnit.SECONDS)); release.countDown()
                assertTrue(a.get(5, TimeUnit.SECONDS).hits.isNotEmpty()); assertTrue(b.get(5, TimeUnit.SECONDS).hits.isNotEmpty())
                assertTrue(f.repo.pendingApiQueries(f.kb).isEmpty()); assertTrue(f.repo.pendingApiQueries(kb2).isEmpty())
                assertEquals(1, f.db.query("SELECT COUNT(*) AS n FROM embedding_query_vectors").single().long("n"))
                f.repo.retrieve("both", QUERY, knowledgeBaseIds = listOf(f.kb, kb2))
                assertEquals(2, f.api.queryCalls.get())
            } finally {
                release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun fixture(failQueryCacheWrite: Boolean = false, failureMode: String = ""): Fixture {
        val db = JdbcSqlConnection(); Migrations.apply(db)
        val binding = ApiEmbeddingBinding("fixture", "https://example.invalid/v1/embeddings", 1, "fixture", 1, 8, "retrieval")
        val api = Api(binding.spaceId)
        val connection = if (!failQueryCacheWrite && failureMode.isEmpty()) db else object : SqlConnection by db {
            var recoveryDeleteFailed = false
            var cacheInserted = false
            var commitAcknowledgmentFailed = false
            override fun execute(sql: String, args: List<Any?>) {
                if (failQueryCacheWrite && sql.startsWith("INSERT INTO embedding_query_vectors")) error("synthetic cache write failure")
                if (failureMode == "delete" && sql.startsWith("DELETE FROM embedding_query_attempts")) error("synthetic cleanup failure")
                if (failureMode == "recover-delete" && args.size == 5 && sql.startsWith("DELETE FROM embedding_query_attempts") && !recoveryDeleteFailed) {
                    recoveryDeleteFailed = true
                    error("synthetic recovery cleanup failure")
                }
                db.execute(sql, args)
                if (sql.startsWith("INSERT INTO embedding_query_vectors")) cacheInserted = true
                if (failureMode == "interrupt-after-insert" && sql.startsWith("INSERT INTO embedding_query_vectors"))
                    throw InterruptedException("synthetic crash after insert")
            }
            override fun query(sql: String, args: List<Any?>): List<SqlRow> {
                val rows = db.query(sql, args)
                if (failureMode == "replace-recovery-owner" && sql.startsWith("SELECT error, retry_authorized FROM embedding_query_attempts") &&
                    rows.singleOrNull()?.string("error") == "API_QUERY_UNKNOWN: synthetic unknown") {
                    db.execute("UPDATE embedding_query_attempts SET error=?,retry_authorized=0 WHERE kb_id=? AND space_id=? AND query_hash=?",
                        listOf("API_QUERY_PENDING: explicit retry authorization is required; owner=replacement") + args)
                }
                return rows
            }
            override fun <T> transaction(block: () -> T): T {
                val result = db.transaction(block)
                if (failureMode == "commit-ack" && cacheInserted && !commitAcknowledgmentFailed) {
                    commitAcknowledgmentFailed = true
                    throw InterruptedException("synthetic interrupted commit acknowledgment")
                }
                return result
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
        fun pending(error: String) = db.execute(
            "INSERT INTO embedding_query_attempts(kb_id,space_id,query_hash,error,updated_at) VALUES(?,?,?,?,?)",
            listOf(kb, api.spaceId, HASH, error, "2026-10-05T00:00:00Z"))
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
