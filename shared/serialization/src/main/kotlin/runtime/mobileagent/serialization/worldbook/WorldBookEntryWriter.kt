// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookFieldNames
import runtime.mobileagent.domain.worldbook.WorldBookPosition

/**
 * 条目写出：已知字段全量覆盖写（编辑即生效），未知字段先原样写回（不丢失）。
 *
 * ST 原生格式使用 camelCase；Character Book / Lorebook V3 使用 snake_case，
 * 并在 `extensions` 中镜像 ST 认识的全部可选字段（对齐 ST `convertCharacterBook` 的读取位置）。
 */
internal object WorldBookEntryWriter {

    /** SillyTavern 原生条目 JSON。 */
    fun stEntryJson(entry: WorldBookEntry): JsonObject = buildJsonObject {
        putUnknownFields(entry.originalJson, WorldBookFieldMaps.ST_KEYS, WorldBookFieldMaps.CC_KEYS)
        put(WorldBookFieldNames.ST_UID, numericOrString(entry.uid))
        putStringArray(WorldBookFieldNames.ST_KEY, entry.keys)
        putStringArray(WorldBookFieldNames.ST_KEY_SECONDARY, entry.secondaryKeys)
        put(WorldBookFieldNames.ST_COMMENT, JsonPrimitive(entry.title))
        put(WorldBookFieldNames.ST_CONTENT, JsonPrimitive(entry.content))
        put(WorldBookFieldNames.ST_CONSTANT, JsonPrimitive(entry.constant))
        put(WorldBookFieldNames.ST_VECTORIZED, JsonPrimitive(entry.vectorized))
        put(WorldBookFieldNames.ST_SELECTIVE, JsonPrimitive(entry.selective))
        put(WorldBookFieldNames.ST_SELECTIVE_LOGIC, JsonPrimitive(entry.selectiveLogic.code))
        put(WorldBookFieldNames.ST_ADD_MEMO, JsonPrimitive(entry.addMemo))
        put(WorldBookFieldNames.ST_ORDER, JsonPrimitive(entry.order))
        // CCv2/V3 独立预算字段：本产品无损保留（ST 原生格式无此字段，写出为惰性附加字段）。
        entry.priority?.let { put(WorldBookFieldNames.CC_PRIORITY, JsonPrimitive(it)) }
        put(WorldBookFieldNames.ST_POSITION, JsonPrimitive(entry.position.code))
        put(WorldBookFieldNames.ST_DISABLE, JsonPrimitive(!entry.enabled))
        put(WorldBookFieldNames.ST_IGNORE_BUDGET, JsonPrimitive(entry.ignoreBudget))
        put(WorldBookFieldNames.ST_EXCLUDE_RECURSION, JsonPrimitive(entry.excludeRecursion))
        put(WorldBookFieldNames.ST_PREVENT_RECURSION, JsonPrimitive(entry.preventRecursion))
        put(WorldBookFieldNames.ST_MATCH_PERSONA, JsonPrimitive(entry.matchPersonaDescription))
        put(WorldBookFieldNames.ST_MATCH_CHAR_DESC, JsonPrimitive(entry.matchCharacterDescription))
        put(WorldBookFieldNames.ST_MATCH_CHAR_PERSONALITY, JsonPrimitive(entry.matchCharacterPersonality))
        put(WorldBookFieldNames.ST_MATCH_CHAR_DEPTH, JsonPrimitive(entry.matchCharacterDepthPrompt))
        put(WorldBookFieldNames.ST_MATCH_SCENARIO, JsonPrimitive(entry.matchScenario))
        put(WorldBookFieldNames.ST_MATCH_CREATOR_NOTES, JsonPrimitive(entry.matchCreatorNotes))
        put(WorldBookFieldNames.ST_DELAY_UNTIL_RECURSION, JsonPrimitive(entry.delayUntilRecursion))
        put(WorldBookFieldNames.ST_PROBABILITY, JsonPrimitive(entry.probability))
        put(WorldBookFieldNames.ST_USE_PROBABILITY, JsonPrimitive(entry.useProbability))
        put(WorldBookFieldNames.ST_DEPTH, JsonPrimitive(entry.depth))
        put(WorldBookFieldNames.ST_OUTLET_NAME, JsonPrimitive(entry.outletName))
        put(WorldBookFieldNames.ST_GROUP, JsonPrimitive(entry.group))
        put(WorldBookFieldNames.ST_GROUP_OVERRIDE, JsonPrimitive(entry.groupOverride))
        put(WorldBookFieldNames.ST_GROUP_WEIGHT, JsonPrimitive(entry.groupWeight))
        putIntOrNull(WorldBookFieldNames.ST_SCAN_DEPTH, entry.scanDepth)
        putBoolOrNull(WorldBookFieldNames.ST_CASE_SENSITIVE, entry.caseSensitive)
        putBoolOrNull(WorldBookFieldNames.ST_MATCH_WHOLE_WORDS, entry.matchWholeWords)
        putBoolOrNull(WorldBookFieldNames.ST_USE_GROUP_SCORING, entry.useGroupScoring)
        put(WorldBookFieldNames.ST_AUTOMATION_ID, JsonPrimitive(entry.automationId))
        put(WorldBookFieldNames.ST_ROLE, JsonPrimitive(entry.role.code))
        putIntOrNull(WorldBookFieldNames.ST_STICKY, entry.sticky)
        putIntOrNull(WorldBookFieldNames.ST_COOLDOWN, entry.cooldown)
        putIntOrNull(WorldBookFieldNames.ST_DELAY, entry.delay)
        putStringArray(WorldBookFieldNames.ST_TRIGGERS, entry.triggers)
        put(
            WorldBookFieldNames.ST_CHARACTER_FILTER,
            buildJsonObject {
                // 嵌套未知键先原样保留，再用 typed 字段覆盖（is_exclude 等别名视为已管理）。
                (entry.originalJson[WorldBookFieldNames.ST_CHARACTER_FILTER] as? JsonObject)
                    ?.let { putUnknownFields(it, CHARACTER_FILTER_KEYS) }
                put("isExclude", JsonPrimitive(entry.characterFilter.isExclude))
                putStringArray("names", entry.characterFilter.names)
                putStringArray("tags", entry.characterFilter.tags)
            },
        )
        put(WorldBookFieldNames.ST_EXTENSIONS, entryExtensions(entry))
        entry.displayIndex?.let { put("displayIndex", JsonPrimitive(it)) }
        // ST 原生格式没有 use_regex 语义；仅在为 true 时写出标记，便于本产品无损往返。
        if (entry.useRegex) put(WorldBookFieldNames.CC_USE_REGEX, JsonPrimitive(true))
    }

