// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.charactercard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * ============================================================================================
 * 角色卡（character card）数据契约 — 纯 JVM 数据，无 I/O。
 * ============================================================================================
 *
 * 规范基线（一手规范，本仓库按行为独立实现，不复制实现代码）：
 *   - Character Card V2: https://github.com/malfoyslastname/character-card-spec-v2
 *     (`spec_v2.md`, `spec = "chara_card_v2"`, `spec_version = "2.0"`)
 *   - Character Card V3: https://github.com/kwaroran/character-card-spec-v3
 *     (`SPEC_V3.md`, `spec = "chara_card_v3"`, `spec_version = "3.0"`)
 *   - legacy Tavern V1：六个根字段 `name / description / personality / scenario / first_mes / mes_example`
 *   - PNG 嵌入：标准 tEXt chunk，keyword 为 `chara`（V1/V2）或 `ccv3`（V3），
 *     值为 UTF-8 JSON 的 base64 文本；两者同时存在时以 `ccv3` 为准。
 *
 * 语义边界：
 *   - 卡内文本是**不可信数据**。本类型不做提示组装、不执行卡内脚本/模板/正则，也不联网取 assets。
 *   - 任何字段都不代表权限、工具授权或模型配置；未知字段只原样保留。
 *   - [originalJson] 保存解析时的**完整原始 JSON 根对象**（含未知字段、`extensions`、`assets`），
 *     导入不丢字段；PNG 头像像素不抽取、不保存，PNG 只当元数据载体。
 *   - V2/V3 的 `data.character_book` 由 serialization 层复用既有 `WorldBookCodec` 解析后单独返回，
 *     本类型只在 [originalJson] 中原样保留它，不重复建模。
 * ============================================================================================
 */

/** 角色卡载体格式。 */
@Serializable
enum class CharacterCardFormat {
    /** legacy Tavern V1：六个字段直接位于根对象。 */
    V1,

    /** Character Card V2：`{spec:"chara_card_v2", spec_version:"2.0", data:{...}}`。 */
    V2,

    /** Character Card V3：`{spec:"chara_card_v3", spec_version:"3.0", data:{..., character_book?, assets?, nickname?}}`。 */
    V3,
}

/**
 * 一张导入完成的角色卡（规范化后的已知字段 + 完整原始 JSON）。
 *
 * @param name 展示名；载体未提供可用名字时由导入器回退（回退会给出 notice）。
 * @param originalJson 完整原始 JSON 根对象；未知字段、`extensions`、`assets` 等全部原样保留。
 * @param format 探测到的载体格式；缺省 [CharacterCardFormat.V2] 只用于构造默认值。
 */
@Serializable
data class CharacterCardDocument(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val exampleMessages: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val creatorNotes: String = "",
    val creator: String = "",
    val characterVersion: String = "",
    val tags: List<String> = emptyList(),
    val nickname: String = "",
    val format: CharacterCardFormat = CharacterCardFormat.V2,
    val originalJson: JsonObject = JsonObject(emptyMap()),
    /** CCv3 `group_only_greetings`：仅群聊使用的额外开场白；V1/V2 恒为空。 */
    val groupOnlyGreetings: List<String> = emptyList(),
)

/**
 * 角色卡 JSON 字段名常量（V1 根字段、V2/V3 `data` 字段、载体判别字段）。
 *
 * 只固定键名，不解释语义；未知字段不在清单内，导入/导出时按原样保留。
 */
object CharacterCardFieldNames {
    // V1 根字段 / V2、V3 的 data 字段（snake_case）
    const val NAME = "name"
    const val DESCRIPTION = "description"
    const val PERSONALITY = "personality"
    const val SCENARIO = "scenario"
    const val FIRST_MES = "first_mes"
    const val MES_EXAMPLE = "mes_example"
    const val SYSTEM_PROMPT = "system_prompt"
    const val POST_HISTORY_INSTRUCTIONS = "post_history_instructions"
    const val ALTERNATE_GREETINGS = "alternate_greetings"
    const val CREATOR_NOTES = "creator_notes"
    const val CREATOR = "creator"
    const val CHARACTER_VERSION = "character_version"
    const val TAGS = "tags"

    // CCv3 新增
    const val NICKNAME = "nickname"
    const val GROUP_ONLY_GREETINGS = "group_only_greetings"
    const val ASSETS = "assets"
    const val CREATOR_NOTES_MULTILINGUAL = "creator_notes_multilingual"
    const val SOURCE = "source"

    // 载体判别 / 内嵌世界书
    const val SPEC = "spec"
    const val SPEC_VERSION = "spec_version"
    const val DATA = "data"
    const val CHARACTER_BOOK = "character_book"
    const val EXTENSIONS = "extensions"

    // spec 取值
    const val SPEC_CHARA_CARD_V2 = "chara_card_v2"
    const val SPEC_CHARA_CARD_V3 = "chara_card_v3"

    /** V1 判定所需的“角色卡特有”字段：世界书不会有这两个键。 */
    val V1_GREETING_KEYS: Set<String> = setOf(FIRST_MES, MES_EXAMPLE)
}
