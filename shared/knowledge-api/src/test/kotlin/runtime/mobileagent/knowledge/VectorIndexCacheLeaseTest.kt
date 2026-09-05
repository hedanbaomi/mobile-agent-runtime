// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Active-use lifecycle tests for [VectorIndexCache] (b07 follow-up finding B,
 * 3f75 findings C/D).
 *
 * A borrowed handle must never be closed while a search is using it:
 * invalidate, LRU eviction, same-key replacement, and [VectorIndexCache.close]
 * only retire the entry and free the native handle once the last lease is
 * released.  Concurrent misses for one key share a single build.  Retired
 * entries leave no residue once released, and a build that finishes after
 * invalidation/close is discarded instead of resurrecting the cache.
 */
class VectorIndexCacheLeaseTest {
    private class FakeIndex : VectorIndexPort {
        override val spaceId: String = "space"
        override val dimension: Int = 2
        val closeCount = AtomicInteger(0)
        @Volatile var closed = false
        val searchEntered = CountDownLatch(1)
        val searchRelease = CountDownLatch(1)

        override fun add(id: String, vector: FloatArray) = Unit

        override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> {
            searchEntered.countDown()
            check(searchRelease.await(10, TimeUnit.SECONDS)) { "search gate timed out" }
            check(!closed) { "index is closed" }
            return listOf("doc1" to 1.0f)
        }

        override fun close() {
            closed = true
            closeCount.incrementAndGet()
        }
    }

    private fun key(generation: String = "g1", kb: String = "kbA") =
        VectorIndexCache.Key(kb, "space", 2, generation)

    private val ids = setOf("doc1")

    @Test
    fun invalidateDuringSearchDoesNotCloseBorrowedHandle() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val index = FakeIndex()
        cache.publish(key(), ids, index)

