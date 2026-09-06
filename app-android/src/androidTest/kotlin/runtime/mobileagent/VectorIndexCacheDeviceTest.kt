// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.knowledge.VectorIndexCache
import runtime.mobileagent.vector.UsearchVectorIndex

/**
 * Native ANN lifecycle on-device (b07 follow-up finding B): a leased USearch
 * handle must survive invalidate/eviction/replace/close until its last lease
 * is released, and must be freed exactly once afterwards.  This exercises the
 * real JNI close path — not a fake — without manufacturing a true use-after-
 * free: the test asserts the new lifecycle guarantee holds.
 */
@RunWith(AndroidJUnit4::class)
class VectorIndexCacheDeviceTest {
    private fun nativeIndex(): UsearchVectorIndex {
        val index = UsearchVectorIndex("device-space", 8, 2)
        index.add("a", FloatArray(8) { 1.0f })
        index.add("b", FloatArray(8) { if (it == 0) 1.0f else 0.0f })
        return index
    }

    private fun key(kb: String = "kbA", generation: String = "g1") =
        VectorIndexCache.Key(kb, "device-space", 8, generation)

    private val ids = setOf("a", "b")
    private val query = FloatArray(8) { 1.0f }

    @Test
    fun nativeLeaseSurvivesInvalidateUntilRelease() {
        val cache = VectorIndexCache(null, maxEntries = 4)
        cache.publish(key(), ids, nativeIndex())

        val lease = checkNotNull(cache.acquire(key(), ids))
        cache.invalidateKnowledgeBase("kbA")
        // Retired but borrowed: the native handle must still answer.
        val found = lease.index.search(query, 2)
        assertEquals(listOf("a", "b"), found.map { it.first })
        lease.close()
        // Freed exactly once afterwards: use now fails instead of touching a
        // dangling pointer.
        assertThrows(IllegalStateException::class.java) { lease.index.search(query, 1) }
        cache.close()
    }

    @Test
    fun nativeLeaseSurvivesLruEvictionAndReplacement() {
        val cache = VectorIndexCache(null, maxEntries = 1)
        cache.publish(key(), ids, nativeIndex())
        val lease = checkNotNull(cache.acquire(key(), ids))

        cache.publish(key(kb = "kbB"), ids, nativeIndex())
        assertEquals(listOf("a", "b"), lease.index.search(query, 2).map { it.first })
        lease.close()
        assertThrows(IllegalStateException::class.java) { lease.index.search(query, 1) }

        val replacement = nativeIndex()
        cache.publish(key(), ids, replacement)
        val fresh = checkNotNull(cache.acquire(key(), ids))
        assertTrue(fresh.index === replacement)
        fresh.close()
        cache.close()
    }

    @Test
    fun concurrentSearchAndInvalidateNeverObservesClosedHandle() {
        // CI job 101318610136 failed here with expected:<0> but was:<1> after
        // swallowing the IllegalStateException.  Keep the first exception and
        // require every worker to finish so a closed-handle or native thread-
        // slot failure cannot hide behind a join timeout.
        val cache = VectorIndexCache(null, maxEntries = 4)
        val failures = AtomicInteger(0)
        val firstFailure = AtomicReference<Throwable>()
        val stop = CountDownLatch(1)
        val searchers = (1..4).map { index ->
            thread(name = "vector-searcher-$index") {
                while (!stop.await(5, TimeUnit.MILLISECONDS)) {
                    try {
                        val lease = cache.acquire(key(), ids)
                        if (lease == null) {
                            cache.publish(key(), ids, nativeIndex())
                        } else {
                            lease.use { it.index.search(query, 2) }
                        }
                    } catch (failure: Throwable) {
                        firstFailure.compareAndSet(null, failure)
                        failures.incrementAndGet()
                    }
                }
            }
        }
        val invalidator = thread(name = "vector-invalidator") {
            repeat(50) {
                cache.invalidateKnowledgeBase("kbA")
                Thread.sleep(5)
            }
            stop.countDown()
        }
        invalidator.join(30_000)
        assertTrue("invalidator must finish", !invalidator.isAlive)
        searchers.forEach { worker ->
            worker.join(30_000)
            assertTrue("${worker.name} must finish", !worker.isAlive)
        }
        val observed = firstFailure.get()
        assertEquals(
            "concurrent search/invalidate must not observe a closed or failed native handle" +
                (observed?.let { ": ${it.javaClass.name}: ${it.message}\n${it.stackTraceToString()}" } ?: ""),
            0,
            failures.get(),
        )
        cache.close()
    }
}
