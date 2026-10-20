// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.charactercard.CharacterCardFormat

/**
 * 正向导入：中文 V1/V2/V3 全字段、未知字段保全、名字回退与截断。
 */
class CharacterCardJsonImportTest {

    private fun decode(text: String, fallbackName: String = "回退名") =
        CharacterCardCodec.decodeJson(text, fallbackName)

    @Test
    fun `v1 card reads every legacy field and preserves the whole root`() {
        val json = """
            {
              "name": "阿狸",
              "description": "一只中文角色卡",
              "personality": "活泼",
              "scenario": "海边小镇",
              "first_mes": "你好呀",
              "mes_example": "<START>\n{{user}}: 嗨\n{{char}}: 嗨！",
              "unknown_root": {"keep": [1, 2, 3]}
            }
        """.trimIndent()

        val result = decode(json)
        val document = result.document

        assertEquals(CharacterCardFormat.V1, document.format)
        assertEquals("阿狸", document.name)
        assertEquals("一只中文角色卡", document.description)
        assertEquals("活泼", document.personality)
        assertEquals("海边小镇", document.scenario)
        assertEquals("你好呀", document.firstMessage)
        assertEquals("<START>\n{{user}}: 嗨\n{{char}}: 嗨！", document.exampleMessages)
        assertEquals("", document.systemPrompt)
        assertEquals("", document.postHistoryInstructions)
        assertEquals(emptyList<String>(), document.alternateGreetings)
        assertEquals("", document.creatorNotes)
        assertEquals("", document.nickname)
        assertEquals(emptyList<String>(), document.tags)
        assertEquals(CharacterCardContainers.JSON, result.container)
        assertNull(result.worldBook)
        assertEquals(Json.parseToJsonElement(json), document.originalJson)
        val unknown = document.originalJson["unknown_root"] as JsonObject
        assertEquals(3, (unknown["keep"] as JsonArray).size)
    }

    @Test
    fun `v2 card reads all fields and keeps unknown extensions`() {
        val json = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "阿狸",
                "description": "中文描述",
                "personality": "活泼",
                "scenario": "小镇",
                "first_mes": "你好呀",
                "mes_example": "<START>\n{{char}}: 嗨！",
                "creator_notes": "作者注",
                "system_prompt": "系统提示",
                "post_history_instructions": "后置指令",
                "alternate_greetings": ["早安", "晚安"],
                "tags": ["中文", "测试"],
                "creator": "作者",
                "character_version": "1.0",
                "extensions": {"talkativeness": "0.5", "unknown_ext": {"deep": [1, 2]}},
                "unknown_data_field": {"a": true}
              },
              "unknown_root_field": [1, 2, 3]
            }
        """.trimIndent()

        val result = decode(json)
        val document = result.document

        assertEquals(CharacterCardFormat.V2, document.format)
        assertEquals("阿狸", document.name)
        assertEquals("中文描述", document.description)
        assertEquals("活泼", document.personality)
        assertEquals("小镇", document.scenario)
        assertEquals("你好呀", document.firstMessage)
        assertEquals("<START>\n{{char}}: 嗨！", document.exampleMessages)
        assertEquals("系统提示", document.systemPrompt)
        assertEquals("后置指令", document.postHistoryInstructions)
        assertEquals(listOf("早安", "晚安"), document.alternateGreetings)
        assertEquals("作者注", document.creatorNotes)
        assertEquals("作者", document.creator)
        assertEquals("1.0", document.characterVersion)
        assertEquals(listOf("中文", "测试"), document.tags)
        assertEquals("", document.nickname)
        assertEquals(Json.parseToJsonElement(json), document.originalJson)
        val data = document.originalJson["data"] as JsonObject
        assertTrue(data.containsKey("extensions"))
        assertTrue(data.containsKey("unknown_data_field"))
        assertTrue(document.originalJson.containsKey("unknown_root_field"))
        assertNull(result.worldBook)
    }

    @Test
    fun `v3 card reads nickname group greetings and keeps assets`() {
        val json = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "阿狸",
                "description": "中文描述",
                "first_mes": "你好呀",
                "nickname": "小狸",
                "group_only_greetings": ["群聊早安"],
                "assets": [{"type": "icon", "uri": "ccdefault:", "name": "main", "ext": "png"}],
                "creator_notes_multilingual": {"zh": "中文注"},
                "source": ["https://example.invalid/card"],
                "creation_date": 0,
                "modification_date": 0,
                "extensions": {}
              }
            }
        """.trimIndent()

        val result = decode(json)
        val document = result.document

        assertEquals(CharacterCardFormat.V3, document.format)
        assertEquals("小狸", document.nickname)
        assertEquals(listOf("群聊早安"), document.groupOnlyGreetings)
        val data = document.originalJson["data"] as JsonObject
        val assets = data["assets"] as JsonArray
        assertEquals(1, assets.size)
        assertTrue(data.containsKey("creator_notes_multilingual"))
        assertTrue(data.containsKey("source"))
    }

    @Test
    fun `blank name falls back with a notice and the fallback is not content-bearing`() {
        val result = decode("""{"first_mes": "hi", "name": "   "}""", fallbackName = "回退名")

        assertEquals("回退名", result.document.name)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.CARD_NAME_FALLBACK })
        assertTrue(result.notices.none { it.message.contains("回退名") })
    }

    @Test
    fun `over long name is truncated with a notice`() {
        val result = CharacterCardCodec.decodeJson(
            text = """{"first_mes": "hi", "name": "一二三四五"}""",
            fallbackName = "回退名",
            limits = CharacterCardLimits(maxNameChars = 3),
        )

        assertEquals("一二三", result.document.name)
        assertTrue(result.notices.any { it.code == CharacterCardNoticeCodes.CARD_NAME_TRUNCATED })
    }

    @Test
    fun `preservation notices explain that scripts and assets were not run`() {
        val result = decode("""{"first_mes": "hi", "name": "阿狸"}""")
        val codes = result.notices.map { it.code }.toSet()

        assertTrue(codes.contains(CharacterCardNoticeCodes.CARD_SCRIPTS_NOT_EXECUTED))
        assertTrue(codes.contains(CharacterCardNoticeCodes.CARD_ASSETS_NOT_FETCHED))
        assertTrue(codes.contains(CharacterCardNoticeCodes.CARD_UNKNOWN_FIELDS_PRESERVED))
    }
}
