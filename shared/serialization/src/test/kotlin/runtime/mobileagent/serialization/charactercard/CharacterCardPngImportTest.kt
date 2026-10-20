// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.charactercard.CharacterCardFormat

/**
 * PNG 元数据载体：chara/ccv3 优先级、拒绝损坏 ccv3（不回退）、冲突/重复元数据、
 * CRC/截断/IEND、压缩文本拒绝、chunk 数上限与 base64/UTF-8 负向。
 */
class CharacterCardPngImportTest {

    private val v1Json = """
        {"name": "阿狸", "description": "描述", "personality": "", "scenario": "",
         "first_mes": "你好", "mes_example": ""}
    """.trimIndent()

    private val v2Json = """
        {"spec": "chara_card_v2", "spec_version": "2.0", "data": {"name": "阿狸v2", "first_mes": "你好"}}
    """.trimIndent()

    private val v3Json = """
        {"spec": "chara_card_v3", "spec_version": "3.0", "data": {"name": "阿狸v3", "first_mes": "你好"}}
    """.trimIndent()

    private fun decode(bytes: ByteArray): CharacterCardImportResult = CharacterCardCodec.decode(bytes, "PNG 回退")

    private fun rejection(bytes: ByteArray, limits: CharacterCardLimits = CharacterCardLimits()): CharacterCardImportException =
        assertThrows(CharacterCardImportException::class.java) { CharacterCardCodec.decodeBytes(bytes, "PNG 回退", limits) }

    private fun charaChunk(json: String) = PngTestImage.text(PngTextMetadata.KEYWORD_CHARA, PngTestImage.jsonValue(json))

    private fun ccv3Chunk(json: String) = PngTestImage.text(PngTextMetadata.KEYWORD_CCV3, PngTestImage.jsonValue(json))

    @Test
    fun `chara text chunk imports a v1 card`() {
        val result = decode(PngTestImage.validPng(charaChunk(v1Json)))

        assertEquals(CharacterCardContainers.PNG, result.container)
        assertEquals(CharacterCardFormat.V1, result.document.format)
        assertEquals("阿狸", result.document.name)
        val codes = result.notices.map { it.code }.toSet()
        assertTrue(codes.contains(CharacterCardNoticeCodes.PNG_CHARA_USED))
        assertTrue(codes.contains(CharacterCardNoticeCodes.PNG_AVATAR_NOT_IMPORTED))
        assertTrue(codes.contains(CharacterCardNoticeCodes.PNG_ASSETS_NOT_IMPORTED))
    }

    @Test
    fun `chara text chunk imports a v2 card`() {
        val result = decode(PngTestImage.validPng(charaChunk(v2Json)))

        assertEquals(CharacterCardFormat.V2, result.document.format)
        assertEquals("阿狸v2", result.document.name)
    }

