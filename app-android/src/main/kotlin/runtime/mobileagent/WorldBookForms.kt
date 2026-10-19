// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.*
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.feature.worldbooks.*

/** UI drafts remain strings until an explicit save, preview or entry switch validates them. */
internal object WorldBookForms {
    private val json = Json { encodeDefaults = true }
    private data class Field(val key: String, val zh: String, val en: String, val kind: WorldBookFieldKind,
        val choices: List<String> = emptyList(), val array: Boolean = false, val nullable: Boolean = false, val objectValue: Boolean = false)
    private val entry = listOf(
        text("title", "标题", "Title"), text("content", "正文", "Content", true),
        array("keys", "触发词（每行一个）", "Keys (one per line)"),
        bool("enabled", "启用条目", "Enable entry"), bool("constant", "常驻", "Always active"),
        array("secondaryKeys", "次要关键词（每行一个）", "Secondary keys (one per line)"),
        bool("selective", "启用次要条件", "Use secondary conditions"),
        choice("selectiveLogic", "次要条件逻辑", "Secondary logic", WorldBookSelectiveLogic.entries.map { it.name }),
        number("order", "插入顺序", "Insertion order"),
        choice("position", "插入位置", "Insertion position", WorldBookPosition.entries.map { it.name }),
        number("depth", "插入深度", "Insertion depth"),
        choice("role", "深度消息角色", "Depth message role", WorldBookRole.entries.map { it.name }),
        text("outletName", "出口名称", "Outlet name"),
        number("scanDepth", "扫描深度（留空继承）", "Scan depth (empty inherits)", true),
        nullableBool("caseSensitive", "区分大小写", "Case sensitive"),
        nullableBool("matchWholeWords", "整词匹配", "Whole-word matching"),
        bool("excludeRecursion", "不被递归激活", "Exclude from recursion"),
        bool("preventRecursion", "阻止继续递归", "Prevent further recursion"),
        number("delayUntilRecursion", "延迟递归层级", "Delayed recursion level"),
        bool("useProbability", "启用概率", "Use probability"), number("probability", "触发概率百分比", "Trigger probability percent"),
        text("group", "包含组（逗号分隔）", "Inclusion groups (comma-separated)"),
        bool("groupOverride", "组内优先选择", "Prioritize within group"), number("groupWeight", "组内随机权重", "Group weight"),
        nullableBool("useGroupScoring", "按关键词数量评分", "Score groups by key matches"),
        number("sticky", "持续消息数（留空关闭）", "Sticky messages (empty disables)", true),
        number("cooldown", "冷却消息数（留空关闭）", "Cooldown messages (empty disables)", true),
        number("delay", "初始延迟消息数（留空关闭）", "Initial message delay (empty disables)", true),
        array("triggers", "生成类型（每行一个，空为全部）", "Generation types (one per line; empty means all)"),
        bool("ignoreBudget", "忽略世界书软预算（仍受请求上限约束）", "Ignore book budget (request limit still applies)"),
        bool("matchPersonaDescription", "匹配 Persona 描述", "Match persona description"),
        bool("matchCharacterDescription", "匹配角色描述", "Match character description"),
        bool("matchCharacterPersonality", "匹配角色性格", "Match character personality"),
        bool("matchCharacterDepthPrompt", "匹配角色备注", "Match character note"),
        bool("matchScenario", "匹配场景", "Match scenario"), bool("matchCreatorNotes", "匹配作者备注", "Match creator notes"),
        objectField("characterFilter", "角色过滤器 JSON", "Character filter JSON"),
        bool("vectorized", "保留向量触发标记（需要外部扩展）", "Vector activation flag (external extension required)"),
        text("automationId", "自动化标识（保留，不执行）", "Automation ID (preserved, never executed)"),
        bool("addMemo", "保留标题备忘标记", "Preserve memo flag"),
        number("displayIndex", "列表显示顺序（可留空）", "Display index (optional)", true),
        objectField("extensions", "扩展字段 JSON（保留，不执行）", "Extension JSON (preserved, never executed)"),
    )
    private val settings = listOf(
        number("scanDepth", "默认扫描消息数", "Default scan depth"),
        number("budgetPercent", "可用输入预算百分比", "Input budget percent"), number("budgetCap", "预算绝对上限（0 为不设软上限）", "Absolute budget cap (0 means no soft cap)"),
        bool("recursive", "递归扫描", "Recursive scanning"), number("maxRecursionSteps", "最多递归步数（0 使用安全上限）", "Maximum recursion steps (0 uses safety limit)"),
        number("minActivations", "最少激活数", "Minimum activations"), number("minActivationsDepthMax", "扩展扫描最大深度（0 使用安全上限）", "Expanded scan depth limit (0 uses safety limit)"),
        bool("includeNames", "匹配消息发送者名称", "Include participant names"),
        bool("caseSensitive", "默认区分大小写", "Default case sensitivity"), bool("matchWholeWords", "默认整词匹配", "Default whole-word matching"),
        bool("useGroupScoring", "默认启用组评分", "Default group scoring"), bool("overflowAlert", "提示预算溢出", "Show budget overflow"),
        choice("insertionStrategy", "多书排序策略", "Multiple-book ordering", WorldBookInsertionStrategy.entries.map { it.name }),
    )

