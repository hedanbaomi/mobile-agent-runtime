// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.worldbook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * ============================================================================================
 * World book (SillyTavern "World Info") core data contract — 公开接口清单
 * ============================================================================================
 *
 * 本文件只承载纯 JVM 数据；不访问网络、数据库、Android 或文件系统。
 * 上游行为基线：SillyTavern 1.19.0 `public/scripts/world-info.js`
 * (git commit 06bde939fb1e9c4c8d8641d810f0a916b5bce127) 与 `public/scripts/macros.js`；
 * Character Card V2/V3 规范：`character-card-v2.md`（commit 8083fb388615ccbce768e97cbbd49d2b3214632c）、
 * `character-card-v3.md`（commit f3a86af019fbd99f788f7a1155f399655b34ab35）。
 * 本仓库按行为独立实现，不复制上游实现代码。
 *
 * 模块归属（主协调者接线用）：
 *   - 本包 runtime.mobileagent.domain.worldbook ........................ 纯数据（本文件）
 *   - runtime.mobileagent.serialization.worldbook ...................... WorldBookCodec / 导入结果
 *   - runtime.mobileagent.agent.worldbook .............................. WorldBookEngine / Matcher / Macros / Effects
 *   - WorldBookStorage.kt .............................................. 主协调者（本包，未创建）
 *
 * 公开接口清单
 * ------------
 * 文档与条目
 *   WorldBookDocument(name, entries, description, settings, originalJson, format)
 *   WorldBookEntry(uid, keys, content, title, enabled, secondaryKeys, constant, ...)  // 完整 typed ST 字段
 *   WorldBookCharacterFilter(isExclude, names, tags)
 *   WorldBookSettings(...扫描深度/递归/最小激活/预算/匹配开关/硬限额)
 *   enum WorldBookFormat                 NATIVE | SILLY_TAVERN | CHARACTER_BOOK | LOREBOOK_V3
 *                                        | CHARACTER_CARD_V2 | CHARACTER_CARD_V3
 *   enum WorldBookPosition               BEFORE_CHAR=0 AFTER_CHAR=1 AN_TOP=2 AN_BOTTOM=3
 *                                        AT_DEPTH=4 EM_TOP=5 EM_BOTTOM=6 OUTLET=7   （对照 ST 0..7）
 *   enum WorldBookRole                   SYSTEM=0 USER=1 ASSISTANT=2                 （对照 ST 0..2）
 *   enum WorldBookSelectiveLogic         AND_ANY=0 NOT_ALL=1 NOT_ANY=2 AND_ALL=3
 *   enum WorldBookInsertionStrategy      EVENLY=0 CHARACTER_FIRST=1 GLOBAL_FIRST=2
 *   enum WorldBookSourceKind             GLOBAL | CHARACTER | CHAT | PERSONA
 *   enum WorldBookTimedEffectType        STICKY | COOLDOWN | DELAY
 *   enum WorldBookScanState              INITIAL | RECURSION | MIN_ACTIVATIONS
 *   enum WorldBookDecisionStatus         ACTIVATED | SKIPPED | INELIGIBLE | DENIED | NEEDS_EXTERNAL
 *
 * 扫描输入（给引擎；不含任何 I/O）
 *   WorldBookSource(id, revision, document, kind)
 *   WorldBookScanMessage(text, role, name)
 *   WorldBookContext(characterName, userName, characterDescription, characterPersonality,
 *                    characterDepthPrompt, personaDescription, scenario, creatorNotes,
 *                    characterTags, authorNote, exampleMessages, characterFileName, injectedPrompts)
 *   WorldBookScanRequest(sources, messages, settings, context, messageCount, generationType,
 *                        previousEffects, seed)
 *
 * 扫描输出（可序列化，便于持久 Run 结果与跨重启 timed effects）
 *   WorldBookTimedEffect(type, bookId, entryUid, hash, start, end, protected)
 *   WorldBookActivation(bookId, revision, entryUid, title, content, position, depth, role,
 *                       outletName, order, reason, estimatedUnits, hash, index)
 *   WorldBookDecision(bookId, entryUid, title, status, reason, matchedKeys, estimatedUnits, loop, scanState)
 *   WorldBookEvaluation(activated, decisions, nextEffects, warnings, estimatedUnits, loops,
 *                       budgetUnits, hardBudgetUnits)
 *
 * 引擎入口（本包之外）
 *   runtime.mobileagent.agent.worldbook.WorldBookEngine.evaluate(
 *       request: WorldBookScanRequest,
 *       budgetUnits: Int,
 *       hardBudgetUnits: Int = budgetUnits,
 *       estimateUnits: (String) -> Int,
 *   ): WorldBookEvaluation
 *       —— 纯函数；不请求网络或数据库。
 *       —— budgetUnits 为世界书软预算（ignoreBudget 条目可越过），hardBudgetUnits 为请求硬预算
 *          （任何条目都不可越过）。
 *
 * 编解码入口（serialization 模块）
 *   WorldBookCodec.decode(text: String, fallbackName: String = "Imported world book"): WorldBookImportResult
 *   WorldBookCodec.encode(document: WorldBookDocument, format: WorldBookFormat = document.format): String
 *   WorldBookCodec.encodeNative(document: WorldBookDocument): String
 *   WorldBookImportResult(document, notices)
 *   WorldBookImportNotice(entryUid: String?, code: String, message: String)
 *
 * 原样保留承诺：每个条目保留 originalJson（原始 JSON 对象）与 originalKey（原始 map key），
 * 文档保留 originalJson（原始文档/角色卡 JSON）。未知字段与 extensions 不丢失；编辑后导出
 * 覆盖已编辑的已知字段并保留未知字段。
 * ============================================================================================
 */

