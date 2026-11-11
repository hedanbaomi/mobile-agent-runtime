// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.KnowledgePin
import runtime.mobileagent.knowledge.*

class KnowledgeRetrievalCoordinatorTest {
    @Test fun blankQueryNeverDispatchesAndKeepsDistinctRequestedPins() {
        val coordinator = KnowledgeRetrievalCoordinator { _, _ -> error("no query dispatch permitted") }
        val result = coordinator.retrieve("run", "  ", 8, listOf("a", "a", "b"))
        assertTrue(result.hits.isEmpty())
        assertEquals(listOf("a", "b"), result.coverage!!.requested)
        assertEquals(listOf(KnowledgePin("a", null, ""), KnowledgePin("b", null, "")), result.usedGenerations)
    }

    @Test fun aggregationKeepsChannelRanksGenerationPinsAndCoverageFromTheSameCall() {
        val coordinator = KnowledgeRetrievalCoordinator { _, bases ->
            assertEquals(bases.size, bases.distinct().size)
            val hits = bases.filter { it != "missing" }.map { id ->
                listOf(SearchHit("chunk-$id", "doc-$id", "Evidence $id", if (id == "a") 100.0 else 0.1, id, "v-$id"))
            }
            KnowledgeRetrievalFacts(hits, listOf("source warning"), bases.filter { it != "missing" },
                listOf(UnavailableSource("missing", RetrievalUnavailableReason.KB_NOT_FOUND)),
                mapOf("a" to KnowledgePin("a", "frozen-generation", "space")), setOf("b"))
        }
        val first = coordinator.retrieve("run", "evidence", 8, listOf("a", "b", "missing", "a"))
        val reversed = coordinator.retrieve("run", "evidence", 8, listOf("missing", "b", "a"))
        assertEquals(first.hits, reversed.hits)
        assertEquals(listOf("b"), first.usedRemoteEmbedding)
        assertEquals("frozen-generation", first.usedGenerations.first().generationId)
        assertTrue(first.coverage!!.partial)
        assertEquals(first.coverage!!.notice(), first.warnings.last())
        assertEquals(first.hits.map { it.chunkId }, first.citations.map { it.chunkId })
        assertTrue(first.citations.all { it.runId == "run" })
    }
}
