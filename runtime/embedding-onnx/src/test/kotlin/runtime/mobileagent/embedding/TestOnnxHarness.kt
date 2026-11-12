// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

internal class RecordingBatchTensor(
    val data: Array<LongArray>,
    private val onCloseHook: (() -> Unit)? = null,
) : OnnxBatchTensor {
    val closeCalls = AtomicInteger(0)
    val isClosed: Boolean get() = closeCalls.get() > 0

    override fun close() {
        closeCalls.incrementAndGet()
        onCloseHook?.invoke()
    }
}

internal class RecordedSessionRun(
    val outputName: String,
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
    val tokenTypeIds: Array<LongArray>?,
)

internal class DeterministicSessionRunner(
    private val hiddenDimension: Int,
    override val inputNames: Set<String> = setOf("input_ids", "attention_mask", "token_type_ids"),
    private val expectedPadId: Long? = null,
    private val failOnCreateTensorIndex: Int? = null,
    private val onCreateFailure: (() -> Throwable) = { IllegalStateException("tensor allocation failed") },
    private val afterCreateTensorIndex: Int? = null,
    private val afterCreateHook: (() -> Unit)? = null,
    private val onCloseTensorFailureIndex: Int? = null,
    private val onCloseRunnerHook: (() -> Unit)? = null,
    private val runOverride: ((RecordedSessionRun, Int) -> Any)? = null,
) : OnnxSessionRunner {
    val allocatedTensors = CopyOnWriteArrayList<RecordingBatchTensor>()
    val runs = CopyOnWriteArrayList<RecordedSessionRun>()
    val closeCalls = AtomicInteger(0)
    private val tensorCounter = AtomicInteger(0)

    override fun createLongBatchTensor(data: Array<LongArray>): OnnxBatchTensor {
        val currentIndex = tensorCounter.getAndIncrement()
        if (failOnCreateTensorIndex == currentIndex) {
            throw onCreateFailure()
        }
        val copy = Array(data.size) { row -> data[row].copyOf() }
        val tensor = RecordingBatchTensor(copy) {
            if (onCloseTensorFailureIndex == currentIndex) {
                throw IllegalStateException("close failure on tensor $currentIndex")
            }
        }
        allocatedTensors += tensor
        if (afterCreateTensorIndex == currentIndex) {
            afterCreateHook?.invoke()
        }
        return tensor
    }

    override fun run(inputs: Map<String, OnnxBatchTensor>, outputName: String): Any {
        val ids = (inputs.getValue("input_ids") as RecordingBatchTensor).data
        val mask = (inputs.getValue("attention_mask") as RecordingBatchTensor).data
        val types = (inputs["token_type_ids"] as? RecordingBatchTensor)?.data
        val recorded = RecordedSessionRun(
            outputName = outputName,
            inputIds = Array(ids.size) { ids[it].copyOf() },
            attentionMask = Array(mask.size) { mask[it].copyOf() },
            tokenTypeIds = types?.let { arr -> Array(arr.size) { arr[it].copyOf() } },
        )
        val runIndex = runs.size
        runs += recorded
        if (runOverride != null) {
            return runOverride.invoke(recorded, runIndex)
        }
        return synthesizeContextualHiddenStates(recorded)
    }

    internal fun synthesizeContextualHiddenStates(recorded: RecordedSessionRun): Array<Array<FloatArray>> {
        val batchSize = recorded.inputIds.size
        return Array(batchSize) { b ->
            val seqIds = recorded.inputIds[b]
            val seqMask = recorded.attentionMask[b]
            val seqTypes = recorded.tokenTypeIds?.get(b)
            var validTokenContext = 0L
            for (s in seqIds.indices) {
                if (seqMask[s] != 0L) {
                    validTokenContext = validTokenContext * 31L + seqIds[s] + (s + 1L)
                } else if (expectedPadId != null) {
                    check(seqIds[s] == expectedPadId) {
                        "Expected padId=$expectedPadId at [batch=$b, seq=$s], got ${seqIds[s]}"
                    }
                }
            }
            val contextSignal = ((validTokenContext and 0xFFFFL).toFloat() / 65535.0f) + 0.15f
            Array(seqIds.size) { s ->
                if (seqMask[s] == 0L) {
                    // Emit huge batch-dependent distractors on padded positions so any mask leak breaks equivalence.
                    FloatArray(hiddenDimension) { d -> 950.0f * (b + 1) - 725.0f * (s + 1) + d * 33.0f }
                } else {
                    val token = seqIds[s].toDouble()
                    val type = (seqTypes?.get(s) ?: 0L).toDouble()
                    FloatArray(hiddenDimension) { d ->
                        val angle = token * (d + 1) * 0.17 + (s + 1) * 0.11 + type * 0.23
                        (sin(angle) + 0.5 * cos(angle * 0.7) + contextSignal * (d + 1) * 0.05).toFloat()
                    }
                }
            }
        }
    }

    override fun close() {
        closeCalls.incrementAndGet()
        onCloseRunnerHook?.invoke()
    }
}

