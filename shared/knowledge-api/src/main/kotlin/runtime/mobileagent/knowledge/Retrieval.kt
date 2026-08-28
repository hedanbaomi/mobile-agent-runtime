// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

data class Citation(
    val citationId: String,
    val runId: String,
    val knowledgeBaseId: String,
    val documentId: String,
    val chunkId: String,
    val assetId: String? = null,
)

data class SearchHit(
    val chunkId: String,
    val documentId: String,
    val text: String,
    val score: Double,
)

fun interface KnowledgeSearch {
    suspend fun search(knowledgeBaseIds: List<String>, query: String, topK: Int): List<SearchHit>
}

object ReciprocalRankFusion {
    fun merge(rankings: List<List<SearchHit>>, k: Int = 60): List<SearchHit> {
        val scores = linkedMapOf<String, Double>()
        val docs = linkedMapOf<String, SearchHit>()
        rankings.forEach { ranking ->
            ranking.forEachIndexed { index, hit ->
                scores[hit.chunkId] = (scores[hit.chunkId] ?: 0.0) + 1.0 / (k + index + 1)
                docs.putIfAbsent(hit.chunkId, hit)
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { (id, score) ->
            docs.getValue(id).copy(score = score)
        }
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
