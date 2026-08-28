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
