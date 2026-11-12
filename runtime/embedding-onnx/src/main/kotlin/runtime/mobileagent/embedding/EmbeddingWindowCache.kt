// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

/** One verified model session owns this boundary; vectors never cross model spaces. */
internal class EmbeddingWindowCache(
    private val maxEntries: Int = 128,
    private val maxPayloadBytes: Long = 1_048_576,
) {
    private class Key(val ids: LongArray, val mask: LongArray, val types: LongArray) {
        constructor(encoded: BertWordPieceTokenizer.Encoded) : this(encoded.inputIds, encoded.attentionMask, encoded.tokenTypeIds)
        fun owned() = Key(ids.copyOf(), mask.copyOf(), types.copyOf())
        val bytes: Long get() = (ids.size.toLong() + mask.size + types.size) * Long.SIZE_BYTES
        override fun hashCode(): Int = (ids.contentHashCode() * 31 + mask.contentHashCode()) * 31 + types.contentHashCode()
        override fun equals(other: Any?): Boolean = other is Key && ids.contentEquals(other.ids) &&
            mask.contentEquals(other.mask) && types.contentEquals(other.types)
    }
    private data class Entry(val vector: FloatArray, val payloadBytes: Long)
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var payloadBytes = 0L
    private var closed = false

    init {
        require(maxEntries in 0..128)
        require(maxPayloadBytes in 0..1_048_576)
    }

    /** Returns a copy of the cached embedding vector for [encoded], or null on a cache miss. */
    @Synchronized
    fun get(encoded: BertWordPieceTokenizer.Encoded): FloatArray? {
        check(!closed) { "Local embedding runtime is closed" }
        checkInterrupted()
        return entries[Key(encoded)]?.vector?.copyOf()
    }

    /** Stores [vector] in the cache for [encoded], evicting LRU entries as required by budgets. */
    @Synchronized
    fun put(encoded: BertWordPieceTokenizer.Encoded, vector: FloatArray) {
        check(!closed) { "Local embedding runtime is closed" }
        checkInterrupted()
        val key = Key(encoded)
        val bytes = key.bytes + vector.size.toLong() * Float.SIZE_BYTES
        if (maxEntries > 0 && bytes <= maxPayloadBytes && vector.all { it.isFinite() }) {
            val old = entries.remove(key)
            if (old != null) {
                payloadBytes -= old.payloadBytes
            }
            while (entries.size >= maxEntries || payloadBytes + bytes > maxPayloadBytes) {
                val iterator = entries.entries.iterator()
                payloadBytes -= iterator.next().value.payloadBytes
                iterator.remove()
            }
            entries[key.owned()] = Entry(vector.copyOf(), bytes)
            payloadBytes += bytes
        }
    }

    /** Coalesces identical in-flight windows; the ONNX session already executes one window at a time. */
    @Synchronized
    fun vector(encoded: BertWordPieceTokenizer.Encoded, infer: () -> FloatArray): FloatArray {
        val cached = get(encoded)
        if (cached != null) return cached
        val vector = infer()
        put(encoded, vector)
        return vector
    }

    /** Serializes shutdown with inference and releases every token/vector reference. */
    @Synchronized
    fun close(release: () -> Unit = {}) {
        if (closed) return
        closed = true
        entries.clear()
        payloadBytes = 0
        release()
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Local embedding interrupted")
    }
}