    /** Character Book / Lorebook V3 条目 JSON（snake_case + ST 扩展镜像）。 */
    fun ccEntryJson(entry: WorldBookEntry): JsonObject = buildJsonObject {
        putUnknownFields(entry.originalJson, WorldBookFieldMaps.ST_KEYS, WorldBookFieldMaps.CC_KEYS)
        val numericUid = entry.uid.toLongOrNull()
        put(WorldBookFieldNames.CC_ID, if (numericUid != null) JsonPrimitive(numericUid) else JsonPrimitive(entry.uid))
        putStringArray(WorldBookFieldNames.CC_KEYS, entry.keys)
        putStringArray(WorldBookFieldNames.CC_SECONDARY_KEYS, entry.secondaryKeys)
        put(WorldBookFieldNames.CC_CONTENT, JsonPrimitive(entry.content))
        put("comment", JsonPrimitive(entry.title))
        if (entry.title.isNotEmpty()) put("name", JsonPrimitive(entry.title))
        put(WorldBookFieldNames.CC_ENABLED, JsonPrimitive(entry.enabled))
        put(WorldBookFieldNames.CC_INSERTION_ORDER, JsonPrimitive(entry.order))
        entry.priority?.let { put(WorldBookFieldNames.CC_PRIORITY, JsonPrimitive(it)) }
        put(WorldBookFieldNames.CC_CASE_SENSITIVE, JsonPrimitive(entry.caseSensitive ?: false))
        put("selective", JsonPrimitive(entry.selective))
        put("constant", JsonPrimitive(entry.constant))
        put(
            "position",
            when (entry.position) {
                WorldBookPosition.BEFORE_CHAR -> JsonPrimitive("before_char")
                WorldBookPosition.AFTER_CHAR -> JsonPrimitive("after_char")
                else -> JsonPrimitive(entry.position.code)
            },
        )
        // Character Card V3 的 use_regex 只在为 true 时写出（CCv2 规范不含该字段）。
        if (entry.useRegex) put(WorldBookFieldNames.CC_USE_REGEX, JsonPrimitive(true))
        put(WorldBookFieldNames.CC_EXTENSIONS, ccExtensions(entry))
    }

