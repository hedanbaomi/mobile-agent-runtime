// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.io.File
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

internal interface OnnxBatchTensor : AutoCloseable

internal interface OnnxSessionRunner : AutoCloseable {
    val inputNames: Set<String>
    fun createLongBatchTensor(data: Array<LongArray>): OnnxBatchTensor
    fun run(inputs: Map<String, OnnxBatchTensor>, outputName: String): Any
}

internal interface OrtBackendSession : AutoCloseable {
    val inputNames: Set<String>
    fun createLongTensor(data: Array<LongArray>): OnnxBatchTensor
    fun runSession(inputs: Map<String, OnnxBatchTensor>, outputName: String): Any
}

private class AndroidOrtBackendSession(modelFile: File) : OrtBackendSession {
    private val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session = ai.onnxruntime.OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(modelFile.absolutePath, options)
    }

    override val inputNames: Set<String> = try {
        session.inputNames.toSet()
    } catch (failure: Throwable) {
        runCatching { session.close() }
        throw failure
    }

    override fun createLongTensor(data: Array<LongArray>): OnnxBatchTensor =
        OrtBatchTensor(ai.onnxruntime.OnnxTensor.createTensor(environment, data))

    override fun runSession(inputs: Map<String, OnnxBatchTensor>, outputName: String): Any {
        val ortInputs = LinkedHashMap<String, ai.onnxruntime.OnnxTensor>(inputs.size)
        for ((name, tensor) in inputs) {
            val ortTensor = tensor as? OrtBatchTensor
                ?: error("Unsupported ONNX batch tensor type: ${tensor::class.java.name}")
            check(!ortTensor.isClosed) { "ONNX input tensor $name is already closed" }
            ortInputs[name] = ortTensor.tensor
        }
        return session.run(ortInputs).use { result ->
            result[outputName].orElseThrow {
                IllegalStateException("ONNX output $outputName is missing")
            }.value
        }
    }

    override fun close() {
        session.close()
    }

    private class OrtBatchTensor(
        val tensor: ai.onnxruntime.OnnxTensor,
    ) : OnnxBatchTensor {
        private val closed = AtomicBoolean(false)
        val isClosed: Boolean get() = closed.get()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                tensor.close()
            }
        }
    }
}

internal class OrtSessionRunner internal constructor(
    private val backend: OrtBackendSession,
) : OnnxSessionRunner {
    constructor(modelFile: File) : this(AndroidOrtBackendSession(modelFile))

    private val runLock = Any()
    internal val lifecycle = InFlightCloseGuard(onRelease = backend::close)

    override val inputNames: Set<String> = backend.inputNames

    override fun createLongBatchTensor(data: Array<LongArray>): OnnxBatchTensor =
        lifecycle.withLease {
            backend.createLongTensor(data)
        }

    override fun run(inputs: Map<String, OnnxBatchTensor>, outputName: String): Any =
        lifecycle.withLease {
            synchronized(runLock) {
                check(!lifecycle.isClosing) { CLOSED_RUNTIME_MESSAGE }
                backend.runSession(inputs, outputName)
            }
        }

    override fun close() {
        lifecycle.close()
    }
}

internal class PaddedBatchInputs(
    val maxSequenceLength: Int,
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
    val tokenTypeIds: Array<LongArray>?,
)

internal fun padLongArray(src: LongArray, targetLength: Int, padValue: Long = 0L): LongArray {
    require(targetLength >= src.size) {
        "Target sequence length $targetLength must be at least ${src.size}"
    }
    if (src.size == targetLength) return src
    return LongArray(targetLength).also { padded ->
        System.arraycopy(src, 0, padded, 0, src.size)
        if (padValue != 0L) {
            Arrays.fill(padded, src.size, targetLength, padValue)
        }
    }
}

