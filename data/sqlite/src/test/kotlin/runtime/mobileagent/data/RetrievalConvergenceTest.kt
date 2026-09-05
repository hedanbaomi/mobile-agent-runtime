// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.RetrievalUnavailableReason
import runtime.mobileagent.knowledge.VectorIndexPort

/**
 * Convergence tests for multi-KB retrieval (§5–§7):
 *
 * - traversal order over the same KB set must not change top-K results or
 *   scores (metamorphic invariant);
 * - the ANN handle for a pinned generation is built once and reused across
 *   queries (it is derived state; SQLite vectors remain the truth);
 * - unavailable KBs produce a structured [RetrievalCoverage] instead of
 *   silently shrinking the evidence set.
 */
class RetrievalConvergenceTest {
    @Test
    fun traversalOrderDoesNotChangeTopKResultsOrScores() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val noisy = repo.createKnowledgeBase("Noisy")
        val quiet = repo.createKnowledgeBase("Quiet")
        repeat(40) { i ->
            repo.importBytes(
                "log$i.txt",
                "text/plain",
                "lighthouse maintenance log number $i with routine harbor inspection notes".toByteArray(),
                false,
                noisy,
            )
        }
        repo.importBytes(
            "keeper.txt",
            "text/plain",
            "lighthouse keeper secret codeword azure-harbor-7".toByteArray(),
            false,
            quiet,
        )

        val forward = repo.retrieve("run-forward", "lighthouse keeper secret", 8, listOf(noisy, quiet))
        val reverse = repo.retrieve("run-reverse", "lighthouse keeper secret", 8, listOf(quiet, noisy))

        assertEquals(8, forward.hits.size)
        assertEquals(8, reverse.hits.size)
        assertEquals(forward.hits.map { it.chunkId }, reverse.hits.map { it.chunkId })
        forward.hits.zip(reverse.hits).forEach { (a, b) ->
            assertTrue(abs(a.score - b.score) < 1e-9, "scores differ for ${a.chunkId}: ${a.score} vs ${b.score}")
        }
        // The quiet library's only hit keeps the same rank in both orders.
        val keeperForward = forward.hits.indexOfFirst { "azure-harbor-7" in it.text }
        val keeperReverse = reverse.hits.indexOfFirst { "azure-harbor-7" in it.text }
        assertTrue(keeperForward >= 0 && keeperForward == keeperReverse)

