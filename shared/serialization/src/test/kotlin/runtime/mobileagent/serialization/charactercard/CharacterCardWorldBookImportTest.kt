// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookFormat

/**
 * 内嵌 `character_book`：有书正常返回、无书为 null、坏书明确失败（不静默丢弃）。
 */
class CharacterCardWorldBookImportTest {

    private val cardWithBook = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "书卡",
            "description": "带内嵌世界书",
            "first_mes": "hi",
            "character_book": {
              "name": "设定集",
              "scan_depth": 4,
              "recursive_scanning": true,
              "entries": [
                {
                  "keys": ["龙"],
                  "content": "龙很危险",
                  "enabled": true,
                  "insertion_order": 1,
                  "id": 1,
                  "extensions": {"note": "保留"}
                }
              ]
            }
          }
        }
    """.trimIndent()

    private fun decode(text: String): CharacterCardImportResult = CharacterCardCodec.decodeJson(text, "Test")

    private fun rejection(text: String): CharacterCardImportException =
        assertThrows(CharacterCardImportException::class.java) { decode(text) }

    @Test
    fun `embedded character book is parsed by the world book codec`() {
        val result = decode(cardWithBook)
        val book = result.worldBook!!

        assertEquals("设定集", book.name)
        assertEquals(1, book.entries.size)
        assertEquals("龙很危险", book.entries.single().content)
        assertEquals(listOf("龙"), book.entries.single().keys)
        assertEquals(WorldBookFormat.CHARACTER_CARD_V2, book.format)
        val original = Json.parseToJsonElement(cardWithBook)
        assertEquals(original, result.document.originalJson)
        assertEquals(original.jsonObject["data"]!!.jsonObject["character_book"],
            book.originalJson["data"]!!.jsonObject["character_book"])
        assertEquals(setOf("character_book"), book.originalJson["data"]!!.jsonObject.keys)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.CARD_WORLDBOOK_IMPORTED })
    }

    @Test
    fun `v3 card keeps a v3 character book`() {
        val json = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "书卡",
                "first_mes": "hi",
                "character_book": {
                  "entries": [
                    {"keys": ["^龙"], "content": "正则条目", "enabled": true, "insertion_order": 1, "use_regex": true, "id": 7}
                  ]
                }
              }
            }
        """.trimIndent()

        val result = decode(json)
        val book = result.worldBook!!

        assertEquals(WorldBookFormat.CHARACTER_CARD_V3, book.format)
        assertTrue(book.entries.single().useRegex)
    }

    @Test
    fun `card without a book imports normally`() {
        val result = decode("""{"spec": "chara_card_v2", "data": {"name": "无书", "first_mes": "hi"}}""")

        assertNull(result.worldBook)
        assertEquals("无书", result.document.name)
    }

    @Test
    fun `null character book is treated as absent with a notice`() {
        val result = decode(
            """{"spec": "chara_card_v2", "data": {"name": "空书", "first_mes": "hi", "character_book": null}}""",
        )

        assertNull(result.worldBook)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.CARD_FIELD_NULL })
    }

    @Test
    fun `book without an entries array is rejected`() {
        val noEntries = rejection(
            """{"spec": "chara_card_v2", "data": {"name": "坏书", "first_mes": "hi", "character_book": {"name": "x"}}}""",
        )
        assertEquals(CharacterCardErrorCodes.CHARACTER_BOOK_INVALID, noEntries.code)

        val notArray = rejection(
            """{"spec": "chara_card_v2", "data": {"name": "坏书", "first_mes": "hi", "character_book": {"entries": 5}}}""",
        )
        assertEquals(CharacterCardErrorCodes.CHARACTER_BOOK_INVALID, notArray.code)
    }

    @Test
    fun `book that is not an object is rejected`() {
        val error = rejection(
            """{"spec": "chara_card_v2", "data": {"name": "坏书", "first_mes": "hi", "character_book": []}}""",
        )

        assertEquals(CharacterCardErrorCodes.CHARACTER_BOOK_INVALID, error.code)
    }

    @Test
    fun `book with malformed entries fails instead of importing an empty book`() {
        val error = rejection(
            """{"spec": "chara_card_v2", "data": {"name": "坏书", "first_mes": "hi",
                 "character_book": {"entries": [5]}}}""".trimIndent(),
        )

        assertEquals(CharacterCardErrorCodes.WORLDBOOK_INVALID, error.code)
    }

    @Test
    fun `book with duplicate entry ids fails`() {
        val error = rejection(
            """{"spec": "chara_card_v2", "data": {"name": "坏书", "first_mes": "hi",
                 "character_book": {"entries": [
                   {"keys": ["a"], "content": "1", "id": 3},
                   {"keys": ["b"], "content": "2", "id": 3}
                 ]}}}""".trimIndent(),
        )

        assertEquals(CharacterCardErrorCodes.WORLDBOOK_INVALID, error.code)
    }

    @Test
    fun `book rejection message carries only a stable code`() {
        val error = rejection(
            """{"spec": "chara_card_v2", "data": {"name": "坏书", "first_mes": "hi",
                 "character_book": {"entries": [5]}}}""".trimIndent(),
        )

        assertTrue(error.message!!.contains("entry.not-object"))
        assertTrue(!error.message!!.contains("坏书"))
    }
}