        val outcome = AtomicReference<String>()
        val lease = checkNotNull(cache.acquire(key(), ids))
        val query = thread {
            outcome.set(runCatching { lease.index.search(floatArrayOf(1f, 0f), 1).toString() }
                .exceptionOrNull()?.message ?: "ok")
        }
        assertTrue(index.searchEntered.await(10, TimeUnit.SECONDS))
        cache.invalidateKnowledgeBase("kbA")
        // Retired but still borrowed: the native handle must stay alive.
        assertEquals(0, index.closeCount.get())
        index.searchRelease.countDown()
        query.join(10_000)
        assertEquals("ok", outcome.get())
        lease.close()
        assertEquals(1, index.closeCount.get())
        // A later acquire misses: the retired entry is gone.
        assertNull(cache.acquire(key(), ids))
        assertEquals(0, cache.retiredCount(), "the released entry must leave no residue")
    }

    @Test
    fun lruEvictionDuringSearchDefersCloseUntilRelease() {
        val cache = VectorIndexCache(null, maxEntries = 1)
        val index = FakeIndex()
        cache.publish(key(), ids, index)

        val outcome = AtomicReference<String>()
        val lease = checkNotNull(cache.acquire(key(), ids))
        val query = thread {
            outcome.set(runCatching { lease.index.search(floatArrayOf(1f, 0f), 1).toString() }
                .exceptionOrNull()?.message ?: "ok")
        }
        assertTrue(index.searchEntered.await(10, TimeUnit.SECONDS))
        cache.publish(key(kb = "kbB"), ids, FakeIndex())
        assertEquals(0, index.closeCount.get())
        index.searchRelease.countDown()
        query.join(10_000)
        assertEquals("ok", outcome.get())
        lease.close()
        assertEquals(1, index.closeCount.get())
    }

    @Test
    fun sameKeyReplacementKeepsOldLeaseAliveAndRoutesNewAcquiresToNewIndex() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val old = FakeIndex()
        cache.publish(key(), ids, old)
        val oldLease = checkNotNull(cache.acquire(key(), ids))

        val replacement = FakeIndex()
        // Unblock the replacement's search gate immediately; only the old
        // handle uses the blocking search below.
        replacement.searchRelease.countDown()
        cache.publish(key(), ids, replacement)
        assertEquals(0, old.closeCount.get())

        cache.acquire(key(), ids)!!.use { fresh ->
            assertSame(replacement, fresh.index)
        }
        oldLease.close()
        assertEquals(1, old.closeCount.get())
        assertEquals(0, replacement.closeCount.get())
    }

    @Test
    fun cacheCloseWhileLeasedDefersCloseUntilRelease() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val index = FakeIndex().also { it.searchRelease.countDown() }
        cache.publish(key(), ids, index)
        val lease = checkNotNull(cache.acquire(key(), ids))
        cache.close()
        assertEquals(0, index.closeCount.get())
        lease.index.search(floatArrayOf(1f, 0f), 1)
        lease.close()
        assertEquals(1, index.closeCount.get())
    }

    @Test
    fun concurrentSameKeyMissBuildsOnce() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val builds = AtomicInteger(0)
        val buildEntered = CountDownLatch(1)
        val buildRelease = CountDownLatch(1)
        val results = (1..8).map {
            thread {
                cache.getOrBuild(key(), ids) {
                    builds.incrementAndGet()
                    buildEntered.countDown()
                    check(buildRelease.await(10, TimeUnit.SECONDS))
                    FakeIndex().also { it.searchRelease.countDown() }
                }.use { lease ->
                    lease.index.search(floatArrayOf(1f, 0f), 1)
                }
            }
        }
        // Exactly one thread enters the build; the rest queue on the key
        // monitor.  Give them a moment to arrive, then release the builder.
        assertTrue(buildEntered.await(10, TimeUnit.SECONDS))
        Thread.sleep(500)
        buildRelease.countDown()
        results.forEach { it.join(10_000) }
        assertEquals(1, builds.get(), "concurrent misses for one key must share a single build")
        assertEquals(1, cache.stats().builds)
    }

    @Test
    fun sequentialReuseSharesHandleAndClosesExactlyOnce() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val index = FakeIndex().also { it.searchRelease.countDown() }
        cache.publish(key(), ids, index)
        repeat(3) {
            cache.acquire(key(), ids)!!.use { lease ->
                assertSame(index, lease.index)
            }
        }
        assertEquals(0, index.closeCount.get())
        cache.close()
        assertEquals(1, index.closeCount.get())
    }

    @Test
    fun releasedRetiredEntriesLeaveNoResidue() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        repeat(1000) {
            val index = FakeIndex().also { it.searchRelease.countDown() }
            cache.publish(key(), ids, index)
            val lease = checkNotNull(cache.acquire(key(), ids))
            cache.invalidateKnowledgeBase("kbA")
            lease.close()
            assertEquals(1, index.closeCount.get())
        }
        assertEquals(0, cache.retiredCount(), "released retired entries must not accumulate")
    }

    @Test
    fun buildFinishingAfterInvalidateIsDiscarded() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val built = FakeIndex().also { it.searchRelease.countDown() }
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val worker = thread {
            try {
                cache.getOrBuild(key(), ids) {
                    entered.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS))
                    built
                }
                throw AssertionError("stale build must not return a lease")
            } catch (stale: StaleVectorBuildException) {
                assertEquals("kbA", stale.knowledgeBaseId)
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        cache.invalidateKnowledgeBase("kbA")
        proceed.countDown()
        worker.join(10_000)
        assertEquals(1, built.closeCount.get(), "the stale build must be closed exactly once")
        assertNull(cache.acquire(key(), ids), "an invalidated generation must not be resurrected")
    }

    @Test
    fun buildFinishingAfterCloseNeverPublishes() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val built = FakeIndex().also { it.searchRelease.countDown() }
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val worker = thread {
            try {
                cache.getOrBuild(key(), ids) {
                    entered.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS))
                    built
                }
                throw AssertionError("build after close must not return a lease")
            } catch (_: IllegalStateException) {
                // Either the terminal closed signal or the stale-build signal.
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        cache.close()
        proceed.countDown()
        worker.join(10_000)
        assertEquals(1, built.closeCount.get())
        assertNull(cache.acquire(key(), ids))
        // Future builds fail closed instead of resurrecting the cache.
        assertThrows(IllegalStateException::class.java) {
            cache.getOrBuild(key(), ids) { FakeIndex() }
        }
        assertThrows(IllegalStateException::class.java) {
            cache.publish(key(), ids, FakeIndex())
        }
    }

    @Test
    fun invalidateOfOtherKbDoesNotStaleThisBuild() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val built = FakeIndex().also { it.searchRelease.countDown() }
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val lease = AtomicReference<VectorIndexCache.VectorIndexLease?>()
        val worker = thread {
            lease.set(cache.getOrBuild(key(), ids) {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                built
            })
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        cache.invalidateKnowledgeBase("kbB")
        proceed.countDown()
        worker.join(10_000)
        checkNotNull(lease.get()).use {
            assertSame(built, it.index)
        }
        assertEquals(0, built.closeCount.get())
    }
}