/**
 * 一本世界书（文档根）。`originalJson` 保存导入时的完整原始文档（角色卡格式保存整张卡），
 * `format` 记录来源载体，供无损再导出。
 */
@Serializable
data class WorldBookDocument(
    val name: String,
    val entries: List<WorldBookEntry> = emptyList(),
    val description: String = "",
    val settings: WorldBookSettings = WorldBookSettings(),
    val originalJson: JsonObject = JsonObject(emptyMap()),
    val format: WorldBookFormat = WorldBookFormat.NATIVE,
)

/**
 * 世界书载体格式。导入时按结构探测，导出时按此选择目标结构。
 */
@Serializable
enum class WorldBookFormat {
    /** 本产品原生信封；无损（含 originalJson / originalKey）。 */
    NATIVE,

    /** SillyTavern 世界书文件：`entries` 为以 uid 字符串为键的对象映射，camelCase 字段。 */
    SILLY_TAVERN,

    /** Character Card V2 内嵌 `character_book`（snake_case 字段、数组条目）；亦兼容独立导出。 */
    CHARACTER_BOOK,

    /** 独立 Lorebook V3：`spec = "lorebook_v3"`，`entries` 数组，snake_case 字段。 */
    LOREBOOK_V3,

    /** 完整角色卡 V2（`spec = "chara_card_v2"`，世界书位于 `data.character_book`）。 */
    CHARACTER_CARD_V2,

    /** 完整角色卡 V3（`spec = "chara_card_v3"`，世界书位于 `data.character_book`）。 */
    CHARACTER_CARD_V3,
}

/**
 * 条目在提示中的插入位置。数值与 ST `world_info_position` 一致（0..7）。
 */
@Serializable
enum class WorldBookPosition(val code: Int) {
    BEFORE_CHAR(0),
    AFTER_CHAR(1),
    AN_TOP(2),
    AN_BOTTOM(3),
    AT_DEPTH(4),
    EM_TOP(5),
    EM_BOTTOM(6),
    OUTLET(7),
    ;

