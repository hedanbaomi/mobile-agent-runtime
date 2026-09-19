// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.util.concurrent.atomic.AtomicLong

/**
 * Thrown when a vector-index build finishes after its knowledge base was
 * invalidated or the cache was closed.  The built handle is already closed
 * and never published: callers must not serve it, and must not retry
 * blindly against a moved-on generation.
 */
open class StaleVectorBuildException(
    val knowledgeBaseId: String,
    detail: String? = null,
) : IllegalStateException(
    if (detail == null) {
        "Vector index build for knowledge base $knowledgeBaseId went stale before publish"
    } else {
        "Vector index build for knowledge base $knowledgeBaseId $detail"
    },
)

/**
 * Thrown when a build was about to publish an index holding fewer vectors than
 * the member set it claims (a silently skipped or corrupt embedding).  The
 * handle has already been closed and is never published: serving it would
 * answer queries from an incomplete generation.
 *
 * This extends [StaleVectorBuildException] on purpose.  Existing publish-path
 * callers (see `KnowledgeRepository` retrieval) already degrade the vector
 * channel to lexical-only for a stale build, and an incomplete index deserves
 * exactly the same fail-closed treatment instead of a partial "success".
 */
class IncompleteVectorIndexException(
    knowledgeBaseId: String,
    val expectedVectors: Int,
    val actualVectors: Int,
) : StaleVectorBuildException(
    knowledgeBaseId,
    "was built with $actualVectors of $expectedVectors vectors; refusing to publish a partial index",
)

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
 *
 * Active-use safety (b07 follow-up finding B): the cache never hands out a
 * raw [VectorIndexPort].  Callers hold a [VectorIndexLease]; eviction,
 * replacement, invalidation, and [close] only *retire* entries with active
 * leases and free the native handle once the last lease is released.  A
 * search in progress can therefore never observe a closed/freed index.
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
        /** Builds rejected because they did not cover their claimed member set. */
        val incompleteRejects: Long = 0,
    )

    /**
     * Borrowed-handle guard.  The index stays alive at least until [close];
     * closing is idempotent, so `use { }` blocks and early returns are safe.
     */
    inner class VectorIndexLease internal constructor(
        val index: VectorIndexPort,
        private val entry: Entry,
    ) : AutoCloseable {
        private var released = false

        override fun close() = release()

        fun release() = synchronized(lock) {
            if (released) return@synchronized
            released = true
            entry.refCount--
            check(entry.refCount >= 0) { "Vector index lease released twice" }
            if (entry.refCount == 0 && entry.retired) {
                retired.remove(entry)
                runCatching { entry.index.close() }
            }
        }
    }

    /**
     * Cache entry with identity equality.  This must stay a plain class:
     * `refCount` and `retired` mutate while the entry sits in the [retired]
     * set, and data-class equality over mutable fields would corrupt hash
     * removal and strand closed handles (3f75 finding C).
     */
    internal class Entry(
        val index: VectorIndexPort,
        val memberIds: Set<String>,
        var refCount: Int = 0,
        var retired: Boolean = false,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>(maxEntries, 0.75f, true)
    /** Retired entries with outstanding leases; freed when the last lease ends. */
    private val retired = linkedSetOf<Entry>()
    /** Per-key build monitors so concurrent misses for one key build once. */
    private val buildLocks = linkedMapOf<Key, BuildGate>()
    /**
     * Lifecycle epochs (3f75 finding D).  [closed] is terminal: after
     * [close], no build may publish and no new entry may appear.
     * [globalEpoch] moves on [close]; [kbEpochs] moves per knowledge base on
     * [invalidateKnowledgeBase].  A builder captures both before building and
     * must observe them unchanged before publishing.
     */
    private var closed = false
    private var globalEpoch = 0L
    private val kbEpochs = linkedMapOf<String, Long>()
    private val builds = AtomicLong(0)
    private val reuseHits = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val incompleteRejects = AtomicLong(0)

    /** Test/debugging introspection: retired entries awaiting their last lease. */
    internal fun retiredCount(): Int = synchronized(lock) { retired.size }

    /**
     * Acquire the cached index for [key] when [memberIds] exactly match the
     * cached set, else null.  Callers build and [publish] on a miss, or use
     * [getOrBuild] for single-flight construction.
     */
    fun acquire(key: Key, memberIds: Set<String>): VectorIndexLease? = synchronized(lock) {
        val entry = entries[key]
        if (entry == null || entry.retired || entry.memberIds != memberIds) return@synchronized null
        // Touch for access-order LRU.
        entries.remove(key)
        entries[key] = entry
        entry.refCount++
        reuseHits.incrementAndGet()
        VectorIndexLease(entry.index, entry)
    }

    /**
     * Publish a freshly built index.  A live entry for [key] is retired, not
     * closed: active leases keep working, future [acquire] calls see the new
     * index, and the old handle is freed when its last lease is released.
     * LRU eviction retires the same way.
     */
    fun publish(key: Key, memberIds: Set<String>, index: VectorIndexPort) = synchronized(lock) {
        check(!closed) { "VectorIndexCache is closed" }
        incompleteness(key, index, memberIds)?.let { failure ->
            runCatching { index.close() }
            throw failure
        }
        publishLocked(key, memberIds, index)
    }

    /**
     * Fail-closed completeness gate.  An index that reports a vector count
     * different from [memberIds] is a partial build (for example a skipped or
     * corrupt embedding blob); callers close it and never publish it, so no
     * query can be answered from an incomplete generation.  Ports that report
     * `-1` (count unknown) are trusted as before.
     *
     * Returns the failure to throw, or null when the index is complete.  The
     * caller owns closing the surplus handle exactly once.
     */
    private fun incompleteness(
        key: Key,
        index: VectorIndexPort,
        memberIds: Set<String>,
    ): IncompleteVectorIndexException? {
        val reported = index.vectorCount
        if (reported < 0 || reported == memberIds.size) return null
        incompleteRejects.incrementAndGet()
        return IncompleteVectorIndexException(key.knowledgeBaseId, memberIds.size, reported)
    }

    private fun publishLocked(key: Key, memberIds: Set<String>, index: VectorIndexPort) {
        entries.remove(key)?.let(::retireLocked)
        entries[key] = Entry(index, memberIds)
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            retireLocked(eldest.value)
            evictions.incrementAndGet()
        }
        builds.incrementAndGet()
    }

    /**
     * Single-flight gate for one key.  [holders] counts every caller that
     * has taken this gate and not yet left it.  The gate stays in the map
     * until the last holder leaves, so a failed first build cannot orphan a
     * waiter on a stale monitor while a newcomer starts a second concurrent
     * build (9f5257 finding B).  Waiters serialize: after a failure the next
     * holder retries the build alone.
     */
    private class BuildGate {
        var holders: Int = 0
    }

    /**
     * Single-flight miss path: concurrent callers for one key share a single
     * [build] result.  The build runs outside the cache lock but under the
     * key gate; a loser that finds a fresh live entry closes its own
     * surplus handle instead of orphaning the winner's lease.
     *
     * Staleness (3f75 finding D): the builder captures the lifecycle epochs
     * before building.  If the cache was closed or this knowledge base was
     * invalidated while building, the built handle is closed and never
     * published — an invalidated generation cannot be resurrected, and a
     * closed cache stays empty.
     */
    fun getOrBuild(key: Key, memberIds: Set<String>, build: () -> VectorIndexPort): VectorIndexLease {
        val startEpoch = synchronized(lock) {
            check(!closed) { "VectorIndexCache is closed" }
            globalEpoch to (kbEpochs[key.knowledgeBaseId] ?: 0L)
        }
        acquire(key, memberIds)?.let { return it }
        val gate = synchronized(lock) {
            val existing = buildLocks.getOrPut(key) { BuildGate() }
            existing.holders++
            existing
        }
        try {
            synchronized(gate) {
                acquire(key, memberIds)?.let { return it }
                val index = build()
                var published = false
                var surplusClosed = false
                try {
                    incompleteness(key, index, memberIds)?.let { failure ->
                        surplusClosed = true
                        runCatching { index.close() }
                        throw failure
                    }
                    synchronized(lock) {
                        if (closed) {
                            throw IllegalStateException("VectorIndexCache is closed")
                        }
                        if (globalEpoch != startEpoch.first ||
                            (kbEpochs[key.knowledgeBaseId] ?: 0L) != startEpoch.second
                        ) {
                            throw StaleVectorBuildException(key.knowledgeBaseId)
                        }
                        // Re-check under the cache lock: a concurrent publish
                        // (invalidation race) may have installed a live entry.
                        val existing = entries[key]
                        if (existing != null && !existing.retired && existing.memberIds == memberIds) {
                            runCatching { index.close() }
                            existing.refCount++
                            reuseHits.incrementAndGet()
                            return VectorIndexLease(existing.index, existing)
                        }
                        publishLocked(key, memberIds, index)
                        published = true
                        val installed = checkNotNull(entries[key]) {
                            "Vector index publish did not install the entry"
                        }
                        installed.refCount++
                        reuseHits.incrementAndGet()
                        return VectorIndexLease(installed.index, installed)
                    }
                } catch (failure: Throwable) {
                    // Only the unpublished surplus may be closed here: a
                    // published entry belongs to the cache (and possibly to
                    // leases acquired above), never to this builder.
                    if (!published && !surplusClosed) runCatching { index.close() }
                    throw failure
                }
            }
        } finally {
            synchronized(lock) {
                // The gate leaves the map only with its last holder: a failed
                // first build cannot strand a waiter on a stale monitor while
                // a newcomer opens a second concurrent build.  After close(),
                // the map is already clear and a fresh gate (if any) belongs
                // to post-close callers, which the closed check rejects.
                gate.holders--
                if (gate.holders == 0 && buildLocks[key] === gate) buildLocks.remove(key)
            }
        }
    }

    fun invalidateKnowledgeBase(knowledgeBaseId: String) = synchronized(lock) {
        kbEpochs[knowledgeBaseId] = (kbEpochs[knowledgeBaseId] ?: 0L) + 1
        entries.keys.filter { it.knowledgeBaseId == knowledgeBaseId }.forEach { key ->
            entries.remove(key)?.let(::retireLocked)
        }
    }

    fun stats(): Stats = Stats(builds.get(), reuseHits.get(), evictions.get(), incompleteRejects.get())

    /**
     * Terminal close.  Retires every live entry (leased handles close when
     * their last lease is released) and forbids every future publish:
     * in-flight builders observe the epoch move and close their surplus
     * instead of resurrecting the cache.
     */
    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        globalEpoch++
        entries.values.forEach(::retireLocked)
        entries.clear()
        buildLocks.clear()
    }

    private fun retireLocked(entry: Entry) {
        if (entry.retired) return
        entry.retired = true
        if (entry.refCount == 0) {
            runCatching { entry.index.close() }
        } else {
            retired.add(entry)
        }
    }
}

/** In-process deterministic [VectorIndexPort] used when no native factory is configured. */
class CosineVectorIndexPort(
    override val spaceId: String,
    override val dimension: Int,
) : VectorIndexPort {
    private val delegate = CosineIndex(dimension)
    private val uniqueIds = linkedSetOf<String>()

    /** Exact count: [CosineIndex] keeps one row per unique id. */
    override val vectorCount: Int get() = uniqueIds.size

    override fun add(id: String, vector: FloatArray) {
        delegate.add(id, vector)
        uniqueIds.add(id)
    }

    override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> =
        // Secondary id order keeps fusion input deterministic across runs.
        delegate.search(query, Int.MAX_VALUE)
            .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
            .take(topK)
}
