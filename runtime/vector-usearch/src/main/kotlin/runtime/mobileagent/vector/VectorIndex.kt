// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.vector

interface VectorIndex {
    val spaceId: String
    fun add(id: String, vector: FloatArray)
    fun search(query: FloatArray, topK: Int): List<Pair<String, Float>>
}

class BruteForceVectorIndex(override val spaceId: String, private val dimension: Int) : VectorIndex {
    private val rows = linkedMapOf<String, FloatArray>()

    override fun add(id: String, vector: FloatArray) {
        require(vector.size == dimension)
        rows[id] = vector
    }

    override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> {
        require(query.size == dimension)
        return rows.map { (id, vec) -> id to cosine(query, vec) }
            .sortedByDescending { it.second }
            .take(topK)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat()
    }
}
