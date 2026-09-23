// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.SearchHit
import runtime.mobileagent.knowledge.TextEmbedder

/**
 * Regression harness for the silent app-wide freeze found in the review-APK
 * QA round (P1 "对话轮次静默挂死"): local embedding inference used to run inside
 * `db.transaction { ... }` (document publication, index rebuild, local space
 * upgrade).  The Android store connection serializes every statement behind
 * one monitor and holds it for a whole transaction, so a transaction held
 * across inference starves every other repository user — including the chat
 * pipeline that renders run feedback, which then never shows an answer, a
 * failure banner, or a timeout.
 *
 * The invariant these tests lock: an embedding backend is NEVER invoked while
 * a database transaction is open on the calling thread.
 */
class EmbeddingOutsideTransactionTest {

    /** Counts transaction scopes so an embedder can assert that none is open. */
    private class TransactionRecorder(private val delegate: SqlConnection) : SqlConnection by delegate {
        var depth = 0
            private set
        val inTransaction: Boolean get() = depth > 0

        override fun <T> transaction(block: () -> T): T {
            depth += 1
            try {
                return delegate.transaction(block)
            } finally {
                depth -= 1
            }
        }
    }

    /** Delegates hashing vectors and records the transaction state at every call. */
    private class GuardedEmbedder(
        private val inner: TextEmbedder,
        private val inTransaction: () -> Boolean,
    ) : TextEmbedder {
        var calls = 0
        var callsInsideTransaction = 0
        override val spaceId: String get() = inner.spaceId
        override val dimension: Int get() = inner.dimension

        override fun embed(text: String): FloatArray {
            calls += 1
            if (inTransaction()) callsInsideTransaction += 1
            return inner.embed(text)
        }
    }

    /** Hashed vectors behind an explicit space id, so a legacy `onnx:` space can be simulated. */
    private class FixtureEmbedder(
        override val spaceId: String,
        override val dimension: Int = 32,
    ) : TextEmbedder {
        private val inner = HashingTextEmbedder(dimension)
        override fun embed(text: String): FloatArray = inner.embed(text)
    }

    @Test
    fun documentPublicationEmbedsWithoutHoldingATransaction() {
        val recorder = TransactionRecorder(JdbcSqlConnection())
        Migrations.apply(recorder)
        val embedder = GuardedEmbedder(HashingTextEmbedder(), recorder::inTransaction)
        val repo = KnowledgeRepository(recorder, MemoryBlobSink(), embedder)

        val job = repo.importBytes(
            "notes.txt",
            "text/plain",
            "Alpha widget torque spec is 12Nm.".toByteArray(),
            visionConfigured = false,
        )

        assertEquals(ImportStage.READY, job.stage)
        assertTrue(embedder.calls > 0, "the import must actually reach the embedding backend")
        assertEquals(0, embedder.callsInsideTransaction, transactionViolation(embedder))
        assertTrue(repo.search("widget").any { "12Nm" in it.text })
    }

    @Test
    fun indexRebuildReEmbedsWithoutHoldingATransaction() {
        val recorder = TransactionRecorder(JdbcSqlConnection())
        Migrations.apply(recorder)
        val embedder = GuardedEmbedder(HashingTextEmbedder(), recorder::inTransaction)
        val repo = KnowledgeRepository(recorder, MemoryBlobSink(), embedder)
        val job = repo.importBytes(
            "notes.txt",
            "text/plain",
            "Alpha widget torque spec is 12Nm.".toByteArray(),
            visionConfigured = false,
        )
        assertEquals(ImportStage.READY, job.stage)
        val kbId = repo.listKnowledgeBases().single().first
        // Drop the immutable cache so the rebuild has real cache misses to embed.
        recorder.execute("DELETE FROM embeddings")
        embedder.calls = 0
        embedder.callsInsideTransaction = 0

        repo.rebuildIndex(kbId)

        assertTrue(embedder.calls > 0, "the rebuild must actually reach the embedding backend")
        assertEquals(0, embedder.callsInsideTransaction, transactionViolation(embedder))
        assertTrue(repo.search("widget").any { "12Nm" in it.text })
    }

