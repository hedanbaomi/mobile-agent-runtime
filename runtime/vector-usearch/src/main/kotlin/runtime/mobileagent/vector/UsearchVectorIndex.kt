// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.vector

import runtime.mobileagent.knowledge.VectorIndexFactory

/**
 * Real USearch-backed Android ANN index.  The native library is built from
 * the official USearch v2.25.1 headers for both arm64-v8a and x86_64; no
 * brute-force fallback is hidden behind this class.  Callers that cannot load
 * the native library should omit the factory and use the explicit JVM index.
 */
class UsearchVectorIndex(
    override val spaceId: String,
    override val dimension: Int,
    capacity: Int,
) : VectorIndex {
    private var pointer: Long = NativeUsearchIndex.create(dimension, capacity)
    private val keys = linkedMapOf<Long, String>()
    private val vectors = linkedMapOf<Long, FloatArray>()
    private var nextKey = 1L

    override fun add(id: String, vector: FloatArray) {
        check(pointer != 0L) { "USearch index is closed" }
        require(vector.size == dimension) { "vector dimension mismatch" }
        require(id !in keys.values) { "duplicate vector id: $id" }
        val key = nextKey++
        NativeUsearchIndex.add(pointer, key, vector)
        keys[key] = id
        vectors[key] = vector.copyOf()
    }

    override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> {
        check(pointer != 0L) { "USearch index is closed" }
        require(query.size == dimension) { "query dimension mismatch" }
        if (topK <= 0 || keys.isEmpty()) return emptyList()
        return NativeUsearchIndex.search(pointer, query, topK.coerceAtMost(keys.size)).asSequence().mapNotNull { key ->
            val id = keys[key] ?: return@mapNotNull null
            id to cosine(query, vectors.getValue(key))
        }.toList()
    }

    override fun close() {
        if (pointer == 0L) return
        NativeUsearchIndex.close(pointer)
        pointer = 0L
        keys.clear()
        vectors.clear()
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

/** Factory to inject into KnowledgeRepository from the Android app container. */
class UsearchVectorIndexFactory : VectorIndexFactory {
    override fun create(spaceId: String, dimension: Int, capacity: Int): VectorIndex =
        UsearchVectorIndex(spaceId, dimension, capacity)
}

private object NativeUsearchIndex {
    init {
        System.loadLibrary("usearch_jni")
    }

    fun create(dimension: Int, capacity: Int): Long = nativeCreate(dimension, capacity)

    fun add(pointer: Long, key: Long, vector: FloatArray) = nativeAdd(pointer, key, vector)

    fun search(pointer: Long, query: FloatArray, topK: Int): LongArray = nativeSearch(pointer, query, topK)

    fun close(pointer: Long) = nativeClose(pointer)

    private external fun nativeCreate(dimension: Int, capacity: Int): Long
    private external fun nativeAdd(pointer: Long, key: Long, vector: FloatArray)
    private external fun nativeSearch(pointer: Long, query: FloatArray, topK: Int): LongArray
    private external fun nativeClose(pointer: Long)
}
