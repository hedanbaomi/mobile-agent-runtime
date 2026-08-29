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
    val outputName: String = "last_hidden_state",
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

    init {
        require(maxSequenceLength >= 3) { "Model pack maxSequenceLength must be at least 3" }
        val root = Json.parseToJsonElement(tokenizerJson).jsonObject
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
        val wordPieces = basicTokens(text).flatMap(::wordPiece)
        val available = maxSequenceLength - 2
        val pieces = wordPieces.take(available)
        val ids = LongArray(maxSequenceLength) { padId.toLong() }
        val mask = LongArray(maxSequenceLength)
        val types = LongArray(maxSequenceLength)
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
        normalized.forEach { char ->
            when {
                char.isWhitespace() -> flush()
                isChineseChar(char.code) -> {
                    flush()
                    result += char.toString()
                }
                isPunctuation(char) -> {
                    flush()
                    result += char.toString()
                }
                else -> current.append(char)
            }
        }
        flush()
        return result
    }

    private fun wordPiece(token: String): List<String> {
        if (token.length > maxInputCharsPerWord) return listOf(unknownToken)
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
                end--
            }
            if (match == null) return listOf(unknownToken)
            pieces += match
            start = end
        }
        return pieces
    }

    private fun normalize(text: String): String {
        val cleaned = buildString(text.length) {
            text.forEach { char ->
                when {
                    char.code == 0 || char.code == 0xFFFD || isControl(char) -> Unit
                    char.isWhitespace() -> append(' ')
                    else -> append(char)
                }
            }
        }
        val withChineseBoundaries = buildString(cleaned.length + 8) {
            cleaned.forEach { char ->
                if (isChineseChar(char.code)) append(' ')
                append(char)
                if (isChineseChar(char.code)) append(' ')
            }
        }
        val lower = withChineseBoundaries.lowercase(Locale.ROOT)
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    }

    private fun isControl(char: Char): Boolean =
        char != '\t' && char != '\n' && char != '\r' && Character.getType(char) == Character.CONTROL.toInt()

    private fun isPunctuation(char: Char): Boolean =
        char.code in 33..47 || char.code in 58..64 || char.code in 91..96 || char.code in 123..126 ||
            Character.getType(char) in setOf<Int>(
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
    private val session = ai.onnxruntime.OrtSession.SessionOptions().use { options ->
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
    }

    override fun embed(text: String): FloatArray {
        val encoded = tokenizer.encode(text)
        val inputs = linkedMapOf<String, ai.onnxruntime.OnnxTensor>()
        return try {
            inputs["input_ids"] = ai.onnxruntime.OnnxTensor.createTensor(environment, arrayOf(encoded.inputIds))
            inputs["attention_mask"] = ai.onnxruntime.OnnxTensor.createTensor(environment, arrayOf(encoded.attentionMask))
            inputs["token_type_ids"] = ai.onnxruntime.OnnxTensor.createTensor(environment, arrayOf(encoded.tokenTypeIds))
            synchronized(session) {
                session.run(inputs).use { result ->
                    val output = result[pack.manifest.outputName].orElseThrow {
                        IllegalStateException("ONNX output ${pack.manifest.outputName} is missing")
                    }.value
                    normalize(meanPool(output, encoded.attentionMask))
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
        val pooled = FloatArray(dimension)
        var count = 0L
        rows.forEachIndexed { index, rowValue ->
            if (mask[index] == 0L) return@forEachIndexed
            val row = rowValue as? FloatArray ?: error("ONNX output row is not float32")
            require(row.size == dimension) { "ONNX output dimension differs from manifest" }
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
