// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.util.concurrent.atomic.AtomicLong

/**
 * Reusable native ANN index lifecycle keyed by
 * `(knowledgeBaseId, embeddingSpaceId, dimension, generationId)`.
 *
 * Previously every query rebuilt the index (`create` + `add` of all vectors +
 * `search` + `close`), which unit tests with hundreds of chunks hide but
 * which dominates query cost on real 300–500 file knowledge bases.
 *
 * Lifecycle:
 * - query reuses the cached handle while the active generation id and the
 *   exact member id set still match; the member set check also catches drift
 *   (for example a deleted document) without re-hashing vectors;
 * - a new generation publishes under a new id, so the next query builds once
 *   and atomically switches; the orphaned entry is evicted by bound;
 * - eviction and [close] release native handles; a process restart rebuilds
 *   from the SQLite vector truth, so the cache is purely derived state;
 * - the API embedding query-vector cache is a different layer (billable
 *   provider vectors) and is untouched by this cache.
 */
class VectorIndexCache(
    private val factory: VectorIndexFactory?,
    private val maxEntries: Int = 4,
) {
    data class Key(
        val knowledgeBaseId: String,
        val spaceId: String,
        val dimension: Int,
        val generationId: String,
    )

    data class Stats(
        val builds: Long,
        val reuseHits: Long,
        val evictions: Long,
    )

    private data class Entry(val index: VectorIndexPort, val memberIds: Set<String>)

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>(maxEntries, 0.75f, true)
    private val builds = AtomicLong(0)
    private val reuseHits = AtomicLong(0)
    private val evictions = AtomicLong(0)

    /**
     * Return the cached index when [memberIds] exactly match the cached set,
     * else null.  Callers load vectors and [put] on a miss.
     */
    fun get(key: Key, memberIds: Set<String>): VectorIndexPort? = synchronized(lock) {
        val entry = entries[key]
        if (entry == null || entry.memberIds != memberIds) return@synchronized null
        // Touch for access-order LRU.
        entries.remove(key)
        entries[key] = entry
        reuseHits.incrementAndGet()
        entry.index
    }

    fun put(key: Key, memberIds: Set<String>, index: VectorIndexPort) = synchronized(lock) {
        entries.remove(key)?.index?.close()
        entries[key] = Entry(index, memberIds)
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            runCatching { eldest.value.index.close() }
            evictions.incrementAndGet()
        }
        builds.incrementAndGet()
    }

    fun invalidateKnowledgeBase(knowledgeBaseId: String) = synchronized(lock) {
        entries.keys.filter { it.knowledgeBaseId == knowledgeBaseId }.forEach { key ->
            entries.remove(key)?.index?.close()
        }
    }

    fun stats(): Stats = Stats(builds.get(), reuseHits.get(), evictions.get())

    fun close() = synchronized(lock) {
        entries.values.forEach { runCatching { it.index.close() } }
        entries.clear()
    }
}

/** In-process deterministic [VectorIndexPort] used when no native factory is configured. */
class CosineVectorIndexPort(
    override val spaceId: String,
    override val dimension: Int,
) : VectorIndexPort {
    private val delegate = CosineIndex(dimension)

    override fun add(id: String, vector: FloatArray) = delegate.add(id, vector)

    override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> =
        // Secondary id order keeps fusion input deterministic across runs.
        delegate.search(query, Int.MAX_VALUE)
            .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
            .take(topK)
}
