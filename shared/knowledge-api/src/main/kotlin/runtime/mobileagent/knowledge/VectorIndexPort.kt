// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/**
 * Optional ANN seam for the repository.  The JVM implementation remains the
 * deterministic [CosineIndex]; Android can inject the real USearch JNI
 * implementation without making the data module depend on native code.
 */
interface VectorIndexPort : AutoCloseable {
    val spaceId: String
    val dimension: Int

    fun add(id: String, vector: FloatArray)
    fun search(query: FloatArray, topK: Int): List<Pair<String, Float>>

    override fun close() = Unit
}

fun interface VectorIndexFactory {
    fun create(spaceId: String, dimension: Int, capacity: Int): VectorIndexPort
}
