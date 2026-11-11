// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import runtime.mobileagent.domain.KnowledgePin
import runtime.mobileagent.knowledge.*

/** Facts produced by one authorized retrieval, not a later read of live generations. */
internal data class KnowledgeRetrievalFacts(
    val rankings: List<List<SearchHit>>,
    val warnings: List<String>,
    val searched: List<String>,
    val unavailable: List<UnavailableSource>,
    val usedPins: Map<String, KnowledgePin>,
    val remoteEmbeddingKbIds: Set<String>,
)

/** Owns scope aggregation, channel fusion and citation binding; sources own transactions/receipts. */
internal class KnowledgeRetrievalCoordinator(
    private val collectSources: (String, List<String>) -> KnowledgeRetrievalFacts,
) {
    fun retrieve(runId: String, query: String, topK: Int, requested: List<String>): RetrievalResult {
        val bases = requested.distinct()
        if (query.isBlank()) return RetrievalResult(
            emptyList(), emptyList(), listOf("empty query"),
            RetrievalCoverage(requested = bases),
            usedGenerations = bases.map { KnowledgePin(it, null, "") },
        )
        val facts = collectSources(query, bases)
        // Keep each KB/channel ranking separate: concatenation makes RRF traversal-order dependent.
        val hits = ReciprocalRankFusion.preferClaimSupporting(ReciprocalRankFusion.merge(facts.rankings)).take(topK)
        val warnings = facts.warnings.toMutableList()
        if (hits.isEmpty()) warnings += "No in-scope evidence"
        val coverage = RetrievalCoverage(bases, facts.searched, facts.unavailable)
        formatRetrievalCoverageNotice(coverage)?.let { warnings += it }
        return RetrievalResult(
            hits, CitationMap.bind(runId, hits), warnings, coverage,
            usedGenerations = bases.map { facts.usedPins[it] ?: KnowledgePin(it, null, "") },
            usedRemoteEmbedding = bases.filter { it in facts.remoteEmbeddingKbIds },
        )
    }
}
