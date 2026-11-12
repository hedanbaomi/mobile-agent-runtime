// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchEmbeddingDynamicPaddingTest {

    @Test
    fun encodedValueEqualityAndHashCodeCompareUnderlyingArraysByContent() {
        val first = BertWordPieceTokenizer.Encoded(
            inputIds = longArrayOf(101L, 14L, 15L, 102L),
            attentionMask = longArrayOf(1L, 1L, 1L, 1L),
            tokenTypeIds = longArrayOf(0L, 0L, 0L, 0L),
        )
        val identicalClone = BertWordPieceTokenizer.Encoded(
            inputIds = longArrayOf(101L, 14L, 15L, 102L),
            attentionMask = longArrayOf(1L, 1L, 1L, 1L),
            tokenTypeIds = longArrayOf(0L, 0L, 0L, 0L),
        )
        val differentIds = identicalClone.copy(inputIds = longArrayOf(101L, 14L, 16L, 102L))
        val differentMask = identicalClone.copy(attentionMask = longArrayOf(1L, 1L, 1L, 0L))
        val differentTypes = identicalClone.copy(tokenTypeIds = longArrayOf(0L, 0L, 1L, 0L))

        assertTrue(first.inputIds !== identicalClone.inputIds)
        assertEquals(first, identicalClone)
        assertEquals(first.hashCode(), identicalClone.hashCode())
        assertNotEquals(first, differentIds)
        assertNotEquals(first, differentMask)
        assertNotEquals(first, differentTypes)

        val set = mutableSetOf(first)
        assertFalse("Duplicate window content must be rejected by Set.add", set.add(identicalClone))
        assertTrue(set.add(differentMask))
    }

    @Test
    fun coldCacheDuplicateDocumentsAndDuplicateSlidingWindowsInferOnce() {
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 6, dimension = 6, maxSequenceLength = 12) { pack ->
            val runner = DeterministicSessionRunner(hiddenDimension = 6, expectedPadId = 3L)
            OnnxTextEmbedder(pack, runner).use { embedder ->
                // 40 identical cold-cache documents (> 2 * CHUNK_SIZE) must collapse to 1 window in 1 session run.
                val duplicates = List(40) { "alpha beta gamma" }
                val vectors = embedder.embedBatch(duplicates)
                assertEquals(40, vectors.size)
                assertEquals(1, runner.runs.size)
                assertEquals(1, runner.runs[0].inputIds.size)
                for (i in 1 until vectors.size) {
                    assertArrayEquals(vectors[0], vectors[i], 1e-6f)
                }
                // Mutating one caller output array must not corrupt sibling results.
                vectors[0][0] = 42.0f
                assertNotEquals(42.0f, vectors[1][0], 1e-6f)
            }
        }

        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 6, dimension = 6, maxSequenceLength = 12) { pack ->
            val runner = DeterministicSessionRunner(hiddenDimension = 6, expectedPadId = 3L)
            OnnxTextEmbedder(pack, runner).use { embedder ->
                // 20 identical sentence windows ("common.") + 1 trailing window ("cat") -> 2 unique windows in 1 run.
                val text = "common. ".repeat(20) + "cat"
                val windows = embedder.tokenizer.encodeWindows(text)
                assertEquals(21, windows.size)

                val vec = embedder.embed(text)
                assertEquals(6, vec.size)
                assertEquals(1, runner.runs.size)
                assertEquals(2, runner.runs[0].inputIds.size)
            }
        }
    }

    @Test
    fun nonZeroPadTokenIdPopulatesInputIdsWhileAttentionAndTokenTypesRemainZero() {
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 4, dimension = 4, maxSequenceLength = 16) { pack ->
            val runner = DeterministicSessionRunner(hiddenDimension = 4, expectedPadId = 3L)
            OnnxTextEmbedder(pack, runner).use { embedder ->
                assertEquals(3, embedder.tokenizer.padId)
                // "cat" -> [CLS, cat, SEP] (len 3); "alpha beta gamma delta" -> [CLS, alpha, beta, gamma, delta, SEP] (len 6)
                embedder.embedBatch(listOf("cat", "alpha beta gamma delta", "dog fox"))
                assertEquals(1, runner.runs.size)
                val run = runner.runs.single()
                assertEquals(3, run.inputIds.size)

                // Short sequence 0 (3 valid tokens, padded to 6 with padId = 3L)
                assertArrayEquals(longArrayOf(101L, 15L, 102L, 3L, 3L, 3L), run.inputIds[0])
                assertArrayEquals(longArrayOf(1L, 1L, 1L, 0L, 0L, 0L), run.attentionMask[0])
                assertArrayEquals(longArrayOf(0L, 0L, 0L, 0L, 0L, 0L), run.tokenTypeIds!![0])

                // Longest sequence 1 (6 valid tokens, zero padding needed)
                assertArrayEquals(longArrayOf(101L, 10L, 11L, 12L, 13L, 102L), run.inputIds[1])
                assertArrayEquals(longArrayOf(1L, 1L, 1L, 1L, 1L, 1L), run.attentionMask[1])

                // Medium sequence 2 (4 valid tokens, padded to 6 with padId = 3L)
                assertArrayEquals(longArrayOf(101L, 16L, 17L, 102L, 3L, 3L), run.inputIds[2])
                assertArrayEquals(longArrayOf(1L, 1L, 1L, 1L, 0L, 0L), run.attentionMask[2])
                assertArrayEquals(longArrayOf(0L, 0L, 0L, 0L, 0L, 0L), run.tokenTypeIds[2])
            }
        }

        // Direct production helper verification: full-length array reuse & omitting token_type_ids when absent
        val shortWindow = BertWordPieceTokenizer.Encoded(
            longArrayOf(101L, 10L, 102L),
            longArrayOf(1L, 1L, 1L),
            longArrayOf(0L, 0L, 0L),
        )
        val maxWindow = BertWordPieceTokenizer.Encoded(
            longArrayOf(101L, 10L, 11L, 12L, 102L),
            longArrayOf(1L, 1L, 1L, 1L, 1L),
            longArrayOf(0L, 0L, 0L, 0L, 0L),
        )
        val padded = buildPaddedBatchInputs(listOf(shortWindow, maxWindow), padTokenId = 7L, includeTokenTypeIds = false)
        assertEquals(5, padded.maxSequenceLength)
        assertArrayEquals(longArrayOf(101L, 10L, 102L, 7L, 7L), padded.inputIds[0])
        assertArrayEquals(longArrayOf(1L, 1L, 1L, 0L, 0L), padded.attentionMask[0])
        assertSame(maxWindow.inputIds, padded.inputIds[1])
        assertSame(maxWindow.attentionMask, padded.attentionMask[1])
        assertNull(padded.tokenTypeIds)
    }

    @Test
    fun batchInferenceMatchesSingleItemInferenceWithinToleranceAcrossChunksAndProjection() {
        val corpus = listOf(
            "cat",
            "alpha beta gamma delta",
            "dog. fox! bird.",
            "中文 common tree river stone cloud",
            "fish",
            "alpha. beta. gamma. delta. common. cat.",
            "stone cloud river",
            "bird fish fox",
            "common dog",
            "tree",
            "alpha beta",
            "gamma delta fox",
            "中文 cat dog",
            "river stone",
            "cloud!",
            "alpha gamma stone",
            "beta delta tree",
            "fox bird fish common",
            "cat. dog. tree. river.",
            "stone",
            "alpha! beta! fish!",
            "common cloud",
        )
        // Corpus produces > 32 unique windows so CHUNK_SIZE=16 executes multiple chunks with varied maxSeqLen.
        TestOnnxFixtures.withModelPack(
            padId = 3,
            hiddenDimension = 8,
            dimension = 5,
            maxSequenceLength = 10,
            withProjection = true,
        ) { pack ->
            val batchRunner = DeterministicSessionRunner(hiddenDimension = 8, expectedPadId = 3L)
            val batchResults = OnnxTextEmbedder(pack, batchRunner).use { embedder ->
                embedder.embedBatch(corpus)
            }
            assertTrue("Expected multi-chunk execution for > 16 unique windows", batchRunner.runs.size >= 2)
            assertTrue(batchRunner.runs.all { it.inputIds.size <= OnnxTextEmbedder.CHUNK_SIZE })

            val singleResults = corpus.map { text ->
                val singleRunner = DeterministicSessionRunner(hiddenDimension = 8, expectedPadId = 3L)
                OnnxTextEmbedder(pack, singleRunner).use { embedder ->
                    embedder.embed(text)
                }
            }

            assertEquals(corpus.size, batchResults.size)
            for (i in corpus.indices) {
                val batchVec = batchResults[i]
                val singleVec = singleResults[i]
                assertEquals(5, batchVec.size)
                var normSq = 0.0
                for (d in batchVec.indices) {
                    val diff = abs(batchVec[d] - singleVec[d])
                    assertTrue(
                        "Mismatch at doc $i dim $d: batch=${batchVec[d]}, single=${singleVec[d]}, diff=$diff",
                        diff <= 1e-5f,
                    )
                    normSq += batchVec[d].toDouble() * batchVec[d].toDouble()
                }
                assertEquals(1.0f, sqrt(normSq).toFloat(), 1e-5f)
            }
        }
    }

    @Test
    fun productionMeanPoolRejectsEmptyMaskAndClosesTensorsOnFailureAndCancellation() {
        val zeroMaskWindow = BertWordPieceTokenizer.Encoded(
            inputIds = longArrayOf(101L, 10L, 102L),
            attentionMask = longArrayOf(0L, 0L, 0L),
            tokenTypeIds = longArrayOf(0L, 0L, 0L),
        )
        val rawOutput = arrayOf(arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), floatArrayOf(5f, 6f)))
        assertThrows(IllegalArgumentException::class.java) {
            meanPoolAndNormalizeBatch(
                output = rawOutput,
                windows = listOf(zeroMaskWindow),
                maxSeqLen = 3,
                hiddenDimension = 2,
                normalizeOutput = true,
            )
        }

        // Real OnnxTextEmbedder interruption & coroutine cancellation close 100% of allocated tensors
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 4, dimension = 4) { pack ->
            val runner = DeterministicSessionRunner(
                hiddenDimension = 4,
                runOverride = { _, _ -> throw IllegalStateException("ORT native execution error") },
            )
            OnnxTextEmbedder(pack, runner).use { embedder ->
                assertThrows(IllegalStateException::class.java) {
                    embedder.embedBatch(listOf("cat", "alpha beta"))
                }
                assertEquals(3, runner.allocatedTensors.size)
                assertTrue("Every allocated tensor must be closed in finally", runner.allocatedTensors.all { it.isClosed })

                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        cancel()
                        embedder.embedBatchCancellable(listOf("dog"))
                    }
                }
            }
            assertEquals(1, runner.closeCalls.get())
        }
    }
}
