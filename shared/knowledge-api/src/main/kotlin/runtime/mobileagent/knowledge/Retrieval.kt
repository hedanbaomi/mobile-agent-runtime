// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import runtime.mobileagent.domain.KnowledgePin

data class Citation(
    val citationId: String,
    val runId: String,
    val knowledgeBaseId: String,
    val documentId: String,
    val chunkId: String,
    val assetId: String? = null,
    val page: Int? = null,
    val documentVersionId: String = "",
    val sourceSpan: String? = null,
)

data class SearchHit(
    val chunkId: String,
    val documentId: String,
    val text: String,
    val score: Double,
    val knowledgeBaseId: String = "",
    val documentVersionId: String = "",
    val assetId: String? = null,
    val page: Int? = null,
    val sourceSpan: String? = null,
)

data class EvidenceLocator(
    val documentId: String,
    val displayName: String,
    val page: Int?,
    val assetId: String?,
    val sourceSpan: String?,
    val blobHash: String?,
    val removed: Boolean,
)

data class RetrievalResult(
    val hits: List<SearchHit>,
    val citations: List<Citation>,
    val warnings: List<String> = emptyList(),
    /** Structured retrieval scope for this run; null only for pre-coverage callers. */
    val coverage: RetrievalCoverage? = null,
    /**
     * Exact generation pins consumed by this retrieval (one per requested
     * KB; null generationId while the KB has no READY generation).  These
     * come from the retrieve call itself — never from a later re-read — so
     * the run manifest records execution facts, not nearby state (b07
     * follow-up finding D).  Carries ids only, never query text.
     */
    val usedGenerations: List<KnowledgePin> = emptyList(),
)

/** Why a requested knowledge base did not participate in this retrieval. */
enum class RetrievalUnavailableReason {
    KB_NOT_FOUND,
    CONSENT_MISSING,
    EMBEDDING_UNAVAILABLE,
    GENERATION_NOT_READY,
    QUERY_NOT_AUTHORIZED,
    PROVIDER_UNAVAILABLE,
    RETRIEVAL_DISABLED,
}

data class UnavailableSource(
    val knowledgeBaseId: String,
    val reason: RetrievalUnavailableReason,
)

/**
 * Durable retrieval-scope fact for one run: which KBs were requested, which
 * were actually searched, and why the rest were skipped.  Carries no query
 * text, so it is safe for diagnostics, prompts, and persistence.
 */
data class RetrievalCoverage(
    val requested: List<String> = emptyList(),
    val searched: List<String> = emptyList(),
    val unavailable: List<UnavailableSource> = emptyList(),
) {
    val partial: Boolean get() = unavailable.isNotEmpty()

    /**
     * Short runtime-authored scope note.  It is injected into the model
     * prompt by the runtime (never by the model) and shown in the UI, so the
     * model and the user both see that some sources did not participate.
     * Returns null when coverage is complete.
     */
    fun notice(): String? {
        if (!partial) return null
        val detail = unavailable.joinToString("; ") { "${it.knowledgeBaseId}: ${it.reason.name}" }
        return "Partial retrieval scope: searched ${searched.size}/${requested.size} knowledge bases. " +
            "Unavailable: $detail. Do not claim the missing sources were searched."
    }
}

object CitationMap {
    fun bind(runId: String, hits: List<SearchHit>): List<Citation> =
        hits.mapIndexed { index, hit ->
            Citation(
                citationId = index.toString(),
                runId = runId,
                knowledgeBaseId = hit.knowledgeBaseId,
                documentId = hit.documentId,
                chunkId = hit.chunkId,
                assetId = hit.assetId,
                page = hit.page,
                documentVersionId = hit.documentVersionId,
                sourceSpan = hit.sourceSpan,
            )
        }

    fun resolve(citations: List<Citation>, citationId: String): Citation? =
        citations.firstOrNull { it.citationId == citationId }
}

fun interface KnowledgeSearch {
    suspend fun search(knowledgeBaseIds: List<String>, query: String, topK: Int): List<SearchHit>
}

object ReciprocalRankFusion {
    /**
     * Fuse per-source rankings into one order-invariant ranking.
     *
     * Each input list must be ONE source ranking (one KB's lexical hits, or
     * one KB's vector hits) in that source's own rank order.  Callers must
     * NOT concatenate several KBs into one list first: concatenated positions
     * depend on KB traversal order and would bias the fusion.  Scores are
     * rank-based only, so embedding spaces with incomparable cosine scales
     * are never mixed by raw score.
     *
     * Ties break deterministically on (chunkId, knowledgeBaseId), so the
     * output is identical for any traversal order over the same KB set.
     */
    fun merge(rankings: List<List<SearchHit>>, k: Int = 60): List<SearchHit> {
        val scores = linkedMapOf<String, Double>()
        val docs = linkedMapOf<String, SearchHit>()
        rankings.forEach { ranking ->
            ranking.forEachIndexed { index, hit ->
                scores[hit.chunkId] = (scores[hit.chunkId] ?: 0.0) + 1.0 / (k + index + 1)
                docs.putIfAbsent(hit.chunkId, hit)
            }
        }
        return scores.keys
            .sortedWith(
                compareByDescending<String> { scores.getValue(it) }
                    .thenBy { docs.getValue(it).chunkId }
                    .thenBy { docs.getValue(it).knowledgeBaseId },
            )
            .map { id -> docs.getValue(id).copy(score = scores.getValue(id)) }
    }
}

object RetrievalBudget {
    const val DEFAULT_MAX_CHARS = 6000

    fun clip(hits: List<SearchHit>, maxChars: Int = DEFAULT_MAX_CHARS): List<SearchHit> {
        if (maxChars <= 0) return emptyList()
        val out = mutableListOf<SearchHit>()
        var used = 0
        for (hit in hits) {
            if (used >= maxChars) break
            val remaining = maxChars - used
            val text = if (hit.text.length <= remaining) hit.text else hit.text.take(remaining)
            if (text.isEmpty()) break
            out += hit.copy(text = text)
            used += text.length
        }
        return out
    }
}
