// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversarialBatchEmbeddingTest {

    private fun encoded(vararg ids: Long) =
        BertWordPieceTokenizer.Encoded(ids, LongArray(ids.size) { 1L }, LongArray(ids.size))

    @Test
    fun windowCacheConcurrentMultiThreadedStressTest() {
        val maxEntries = 40
        val cache = EmbeddingWindowCache(maxEntries = maxEntries, maxPayloadBytes = 50_000L)
        val threadCount = 12
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val failureRef = AtomicReference<Throwable?>(null)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    val random = Random(t * 1000 + 42)
                    repeat(150) {
                        val enc = encoded(random.nextLong(1, 100))
                        if (random.nextBoolean()) {
                            cache.put(enc, FloatArray(8) { random.nextFloat() })
                        } else {
                            cache.get(enc)?.let { retrieved ->
                                assertEquals(8, retrieved.size)
                                retrieved[0] = 999f
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    failureRef.compareAndSet(null, failure)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Concurrent stress test timed out", doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertNull("Concurrent stress test encountered exception: ${failureRef.get()}", failureRef.get())

        var retrievedCount = 0
        for (token in 1L..100L) {
            val vec = cache.get(encoded(token))
            if (vec != null) {
                retrievedCount++
                assertTrue("Cache vector was mutated across threads", vec[0] != 999f)
            }
        }
        assertTrue(retrievedCount <= maxEntries)
        cache.close()
    }

    @Test
    fun windowCacheLruPayloadBudgetAndNonFiniteGuards() {
        val lruCache = EmbeddingWindowCache(maxEntries = 3)
        val w1 = encoded(1)
        val w2 = encoded(2)
        val w3 = encoded(3)
        val w4 = encoded(4)
        lruCache.put(w1, floatArrayOf(1f))
        lruCache.put(w2, floatArrayOf(2f))
        lruCache.put(w3, floatArrayOf(3f))
        assertNotNull(lruCache.get(w1))
        lruCache.put(w4, floatArrayOf(4f))
        assertNull("w2 must be evicted as LRU", lruCache.get(w2))
        assertNotNull(lruCache.get(w1))
        assertNotNull(lruCache.get(w3))
        assertNotNull(lruCache.get(w4))

        val byteCache = EmbeddingWindowCache(maxEntries = 10, maxPayloadBytes = 80L)
        byteCache.put(w1, floatArrayOf(1f, 1f))
        byteCache.put(w2, floatArrayOf(2f, 2f))
        byteCache.put(w3, floatArrayOf(3f, 3f))
        assertNull("w1 must be evicted due to byte budget", byteCache.get(w1))
        assertNotNull(byteCache.get(w2))
        assertNotNull(byteCache.get(w3))

        byteCache.put(w4, floatArrayOf(Float.NaN, 1.0f))
        assertNull(byteCache.get(w4))
        byteCache.put(w4, floatArrayOf(Float.POSITIVE_INFINITY, 1.0f))
        assertNull(byteCache.get(w4))
    }

    @Test
    fun partialTensorAllocationAndCloseFailuresStillReleaseEveryTensorAndPreservePrimaryError() {
        // 1. Third createLongBatchTensor (token_type_ids) throws -> first 2 tensors are still closed!
        TestOnnxFixtures.withModelPack(padId = 5, hiddenDimension = 4, dimension = 4) { pack ->
            val runner = DeterministicSessionRunner(
                hiddenDimension = 4,
                failOnCreateTensorIndex = 2,
                onCreateFailure = { IllegalStateException("OOM allocating token_type_ids") },
            )
            OnnxTextEmbedder(pack, runner).use { embedder ->
                val ex = assertThrows(IllegalStateException::class.java) {
                    embedder.embed("alpha beta")
                }
                assertTrue(ex.message!!.contains("token_type_ids"))
                assertEquals(2, runner.allocatedTensors.size)
                assertTrue(runner.allocatedTensors.all { it.isClosed })
            }
        }

        // 2. Session without token_type_ids allocates only 2 tensors; run throws AND tensor[0].close() throws ->
        //    primary exception is preserved (not masked), close error is suppressed, and tensor[1] still closes!
        TestOnnxFixtures.withModelPack(padId = 5, hiddenDimension = 4, dimension = 4) { pack ->
            val runner = DeterministicSessionRunner(
                hiddenDimension = 4,
                inputNames = setOf("input_ids", "attention_mask"),
                onCloseTensorFailureIndex = 0,
                runOverride = { _, _ -> throw IllegalArgumentException("Primary inference failure") },
            )
            OnnxTextEmbedder(pack, runner).use { embedder ->
                val primary = assertThrows(IllegalArgumentException::class.java) {
                    embedder.embedBatch(listOf("cat", "alpha beta gamma"))
                }
                assertEquals("Primary inference failure", primary.message)
                assertEquals(1, primary.suppressed.size)
                assertEquals(2, runner.allocatedTensors.size)
                assertTrue(runner.allocatedTensors.all { it.closeCalls.get() == 1 })
            }
        }

        // 3. Zero-norm output in meanPoolAndNormalizeBatch throws in post-processing -> all tensors still closed!
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 4, dimension = 4) { pack ->
            val runner = DeterministicSessionRunner(
                hiddenDimension = 4,
                runOverride = { recorded, _ ->
                    Array(recorded.inputIds.size) { b ->
                        Array(recorded.inputIds[b].size) { FloatArray(4) { 0.0f } }
                    }
                },
            )
            OnnxTextEmbedder(pack, runner).use { embedder ->
                assertThrows(IllegalArgumentException::class.java) {
                    embedder.embed("cat")
                }
                assertEquals(3, runner.allocatedTensors.size)
                assertTrue(runner.allocatedTensors.all { it.isClosed })
            }
        }
    }

    @Test
    fun productionMaskedMeanPoolingWithInteriorMaskGapsAndTrailingGarbageIsExact() {
        val windowWithGaps = BertWordPieceTokenizer.Encoded(
            inputIds = longArrayOf(101L, 10L, 3L, 11L, 3L, 102L),
            attentionMask = longArrayOf(1L, 1L, 0L, 1L, 0L, 1L),
            tokenTypeIds = LongArray(6),
        )
        // Batch maxSeqLen = 9 (3 extra padded trailing rows beyond window.attentionMask.size = 6)
        val rows = Array(9) { seq ->
            if (seq in setOf(0, 1, 3, 5)) {
                floatArrayOf((seq + 1) * 2.0f, (seq + 1) * -1.0f, 4.0f)
            } else {
                floatArrayOf(1e6f, -1e6f, 99999f)
            }
        }
        val actual = meanPoolAndNormalizeBatch(
            output = arrayOf(rows),
            windows = listOf(windowWithGaps),
            maxSeqLen = 9,
            hiddenDimension = 3,
            normalizeOutput = false,
        ).single()

        // Valid seqs are (0, 1, 3, 5) -> (seq + 1) = (1, 2, 4, 6), mean = 13 / 4 = 3.25
        assertArrayEquals(floatArrayOf(6.5f, -3.25f, 4.0f), actual, 1e-6f)
    }

    @Test
    fun threadInterruptionAndInvalidManifestReleaseRunnerCleanly() {
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 4, dimension = 4) { pack ->
            val runner = DeterministicSessionRunner(
                hiddenDimension = 4,
                afterCreateTensorIndex = 2,
                afterCreateHook = { Thread.currentThread().interrupt() },
            )
            OnnxTextEmbedder(pack, runner).use { embedder ->
                try {
                    assertThrows(InterruptedException::class.java) {
                        embedder.embedBatch(listOf("alpha", "beta"))
                    }
                } finally {
                    Thread.interrupted()
                }
                assertEquals(0, runner.runs.size)
                assertEquals(3, runner.allocatedTensors.size)
                assertTrue("All 3 allocated tensors must close on post-allocation interrupt", runner.allocatedTensors.all { it.isClosed })
            }
        }

        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 4, dimension = 4, pooling = "cls") { badPack ->
            val runner = DeterministicSessionRunner(hiddenDimension = 4)
            assertThrows(IllegalArgumentException::class.java) {
                OnnxTextEmbedder(badPack, runner)
            }
            assertEquals("Failed constructor must close injected runner", 1, runner.closeCalls.get())
        }
    }
}