internal fun buildPaddedBatchInputs(
    batch: List<BertWordPieceTokenizer.Encoded>,
    padTokenId: Long,
    includeTokenTypeIds: Boolean,
): PaddedBatchInputs {
    require(batch.isNotEmpty()) { "ONNX inference batch must not be empty" }
    val maxSeqLen = batch.maxOf { it.inputIds.size }
    for (window in batch) {
        require(window.attentionMask.size == window.inputIds.size) {
            "ONNX attention mask length (${window.attentionMask.size}) differs from inputIds (${window.inputIds.size})"
        }
        if (includeTokenTypeIds) {
            require(window.tokenTypeIds.size == window.inputIds.size) {
                "ONNX tokenTypeIds length (${window.tokenTypeIds.size}) differs from inputIds (${window.inputIds.size})"
            }
        }
    }
    val batchSize = batch.size
    val batchInputIds = Array(batchSize) { i ->
        padLongArray(batch[i].inputIds, maxSeqLen, padValue = padTokenId)
    }
    val batchMask = Array(batchSize) { i ->
        padLongArray(batch[i].attentionMask, maxSeqLen, padValue = 0L)
    }
    val batchTokenTypes = if (includeTokenTypeIds) {
        Array(batchSize) { i ->
            padLongArray(batch[i].tokenTypeIds, maxSeqLen, padValue = 0L)
        }
    } else {
        null
    }
    return PaddedBatchInputs(
        maxSequenceLength = maxSeqLen,
        inputIds = batchInputIds,
        attentionMask = batchMask,
        tokenTypeIds = batchTokenTypes,
    )
}

internal fun meanPoolAndNormalizeBatch(
    output: Any,
    windows: List<BertWordPieceTokenizer.Encoded>,
    maxSeqLen: Int,
    hiddenDimension: Int,
    normalizeOutput: Boolean,
    projection: DenseProjection? = null,
): List<FloatArray> {
    val batch = output as? Array<*> ?: error("ONNX output is not a tensor batch")
    require(batch.size == windows.size) { "ONNX output batch size mismatch" }
    return windows.indices.map { i ->
        val window = windows[i]
        val rows = batch[i] as? Array<*> ?: error("ONNX output has no sequence rows")
        require(rows.size == maxSeqLen) { "ONNX output sequence length differs from batch tensor" }
        val mask = window.attentionMask
        require(mask.size <= rows.size) { "ONNX attention mask exceeds batch sequence length" }
        val pooled = FloatArray(hiddenDimension)
        var count = 0L
        for (seq in mask.indices) {
            if (mask[seq] == 0L) continue
            val row = rows[seq] as? FloatArray ?: error("ONNX output row is not float32")
            require(row.size == pooled.size) { "ONNX output dimension differs from manifest" }
            for (d in row.indices) pooled[d] += row[d]
            count++
        }
        require(count > 0) { "ONNX tokenizer produced an empty attention mask" }
        for (d in pooled.indices) pooled[d] /= count.toFloat()
        l2Normalize(projection?.apply(pooled) ?: pooled, normalizeOutput)
    }
}

internal fun l2Normalize(vector: FloatArray, enabled: Boolean = true): FloatArray {
    if (!enabled) return vector
    var norm = 0.0
    vector.forEach { norm += it.toDouble() * it.toDouble() }
    val scale = sqrt(norm).toFloat()
    require(scale.isFinite() && scale > 1e-12f) { "ONNX embedding has zero norm" }
    for (i in vector.indices) vector[i] /= scale
    return vector
}

internal fun closeBatchTensors(
    tensors: Collection<OnnxBatchTensor>,
    primaryFailure: Throwable? = null,
) {
    var closeFailure: Throwable? = null
    for (tensor in tensors) {
        try {
            tensor.close()
        } catch (failure: Throwable) {
            if (primaryFailure != null) {
                if (primaryFailure !== failure) primaryFailure.addSuppressed(failure)
            } else if (closeFailure == null) {
                closeFailure = failure
            } else {
                closeFailure.addSuppressed(failure)
            }
        }
    }
    if (closeFailure != null) throw closeFailure
}

internal fun validateModelPackManifest(pack: OnnxModelPack) {
    require(pack.manifest.pooling.equals("mean", ignoreCase = true)) {
        "Only mean pooling is supported by the Android local embedder"
    }
    require(pack.manifest.distance.equals("cosine", ignoreCase = true)) {
        "Only cosine distance is supported by the Android local embedder"
    }
    require(pack.manifest.dimension > 0) { "Model pack dimension must be positive" }
    require(pack.manifest.windowStrategy == "sentence-bounded-coverage-mean-v3")
    require(pack.projectionFile != null || pack.manifest.hiddenDimension == pack.manifest.dimension)
}
