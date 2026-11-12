// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class EmbeddingWindowCacheTest {
    @Test fun repeatedWindowsAcrossDifferentChunksExecuteTheModelOnce() {
        val cache = EmbeddingWindowCache()
        val calls = AtomicInteger()
        val first = cache.vector(encoded(101, 45, 102)) { calls.incrementAndGet(); floatArrayOf(0.25f, 0.75f) }
        first[0] = 99f
        val second = cache.vector(encoded(101, 45, 102)) { calls.incrementAndGet(); floatArrayOf(0.25f, 0.75f) }
        assertEquals(1, calls.get())
        assertArrayEquals(floatArrayOf(0.25f, 0.75f), second, 0f)
        second[1] = 99f
        assertArrayEquals(floatArrayOf(0.25f, 0.75f), cache.vector(encoded(101, 45, 102)) { error("cache miss") }, 0f)
    }

    @Test fun idsMasksTypesAndHashCollisionsRemainDistinct() {
        val cache = EmbeddingWindowCache()
        val calls = AtomicInteger()
        fun evaluate(value: BertWordPieceTokenizer.Encoded): FloatArray =
            cache.vector(value) { floatArrayOf(calls.incrementAndGet().toFloat()) }
        // Both ID arrays have the same contentHashCode; equality must check the full tensors.
        val a = encoded(0, 31)
        val b = encoded(1, 0)
        assertEquals(a.inputIds.contentHashCode(), b.inputIds.contentHashCode())
        assertEquals(1f, evaluate(a).single())
        assertEquals(2f, evaluate(b).single())
        assertEquals(3f, evaluate(a.copy(attentionMask = longArrayOf(1, 0))).single())
        assertEquals(4f, evaluate(a.copy(tokenTypeIds = longArrayOf(0, 1))).single())
        assertEquals(1f, evaluate(a).single())
        assertEquals(4, calls.get())
    }

    @Test fun retainedKeysCannotBeChangedByTheCaller() {
        val cache = EmbeddingWindowCache()
        val original = encoded(1, 2)
        cache.vector(original) { floatArrayOf(0.5f) }
        original.inputIds[0] = 99
        original.attentionMask[0] = 0
        original.tokenTypeIds[0] = 99
        assertArrayEquals(floatArrayOf(0.5f), cache.vector(encoded(1, 2)) { error("mutable key") }, 0f)
    }

    @Test fun entryAndPayloadBudgetsEvictTheLeastRecentlyUsedWindow() {
        val cache = EmbeddingWindowCache(maxEntries = 2)
        cache.vector(encoded(1)) { floatArrayOf(1f) }
        cache.vector(encoded(2)) { floatArrayOf(2f) }
        cache.vector(encoded(1)) { error("recent entry lost") }
        cache.vector(encoded(3)) { floatArrayOf(3f) }
        cache.vector(encoded(1)) { error("LRU entry order lost") }
        assertEquals(20f, cache.vector(encoded(2)) { floatArrayOf(20f) }.single())

        val small = EmbeddingWindowCache(maxPayloadBytes = 1)
        val calls = AtomicInteger()
        repeat(2) { small.vector(encoded(1)) { calls.incrementAndGet(); FloatArray(512) } }
        assertEquals(2, calls.get())
        val disabled = EmbeddingWindowCache(maxEntries = 0)
        repeat(2) { disabled.vector(encoded(1)) { calls.incrementAndGet(); floatArrayOf(1f) } }
        assertEquals(4, calls.get())
    }

    @Test fun concurrentIdenticalWindowsShareOneInference() {
        val cache = EmbeddingWindowCache()
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val results = List(4) { workers.submit<FloatArray> {
                check(start.await(5, TimeUnit.SECONDS))
                cache.vector(encoded(1)) { calls.incrementAndGet(); floatArrayOf(0.5f) }
            } }
            start.countDown()
            results.forEach { assertArrayEquals(floatArrayOf(0.5f), it.get(5, TimeUnit.SECONDS), 0f) }
            assertEquals(1, calls.get())
        } finally { workers.shutdownNow() }
    }

    @Test fun failureInterruptionAndClosedRuntimeNeverBecomeCacheHits() {
        val cache = EmbeddingWindowCache()
        assertThrows(IllegalStateException::class.java) { cache.vector(encoded(1)) { error("inference failed") } }
        assertArrayEquals(floatArrayOf(1f), cache.vector(encoded(1)) { floatArrayOf(1f) }, 0f)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { cache.vector(encoded(1)) { error("interrupted inference") } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        val releases = AtomicInteger()
        cache.close { releases.incrementAndGet() }
        cache.close { releases.incrementAndGet() }
        assertEquals(1, releases.get())
        assertThrows(IllegalStateException::class.java) { cache.vector(encoded(1)) { error("closed inference") } }
    }

    @Test fun invalidVectorsAreNotRetained() {
        val cache = EmbeddingWindowCache()
        val calls = AtomicInteger()
        repeat(2) { cache.vector(encoded(1)) { calls.incrementAndGet(); floatArrayOf(Float.NaN) } }
        assertEquals(2, calls.get())
    }

    @Test fun getAndPutDirectAccessWithIsolationAndEviction() {
        val cache = EmbeddingWindowCache(maxEntries = 2)
        val w1 = encoded(1)
        val w2 = encoded(2)
        val w3 = encoded(3)

        assertNull(cache.get(w1))
        val v1 = floatArrayOf(0.1f, 0.2f)
        cache.put(w1, v1)
        val retrieved = cache.get(w1)
        assertNotNull(retrieved)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f), retrieved, 0f)

        // Mutating returned array does not affect cache
        retrieved!![0] = 99.0f
        assertArrayEquals(floatArrayOf(0.1f, 0.2f), cache.get(w1), 0f)

        // Mutating source array after put does not affect cache
        v1[0] = 88.0f
        assertArrayEquals(floatArrayOf(0.1f, 0.2f), cache.get(w1), 0f)

        // Add second entry
        cache.put(w2, floatArrayOf(0.3f, 0.4f))
        assertNotNull(cache.get(w2))
        assertNotNull(cache.get(w1)) // touches w1 last, so w2 is LRU

        // Adding third entry evicts LRU (which is w2)
        cache.put(w3, floatArrayOf(0.5f, 0.6f))
        assertNotNull(cache.get(w1))
        assertNull(cache.get(w2))
        assertNotNull(cache.get(w3))
    }

    @Test fun getAndPutRespectClosedAndInterruptedStatus() {
        val cache = EmbeddingWindowCache()
        val w = encoded(1)
        cache.put(w, floatArrayOf(1.0f))

        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { cache.get(w) }
            assertThrows(InterruptedException::class.java) { cache.put(w, floatArrayOf(2.0f)) }
        } finally {
            Thread.interrupted()
        }

        cache.close()
        assertThrows(IllegalStateException::class.java) { cache.get(w) }
        assertThrows(IllegalStateException::class.java) { cache.put(w, floatArrayOf(3.0f)) }
    }

    private fun encoded(vararg ids: Long) = BertWordPieceTokenizer.Encoded(ids, LongArray(ids.size) { 1L }, LongArray(ids.size))
}