    @Test
    fun `ccv3 text chunk imports a v3 card`() {
        val result = decode(PngTestImage.validPng(ccv3Chunk(v3Json)))

        assertEquals(CharacterCardFormat.V3, result.document.format)
        assertEquals("阿狸v3", result.document.name)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.PNG_CCV3_USED })
    }

    @Test
    fun `ccv3 is preferred when both chunks are valid`() {
        val result = decode(PngTestImage.validPng(charaChunk(v2Json), ccv3Chunk(v3Json)))

        assertEquals(CharacterCardFormat.V3, result.document.format)
        assertEquals("阿狸v3", result.document.name)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.PNG_CCV3_PREFERRED })
    }

    @Test
    fun `corrupt ccv3 base64 is rejected instead of falling back to chara`() {
        val png = PngTestImage.validPng(
            charaChunk(v2Json),
            PngTestImage.text(PngTextMetadata.KEYWORD_CCV3, "!!!not-base64!!!"),
        )

        assertEquals(CharacterCardErrorCodes.PNG_BASE64_INVALID, rejection(png).code)
    }

    @Test
    fun `ccv3 with broken json is rejected instead of falling back to chara`() {
        val png = PngTestImage.validPng(
            charaChunk(v2Json),
            PngTestImage.text(PngTextMetadata.KEYWORD_CCV3, PngTestImage.jsonValue("{not json")),
        )

        assertEquals(CharacterCardErrorCodes.JSON_SYNTAX, rejection(png).code)
    }

    @Test
    fun `duplicate chara chunks are rejected`() {
        val png = PngTestImage.validPng(charaChunk(v1Json), charaChunk(v1Json))

        assertEquals(CharacterCardErrorCodes.PNG_DUPLICATE_METADATA, rejection(png).code)
    }

    @Test
    fun `duplicate ccv3 chunks are rejected`() {
        val png = PngTestImage.validPng(ccv3Chunk(v3Json), ccv3Chunk(v3Json))

        assertEquals(CharacterCardErrorCodes.PNG_DUPLICATE_METADATA, rejection(png).code)
    }

    @Test
    fun `crc mismatch is rejected`() {
        val png = PngTestImage.flipLastByte(PngTestImage.validPng(charaChunk(v1Json)))

        assertEquals(CharacterCardErrorCodes.PNG_CRC, rejection(png).code)
    }

    @Test
    fun `truncated png is rejected`() {
        val png = PngTestImage.truncated(PngTestImage.validPng(charaChunk(v1Json)), 5)

        assertEquals(CharacterCardErrorCodes.PNG_CHUNK_TRUNCATED, rejection(png).code)
    }

    @Test
    fun `png without iend is rejected`() {
        val png = PngTestImage.png(PngTestImage.ihdr(), charaChunk(v1Json))

        assertEquals(CharacterCardErrorCodes.PNG_MISSING_IEND, rejection(png).code)
    }

    @Test
    fun `invalid chunk length is rejected`() {
        val png = PngTestImage.signature() + PngTestImage.be32(-1) + "tEXt".toByteArray() + ByteArray(4)

        assertEquals(CharacterCardErrorCodes.PNG_CHUNK_LENGTH, rejection(png).code)
    }

    @Test
    fun `png without ihdr first is rejected`() {
        val png = PngTestImage.png(PngTestImage.text("Comment", "hi"), PngTestImage.iend())

        assertEquals(CharacterCardErrorCodes.PNG_IHDR, rejection(png).code)
    }

    @Test
    fun `compressed text chunk for a card keyword is rejected without inflating`() {
        val png = PngTestImage.validPng(PngTestImage.compressedText("zTXt", PngTextMetadata.KEYWORD_CHARA))

        assertEquals(CharacterCardErrorCodes.PNG_UNSUPPORTED_TEXT_CHUNK, rejection(png).code)
    }

    @Test
    fun `png without card metadata is rejected`() {
        val png = PngTestImage.validPng(PngTestImage.text("Comment", "hi"))

        assertEquals(CharacterCardErrorCodes.PNG_METADATA_MISSING, rejection(png).code)
    }

    @Test
    fun `chunk count limit is enforced`() {
        val png = PngTestImage.validPng(charaChunk(v1Json))

        assertEquals(
            CharacterCardErrorCodes.PNG_CHUNK_COUNT,
            rejection(png, CharacterCardLimits(maxPngChunks = 2)).code,
        )
    }

    @Test
    fun `card metadata that is not valid utf8 is rejected`() {
        val invalidUtf8 = Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        val png = PngTestImage.validPng(PngTestImage.text(PngTextMetadata.KEYWORD_CHARA, invalidUtf8))

        assertEquals(CharacterCardErrorCodes.ENCODING_INVALID, rejection(png).code)
    }

    @Test
    fun `metadata larger than the configured limit is rejected`() {
        val png = PngTestImage.validPng(charaChunk(v1Json))

        assertEquals(
            CharacterCardErrorCodes.PNG_METADATA_TOO_LARGE,
            rejection(png, CharacterCardLimits(maxPngTextChars = 8)).code,
        )
    }
}
