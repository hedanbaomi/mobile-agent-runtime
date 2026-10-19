// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.worldbook.WorldBookFieldNames

/** 有界 notice 收集器：超过限额后只保留一条汇总。 */
internal class NoticeSink(private val limits: WorldBookImportLimits) {
    private val items = mutableListOf<WorldBookImportNotice>()
    private var suppressed = 0

    fun add(entryUid: String?, code: String, message: String) {
        if (items.size >= limits.maxNotices) {
            suppressed++
            return
        }
        items += WorldBookImportNotice(entryUid = entryUid, code = code, message = message)
    }

    fun build(): List<WorldBookImportNotice> {
        if (suppressed <= 0) return items.toList()
        return items + WorldBookImportNotice(
            entryUid = null,
            code = WorldBookNoticeCodes.NOTICES_TRUNCATED,
            message = "$suppressed further notices suppressed by limit ${limits.maxNotices}.",
        )
    }
}

/** 已知字段名集合：导出时用于“未知字段保留 + 已知字段覆盖”。 */
internal object WorldBookFieldMaps {
    /** ST 原生条目字段名（camelCase）。 */
    val ST_KEYS: Set<String> = setOf(
        WorldBookFieldNames.ST_UID, WorldBookFieldNames.ST_KEY, WorldBookFieldNames.ST_KEY_SECONDARY,
        WorldBookFieldNames.ST_COMMENT, WorldBookFieldNames.ST_CONTENT, WorldBookFieldNames.ST_CONSTANT,
        WorldBookFieldNames.ST_VECTORIZED, WorldBookFieldNames.ST_SELECTIVE, WorldBookFieldNames.ST_SELECTIVE_LOGIC,
        WorldBookFieldNames.ST_ADD_MEMO, WorldBookFieldNames.ST_ORDER, WorldBookFieldNames.ST_POSITION,
        WorldBookFieldNames.ST_DISABLE, WorldBookFieldNames.ST_IGNORE_BUDGET, WorldBookFieldNames.ST_EXCLUDE_RECURSION,
        WorldBookFieldNames.ST_PREVENT_RECURSION, WorldBookFieldNames.ST_MATCH_PERSONA,
        WorldBookFieldNames.ST_MATCH_CHAR_DESC, WorldBookFieldNames.ST_MATCH_CHAR_PERSONALITY,
        WorldBookFieldNames.ST_MATCH_CHAR_DEPTH, WorldBookFieldNames.ST_MATCH_SCENARIO,
        WorldBookFieldNames.ST_MATCH_CREATOR_NOTES, WorldBookFieldNames.ST_DELAY_UNTIL_RECURSION,
        WorldBookFieldNames.ST_PROBABILITY, WorldBookFieldNames.ST_USE_PROBABILITY, WorldBookFieldNames.ST_DEPTH,
        WorldBookFieldNames.ST_OUTLET_NAME, WorldBookFieldNames.ST_GROUP, WorldBookFieldNames.ST_GROUP_OVERRIDE,
        WorldBookFieldNames.ST_GROUP_WEIGHT, WorldBookFieldNames.ST_SCAN_DEPTH, WorldBookFieldNames.ST_CASE_SENSITIVE,
        WorldBookFieldNames.ST_MATCH_WHOLE_WORDS, WorldBookFieldNames.ST_USE_GROUP_SCORING,
        WorldBookFieldNames.ST_AUTOMATION_ID, WorldBookFieldNames.ST_ROLE, WorldBookFieldNames.ST_STICKY,
        WorldBookFieldNames.ST_COOLDOWN, WorldBookFieldNames.ST_DELAY, WorldBookFieldNames.ST_TRIGGERS,
        WorldBookFieldNames.ST_CHARACTER_FILTER, WorldBookFieldNames.ST_EXTENSIONS, "displayIndex",
    )

    /** Character Book / Lorebook V3 条目字段名（snake_case）。 */
    val CC_KEYS: Set<String> = setOf(
        WorldBookFieldNames.CC_KEYS, WorldBookFieldNames.CC_SECONDARY_KEYS, WorldBookFieldNames.CC_CONTENT,
        WorldBookFieldNames.CC_INSERTION_ORDER, WorldBookFieldNames.CC_CASE_SENSITIVE,
        WorldBookFieldNames.CC_PRIORITY, WorldBookFieldNames.CC_ID, WorldBookFieldNames.CC_ENABLED,
        WorldBookFieldNames.CC_USE_REGEX, WorldBookFieldNames.CC_EXTENSIONS, "comment", "name",
        "selective", "constant", "position",
    )

