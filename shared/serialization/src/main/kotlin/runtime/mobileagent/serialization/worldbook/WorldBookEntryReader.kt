// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.worldbook.WorldBookCharacterFilter
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookFieldNames

/**
 * 条目读取：SillyTavern 原生（camelCase）与 Character Book / Lorebook V3（snake_case）。
 *
 * 读取策略：字段缺失 → typed 默认值；类型不符 → 记录 notice 并取安全默认值；
 * 原始 JSON 完整存入 `originalJson`（未知字段/扩展永不丢失）。
 * 类型化可选字段优先取 `extensions`（对齐 ST `convertCharacterBook`）。
 */
internal object WorldBookEntryReader {

    /** 读取 SillyTavern 原生条目（也容忍 snake_case 别名）。 */
    fun readStEntry(
        raw: JsonObject,
        uid: String,
        originalKey: String?,
        sink: NoticeSink,
        limits: WorldBookImportLimits,
    ): WorldBookEntry = readEntry(raw, uid, originalKey, fromExtensions = false, sink = sink, limits = limits)

    /** 读取 Character Book / Lorebook V3 条目（snake_case，typed 覆盖值优先取 `extensions`）。 */
    fun readCcEntry(
        raw: JsonObject,
        index: Int,
        sink: NoticeSink,
        limits: WorldBookImportLimits,
    ): WorldBookEntry {
        val uidElement = raw[WorldBookFieldNames.CC_ID]
        val uid = when (uidElement) {
            null, is JsonNull -> {
                sink.add(null, WorldBookNoticeCodes.ENTRY_UID_MISSING, "Entry #$index has no 'id'; using array index.")
                index.toString()
            }

            else -> JsonRead.scalarString(uidElement) ?: index.toString()
        }
        return readEntry(raw, uid, null, fromExtensions = true, sink = sink, limits = limits)
    }

