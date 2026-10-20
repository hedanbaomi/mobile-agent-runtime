// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import runtime.mobileagent.domain.charactercard.CharacterCardFieldNames
import runtime.mobileagent.domain.charactercard.CharacterCardFormat

/**
 * 严格字段读取：已知字段**类型不符即失败**，不把数字/布尔/对象/数组静默转成字符串或空值。
 *
 * 显式 `null` 按“未提供”处理，但**不静默**：每处都给出 `card.field-null` notice 并写明字段名。
 */
internal object StrictField {

    /** 可选字符串字段；缺失返回 null，显式 null 返回 null 并告警。 */
    fun string(obj: JsonObject, key: String, sink: CharacterCardNoticeSink): String? {
        val element = obj[key] ?: return null
        if (element is JsonNull) {
            sink.add(CharacterCardNoticeCodes.CARD_FIELD_NULL, "Field '$key' was null; treated as absent.")
            return null
        }
        return stringValue(element, key)
    }

    /** 字符串字段（必填上下文）；`null` 同样是类型错误，由调用方决定是否回退。 */
    fun stringValue(element: JsonElement, key: String): String {
        if (element is JsonNull) throw typeError(key, element)
        val primitive = element as? JsonPrimitive ?: throw typeError(key, element)
        if (!primitive.isString) throw typeError(key, element)
        return primitive.content
    }

    /** 可选字符串数组；元素非字符串（含 null）即失败。 */
    fun stringList(
        obj: JsonObject,
        key: String,
        limits: CharacterCardLimits,
        sink: CharacterCardNoticeSink,
    ): List<String>? {
        val element = obj[key] ?: return null
        if (element is JsonNull) {
            sink.add(CharacterCardNoticeCodes.CARD_FIELD_NULL, "Field '$key' was null; treated as absent.")
            return null
        }
        val array = element as? JsonArray ?: throw typeError(key, element)
        if (array.size > limits.maxListItems) {
            throw CharacterCardImportException(
                CharacterCardErrorCodes.LIST_LIMIT,
                "Field '$key' has ${array.size} items, limit is ${limits.maxListItems}.",
            )
        }
        return array.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive ?: throw typeError("$key[$index]", item)
            if (!primitive.isString) throw typeError("$key[$index]", item)
            primitive.content
        }
    }

    /** 只用于错误消息的类型名（不含任何内容）。 */
    fun typeName(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) "string" else if (element.booleanOrNull != null) "boolean" else "number"
    }

    fun typeError(key: String, element: JsonElement): CharacterCardImportException =
        CharacterCardImportException(
            CharacterCardErrorCodes.FIELD_TYPE,
            "Field '$key' has the wrong JSON type (${typeName(element)}); refusing to coerce it.",
        )
}

/** 规范化后的已知字段集合（未包含 `originalJson`，由 codec 直接保留整棵原始 JSON）。 */
internal class CharacterCardFields(
    val name: String?,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val exampleMessages: String,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val alternateGreetings: List<String>,
    val creatorNotes: String,
    val creator: String,
    val characterVersion: String,
    val tags: List<String>,
    val nickname: String,
    val groupOnlyGreetings: List<String>,
)

/**
 * 按探测结果从 `data`（V2/V3）或根对象（V1）读取已知字段。
 *
 * `nickname` / `group_only_greetings` 只在 V3 读取；V2 中出现时视为未知扩展字段，仍由 originalJson 保留。
 */
internal object CharacterCardFieldReader {

    fun read(
        detection: CharacterCardDetection,
        limits: CharacterCardLimits,
        sink: CharacterCardNoticeSink,
    ): CharacterCardFields {
        val source = detection.data
        val v3 = detection.format == CharacterCardFormat.V3

        fun text(key: String): String = StrictField.string(source, key, sink) ?: ""
        fun list(key: String): List<String> = StrictField.stringList(source, key, limits, sink) ?: emptyList()

        return CharacterCardFields(
            name = StrictField.string(source, CharacterCardFieldNames.NAME, sink),
            description = text(CharacterCardFieldNames.DESCRIPTION),
            personality = text(CharacterCardFieldNames.PERSONALITY),
            scenario = text(CharacterCardFieldNames.SCENARIO),
            firstMessage = text(CharacterCardFieldNames.FIRST_MES),
            exampleMessages = text(CharacterCardFieldNames.MES_EXAMPLE),
            systemPrompt = text(CharacterCardFieldNames.SYSTEM_PROMPT),
            postHistoryInstructions = text(CharacterCardFieldNames.POST_HISTORY_INSTRUCTIONS),
            alternateGreetings = list(CharacterCardFieldNames.ALTERNATE_GREETINGS),
            creatorNotes = text(CharacterCardFieldNames.CREATOR_NOTES),
            creator = text(CharacterCardFieldNames.CREATOR),
            characterVersion = text(CharacterCardFieldNames.CHARACTER_VERSION),
            tags = list(CharacterCardFieldNames.TAGS),
            nickname = if (v3) text(CharacterCardFieldNames.NICKNAME) else "",
            groupOnlyGreetings = if (v3) list(CharacterCardFieldNames.GROUP_ONLY_GREETINGS) else emptyList(),
        )
    }
}