    fun entryFields(value: WorldBookEntry, chinese: Boolean): List<WorldBookFieldUi> = fields(entry, json.encodeToJsonElement(value).jsonObject, chinese)
    fun settingsFields(value: WorldBookSettings, chinese: Boolean): List<WorldBookFieldUi> = fields(settings, json.encodeToJsonElement(value).jsonObject, chinese)
    fun applyEntry(value: WorldBookEntry, values: Map<String, String>): WorldBookEntry =
        json.decodeFromJsonElement(apply(entry, json.encodeToJsonElement(value).jsonObject, values))
    fun applySettings(value: WorldBookSettings, values: Map<String, String>): WorldBookSettings =
        json.decodeFromJsonElement(apply(settings, json.encodeToJsonElement(value).jsonObject, values))

    private fun fields(specs: List<Field>, value: JsonObject, chinese: Boolean) = specs.map { spec ->
        val element = value[spec.key] ?: JsonNull
        val text = when {
            element == JsonNull -> ""
            spec.array -> element.jsonArray.joinToString("\n") { it.jsonPrimitive.content }
            element is JsonPrimitive -> element.content
            else -> element.toString()
        }
        WorldBookFieldUi(spec.key, if (chinese) spec.zh else spec.en, text, spec.kind,
            options = spec.choices.map { it to label(it, chinese) })
    }

    private fun apply(specs: List<Field>, original: JsonObject, values: Map<String, String>): JsonObject = JsonObject(original.toMutableMap().apply {
        specs.forEach { spec -> values[spec.key]?.let { raw ->
            this[spec.key] = when {
                spec.nullable && raw.isBlank() -> JsonNull
                spec.array -> JsonArray(raw.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it.trim()) })
                spec.objectValue -> Json.parseToJsonElement(raw).also { require(it is JsonObject) { "${spec.en} must be an object" } }
                spec.kind == WorldBookFieldKind.BOOLEAN || spec.choices == listOf("", "true", "false") -> JsonPrimitive(requireNotNull(raw.toBooleanStrictOrNull()) { "${spec.en} must be true or false" })
                spec.kind == WorldBookFieldKind.NUMBER -> JsonPrimitive(requireNotNull(raw.toIntOrNull()) { "${spec.en} must be an integer" })
                spec.kind == WorldBookFieldKind.CHOICE -> JsonPrimitive(raw.also { require(it in spec.choices) { "Invalid ${spec.en}" } })
                else -> JsonPrimitive(raw)
            }
        } }
    })

    private fun label(value: String, zh: Boolean): String = if (!zh) value.ifEmpty { "Inherit" } else when (value) {
        "" -> "继承"; "true" -> "是"; "false" -> "否"
        "AND_ANY" -> "任一次要词命中"; "AND_ALL" -> "全部次要词命中"; "NOT_ANY" -> "没有次要词命中"; "NOT_ALL" -> "次要词未全部命中"
        "BEFORE_CHAR" -> "角色描述之前"; "AFTER_CHAR" -> "角色描述之后"; "AN_TOP" -> "作者注释之前"; "AN_BOTTOM" -> "作者注释之后"
        "AT_DEPTH" -> "指定消息深度"; "EM_TOP" -> "示例对话之前"; "EM_BOTTOM" -> "示例对话之后"; "OUTLET" -> "命名出口"
        "SYSTEM" -> "系统"; "USER" -> "用户"; "ASSISTANT" -> "助手"
        "EVENLY" -> "统一排序"; "CHARACTER_FIRST" -> "角色书优先"; "GLOBAL_FIRST" -> "全局书优先"
        else -> value
    }
    private fun text(key: String, zh: String, en: String, multi: Boolean = false) = Field(key, zh, en, if (multi) WorldBookFieldKind.MULTILINE else WorldBookFieldKind.TEXT)
    private fun array(key: String, zh: String, en: String) = Field(key, zh, en, WorldBookFieldKind.MULTILINE, array = true)
    private fun objectField(key: String, zh: String, en: String) = Field(key, zh, en, WorldBookFieldKind.MULTILINE, objectValue = true)
    private fun bool(key: String, zh: String, en: String) = Field(key, zh, en, WorldBookFieldKind.BOOLEAN)
    private fun nullableBool(key: String, zh: String, en: String) = Field(key, zh, en, WorldBookFieldKind.CHOICE, listOf("", "true", "false"), nullable = true)
    private fun number(key: String, zh: String, en: String, nullable: Boolean = false) = Field(key, zh, en, WorldBookFieldKind.NUMBER, nullable = nullable)
    private fun choice(key: String, zh: String, en: String, choices: List<String>) = Field(key, zh, en, WorldBookFieldKind.CHOICE, choices)
}
