// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.io.File
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.knowledge.TextEmbedder
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.booleanOrNull

/**
 * Immutable metadata for a verified local model pack.
 *
 * [sha256] is the ONNX weights hash.  The tokenizer has its own hash because
 * changing token IDs changes the embedding space even when the weights are
 * untouched.  The extra fields are intentionally part of the manifest rather
 * than inferred from filenames so a caller can display and audit the exact
 * model, preprocessing, pooling, and license binding.
 */
@Serializable
data class ModelPackManifest(
    val id: String,
    val dimension: Int,
    val sha256: String,
    val license: String,
    val spaceId: String = "",
    val modelFile: String = "model.onnx",
    val tokenizerFile: String = "tokenizer.json",
    val tokenizerSha256: String = "",
    val source: String = "",
    val revision: String = "",
    val maxSequenceLength: Int = 128,
    val pooling: String = "mean",
    val normalize: Boolean = true,
    val distance: String = "cosine",
    val tokenizerType: String = "bert-wordpiece",
    val tokenizerStrategy: String = "bert-wordpiece-v2",
    val outputName: String = "last_hidden_state",
    val windowStrategy: String = "sentence-bounded-coverage-mean-v3",
    val hiddenDimension: Int = dimension,
    val projectionFile: String? = null,
    val projectionSha256: String? = null,
)

interface EmbeddingPort {
    val spaceId: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

class MissingModelPackEmbedding : EmbeddingPort {
    override val spaceId: String = "unconfigured"

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        throw AppError(
            ErrorCode.INDEX_NOT_READY,
            "Local embedding model pack is not installed",
            RetryClass.USER_ACTION,
            "embedding",
            "embed",
        ).asException()
    }
}

/** A verified, unpacked ONNX model pack ready for the runtime session. */
data class OnnxModelPack(
    val manifest: ModelPackManifest,
    val modelFile: File,
    val tokenizerFile: File,
    val projectionFile: File? = null,
)

/**
 * Tiny BERT WordPiece tokenizer for the tokenizer.json format shipped by
 * Hugging Face.  Keeping this implementation in the model runtime means the
 * ONNX session receives the same IDs and masks as the published model without
 * pulling a Python or native tokenizer into the Android app.
 */
