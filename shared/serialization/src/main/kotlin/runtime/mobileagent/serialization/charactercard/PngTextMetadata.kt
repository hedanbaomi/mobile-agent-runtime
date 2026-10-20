// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import java.util.Base64

/**
 * 从 PNG tEXt chunk 提取角色卡元数据。
 *
 * 规范行为：
 *  - 只认标准 `tEXt` 的 `chara`（V1/V2）与 `ccv3`（V3）关键字，值为 UTF-8 JSON 的 base64 文本；
 *  - 两者同时存在时用 `ccv3`；`ccv3` 存在但损坏时**直接失败，绝不回退到 `chara`**；
 *  - 同一关键字出现多次视为冲突元数据，拒绝；
 *  - `iTXt` / `zTXt` 中的 `chara` / `ccv3` 明确拒绝（不做无界解压），其它关键字忽略；
 *  - `chara-ext-asset_:*` 等资源 chunk 不解码、不下载，只给 notice。
 */
internal object PngTextMetadata {

    /** 提取结果：JSON 文本、来源关键字（`chara` / `ccv3`）与提取阶段的 notice。 */
    class Metadata(
        val text: String,
        val keyword: String,
        val notices: List<CharacterCardImportNotice>,
    )

    const val KEYWORD_CHARA = "chara"
    const val KEYWORD_CCV3 = "ccv3"

    private const val TYPE_TEXT = "tEXt"
    private const val TYPE_ZTXT = "zTXt"
    private const val TYPE_ITXT = "iTXt"
    private const val NUL = 0.toByte()
    private const val MAX_KEYWORD_LENGTH = 79
    private const val MAX_KEYWORD_SCAN = 80

    private val CARD_KEYWORDS = setOf(KEYWORD_CHARA, KEYWORD_CCV3)

    /**
     * @throws CharacterCardImportException 结构错误、冲突元数据、压缩文本、非法 base64 或超限。
     */
    fun extract(bytes: ByteArray, limits: CharacterCardLimits): Metadata {
        val candidates = LinkedHashMap<String, MutableList<IntArray>>()
        PngChunkScanner.scan(bytes, limits) { type, start, end ->
            when (type) {
                TYPE_TEXT -> collectText(bytes, start, end, limits, candidates)
                TYPE_ITXT, TYPE_ZTXT -> rejectCompressedText(bytes, start, end)
            }
        }
        return select(bytes, candidates, limits)
    }

    private fun collectText(
        bytes: ByteArray,
        start: Int,
        end: Int,
        limits: CharacterCardLimits,
        into: MutableMap<String, MutableList<IntArray>>,
    ) {
        val chunk = readTextChunk(bytes, start, end) ?: return
        if (chunk.keyword !in CARD_KEYWORDS) return
        if (end - chunk.textStart > limits.maxPngTextChars) throw metadataTooLarge()
        into.getOrPut(chunk.keyword) { ArrayList(1) }.add(intArrayOf(chunk.textStart, end))
    }

    private fun rejectCompressedText(bytes: ByteArray, start: Int, end: Int) {
        val chunk = readTextChunk(bytes, start, end) ?: return
        if (chunk.keyword !in CARD_KEYWORDS) return
        throw CharacterCardImportException(
            CharacterCardErrorCodes.PNG_UNSUPPORTED_TEXT_CHUNK,
            "PNG '${chunk.keyword}' metadata is stored in a compressed text chunk; refusing to inflate it.",
        )
    }

    private fun select(
        bytes: ByteArray,
        candidates: Map<String, MutableList<IntArray>>,
        limits: CharacterCardLimits,
    ): Metadata {
        val chara = candidates[KEYWORD_CHARA].orEmpty()
        val ccv3 = candidates[KEYWORD_CCV3].orEmpty()
        if (chara.size > 1) throw duplicateMetadata(KEYWORD_CHARA)
        if (ccv3.size > 1) throw duplicateMetadata(KEYWORD_CCV3)
        val notices = ArrayList<CharacterCardImportNotice>(2)
        if (ccv3.isNotEmpty()) {
            notices += CharacterCardImportNotice(
                CharacterCardNoticeCodes.PNG_CCV3_USED,
                "Using PNG 'ccv3' text metadata.",
            )
            if (chara.isNotEmpty()) {
                notices += CharacterCardImportNotice(
                    CharacterCardNoticeCodes.PNG_CCV3_PREFERRED,
                    "PNG has both 'chara' and 'ccv3' metadata; 'ccv3' is used per Character Card V3.",
                )
            }
            return Metadata(decodeText(bytes, ccv3[0], limits, KEYWORD_CCV3), KEYWORD_CCV3, notices)
        }
        if (chara.isNotEmpty()) {
            notices += CharacterCardImportNotice(
                CharacterCardNoticeCodes.PNG_CHARA_USED,
                "Using PNG 'chara' text metadata.",
            )
            return Metadata(decodeText(bytes, chara[0], limits, KEYWORD_CHARA), KEYWORD_CHARA, notices)
        }
        throw CharacterCardImportException(
            CharacterCardErrorCodes.PNG_METADATA_MISSING,
            "PNG has no 'chara' or 'ccv3' text metadata.",
        )
    }

    private class TextChunk(val keyword: String, val textStart: Int)

    /** 解析 `keyword \0 text` 前缀；关键字非法或缺失时返回 null（非本类型 chunk）。 */
    private fun readTextChunk(bytes: ByteArray, start: Int, end: Int): TextChunk? {
        val scanEnd = minOf(start + MAX_KEYWORD_SCAN, end)
        var index = start
        var nul = -1
        while (index < scanEnd) {
            if (bytes[index] == NUL) {
                nul = index
                break
            }
            index++
        }
        if (nul < 0) return null
        val length = nul - start
        if (length < 1 || length > MAX_KEYWORD_LENGTH) return null
        val keyword = String(bytes, start, length, Charsets.ISO_8859_1)
        if (!isTextKeyword(keyword)) return null
        return TextChunk(keyword, nul + 1)
    }

    /** PNG 规范：关键字为 1..79 个可打印 Latin-1 字符。 */
    private fun isTextKeyword(keyword: String): Boolean =
        keyword.all { char -> char.code in 32..126 || char.code in 161..255 }

    private fun decodeText(
        bytes: ByteArray,
        range: IntArray,
        limits: CharacterCardLimits,
        keyword: String,
    ): String {
        val start = range[0]
        val end = range[1]
        if (end - start > limits.maxPngTextChars) throw metadataTooLarge()
        val raw = String(bytes, start, end - start, Charsets.ISO_8859_1)
        val compact = raw.filterNot { it.isWhitespace() }
        val decoded = try {
            Base64.getDecoder().decode(compact)
        } catch (e: IllegalArgumentException) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.PNG_BASE64_INVALID,
                "PNG '$keyword' metadata is not valid base64.",
            )
        }
        if (decoded.size > limits.maxPngMetadataBytes) throw metadataTooLarge()
        return StrictText.decodeUtf8(decoded)
    }

    private fun duplicateMetadata(keyword: String): CharacterCardImportException =
        CharacterCardImportException(
            CharacterCardErrorCodes.PNG_DUPLICATE_METADATA,
            "PNG has more than one '$keyword' text chunk; conflicting metadata is rejected.",
        )

    private fun metadataTooLarge(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_METADATA_TOO_LARGE,
        "PNG metadata exceeds the configured size limit.",
    )
}
