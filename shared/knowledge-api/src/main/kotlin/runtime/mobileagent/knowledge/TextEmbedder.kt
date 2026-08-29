// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.security.MessageDigest

interface TextEmbedder {
    val spaceId: String
    val dimension: Int
    fun embed(text: String): FloatArray
}

/**
 * Optional batch boundary for providers that can embed several chunks in one
 * request.  The repository uses this only after checking its immutable
 * `(spaceId, contentHash)` cache, so a successful chunk is never sent again
 * merely because an index generation was rebuilt.
 */
interface BatchTextEmbedder {
    fun embedBatch(texts: List<String>): List<FloatArray>
}

/**
 * Suspending boundary used by long running remote embedding operations.
 *
 * Implementations must propagate coroutine cancellation to their transport and
 * must not retain a repository/SQLite transaction while the request is in
 * flight.  [BatchTextEmbedder] remains the synchronous compatibility boundary
 * for local fixtures and existing callers.
 */
interface CancellableBatchTextEmbedder {
    suspend fun embedBatchCancellable(texts: List<String>): List<FloatArray>
}

/**
 * A remote embedding call reached an uncertain transport boundary.  Callers
 * must persist this as UNKNOWN_OUTCOME and require an explicit duplicate
 * charge acknowledgement before retrying; this exception is never retried by
 * an import worker automatically.
 */
class EmbeddingUnknownOutcomeException(
    cause: Throwable? = null,
) : RuntimeException("UNKNOWN_OUTCOME: embedding result is uncertain", cause)

/**
 * Local retrieval fixture space. This is not an ONNX model pack and must not be mixed with
 * a later model-pack spaceId. Production ONNX weights are still a separate authorized pack.
 */
class HashingTextEmbedder(override val dimension: Int = 32) : TextEmbedder {
    override val spaceId: String = "local-hash-v1-d$dimension"

    override fun embed(text: String): FloatArray {
        val out = FloatArray(dimension)
        val payload = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < dimension) {
            val digest = MessageDigest.getInstance("SHA-256").digest(payload + byteArrayOf((offset / 32).toByte()) + byteArrayOf((offset % 32).toByte()))
            val take = minOf(32, dimension - offset)
            for (i in 0 until take) {
                out[offset + i] = ((digest[i].toInt() and 0xFF) / 127.5f) - 1f
            }
            offset += take
        }
        var norm = 0.0
        for (v in out) norm += v * v
        val scale = kotlin.math.sqrt(norm).toFloat().coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] /= scale
        return out
    }
}

class CosineIndex(private val dimension: Int) {
    private val rows = linkedMapOf<String, FloatArray>()

    fun add(id: String, vector: FloatArray) {
        require(vector.size == dimension)
        rows[id] = vector
    }

    fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> {
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