internal object TestOnnxFixtures {
    fun tokenizerJson(padId: Int = 0): String =
        """
        {
          "normalizer": {
            "type": "BertNormalizer",
            "clean_text": true,
            "handle_chinese_chars": true,
            "lowercase": true,
            "strip_accents": true
          },
          "model": {
            "type": "WordPiece",
            "unk_token": "[UNK]",
            "continuing_subword_prefix": "##",
            "vocab": {
              "[PAD]": $padId,
              "[UNK]": 100,
              "[CLS]": 101,
              "[SEP]": 102,
              "alpha": 10,
              "beta": 11,
              "gamma": 12,
              "delta": 13,
              "common": 14,
              "cat": 15,
              "dog": 16,
              "fox": 17,
              "bird": 18,
              "fish": 19,
              "tree": 20,
              "river": 21,
              "stone": 22,
              "cloud": 23,
              "中": 24,
              "文": 25,
              ".": 26,
              "!": 27
            }
          }
        }
        """.trimIndent()

    fun projectionSafetensors(inputDim: Int, outputDim: Int): ByteArray {
        val biasBytes = outputDim * 4
        val weightBytes = outputDim * inputDim * 4
        val header =
            """{"linear.bias":{"dtype":"F32","shape":[$outputDim],"data_offsets":[0,$biasBytes]},""" +
                """"linear.weight":{"dtype":"F32","shape":[$outputDim,$inputDim],"data_offsets":[$biasBytes,${biasBytes + weightBytes}]}}"""
        val headerUtf8 = header.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + headerUtf8.size + biasBytes + weightBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putLong(headerUtf8.size.toLong())
                put(headerUtf8)
                for (o in 0 until outputDim) {
                    putFloat(0.02f * (o + 1))
                }
                for (o in 0 until outputDim) {
                    for (i in 0 until inputDim) {
                        putFloat(if (o == i % outputDim) 0.65f else -0.08f * ((o + i) % 3))
                    }
                }
            }
            .array()
    }

    fun <T> withModelPack(
        padId: Int = 3,
        hiddenDimension: Int = 6,
        dimension: Int = 6,
        maxSequenceLength: Int = 16,
        normalize: Boolean = true,
        withProjection: Boolean = hiddenDimension != dimension,
        pooling: String = "mean",
        block: (OnnxModelPack) -> T,
    ): T {
        val dir = Files.createTempDirectory("mar-onnx-test-").toFile()
        try {
            val modelFile = File(dir, "model.onnx").apply { writeBytes(byteArrayOf(0x08, 0x01)) }
            val tokenizerFile = File(dir, "tokenizer.json").apply {
                writeText(tokenizerJson(padId), Charsets.UTF_8)
            }
            val projectionFile = if (withProjection) {
                File(dir, "dense.safetensors").apply {
                    writeBytes(projectionSafetensors(hiddenDimension, dimension))
                }
            } else {
                null
            }
            val manifest = ModelPackManifest(
                id = "test-pack",
                dimension = dimension,
                hiddenDimension = hiddenDimension,
                sha256 = "0".repeat(64),
                tokenizerSha256 = "1".repeat(64),
                license = "Apache-2.0",
                revision = "rev1",
                maxSequenceLength = maxSequenceLength,
                pooling = pooling,
                normalize = normalize,
                projectionFile = projectionFile?.name,
                projectionSha256 = projectionFile?.let { "2".repeat(64) },
            )
            return block(OnnxModelPack(manifest, modelFile, tokenizerFile, projectionFile))
        } finally {
            dir.deleteRecursively()
        }
    }
}