    /** Character Book 条目 `extensions` 中由 ST 读取的键（写回时必须覆盖，读入时优先）。 */
    val CC_EXTENSION_KEYS: Set<String> = setOf(
        "position", "depth", "probability", "useProbability", "exclude_recursion", "prevent_recursion",
        "delay_until_recursion", "display_index", "group", "group_override", "group_weight", "scan_depth",
        "case_sensitive", "match_whole_words", "use_group_scoring", "automation_id", "role", "vectorized",
        "sticky", "cooldown", "delay", "triggers", "ignore_budget", "selectiveLogic", "outlet_name",
        "match_persona_description", "match_character_description", "match_character_personality",
        "match_character_depth_prompt", "match_scenario", "match_creator_notes",
    )
}

/** kotlinx 读取小工具（容错，所有失败都退化为 null 由调用方给默认值）。 */
internal object JsonRead {
    fun boolean(element: JsonElement?): Boolean? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        if (primitive.isString) {
            return when (primitive.content.trim().lowercase()) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> null
            }
        }
        primitive.intOrNull?.let { return it != 0 }
        return null
    }

    fun int(element: JsonElement?): Int? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it }
        if (primitive.isString) {
            primitive.content.trim().toIntOrNull()?.let { return it }
            primitive.content.trim().toDoubleOrNull()?.let { return it.toInt() }
        }
        return null
    }

    fun string(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        return scalarString(element)
    }

    /** 读取对象的可选字符串字段。 */
    fun stringField(obj: JsonObject, key: String): String? = string(obj[key])

    /** 读取对象的可选整数（含可解析字符串）字段。 */
    fun intField(obj: JsonObject, key: String): Int? = int(obj[key])

    /** 读取对象的可选布尔（含 0/1、true/false 文本）字段。 */
    fun booleanField(obj: JsonObject, key: String): Boolean? = boolean(obj[key])

    fun scalarString(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.content
        else -> null
    }

    fun obj(obj: JsonObject, vararg names: String): JsonObject? {
        for (name in names) {
            val value = obj[name]
            if (value is JsonObject) return value
        }
        return null
    }

    fun stringList(
        element: JsonElement?,
        entryUid: String?,
        fieldLabel: String,
        sink: NoticeSink,
        limits: WorldBookImportLimits,
    ): List<String> {
        if (element == null || element is JsonNull) return emptyList()
        if (element is JsonArray) {
            val result = ArrayList<String>(element.size)
            for (item in element) {
                val value = scalarString(item)
                if (value == null) {
                    sink.add(
                        entryUid,
                        WorldBookNoticeCodes.ENTRY_FIELD_DROPPED,
                        "$fieldLabel contains a non-string item; dropped.",
                    )
                } else {
                    result += value
                }
            }
            return result
        }
        val single = scalarString(element)
        if (single != null) {
            sink.add(
                entryUid,
                WorldBookNoticeCodes.ENTRY_FIELD_COERCED,
                "$fieldLabel was a string; wrapped in an array.",
            )
            return listOf(single)
        }
        sink.add(
            entryUid,
            WorldBookNoticeCodes.ENTRY_FIELD_COERCED,
            "$fieldLabel was not an array; using empty list.",
        )
        return emptyList()
    }
}

/** 覆盖写出：先放入原 JSON 中“非已知字段”的未知字段，再写已知字段。 */
internal fun JsonObjectBuilder.putUnknownFields(original: JsonObject, vararg knownSets: Set<String>) {
    val known = knownSets.flatMap { it }.toSet()
    for ((key, value) in original) {
        if (key !in known) put(key, value)
    }
}

internal fun JsonObjectBuilder.putStringArray(key: String, values: List<String>) {
    put(key, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

internal fun JsonObjectBuilder.putIntOrNull(key: String, value: Int?) {
    if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
}

internal fun JsonObjectBuilder.putBoolOrNull(key: String, value: Boolean?) {
    if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
}