    companion object {
        fun fromCode(code: Int): WorldBookPosition? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 深度注入条目的角色。数值与 ST `extension_prompt_roles` 一致（0..2）。
 */
@Serializable
enum class WorldBookRole(val code: Int) {
    SYSTEM(0),
    USER(1),
    ASSISTANT(2),
    ;

    companion object {
        fun fromCode(code: Int): WorldBookRole? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 次要关键词逻辑。数值与 ST `world_info_logic` 一致（0..3）。
 */
@Serializable
enum class WorldBookSelectiveLogic(val code: Int) {
    /** 主键命中，且任意次键命中。 */
    AND_ANY(0),

    /** 主键命中，且至少一个次键不命中。 */
    NOT_ALL(1),

    /** 主键命中，且所有次键都不命中。 */
    NOT_ANY(2),

    /** 主键命中，且所有次键都命中。 */
    AND_ALL(3),
    ;

    companion object {
        fun fromCode(code: Int): WorldBookSelectiveLogic? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 角色书与全局书的合并插入策略。数值与 ST `world_info_insertion_strategy` 一致（0..2）。
 */
@Serializable
enum class WorldBookInsertionStrategy(val code: Int) {
    EVENLY(0),
    CHARACTER_FIRST(1),
    GLOBAL_FIRST(2),
    ;

    companion object {
        fun fromCode(code: Int): WorldBookInsertionStrategy? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 来源类别，对应 ST 的 chat / persona / character / global 四类书。
 * 排序语义见 [WorldBookEngine] 与 ST `getSortedEntries`。
 */
@Serializable
enum class WorldBookSourceKind {
    GLOBAL,
    CHARACTER,
    CHAT,
    PERSONA,
}

/**
 * 定时效果类型。DELAY 不持久化（由 `messageCount` 与条目 `delay` 派生）。
 */
@Serializable
enum class WorldBookTimedEffectType {
    STICKY,
    COOLDOWN,
    DELAY,
}

/**
 * 扫描状态机状态，对应 ST `scan_state`（NONE 表示停止，不进入状态机）。
 */
@Serializable
enum class WorldBookScanState {
    INITIAL,
    RECURSION,
    MIN_ACTIVATIONS,
}

/**
 * 单条目在本轮评估中的裁决状态。
 */
@Serializable
enum class WorldBookDecisionStatus {
    /** 已加入激活集合。 */
    ACTIVATED,

    /** 已评估但未激活（键未命中、预算不足、概率未过等）。 */
    SKIPPED,

    /** 恒不参与（禁用、触发器不匹配、角色过滤器排除、自动化条目等）。 */
    INELIGIBLE,

    /** 被显式规则否决（@@dont_activate、排除递归、延迟/冷却等）。 */
    DENIED,

    /** 需要本产品不具备的外部环境（向量扩展等），不得静默扩大激活。 */
    NEEDS_EXTERNAL,
}

/**
 * 条目级角色/标签过滤器（ST `characterFilter`）。
 */
@Serializable
data class WorldBookCharacterFilter(
    /** true 时 names/tags 表示排除名单。 */
    val isExclude: Boolean = false,
    val names: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = names.isEmpty() && tags.isEmpty()
}

/**
 * 世界书条目。字段名与 ST 内部字段一一对应（见各自 KDoc），默认值取 ST `newWorldInfoEntryDefinition`。
 *
 * 与 ST 的命名对照：
 *   keys=key, secondaryKeys=keysecondary, title=comment, enabled=!disable,
 *   characterFilter=characterFilter, groupWeight=groupWeight, ...
 */
@Serializable
data class WorldBookEntry(
    /** ST `uid`（原生格式中同时是 entries 映射键）。本产品统一以字符串承载。 */
    val uid: String,
    /** ST `key`：主关键词（可含 `/pattern/flags` 正则键）。 */
    val keys: List<String> = emptyList(),
    /** 条目正文（ST `content`）；保留装饰器原文，装饰器在扫描期解析。 */
    val content: String = "",
    /** ST `comment`：标题/备注。 */
    val title: String = "",
    /** ST `disable` 取反。 */
    val enabled: Boolean = true,
    /** ST `keysecondary`。 */
    val secondaryKeys: List<String> = emptyList(),
    /** ST `constant`：恒定激活。 */
    val constant: Boolean = false,
    /** ST `vectorized`：向量激活条目（本产品无向量扩展，扫描时进入 NEEDS_EXTERNAL）。 */
    val vectorized: Boolean = false,
    /** ST `selective`：是否使用次要关键词（ST 1.19 默认 true）。 */
    val selective: Boolean = true,
    /** ST `selectiveLogic`。 */
    val selectiveLogic: WorldBookSelectiveLogic = WorldBookSelectiveLogic.AND_ANY,
    /** ST `addMemo`：是否把 comment 作为备忘标题展示。 */
    val addMemo: Boolean = false,
    /** ST `order`：插入权重（越大越靠前）。 */
    val order: Int = 100,
    /**
     * Character Card V2/V3 的 `priority`：**独立的**预算优先级字段
     * （token_budget 达到时优先丢弃低 priority），与 [order] 语义不同，导入/导出必须分开保留。
     * 本引擎的预算不使用它（按处理顺序分配），仅无损保留。
     */
    val priority: Int? = null,
    /** ST `position`（0..7）。 */
    val position: WorldBookPosition = WorldBookPosition.BEFORE_CHAR,
    /** ST `ignoreBudget`：可越过软预算，但不可越过请求硬预算。 */
    val ignoreBudget: Boolean = false,
    /** ST `excludeRecursion`：递归扫描时不激活。 */
    val excludeRecursion: Boolean = false,
    /** ST `preventRecursion`：本条目内容不进入递归缓冲。 */
    val preventRecursion: Boolean = false,
    /** ST `matchPersonaDescription`：把 Persona 描述纳入匹配源。 */
    val matchPersonaDescription: Boolean = false,
    /** ST `matchCharacterDescription`。 */
    val matchCharacterDescription: Boolean = false,
    /** ST `matchCharacterPersonality`。 */
    val matchCharacterPersonality: Boolean = false,
    /** ST `matchCharacterDepthPrompt`。 */
    val matchCharacterDepthPrompt: Boolean = false,
    /** ST `matchScenario`。 */
    val matchScenario: Boolean = false,
    /** ST `matchCreatorNotes`。 */
    val matchCreatorNotes: Boolean = false,
    /**
     * ST `delayUntilRecursion`：false→0，true→1，数字表示递归延迟层数；
     * 语义为“直到第 N 个递归层才允许激活”。
     */
    val delayUntilRecursion: Int = 0,
    /** ST `probability`：0..100。 */
    val probability: Int = 100,
    /** ST `useProbability`。 */
    val useProbability: Boolean = true,
    /** ST `depth`：AT_DEPTH 注入深度。 */
    val depth: Int = DEFAULT_DEPTH,
    /** ST `outletName`：OUTLET 位置的目标 outlet。 */
    val outletName: String = "",
    /** ST `group`：包含组，逗号分隔可属多组。 */
    val group: String = "",
    /** ST `groupOverride`：组内优先级覆盖。 */
    val groupOverride: Boolean = false,
    /** ST `groupWeight`：加权随机权重（默认 100）。 */
    val groupWeight: Int = DEFAULT_GROUP_WEIGHT,
    /** ST `scanDepth`：覆盖全局扫描深度；null 表示跟随设置。 */
    val scanDepth: Int? = null,
    /** ST `caseSensitive`：覆盖全局大小写设置；null 表示跟随设置。 */
    val caseSensitive: Boolean? = null,
    /** ST `matchWholeWords`：覆盖全局整词设置；null 表示跟随设置。 */
    val matchWholeWords: Boolean? = null,
    /** ST `useGroupScoring`：覆盖全局组评分设置；null 表示跟随设置。 */
    val useGroupScoring: Boolean? = null,
    /** ST `automationId`：由 Quick Reply 自动化驱动；本产品不执行外部自动化。 */
    val automationId: String = "",
    /** ST `role`（0..2），仅 AT_DEPTH 生效。 */
    val role: WorldBookRole = WorldBookRole.SYSTEM,
    /** ST `sticky`：激活后保持的消息数；null/0 表示关闭。 */
    val sticky: Int? = null,
    /** ST `cooldown`：激活后冷却的消息数；null/0 表示关闭。 */
    val cooldown: Int? = null,
    /** ST `delay`：前 N 条消息内抑制激活；null/0 表示关闭。 */
    val delay: Int? = null,
    /** ST `triggers`：生成类型白名单（空表示不限）。 */
    val triggers: List<String> = emptyList(),
    /**
     * Character Card V3 `use_regex`：为 true 时 [keys] 直接是正则模式（非 `/pattern/flags` 形式），
     * 且 `secondary_keys` 被忽略、`constant` 被忽略；无效正则按“不匹配”处理。
     * CCv2 无此字段，导入时缺省 false（走 ST 的斜杠定界语义）。
     */
    val useRegex: Boolean = false,
    /** ST `characterFilter`。 */
    val characterFilter: WorldBookCharacterFilter = WorldBookCharacterFilter(),
    /** ST `extensions`：原始扩展对象，原样保留。 */
    val extensions: JsonObject = JsonObject(emptyMap()),
    /** ST `displayIndex`（存于 extensions.display_index），仅用于稳定展示排序。 */
    val displayIndex: Int? = null,
    /** 导入该条目的原始 JSON 对象（未知字段全保留）。 */
    val originalJson: JsonObject = JsonObject(emptyMap()),
    /** 导入该条目时使用的原始 map key（ST 原生格式的 uid 键）。 */
    val originalKey: String? = null,
) {
    companion object {
        /** ST `DEFAULT_DEPTH`。 */
        const val DEFAULT_DEPTH: Int = 4

        /** ST `DEFAULT_WEIGHT`（组权重默认值）。 */
        const val DEFAULT_GROUP_WEIGHT: Int = 100
    }
}

/**
 * 扫描与预算设置（纯数据）。默认值取 ST 全局默认（1.19.0），中文默认按子串匹配。
 */
@Serializable
data class WorldBookSettings(
    /** ST `world_info_depth`：默认扫描的最近消息条数。 */
    val scanDepth: Int = 2,
    /** ST `world_info_min_activations`：未达数量则加深扫描。 */
    val minActivations: Int = 0,
    /** ST `world_info_min_activations_depth_max`：最小激活加深的上限深度。 */
    val minActivationsDepthMax: Int = 0,
    /** ST `world_info_budget`：预算百分比（对 maxContext 的百分比）。 */
    val budgetPercent: Int = 25,
    /** ST `world_info_budget_cap`：预算绝对上限；0 表示不设限。 */
    val budgetCap: Int = 0,
    /** ST `world_info_include_names`：是否把角色/用户名字加入扫描文本。 */
    val includeNames: Boolean = true,
    /** ST `world_info_recursive`：递归扫描。 */
    val recursive: Boolean = false,
    /** ST `world_info_overflow_alert`：预算溢出时提示。 */
    val overflowAlert: Boolean = false,
    /** ST `world_info_case_sensitive`：全局大小写敏感。 */
    val caseSensitive: Boolean = false,
    /** ST `world_info_match_whole_words`：全局整词匹配。 */
    val matchWholeWords: Boolean = false,
    /** ST `world_info_use_group_scoring`：全局组评分。 */
    val useGroupScoring: Boolean = false,
    /** ST `world_info_character_strategy`：角色书/全局书合并策略。 */
    val insertionStrategy: WorldBookInsertionStrategy = WorldBookInsertionStrategy.CHARACTER_FIRST,
    /** ST `world_info_max_recursion_steps`：递归轮上限；0 表示不设限（仍受硬轮数上限约束）。 */
    val maxRecursionSteps: Int = 0,
    /** ST `MAX_SCAN_DEPTH`：单条目扫描深度硬上限。 */
    val maxScanDepth: Int = MAX_SCAN_DEPTH,
    /**
     * 整词匹配是否沿用 ST 的 ASCII `\W` 边界语义（默认 true，与 JS 一致）。
     * 中文在两侧均为非 ASCII 单词字符时仍可命中；若需要“中文严格整词”，置 false 改用 Unicode 字母边界。
     */
    val wholeWordAsciiBoundaries: Boolean = true,
    /** 单个正则关键词的最大长度（超出即拒绝执行）。 */
    val maxRegexPatternLength: Int = 512,
    /** 单次评估的正则匹配操作步数预算（charAt 计数），耗尽后拒绝继续正则匹配。 */
    val regexStepBudget: Long = 2_000_000,
    /** 单次评估允许装入扫描缓冲的最大字符数（超出部分截断并告警）。 */
    val maxMessageScanChars: Int = 2_000_000,
    /**
     * **整轮评估**的宏展开总工作量上限（输入扫描 + 输出产出字符数之和）。
     * 防止“数千条小宏条目各自膨胀到单条上限”把整轮拖垮；只能调低，不能超过硬上限。
     */
    val maxRunExpansionChars: Int = HARD_MAX_RUN_EXPANSION_CHARS,
) {
    companion object {
        /** ST `MAX_SCAN_DEPTH`。 */
        const val MAX_SCAN_DEPTH: Int = 1000

        /** 正则操作步数预算的硬上限：任何导入/构造值只能更低，不能更高。 */
        const val HARD_MAX_REGEX_STEP_BUDGET: Long = 2_000_000L

        /** 单个正则模式长度的硬上限。 */
        const val HARD_MAX_REGEX_PATTERN_LENGTH: Int = 512

        /** 单次评估扫描文本字符数的硬上限。 */
        const val HARD_MAX_MESSAGE_SCAN_CHARS: Int = 2_000_000

        /** 整轮宏展开总工作量的硬上限（8 MiB）。 */
        const val HARD_MAX_RUN_EXPANSION_CHARS: Int = 8 shl 20
    }

    /**
     * 逐字段收敛到不可提升的硬上限（见 companion 常量）。
     *
     * 语义：非法/超限值只能**收紧**，不能放宽；非正值的“无界类”字段（步数预算、扫描字符数、
     * 模式长度）按“未设置”处理，回落到硬上限而不是 0，避免把限制收成不可用。
     * 该函数在 domain 层是单一事实来源；引擎与 codec 都调用它，不依赖 UI 校验。
     */
    fun normalized(): WorldBookSettings = copy(
        scanDepth = scanDepth.coerceIn(0, MAX_SCAN_DEPTH),
        minActivations = minActivations.coerceAtLeast(0),
        minActivationsDepthMax = minActivationsDepthMax.coerceIn(0, MAX_SCAN_DEPTH),
        budgetPercent = budgetPercent.coerceIn(0, 100),
        budgetCap = budgetCap.coerceAtLeast(0),
        maxRecursionSteps = maxRecursionSteps.coerceAtLeast(0),
        maxScanDepth = maxScanDepth.coerceIn(0, MAX_SCAN_DEPTH),
        maxRegexPatternLength = (maxRegexPatternLength.takeIf { it > 0 } ?: HARD_MAX_REGEX_PATTERN_LENGTH)
            .coerceAtMost(HARD_MAX_REGEX_PATTERN_LENGTH),
        regexStepBudget = (regexStepBudget.takeIf { it > 0 } ?: HARD_MAX_REGEX_STEP_BUDGET)
            .coerceAtMost(HARD_MAX_REGEX_STEP_BUDGET),
        maxMessageScanChars = (maxMessageScanChars.takeIf { it > 0 } ?: HARD_MAX_MESSAGE_SCAN_CHARS)
            .coerceAtMost(HARD_MAX_MESSAGE_SCAN_CHARS),
        maxRunExpansionChars = (maxRunExpansionChars.takeIf { it > 0 } ?: HARD_MAX_RUN_EXPANSION_CHARS)
            .coerceAtMost(HARD_MAX_RUN_EXPANSION_CHARS),
    )
}

/**
 * 一本世界书作为扫描源。
 *
 * @param kind 来源类别，决定插入策略排序（见 ST `getSortedEntries`）。
 */
@Serializable
data class WorldBookSource(
    val id: String,
    val revision: Int,
    val document: WorldBookDocument,
    val kind: WorldBookSourceKind = WorldBookSourceKind.GLOBAL,
)

/**
 * 待扫描消息。`messages` 必须按时间正序，当前用户消息在最后一条。
 */
@Serializable
data class WorldBookScanMessage(
    val text: String,
    val role: String = "user",
    val name: String = "",
)

/**
 * 聊天无关的扫描上下文（ST `WIGlobalScanData` 的超集）。
 */
@Serializable
data class WorldBookContext(
    val characterName: String = "",
    val userName: String = "User",
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val characterDepthPrompt: String = "",
    val personaDescription: String = "",
    val scenario: String = "",
    val creatorNotes: String = "",
    val characterTags: List<String> = emptyList(),
    val authorNote: String = "",
    val exampleMessages: List<String> = emptyList(),
    /**
     * 角色文件名/标识，对应 ST `getCharaFilename()`，供条目 `characterFilter.names` 使用。
     * 为空时 names 过滤器不生效（与 ST 中 key 缺失时跳过一致）。
     */
    val characterFileName: String = "",
    /**
     * 具备扫描资格的扩展注入文本（ST `extensionPrompts[*].scan`）。
     * 这些文本进入条目扫描缓冲，但不进入正文；本产品不执行 STscript 生成注入。
     */
    val injectedPrompts: List<String> = emptyList(),
    /**
     * 当前提示模板中真实存在的 outlet 名集合（由 RunPreparation 从用户配置宏提取）。
     * 为空表示没有可用出口（离线预览默认如此）：`position = OUTLET` 的条目判
     * `missing-anchor:outlet:*`，不得静默激活。
     */
    val availableOutlets: Set<String> = emptySet(),
)

/**
 * 一轮世界书扫描请求。
 *
 * @param messageCount 用于 timed effects 的“聊天消息数”；缺省为 `messages.size`。
 * @param generationType 生成类型触发器（ST `globalScanData.trigger`）。
 * @param previousEffects 上一轮持久化的 timed effects（跨重启）。
 * @param seed 概率/组权重的确定性种子，保证可复现。
 */
@Serializable
data class WorldBookScanRequest(
    val sources: List<WorldBookSource>,
    val messages: List<WorldBookScanMessage>,
    val settings: WorldBookSettings = WorldBookSettings(),
    val context: WorldBookContext = WorldBookContext(),
    val messageCount: Int = messages.size,
    val generationType: String = "normal",
    val previousEffects: List<WorldBookTimedEffect> = emptyList(),
    val seed: Long = 0L,
)

/**
 * 持久化定时效果。语义与 ST `chat_metadata.timedWorldInfo` 一致：
 * `start`/`end` 以消息数为单位（不是工具轮）。
 */
@Serializable
data class WorldBookTimedEffect(
    val type: WorldBookTimedEffectType,
    val bookId: String,
    val entryUid: String,
    /** 条目内容 hash；条目被编辑后 hash 变化，旧效果失效。 */
    val hash: Long,
    val start: Int,
    val end: Int,
    /** 受保护效果在消息数未推进时不被清除（sticky 结束转入冷却时使用）。 */
    val protected: Boolean = false,
)

/**
 * 一条已激活条目（正文已做安全宏替换）。`index` 为最终组装顺序。
 */
@Serializable
data class WorldBookActivation(
    val bookId: String,
    val revision: Int,
    val entryUid: String,
    val title: String,
    val content: String,
    val position: WorldBookPosition,
    val depth: Int,
    val role: WorldBookRole,
    val outletName: String,
    val order: Int,
    /** 激活原因（稳定 code，见 WorldBookEngine 的 reason 表）。 */
    val reason: String,
    val estimatedUnits: Int,
    /** 与 [WorldBookTimedEffect.hash] 对应的条目 hash。 */
    val hash: Long = 0L,
    /** 组装顺序下标（0 起）。 */
    val index: Int = 0,
)

/**
 * 单条目裁决记录。**不包含条目正文**，便于安全诊断与持久化。
 */
@Serializable
data class WorldBookDecision(
    val bookId: String,
    val entryUid: String,
    val title: String,
    val status: WorldBookDecisionStatus,
    val reason: String,
    val matchedKeys: List<String> = emptyList(),
    val estimatedUnits: Int = 0,
    /** 产生该裁决的扫描轮次（1 起）。 */
    val loop: Int = 0,
    val scanState: WorldBookScanState = WorldBookScanState.INITIAL,
)

/**
 * 评估结果。全部字段可序列化。
 */
@Serializable
data class WorldBookEvaluation(
    val activated: List<WorldBookActivation> = emptyList(),
    val decisions: List<WorldBookDecision> = emptyList(),
    /** 需要持久化的下一轮效果（仅 STICKY / COOLDOWN；DELAY 由消息数派生）。 */
    val nextEffects: List<WorldBookTimedEffect> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 已激活条目正文的估算总量。 */
    val estimatedUnits: Int = 0,
    val loops: Int = 0,
    val budgetUnits: Int = 0,
    val hardBudgetUnits: Int = 0,
)

/**
 * 导入/导出使用的稳定字段名常量（防止跨模块拼写漂移）。
 */
object WorldBookFieldNames {
    // SillyTavern camelCase
    const val ST_ENTRIES = "entries"
    const val ST_UID = "uid"
    const val ST_KEY = "key"
    const val ST_KEY_SECONDARY = "keysecondary"
    const val ST_COMMENT = "comment"
    const val ST_CONTENT = "content"
    const val ST_CONSTANT = "constant"
    const val ST_VECTORIZED = "vectorized"
    const val ST_SELECTIVE = "selective"
    const val ST_SELECTIVE_LOGIC = "selectiveLogic"
    const val ST_ADD_MEMO = "addMemo"
    const val ST_ORDER = "order"
    const val ST_POSITION = "position"
    const val ST_DISABLE = "disable"
    const val ST_IGNORE_BUDGET = "ignoreBudget"
    const val ST_EXCLUDE_RECURSION = "excludeRecursion"
    const val ST_PREVENT_RECURSION = "preventRecursion"
    const val ST_MATCH_PERSONA = "matchPersonaDescription"
    const val ST_MATCH_CHAR_DESC = "matchCharacterDescription"
    const val ST_MATCH_CHAR_PERSONALITY = "matchCharacterPersonality"
    const val ST_MATCH_CHAR_DEPTH = "matchCharacterDepthPrompt"
    const val ST_MATCH_SCENARIO = "matchScenario"
    const val ST_MATCH_CREATOR_NOTES = "matchCreatorNotes"
    const val ST_DELAY_UNTIL_RECURSION = "delayUntilRecursion"
    const val ST_PROBABILITY = "probability"
    const val ST_USE_PROBABILITY = "useProbability"
    const val ST_DEPTH = "depth"
    const val ST_OUTLET_NAME = "outletName"
    const val ST_GROUP = "group"
    const val ST_GROUP_OVERRIDE = "groupOverride"
    const val ST_GROUP_WEIGHT = "groupWeight"
    const val ST_SCAN_DEPTH = "scanDepth"
    const val ST_CASE_SENSITIVE = "caseSensitive"
    const val ST_MATCH_WHOLE_WORDS = "matchWholeWords"
    const val ST_USE_GROUP_SCORING = "useGroupScoring"
    const val ST_AUTOMATION_ID = "automationId"
    const val ST_ROLE = "role"
    const val ST_STICKY = "sticky"
    const val ST_COOLDOWN = "cooldown"
    const val ST_DELAY = "delay"
    const val ST_TRIGGERS = "triggers"
    const val ST_CHARACTER_FILTER = "characterFilter"
    const val ST_EXTENSIONS = "extensions"

    // Character Book / Lorebook V3 snake_case
    const val CC_NAME = "name"
    const val CC_DESCRIPTION = "description"
    const val CC_SCAN_DEPTH = "scan_depth"
    const val CC_TOKEN_BUDGET = "token_budget"
    const val CC_RECURSIVE_SCANNING = "recursive_scanning"
    const val CC_KEYS = "keys"
    const val CC_SECONDARY_KEYS = "secondary_keys"
    const val CC_CONTENT = "content"
    const val CC_INSERTION_ORDER = "insertion_order"
    const val CC_CASE_SENSITIVE = "case_sensitive"
    const val CC_PRIORITY = "priority"
    const val CC_ID = "id"
    const val CC_ENABLED = "enabled"
    const val CC_USE_REGEX = "use_regex"
    const val CC_CHARACTER_BOOK = "character_book"
    const val CC_SPEC = "spec"
    const val CC_DATA = "data"
    const val CC_EXTENSIONS = "extensions"
    const val SPEC_CHARA_CARD_V2 = "chara_card_v2"
    const val SPEC_CHARA_CARD_V3 = "chara_card_v3"
    const val SPEC_LOREBOOK_V3 = "lorebook_v3"

    // 本产品原生信封
    const val NATIVE_SPEC = "mobileagent.worldbook"
    const val NATIVE_VERSION = 1
    const val NATIVE_SPEC_KEY = "spec"
    const val NATIVE_VERSION_KEY = "version"
    const val NATIVE_FORMAT_KEY = "format"
    const val NATIVE_SETTINGS_KEY = "settings"
    const val NATIVE_ORIGINAL_KEY = "originalJson"
    const val NATIVE_ORIGINAL_MAP_KEY = "originalKey"

    /** ST 内置条件评估支持的生成类型（`GENERATION_TYPE_TRIGGERS` 的稳定子集）。 */
    val GENERATION_TYPES: Set<String> = setOf("normal", "continue", "impersonate", "swipe", "quiet", "regenerate")
}