    @Test
    fun localEmbeddingSpaceUpgradeEmbedsWithoutHoldingATransaction() {
        val recorder = TransactionRecorder(JdbcSqlConnection())
        Migrations.apply(recorder)
        val legacyEmbedder = GuardedEmbedder(FixtureEmbedder("onnx:legacy-fixture-d32"), recorder::inTransaction)
        val importing = KnowledgeRepository(recorder, MemoryBlobSink(), legacyEmbedder)
        val job = importing.importBytes(
            "notes.txt",
            "text/plain",
            "Alpha widget torque spec is 12Nm.".toByteArray(),
            visionConfigured = false,
        )
        assertEquals(ImportStage.READY, job.stage)
        val kbId = importing.listKnowledgeBases().single().first

        // A later process binds the same KB to a newer local model pack and
        // upgrades the legacy space lazily at the retrieval boundary — the
        // exact path the QA run exercised when its chat turn froze.
        val upgradedEmbedder = GuardedEmbedder(FixtureEmbedder("onnx:next-fixture-d48", dimension = 48), recorder::inTransaction)
        val upgraded = KnowledgeRepository(
            recorder,
            MemoryBlobSink(),
            upgradedEmbedder,
            legacyLocalEmbeddingSpaces = setOf(legacyEmbedder.spaceId),
        )

        val hits = upgraded.search("widget", knowledgeBaseIds = listOf(kbId))

        assertTrue(upgradedEmbedder.calls > 0, "the space upgrade must actually reach the embedding backend")
        assertEquals(0, upgradedEmbedder.callsInsideTransaction, transactionViolation(upgradedEmbedder))
        assertTrue(hits.any { "12Nm" in it.text })
    }

    @Test
    fun retrievalIsNotBlockedWhileEmbeddingIsRunning() {
        // Review follow-up (P2): the publication's long embedding phase must
        // hold neither the store transaction nor the repository index lock.
        // Holding either turns a long import into a retrieval freeze (the
        // original app-wide freeze, narrowed to the knowledge index).
        val recorder = TransactionRecorder(JdbcSqlConnection())
        Migrations.apply(recorder)
        val embedEntered = CountDownLatch(1)
        val releaseEmbed = CountDownLatch(1)
        val inner = HashingTextEmbedder()
        val blockingEmbedder = object : TextEmbedder {
            override val spaceId: String get() = inner.spaceId
            override val dimension: Int get() = inner.dimension

            override fun embed(text: String): FloatArray {
                if ("torque" in text) {
                    // Simulate minutes of local inference for the imported document only.
                    embedEntered.countDown()
                    releaseEmbed.await(30, TimeUnit.SECONDS)
                }
                return inner.embed(text)
            }
        }
        val repo = KnowledgeRepository(recorder, MemoryBlobSink(), blockingEmbedder)
        val publisher = Thread {
            runCatching {
                repo.importBytes(
                    "notes.txt",
                    "text/plain",
                    "Alpha widget torque spec is 12Nm.".toByteArray(),
                    visionConfigured = false,
                )
            }
        }
        publisher.start()
        try {
            assertTrue(embedEntered.await(20, TimeUnit.SECONDS), "the import must reach the embedding backend")
            val search = Executors.newSingleThreadExecutor()
            try {
                val pending = search.submit<List<SearchHit>> { repo.search("widget") }
                val hits = try {
                    pending.get(10, TimeUnit.SECONDS)
                } catch (timeout: java.util.concurrent.TimeoutException) {
                    throw AssertionError(
                        "retrieval blocked while embedding was parked in embed(): " +
                            "the index lock (or store transaction) is still held across inference",
                        timeout,
                    )
                }
                assertTrue(hits.isEmpty(), "the unpublished document must not be searchable yet")
            } finally {
                search.shutdownNow()
            }
        } finally {
            releaseEmbed.countDown()
            publisher.join(20_000)
        }
    }

    private fun transactionViolation(embedder: GuardedEmbedder): String =
        "embedding backend was invoked inside a database transaction " +
            "(${embedder.callsInsideTransaction} of ${embedder.calls} calls); " +
            "the store-wide monitor is held for a whole transaction, so this freezes every other repository user"
}
