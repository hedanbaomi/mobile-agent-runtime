// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.charactercard.CharacterCardFormat

/**
 * 负向/边界：非法 UTF-8、未知 spec/major、根与字段类型错误、重复键、深度/节点/长度/列表/文件上限，
 * 以及“世界书或空对象不能被当作角色卡”。
 */
class CharacterCardRejectionTest {

    private fun rejection(text: String, limits: CharacterCardLimits = CharacterCardLimits()): CharacterCardImportException =
        assertThrows(CharacterCardImportException::class.java) { CharacterCardCodec.decodeJson(text, "Test", limits) }

    private fun cardJson(data: String): String =
        """{"spec": "chara_card_v2", "spec_version": "2.0", "data": $data}"""

    @Test
    fun `invalid utf8 bytes are rejected`() {
        val bytes = byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)
        val error = assertThrows(CharacterCardImportException::class.java) {
            CharacterCardCodec.decode(bytes, "Test")
        }

        assertEquals(CharacterCardErrorCodes.ENCODING_INVALID, error.code)
    }

    @Test
    fun `file size limit is enforced`() {
        val error = assertThrows(CharacterCardImportException::class.java) {
            CharacterCardCodec.decodeBytes(ByteArray(64), "Test", CharacterCardLimits(maxFileBytes = 8))
        }

        assertEquals(CharacterCardErrorCodes.FILE_TOO_LARGE, error.code)
    }

    @Test
    fun `non object root is rejected`() {
        assertEquals(CharacterCardErrorCodes.ROOT_NOT_OBJECT, rejection("[1, 2, 3]").code)
        assertEquals(CharacterCardErrorCodes.ROOT_NOT_OBJECT, rejection("\"just a string\"").code)
    }

    @Test
    fun `unknown or foreign spec is rejected`() {
        assertEquals(
            CharacterCardErrorCodes.UNKNOWN_SPEC,
            rejection("""{"spec": "lorebook_v3", "data": {"entries": []}}""").code,
        )
        assertEquals(
            CharacterCardErrorCodes.UNKNOWN_SPEC,
            rejection("""{"spec": "chara_card_v4", "data": {}}""").code,
        )
    }

    @Test
    fun `newer spec major is rejected`() {
        val error = rejection("""{"spec": "chara_card_v3", "spec_version": "4.0", "data": {"name": "x"}}""")

        assertEquals(CharacterCardErrorCodes.UNSUPPORTED_SPEC_VERSION, error.code)
    }

    @Test
    fun `non numeric spec version is rejected`() {
        val error = rejection("""{"spec": "chara_card_v3", "spec_version": "v3", "data": {"name": "x"}}""")

        assertEquals(CharacterCardErrorCodes.INVALID_SPEC_VERSION, error.code)
    }

    @Test
    fun `non string spec version is rejected and not coerced`() {
        val error = rejection("""{"spec": "chara_card_v2", "spec_version": 2.0, "data": {"name": "x"}}""")

        assertEquals(CharacterCardErrorCodes.FIELD_TYPE, error.code)
    }

    @Test
    fun `missing data object is rejected`() {
        assertEquals(
            CharacterCardErrorCodes.DATA_NOT_OBJECT,
            rejection("""{"spec": "chara_card_v2", "spec_version": "2.0"}""").code,
        )
        assertEquals(
            CharacterCardErrorCodes.DATA_NOT_OBJECT,
            rejection("""{"spec": "chara_card_v3", "spec_version": "3.0", "data": []}""").code,
        )
    }

    @Test
    fun `empty object and world book roots are not character cards`() {
        assertEquals(CharacterCardErrorCodes.UNRECOGNIZED, rejection("{}").code)
        assertEquals(
            CharacterCardErrorCodes.UNRECOGNIZED,
            rejection("""{"entries": {"0": {"key": ["a"], "content": "x"}}}""").code,
        )
        assertEquals(
            CharacterCardErrorCodes.UNRECOGNIZED,
            rejection("""{"name": "一本书", "description": "d", "entries": []}""").code,
        )
    }

    @Test
    fun `wrong field types are rejected instead of coerced`() {
        assertEquals(
            CharacterCardErrorCodes.FIELD_TYPE,
            rejection(cardJson("""{"name": "x", "first_mes": "hi", "description": 5}""")).code,
        )
        assertEquals(
            CharacterCardErrorCodes.FIELD_TYPE,
            rejection(cardJson("""{"name": {"nested": true}, "first_mes": "hi"}""")).code,
        )
        assertEquals(
            CharacterCardErrorCodes.FIELD_TYPE,
            rejection(cardJson("""{"name": "x", "first_mes": true}""")).code,
        )
    }

    @Test
    fun `non string list items are rejected`() {
        assertEquals(
            CharacterCardErrorCodes.FIELD_TYPE,
            rejection(cardJson("""{"name": "x", "first_mes": "hi", "tags": ["ok", 5]}""")).code,
        )
        assertEquals(
            CharacterCardErrorCodes.FIELD_TYPE,
            rejection(cardJson("""{"name": "x", "first_mes": "hi", "alternate_greetings": "hi"}""")).code,
        )
    }

    @Test
    fun `duplicate json keys are rejected`() {
        val error = rejection(
            """{"spec": "chara_card_v2", "spec_version": "2.0", "spec": "chara_card_v3", "data": {}}""",
        )

        assertEquals(CharacterCardErrorCodes.JSON_DUPLICATE_KEY, error.code)
    }

    @Test
    fun `json size limit is enforced`() {
        val error = rejection("""{"first_mes": "hi"}""", CharacterCardLimits(maxJsonChars = 4))

        assertEquals(CharacterCardErrorCodes.JSON_SIZE_LIMIT, error.code)
    }

    @Test
    fun `json depth limit is enforced`() {
        val error = rejection(
            """{"first_mes": "hi", "a": {"b": {"c": 1}}}""",
            CharacterCardLimits(maxJsonDepth = 2),
        )

        assertEquals(CharacterCardErrorCodes.JSON_DEPTH_LIMIT, error.code)
    }

    @Test
    fun `json node limit is enforced`() {
        val error = rejection(
            """{"first_mes": "hi", "a": 1, "b": 2}""",
            CharacterCardLimits(maxJsonNodes = 3),
        )

        assertEquals(CharacterCardErrorCodes.JSON_NODE_LIMIT, error.code)
    }

    @Test
    fun `json string limit is enforced`() {
        val error = rejection(
            """{"first_mes": "hi", "description": "0123456789"}""",
            CharacterCardLimits(maxStringChars = 4),
        )

        assertEquals(CharacterCardErrorCodes.JSON_STRING_LIMIT, error.code)
    }

    @Test
    fun `list size limit is enforced`() {
        val error = rejection(
            cardJson("""{"name": "x", "first_mes": "hi", "tags": ["a", "b"]}"""),
            CharacterCardLimits(maxListItems = 1),
        )

        assertEquals(CharacterCardErrorCodes.LIST_LIMIT, error.code)
    }

    @Test
    fun `null optional field is treated as absent with a notice`() {
        val result = CharacterCardCodec.decodeJson(
            cardJson("""{"name": "x", "first_mes": "hi", "description": null}"""),
            "Test",
        )

        assertEquals("", result.document.description)
        assertTrue(
            result.notices.any {
                it.code == CharacterCardNoticeCodes.CARD_FIELD_NULL && it.message.contains("description")
            },
        )
    }

    @Test
    fun `bom is stripped and reported`() {
        val result = CharacterCardCodec.decodeJson("\uFEFF" + """{"first_mes": "hi", "name": "阿狸"}""", "Test")

        assertEquals("阿狸", result.document.name)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.JSON_BOM })
    }

    @Test
    fun `spec version major mismatch only warns`() {
        val result = CharacterCardCodec.decodeJson(
            """{"spec": "chara_card_v2", "spec_version": "3.0", "data": {"name": "x", "first_mes": "hi"}}""",
            "Test",
        )

        assertEquals(CharacterCardFormat.V2, result.document.format)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.CARD_SPEC_VERSION_MISMATCH })
    }

    @Test
    fun `missing spec version only warns`() {
        val result = CharacterCardCodec.decodeJson(
            """{"spec": "chara_card_v2", "data": {"name": "x", "first_mes": "hi"}}""",
            "Test",
        )

        assertEquals("x", result.document.name)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.CARD_SPEC_VERSION_MISSING })
    }
}