internal class BertWordPieceTokenizer(
    tokenizerJson: String,
    private val maxSequenceLength: Int,
) {
    private val vocabulary: Map<String, Int>
    private val unknownToken: String
    private val continuingPrefix: String
    private val maxInputCharsPerWord: Int
    private val clsId: Int
    private val sepId: Int
    private val padId: Int
    private val unknownId: Int
    private val lowercase: Boolean
    private val stripAccents: Boolean

    init {
        require(maxSequenceLength >= 3) { "Model pack maxSequenceLength must be at least 3" }
        val root = Json.parseToJsonElement(tokenizerJson).jsonObject
        val normalizer = root["normalizer"]?.jsonObject ?: error("Missing BERT normalizer")
        require(normalizer["type"]?.jsonPrimitive?.content == "BertNormalizer")
        require(normalizer["clean_text"]?.jsonPrimitive?.booleanOrNull == true)
        require(normalizer["handle_chinese_chars"]?.jsonPrimitive?.booleanOrNull == true)
        lowercase = normalizer["lowercase"]?.jsonPrimitive?.booleanOrNull ?: false
        stripAccents = normalizer["strip_accents"]?.jsonPrimitive?.booleanOrNull ?: lowercase
        val model = root["model"]?.jsonObject ?: error("tokenizer.json has no WordPiece model")
        require(model["type"]?.jsonPrimitive?.content == "WordPiece") {
            "Unsupported tokenizer model; expected WordPiece"
        }
        unknownToken = model["unk_token"]?.jsonPrimitive?.content ?: "[UNK]"
        continuingPrefix = model["continuing_subword_prefix"]?.jsonPrimitive?.content ?: "##"
        maxInputCharsPerWord = model["max_input_chars_per_word"]?.jsonPrimitive?.int ?: 100
        vocabulary = model["vocab"]?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.int }
            ?: error("tokenizer.json has no vocabulary")
        unknownId = vocabulary[unknownToken] ?: error("tokenizer vocabulary has no $unknownToken")
        clsId = vocabulary["[CLS]"] ?: error("tokenizer vocabulary has no [CLS]")
        sepId = vocabulary["[SEP]"] ?: error("tokenizer vocabulary has no [SEP]")
        padId = vocabulary["[PAD]"] ?: 0
    }

    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    fun encode(text: String): Encoded {
        val windows = encodeWindows(text)
        require(windows.size == 1) { "LOCAL_EMBEDDING_WINDOW_REQUIRED: text must not be truncated" }
        return windows.single()
    }

    /** Every WordPiece is assigned exactly once; limits fail before any inference. */
    fun encodeWindows(text: String): List<Encoded> {
        require(text.length <= 65_536) { "LOCAL_EMBEDDING_INPUT_TOO_LARGE" }
        // Keep independent sentences independent: a long boilerplate prefix must not
        // change the contextual representation of a short final fact. Long sentences
        // still receive complete bounded windows. Only whitespace separators disappear,
        // exactly as in BERT normalization; every resulting WordPiece participates.
        val sentences = text.split(Regex("(?<=[.!?])(?=\\s|$)|(?<=[。！？])|[\\r\\n]+"))
            .flatMap { sentence -> basicTokens(sentence).flatMap(::wordPiece).chunked(maxSequenceLength - 2) }
            .ifEmpty { listOf(emptyList()) }
        require(sentences.all { it.isEmpty() } || sentences.any { row -> row.any { it != unknownToken } }) {
            "LOCAL_EMBEDDING_UNSUPPORTED_TEXT"
        }
        require(sentences.sumOf { it.size } <= 128 * (maxSequenceLength - 2)) { "LOCAL_EMBEDDING_TOO_MANY_WINDOWS" }
        // Dense OCR lines / lists must not fail merely because they have many
        // short sentences. Pack them in order only when sentence isolation
        // would exceed the inference budget; never discard any pieces.
        val windows = if (sentences.size <= 128) sentences
            else sentences.flatten().chunked(maxSequenceLength - 2)
        return windows.map(::encodePieces)
    }

    private fun encodePieces(pieces: List<String>): Encoded {
        val size = pieces.size + 2
        val ids = LongArray(size) { padId.toLong() }
        val mask = LongArray(size)
        val types = LongArray(size)
        var cursor = 0
        ids[cursor] = clsId.toLong()
        mask[cursor++] = 1
        pieces.forEach { piece ->
            ids[cursor] = vocabulary[piece]?.toLong() ?: unknownId.toLong()
            mask[cursor++] = 1
        }
        ids[cursor] = sepId.toLong()
        mask[cursor] = 1
        return Encoded(ids, mask, types)
    }

    private fun basicTokens(text: String): List<String> {
        val normalized = normalize(text)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }
        normalized.codePoints().forEach { code ->
            when {
                Character.isWhitespace(code) || Character.isSpaceChar(code) -> flush()
                isChineseChar(code) -> {
                    flush()
                    result += String(Character.toChars(code))
                }
                isPunctuation(code) -> {
                    flush()
                    result += String(Character.toChars(code))
                }
                else -> current.appendCodePoint(code)
            }
        }
        flush()
        return result
    }

    private fun wordPiece(token: String): List<String> {
        if (token.codePointCount(0, token.length) > maxInputCharsPerWord) return listOf(unknownToken)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var match: String? = null
            while (start < end) {
                val candidate = token.substring(start, end)
                val lookup = if (start == 0) candidate else continuingPrefix + candidate
                if (vocabulary.containsKey(lookup)) {
                    match = lookup
                    break
                }
                end = token.offsetByCodePoints(end, -1)
            }
            if (match == null) return listOf(unknownToken)
            pieces += match
            start = end
        }
        return pieces
    }

    private fun normalize(text: String): String {
        val cleaned = buildString(text.length) {
            text.codePoints().forEach { code ->
                when {
                    code == 0 || code == 0xFFFD || isControl(code) -> Unit
                    Character.isWhitespace(code) || Character.isSpaceChar(code) -> append(' ')
                    else -> appendCodePoint(code)
                }
            }
        }
        val cased = if (lowercase) cleaned.lowercase(Locale.ROOT) else cleaned
        return if (stripAccents) Normalizer.normalize(cased, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() } else cased
    }

    private fun isControl(code: Int): Boolean =
        code !in listOf(9, 10, 13) && Character.getType(code) in listOf(
            Character.CONTROL.toInt(), Character.FORMAT.toInt())

    private fun isPunctuation(code: Int): Boolean =
        code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126 ||
            Character.getType(code) in setOf<Int>(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
            )

    private fun isChineseChar(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF || codePoint in 0x3400..0x4DBF ||
            codePoint in 0x20000..0x2A6DF || codePoint in 0x2A700..0x2B73F ||
            codePoint in 0x2B740..0x2B81F || codePoint in 0x2B820..0x2CEAF ||
            codePoint in 0xF900..0xFAFF || codePoint in 0x2F800..0x2FA1F
}