    /** CC 条目 extensions：原文未知键保留，ST 认识的可选字段全部覆盖写出。 */
    private fun ccExtensions(entry: WorldBookEntry): JsonObject = buildJsonObject {
        for ((key, value) in entry.extensions) {
            if (key !in WorldBookFieldMaps.CC_EXTENSION_KEYS) put(key, value)
        }
        put("position", JsonPrimitive(entry.position.code))
        put("depth", JsonPrimitive(entry.depth))
        put("probability", JsonPrimitive(entry.probability))
        put("useProbability", JsonPrimitive(entry.useProbability))
        put("exclude_recursion", JsonPrimitive(entry.excludeRecursion))
        put("prevent_recursion", JsonPrimitive(entry.preventRecursion))
        put("delay_until_recursion", JsonPrimitive(entry.delayUntilRecursion))
        entry.displayIndex?.let { put("display_index", JsonPrimitive(it)) }
        put("group", JsonPrimitive(entry.group))
        put("group_override", JsonPrimitive(entry.groupOverride))
        put("group_weight", JsonPrimitive(entry.groupWeight))
        putIntOrNull("scan_depth", entry.scanDepth)
        putBoolOrNull("case_sensitive", entry.caseSensitive)
        putBoolOrNull("match_whole_words", entry.matchWholeWords)
        putBoolOrNull("use_group_scoring", entry.useGroupScoring)
        put("automation_id", JsonPrimitive(entry.automationId))
        put("role", JsonPrimitive(entry.role.code))
        put("vectorized", JsonPrimitive(entry.vectorized))
        putIntOrNull("sticky", entry.sticky)
        putIntOrNull("cooldown", entry.cooldown)
        putIntOrNull("delay", entry.delay)
        putStringArray("triggers", entry.triggers)
        put("ignore_budget", JsonPrimitive(entry.ignoreBudget))
        put("selectiveLogic", JsonPrimitive(entry.selectiveLogic.code))
        put("outlet_name", JsonPrimitive(entry.outletName))
        put("match_persona_description", JsonPrimitive(entry.matchPersonaDescription))
        put("match_character_description", JsonPrimitive(entry.matchCharacterDescription))
        put("match_character_personality", JsonPrimitive(entry.matchCharacterPersonality))
        put("match_character_depth_prompt", JsonPrimitive(entry.matchCharacterDepthPrompt))
        put("match_scenario", JsonPrimitive(entry.matchScenario))
        put("match_creator_notes", JsonPrimitive(entry.matchCreatorNotes))
    }

    /** ST `extensions`：原样保留，仅补齐 display_index 镜像。 */
    private fun entryExtensions(entry: WorldBookEntry): JsonObject = buildJsonObject {
        for ((key, value) in entry.extensions) put(key, value)
        entry.displayIndex?.let { put("display_index", JsonPrimitive(it)) }
    }

    private fun numericOrString(uid: String): JsonPrimitive {
        val numeric = uid.toLongOrNull()
        return if (numeric != null) JsonPrimitive(numeric) else JsonPrimitive(uid)
    }

    /** `characterFilter` 中由 typed 字段管理的键（其余嵌套键原样保留）。 */
    private val CHARACTER_FILTER_KEYS = setOf("isExclude", "is_exclude", "names", "tags")
}