    private fun readEntry(
        raw: JsonObject,
        uid: String,
        originalKey: String?,
        fromExtensions: Boolean,
        sink: NoticeSink,
        limits: WorldBookImportLimits,
    ): WorldBookEntry {
        val extensions = JsonRead.obj(raw, WorldBookFieldNames.ST_EXTENSIONS, WorldBookFieldNames.CC_EXTENSIONS)
            ?: JsonObject(emptyMap())

        fun source(vararg names: String): JsonElement? {
            if (fromExtensions) {
                for (name in names) {
                    val value = extensions[name]
                    if (value != null) return value
                }
            }
            for (name in names) {
                val value = raw[name]
                if (value != null) return value
            }
            if (!fromExtensions) {
                for (name in names) {
                    val value = extensions[name]
                    if (value != null) return value
                }
            }
            return null
        }

        val keys = JsonRead.stringList(
            raw[WorldBookFieldNames.ST_KEY] ?: raw[WorldBookFieldNames.CC_KEYS],
            uid,
            "$uid.key",
            sink,
            limits,
        )
        val secondaryKeys = JsonRead.stringList(
            raw[WorldBookFieldNames.ST_KEY_SECONDARY] ?: raw[WorldBookFieldNames.CC_SECONDARY_KEYS],
            uid,
            "$uid.keysecondary",
            sink,
            limits,
        )
        for ((field, list) in listOf("key" to keys, "keysecondary" to secondaryKeys)) {
            if (list.size > limits.maxKeysPerEntry) {
                throw WorldBookImportException(
                    WorldBookNoticeCodes.ENTRY_KEY_COUNT_LIMIT,
                    "Entry '$uid' field '$field' has ${list.size} keys, limit is ${limits.maxKeysPerEntry}.",
                )
            }
        }

        val enabled = readEnabled(raw)

        return WorldBookEntry(
            uid = uid,
            keys = keys,
            content = JsonRead.string(raw[WorldBookFieldNames.ST_CONTENT] ?: raw[WorldBookFieldNames.CC_CONTENT]) ?: "",
            title = JsonRead.string(
                raw[WorldBookFieldNames.ST_COMMENT] ?: raw[WorldBookFieldNames.CC_NAME] ?: raw["comment"],
            ) ?: "",
            enabled = enabled,
            secondaryKeys = secondaryKeys,
            constant = JsonRead.boolean(source(WorldBookFieldNames.ST_CONSTANT, "constant")) ?: false,
            vectorized = JsonRead.boolean(source(WorldBookFieldNames.ST_VECTORIZED, "vectorized")) ?: false,
            selective = JsonRead.boolean(source(WorldBookFieldNames.ST_SELECTIVE, "selective")) ?: true,
            selectiveLogic = WorldBookFieldManual.selectiveLogic(raw, extensions, uid, fromExtensions, sink),
            addMemo = JsonRead.boolean(source(WorldBookFieldNames.ST_ADD_MEMO, "addMemo")) ?: false,
            // `priority` 是 CCv2/V3 的独立预算字段，绝不与 order 混同。
            order = JsonRead.int(source(WorldBookFieldNames.ST_ORDER, "insertion_order", "order")) ?: 100,
            priority = JsonRead.int(source(WorldBookFieldNames.CC_PRIORITY, "priority")),
            position = WorldBookFieldManual.position(raw, extensions, uid, fromExtensions, sink),
            ignoreBudget = JsonRead.boolean(source(WorldBookFieldNames.ST_IGNORE_BUDGET, "ignore_budget")) ?: false,
            excludeRecursion = JsonRead.boolean(
                source(WorldBookFieldNames.ST_EXCLUDE_RECURSION, "exclude_recursion"),
            ) ?: false,
            preventRecursion = JsonRead.boolean(
                source(WorldBookFieldNames.ST_PREVENT_RECURSION, "prevent_recursion"),
            ) ?: false,
            matchPersonaDescription = JsonRead.boolean(
                source(WorldBookFieldNames.ST_MATCH_PERSONA, "match_persona_description"),
            ) ?: false,
            matchCharacterDescription = JsonRead.boolean(
                source(WorldBookFieldNames.ST_MATCH_CHAR_DESC, "match_character_description"),
            ) ?: false,
            matchCharacterPersonality = JsonRead.boolean(
                source(WorldBookFieldNames.ST_MATCH_CHAR_PERSONALITY, "match_character_personality"),
            ) ?: false,
            matchCharacterDepthPrompt = JsonRead.boolean(
                source(WorldBookFieldNames.ST_MATCH_CHAR_DEPTH, "match_character_depth_prompt"),
            ) ?: false,
            matchScenario = JsonRead.boolean(source(WorldBookFieldNames.ST_MATCH_SCENARIO, "match_scenario")) ?: false,
            matchCreatorNotes = JsonRead.boolean(
                source(WorldBookFieldNames.ST_MATCH_CREATOR_NOTES, "match_creator_notes"),
            ) ?: false,
            delayUntilRecursion = WorldBookFieldManual.delayUntilRecursion(
                source(WorldBookFieldNames.ST_DELAY_UNTIL_RECURSION, "delay_until_recursion"),
                uid,
                sink,
            ),
            probability = WorldBookFieldManual.probability(
                source(WorldBookFieldNames.ST_PROBABILITY, "probability"),
                uid,
                sink,
            ),
            useProbability = JsonRead.boolean(source(WorldBookFieldNames.ST_USE_PROBABILITY, "useProbability")) ?: true,
            depth = WorldBookFieldManual.depth(source(WorldBookFieldNames.ST_DEPTH, "depth"), uid, sink),
            outletName = JsonRead.string(source(WorldBookFieldNames.ST_OUTLET_NAME, "outlet_name")) ?: "",
            group = JsonRead.string(source(WorldBookFieldNames.ST_GROUP, "group")) ?: "",
            groupOverride = JsonRead.boolean(source(WorldBookFieldNames.ST_GROUP_OVERRIDE, "group_override")) ?: false,
            groupWeight = WorldBookFieldManual.groupWeight(
                source(WorldBookFieldNames.ST_GROUP_WEIGHT, "group_weight"),
                uid,
                sink,
            ),
            scanDepth = JsonRead.int(source(WorldBookFieldNames.ST_SCAN_DEPTH, "scan_depth")),
            caseSensitive = JsonRead.boolean(source(WorldBookFieldNames.ST_CASE_SENSITIVE, "case_sensitive")),
            matchWholeWords = JsonRead.boolean(
                source(WorldBookFieldNames.ST_MATCH_WHOLE_WORDS, "match_whole_words"),
            ),
            useGroupScoring = JsonRead.boolean(
                source(WorldBookFieldNames.ST_USE_GROUP_SCORING, "use_group_scoring"),
            ),
            automationId = JsonRead.string(source(WorldBookFieldNames.ST_AUTOMATION_ID, "automation_id")) ?: "",
            role = WorldBookFieldManual.role(raw, extensions, uid, fromExtensions, sink),
            sticky = JsonRead.int(source(WorldBookFieldNames.ST_STICKY, "sticky")),
            cooldown = JsonRead.int(source(WorldBookFieldNames.ST_COOLDOWN, "cooldown")),
            delay = JsonRead.int(source(WorldBookFieldNames.ST_DELAY, "delay")),
            triggers = JsonRead.stringList(
                source(WorldBookFieldNames.ST_TRIGGERS, "triggers"),
                uid,
                "$uid.triggers",
                sink,
                limits,
            ),
            useRegex = JsonRead.boolean(source(WorldBookFieldNames.CC_USE_REGEX, "useRegex")) ?: false,
            characterFilter = readCharacterFilter(raw, uid, sink, limits),
            extensions = extensions,
            displayIndex = JsonRead.int(source("display_index", "displayIndex")),
            originalJson = raw,
            originalKey = originalKey,
        )
    }

    private fun readEnabled(raw: JsonObject): Boolean {
        val enabledElement = raw[WorldBookFieldNames.CC_ENABLED]
        if (enabledElement != null) return JsonRead.boolean(enabledElement) ?: true
        val disableElement = raw[WorldBookFieldNames.ST_DISABLE]
        if (disableElement != null) return !(JsonRead.boolean(disableElement) ?: false)
        return true
    }

    private fun readCharacterFilter(
        raw: JsonObject,
        uid: String,
        sink: NoticeSink,
        limits: WorldBookImportLimits,
    ): WorldBookCharacterFilter {
        val filter = JsonRead.obj(raw, WorldBookFieldNames.ST_CHARACTER_FILTER) ?: return WorldBookCharacterFilter()
        return WorldBookCharacterFilter(
            isExclude = JsonRead.boolean(filter["isExclude"] ?: filter["is_exclude"] ?: JsonNull) ?: false,
            names = JsonRead.stringList(filter["names"], uid, "$uid.characterFilter.names", sink, limits),
            tags = JsonRead.stringList(filter["tags"], uid, "$uid.characterFilter.tags", sink, limits),
        )
    }
}