/**
 * Synchronous TextEmbedder backed by the verified local ONNX model pack.
 *
 * The model is executed entirely on-device.  The caller is responsible for
 * loading the pack through [AndroidModelPackLoader], which verifies both
 * hashes before this class opens the model file.
 */
class OnnxTextEmbedder(
    private val pack: OnnxModelPack,
) : TextEmbedder, EmbeddingPort, AutoCloseable {
    override val spaceId: String = pack.manifest.spaceId.ifBlank {
        "onnx:${pack.manifest.id}@${pack.manifest.revision}:d${pack.manifest.dimension}:${pack.manifest.distance}"
    }
    override val dimension: Int = pack.manifest.dimension

    private val tokenizer = BertWordPieceTokenizer(
        pack.tokenizerFile.readText(Charsets.UTF_8),
        pack.manifest.maxSequenceLength,
    )
    private val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val projection = pack.projectionFile?.let {
        DenseProjection(it.readBytes(), pack.manifest.hiddenDimension, dimension)
    }
    private val session = ai.onnxruntime.OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(pack.modelFile.absolutePath, options)
    }

    init {
        require(pack.manifest.pooling.equals("mean", ignoreCase = true)) {
            "Only mean pooling is supported by the Android local embedder"
        }
        require(pack.manifest.distance.equals("cosine", ignoreCase = true)) {
            "Only cosine distance is supported by the Android local embedder"
        }
        require(dimension > 0) { "Model pack dimension must be positive" }
        require(pack.manifest.windowStrategy == "sentence-bounded-coverage-mean-v3")
        require(projection != null || pack.manifest.hiddenDimension == dimension)
    }

    override fun embed(text: String): FloatArray {
        val windows = tokenizer.encodeWindows(text)
        val pooled = FloatArray(dimension)
        windows.forEach { window ->
            val vector = embedWindow(window)
            for (i in pooled.indices) pooled[i] += vector[i] / windows.size
        }
        return normalize(pooled)
    }

    private fun embedWindow(encoded: BertWordPieceTokenizer.Encoded): FloatArray {
        val inputs = linkedMapOf<String, ai.onnxruntime.OnnxTensor>()
        return try {
            inputs["input_ids"] = ai.onnxruntime.OnnxTensor.createTensor(environment, arrayOf(encoded.inputIds))
            inputs["attention_mask"] = ai.onnxruntime.OnnxTensor.createTensor(environment, arrayOf(encoded.attentionMask))
            if ("token_type_ids" in session.inputNames) {
                inputs["token_type_ids"] = ai.onnxruntime.OnnxTensor.createTensor(environment, arrayOf(encoded.tokenTypeIds))
            }
            synchronized(session) {
                session.run(inputs).use { result ->
                    val output = result[pack.manifest.outputName].orElseThrow {
                        IllegalStateException("ONNX output ${pack.manifest.outputName} is missing")
                    }.value
                    val pooled = meanPool(output, encoded.attentionMask)
                    normalize(projection?.apply(pooled) ?: pooled)
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)

    override fun close() {
        session.close()
    }

    private fun meanPool(output: Any, mask: LongArray): FloatArray {
        val batch = output as? Array<*> ?: error("ONNX output is not a tensor batch")
        val rows = batch.firstOrNull() as? Array<*> ?: error("ONNX output has no sequence rows")
        require(rows.size == mask.size) { "ONNX output sequence length differs from tokenizer" }
        val pooled = FloatArray(pack.manifest.hiddenDimension)
        var count = 0L
        rows.forEachIndexed { index, rowValue ->
            if (mask[index] == 0L) return@forEachIndexed
            val row = rowValue as? FloatArray ?: error("ONNX output row is not float32")
            require(row.size == pooled.size) { "ONNX output dimension differs from manifest" }
            for (i in row.indices) pooled[i] += row[i]
            count++
        }
        require(count > 0) { "ONNX tokenizer produced an empty attention mask" }
        for (i in pooled.indices) pooled[i] /= count.toFloat()
        return pooled
    }

    private fun normalize(vector: FloatArray): FloatArray {
        if (!pack.manifest.normalize) return vector
        var norm = 0.0
        vector.forEach { norm += it * it }
        val scale = kotlin.math.sqrt(norm).toFloat()
        require(scale.isFinite() && scale > 1e-12f) { "ONNX embedding has zero norm" }
        for (i in vector.indices) vector[i] /= scale
        return vector
    }
}