        listOf(forward, reverse).forEach { result ->
            val coverage = result.coverage
            assertTrue(coverage != null)
            assertEquals(listOf(noisy, quiet).sorted(), coverage!!.requested.sorted())
            assertEquals(listOf(noisy, quiet).sorted(), coverage.searched.sorted())
            assertTrue(coverage.unavailable.isEmpty())
            assertTrue(!coverage.partial)
            assertTrue(coverage.notice() == null)
        }
    }

    @Test
    fun onlyLexicalOnlyVectorAndUnavailableBasesAreCovered() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val ready = repo.createKnowledgeBase("Ready")
        repo.importBytes("doc.txt", "text/plain", "covered evidence paragraph".toByteArray(), false, ready)
        val empty = repo.createKnowledgeBase("Empty")

        val result = repo.retrieve("run-coverage", "covered evidence", 8, listOf(ready, empty, "kb-missing"))
        assertTrue(result.hits.any { "covered evidence" in it.text })

        val coverage = result.coverage!!
        assertEquals(listOf(ready), coverage.searched)
        assertTrue(coverage.partial)
        val byId = coverage.unavailable.associate { it.knowledgeBaseId to it.reason }
        assertEquals(RetrievalUnavailableReason.GENERATION_NOT_READY, byId[empty])
        assertEquals(RetrievalUnavailableReason.KB_NOT_FOUND, byId["kb-missing"])
        assertTrue(result.warnings.any { "部分知识库未参与本次检索" in it }, result.warnings.toString())
        val notice = coverage.notice()
        assertTrue(notice != null && empty in notice && "kb-missing" in notice)
    }

    @Test
    fun vectorIndexBuildHappensOnceAcrossRepeatedQueries() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val creates = AtomicInteger(0)
        val adds = AtomicInteger(0)
        val repo = KnowledgeRepository(
            db,
            MemoryBlobSink(),
            vectorIndexFactory = runtime.mobileagent.knowledge.VectorIndexFactory { spaceId, dimension, _ ->
                creates.incrementAndGet()
                CountingIndex(spaceId, dimension, adds)
            },
        )
        val kb = repo.createKnowledgeBase("Library")
        repeat(5) { i ->
            repo.importBytes("doc$i.txt", "text/plain", "reused index evidence paragraph $i".toByteArray(), false, kb)
        }

        val first = repo.retrieve("run-1", "reused index evidence", 8, listOf(kb))
        val addsAfterBuild = adds.get()
        assertTrue(addsAfterBuild > 0, "expected vectors to be added on build")
        val second = repo.retrieve("run-2", "reused index evidence", 8, listOf(kb))
        val third = repo.retrieve("run-3", "reused index evidence", 8, listOf(kb))
        assertEquals(first.hits.map { it.chunkId }, second.hits.map { it.chunkId })
        assertEquals(first.hits.map { it.chunkId }, third.hits.map { it.chunkId })
        assertEquals(addsAfterBuild, adds.get(), "repeated queries must not re-add vectors")

        // One build for the pinned generation; later queries reuse the handle
        // instead of re-adding every vector.
        assertEquals(1, creates.get(), "index must be built once, was built ${creates.get()} times")
        val stats = repo.vectorIndexStats()
        assertEquals(1, stats.builds)
        assertTrue(stats.reuseHits >= 2, "expected cache reuse, got $stats")

        // A new import publishes a new generation: exactly one rebuild, and
        // the new evidence is searchable through the switched handle.
        repo.importBytes("fresh.txt", "text/plain", "reused index evidence brand-new chapter".toByteArray(), false, kb)
        val fourth = repo.retrieve("run-4", "reused index evidence", 8, listOf(kb))
        assertTrue(fourth.hits.any { "brand-new" in it.text })
        assertEquals(2, creates.get())
        assertEquals(2, repo.vectorIndexStats().builds)
        assertTrue(adds.get() > addsAfterBuild, "rebuild must load the new generation's vectors")
    }

    @Test
    fun retrievalPinsRecordConsumedGenerationsNotLaterState() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.createKnowledgeBase("Library")
        repo.importBytes("doc.txt", "text/plain", "pinned generation evidence paragraph".toByteArray(), false, kb)

        // The first retrieval consumes generation G1 ...
        val first = repo.retrieve("run-g1", "pinned generation evidence", 8, listOf(kb))
        assertTrue(first.hits.isNotEmpty())
        val g1 = first.usedGenerations.single { it.knowledgeBaseId == kb }.generationId
        assertTrue(!g1.isNullOrBlank(), "a searched KB must pin its consumed generation")

        // ... a later publish switches the active generation to G2, but the
        // first result's pins must still describe G1 (execution facts, not a
        // re-read of the active generation).
        repo.importBytes("fresh.txt", "text/plain", "pinned generation evidence brand-new chapter".toByteArray(), false, kb)
        val second = repo.retrieve("run-g2", "pinned generation evidence", 8, listOf(kb))
        val g2 = second.usedGenerations.single { it.knowledgeBaseId == kb }.generationId
        assertTrue(!g2.isNullOrBlank())
        assertTrue(g1 != g2, "expected a generation switch, got g1=$g1 g2=$g2")
        assertEquals(g1, first.usedGenerations.single { it.knowledgeBaseId == kb }.generationId)
    }

    @Test
    fun deletedKnowledgeBaseInvalidatesCoverageAndIndex() {        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val a = repo.createKnowledgeBase("A")
        val b = repo.createKnowledgeBase("B")
        repo.importBytes("a.txt", "text/plain", "alpha evidence".toByteArray(), false, a)
        repo.importBytes("b.txt", "text/plain", "beta evidence".toByteArray(), false, b)
        assertEquals(2, repo.retrieve("run-before", "evidence", 8, listOf(a, b)).hits.size)

        repo.deleteKnowledgeBase(b)
        val after = repo.retrieve("run-after", "evidence", 8, listOf(a, b))
        assertTrue(after.hits.all { it.knowledgeBaseId == a })
        val coverage = after.coverage!!
        assertEquals(listOf(a), coverage.searched)
        assertTrue(coverage.partial)
        assertEquals(RetrievalUnavailableReason.KB_NOT_FOUND, coverage.unavailable.single { it.knowledgeBaseId == b }.reason)
    }

    /**
     * Workflow benchmark B (knowledge task): citations pin exact document
     * versions, stay locatable after the library changes, and explicitly
     * report removal instead of silently retargeting.
     */
    @Test
    fun citationsPinVersionsAndReportRemovalExplicitly() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.createKnowledgeBase("Library")
        repo.importBytes("guide.txt", "text/plain", "versioned deployment guide revision one".toByteArray(), false, kb)

        val first = repo.retrieve("run-v1", "deployment guide", 8, listOf(kb))
        assertTrue(first.hits.isNotEmpty())
        val citation = first.citations.single { it.chunkId == first.hits.first().chunkId }
        assertTrue(citation.documentVersionId.isNotBlank(), "citation must pin its document version")
        assertEquals("run-v1", citation.runId)
        val located = repo.locateCitation(citation)
        assertTrue(!located.removed, "fresh citation must locate")

        // A new document revision changes what fresh retrieval cites, while
        // the old citation still resolves to its pinned version.
        repo.importBytes("guide2.txt", "text/plain", "versioned deployment guide revision two".toByteArray(), false, kb)
        val second = repo.retrieve("run-v2", "deployment guide", 8, listOf(kb))
        assertTrue(second.hits.isNotEmpty())
        assertTrue(repo.locateCitation(citation).removed.not(), "pinned version must stay locatable, got ${repo.locateCitation(citation)}")

        // Deletion is explicit: the locator reports removal, and coverage
        // marks the KB unavailable instead of shrinking silently.
        val documentId = citation.documentId
        repo.deleteDocument(documentId)
        assertTrue(repo.locateCitation(citation).removed, "deleted source must report removal")
    }

    private class CountingIndex(
        override val spaceId: String,
        override val dimension: Int,
        private val adds: AtomicInteger,
    ) : VectorIndexPort {
        private val delegate = runtime.mobileagent.knowledge.CosineIndex(dimension)

        override fun add(id: String, vector: FloatArray) {
            adds.incrementAndGet()
            delegate.add(id, vector)
        }

        override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> =
            delegate.search(query, topK)
    }
}
