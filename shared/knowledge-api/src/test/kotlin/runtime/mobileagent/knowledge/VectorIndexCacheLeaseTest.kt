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

    @Test
    fun failedFirstBuildDoesNotSplitSingleFlight() {
        // 9f5257 finding B: the first builder fails while the second already
        // waits on the same gate; a third arrival must join the same gate
        // instead of opening a second concurrent build.
        val cache = VectorIndexCache(null, maxEntries = 4)
        val firstEntered = CountDownLatch(1)
        val failFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val thirdEnteredBuild = CountDownLatch(1)
        val concurrentBuilds = AtomicInteger(0)
        val maxConcurrentBuilds = AtomicInteger(0)
        val secondResult = AtomicReference<String>()
        val thirdResult = AtomicReference<String>()
        val first = thread {
            try {
                cache.getOrBuild(key(), ids) {
                    firstEntered.countDown()
                    check(failFirst.await(10, TimeUnit.SECONDS))
                    throw IllegalStateException("injected build failure")
                }.close()
                throw AssertionError("failed build must propagate")
            } catch (expected: IllegalStateException) {
                assertEquals("injected build failure", expected.message)
            }
        }
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
        val second = thread {
            secondResult.set(cache.getOrBuild(key(), ids) {
                val now = concurrentBuilds.incrementAndGet()
                maxConcurrentBuilds.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    secondEntered.countDown()
                    check(releaseSecond.await(10, TimeUnit.SECONDS))
                    FakeIndex().also { it.searchRelease.countDown() }
                } finally {
                    concurrentBuilds.decrementAndGet()
                }
            }.use { "second-ok" })
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(Thread.State.BLOCKED, second.state, "second must wait on the first builder's gate")
        failFirst.countDown()
        first.join(10_000)
        assertTrue(secondEntered.await(10, TimeUnit.SECONDS))
        val third = thread {
            thirdResult.set(cache.getOrBuild(key(), ids) {
                thirdEnteredBuild.countDown()
                FakeIndex().also { it.searchRelease.countDown() }
            }.use { "third-ok" })
        }
        // The third arrival must NOT start a second concurrent build while
        // the second still builds: it waits on the same gate.
        assertTrue(!thirdEnteredBuild.await(1, TimeUnit.SECONDS))
        releaseSecond.countDown()
        second.join(10_000)
        third.join(10_000)
        assertEquals("second-ok", secondResult.get())
        assertEquals("third-ok", thirdResult.get())
        assertEquals(1, maxConcurrentBuilds.get(), "at most one build callback may run at a time")
        assertEquals(1, cache.stats().builds, "one publish for the shared key")
    }

    @Test
    fun concurrentSearchAndInvalidateNeverObservesClosedHandle() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val failures = AtomicInteger(0)
        val firstFailure = AtomicReference<Throwable>()
        val stop = CountDownLatch(1)
        val searchers = (1..4).map { index ->
            thread(name = "lease-searcher-$index") {
                while (!stop.await(5, TimeUnit.MILLISECONDS)) {
                    try {
                        cache.getOrBuild(key(), ids) {
                            FakeIndex().also { it.searchRelease.countDown() }
                        }.use { lease ->
                            lease.index.search(floatArrayOf(1f, 0f), 1)
                        }
                    } catch (_: StaleVectorBuildException) {
                        // In-flight rebuild after invalidate is discarded, not a closed handle.
                    } catch (failure: Throwable) {
                        firstFailure.compareAndSet(null, failure)
                        failures.incrementAndGet()
                    }
                }
            }
        }
        val invalidator = thread(name = "lease-invalidator") {
            repeat(50) {
                cache.invalidateKnowledgeBase("kbA")
                Thread.sleep(1)
            }
            stop.countDown()
        }
        invalidator.join(10_000)
        assertTrue(!invalidator.isAlive, "invalidator must finish")
        searchers.forEach { worker ->
            worker.join(10_000)
            assertTrue(!worker.isAlive, "${worker.name} must finish")
        }
        val observed = firstFailure.get()
        assertEquals(
            0,
            failures.get(),
            "concurrent search/invalidate must not observe a closed handle" +
                (observed?.let { ": ${it.javaClass.name}: ${it.message}" } ?: ""),
        )
        cache.close()
    }
    /**
     * An index that reports a vector count different from the member set it
     * claims is a partial build.  It must be closed and never published, so no
     * query can ever be answered from an incomplete generation.
     */
    @Test
    fun publishRejectsIncompleteIndexAndClosesItOnce() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val partial = ReportedIndex(reported = 1)
        val failure = assertThrows(IncompleteVectorIndexException::class.java) {
            cache.publish(key(), setOf("doc1", "doc2"), partial)
        }
        assertEquals("kbA", failure.knowledgeBaseId)
        assertEquals(2, failure.expectedVectors)
        assertEquals(1, failure.actualVectors)
        assertEquals(1, partial.closeCount.get())
        assertEquals(1L, cache.stats().incompleteRejects)
        assertEquals(0L, cache.stats().builds)
        assertNull(cache.acquire(key(), setOf("doc1", "doc2")))
        cache.close()
    }

    @Test
    fun getOrBuildRejectsIncompleteIndexAndClosesItOnce() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val partial = ReportedIndex(reported = 1)
        assertThrows(IncompleteVectorIndexException::class.java) {
            cache.getOrBuild(key(), setOf("doc1", "doc2")) { partial }
        }
        assertEquals(1, partial.closeCount.get())
        assertEquals(1L, cache.stats().incompleteRejects)
        assertEquals(0L, cache.stats().builds)
        assertNull(cache.acquire(key(), setOf("doc1", "doc2")))
        cache.close()
    }

    /**
     * The repository's publish-path catch handles a stale build by degrading to
     * lexical-only.  An incomplete index must take that same fail-closed path
     * instead of reaching the caller as a partial success.
     */
    @Test
    fun incompleteIndexIsHandledByTheStaleBuildCatch() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val failure = assertThrows(StaleVectorBuildException::class.java) {
            cache.publish(key(), setOf("doc1", "doc2"), ReportedIndex(reported = 0))
        }
        assertTrue(failure is IncompleteVectorIndexException)
        cache.close()
    }

    @Test
    fun completeCountIsPublishedAndReused() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        val index = ReportedIndex(reported = 2)
        cache.publish(key(), setOf("doc1", "doc2"), index)
        cache.acquire(key(), setOf("doc1", "doc2"))!!.use { lease -> assertSame(index, lease.index) }
        assertEquals(1L, cache.stats().builds)
        assertEquals(1L, cache.stats().reuseHits)
        assertEquals(0L, cache.stats().incompleteRejects)
        cache.close()
        assertEquals(1, index.closeCount.get())
    }

    /** Minimal port with a reported count; ports that report -1 keep legacy behaviour. */
    private class ReportedIndex(private val reported: Int) : VectorIndexPort {
        override val spaceId: String = "space"
        override val dimension: Int = 2
        override val vectorCount: Int get() = reported
        val closeCount = AtomicInteger(0)

        override fun add(id: String, vector: FloatArray) = Unit

        override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> =
            listOf("doc1" to 1.0f)

        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
