// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import kotlinx.serialization.Serializable

/**
 * 导入过程中产生的非致命说明。导入失败一律抛 [WorldBookImportException]，不返回半成品。
 *
 * [code] 为稳定标识（见 [WorldBookNoticeCodes]），[entryUid] 仅在条目级问题时有值。
 */
@Serializable
data class WorldBookImportNotice(
    val entryUid: String? = null,
    val code: String,
    val message: String,
)

/**
 * 导入结果：完整文档 + 非致命说明。
 */
@Serializable
data class WorldBookImportResult(
    val document: runtime.mobileagent.domain.worldbook.WorldBookDocument,
    val notices: List<WorldBookImportNotice> = emptyList(),
)

/**
 * 致命导入错误。抛出即表示“零写入”：调用方不得持久化任何部分结果。
 */
class WorldBookImportException(
    val code: String,
    message: String,
    val notices: List<WorldBookImportNotice> = emptyList(),
) : IllegalArgumentException("[worldbook-import:$code] $message") {
    /** 便于调用方按类型判断，无需解析消息文本。 */
    val importCode: String get() = code
}

/**
 * 有界导入限额。超过任何一项都直接失败（不截断、不静默丢弃）。
 * 单字段上限用于字符串值；[maxChars] 用于整体输入文本。
 */
@Serializable
data class WorldBookImportLimits(
    /** 输入 JSON 文本最大字符数。 */
    val maxChars: Int = 8 * 1024 * 1024,
    /** JSON 最大嵌套深度。 */
    val maxDepth: Int = 32,
    /** JSON 节点（值）总数上限。 */
    val maxNodes: Int = 400_000,
    /** 单个对象的键数量上限（含重复键探测表大小）。 */
    val maxObjectKeys: Int = 8_192,
    /** 世界书条目数上限。 */
    val maxEntries: Int = 5_000,
    /** 单条目主/次关键词数量上限。 */
    val maxKeysPerEntry: Int = 256,
    /** 单个字符串值最大字符数。 */
    val maxStringChars: Int = 1 shl 20,
    /** 书名最大字符数。 */
    val maxNameChars: Int = 512,
    /** 返回给调用方的 notice 数量上限（超出后截断并追加一条汇总）。 */
    val maxNotices: Int = 2_000,
)

/**
 * 稳定 notice / 异常 code 清单。
 */
object WorldBookNoticeCodes {
    const val JSON_SIZE_LIMIT = "json.size-limit"
    const val JSON_DEPTH_LIMIT = "json.depth-limit"
    const val JSON_NODE_LIMIT = "json.node-limit"
    const val JSON_OBJECT_KEYS_LIMIT = "json.object-keys-limit"
    const val JSON_STRING_LIMIT = "json.string-limit"
    const val JSON_SYNTAX = "json.syntax"
    const val JSON_ROOT_NOT_OBJECT = "json.root-not-object"
    const val JSON_DUPLICATE_KEY = "json.duplicate-key"
    const val JSON_BOM = "json.bom-stripped"

    const val ENTRY_COUNT_LIMIT = "entry.count-limit"
    const val ENTRY_KEY_COUNT_LIMIT = "entry.key-count-limit"
    const val ENTRY_DUPLICATE_UID = "entry.duplicate-uid"
    const val ENTRY_NOT_OBJECT = "entry.not-object"
    const val ENTRY_UID_MISSING = "entry.uid-synthesized"
    const val ENTRY_UID_NON_NUMERIC = "entry.uid-non-numeric"
    const val ENTRY_FIELD_COERCED = "entry.field-coerced"
    const val ENTRY_FIELD_DROPPED = "entry.field-dropped"
    const val ENTRY_FIELD_TRUNCATED = "entry.field-truncated"
    const val ENTRY_NAME_TRUNCATED = "book.name-truncated"

    const val FIELD_POSITION_OUT_OF_RANGE = "field.position-out-of-range"
    const val FIELD_ROLE_OUT_OF_RANGE = "field.role-out-of-range"
    const val FIELD_PROBABILITY_OUT_OF_RANGE = "field.probability-out-of-range"
    const val FIELD_GROUP_WEIGHT_OUT_OF_RANGE = "field.group-weight-out-of-range"
    const val FIELD_DEPTH_OUT_OF_RANGE = "field.depth-out-of-range"
    const val FIELD_NEGATIVE_COUNTER = "field.negative-counter"

    const val FORMAT_DETECTED = "format.detected"
    const val SETTINGS_CLAMPED = "settings.clamped"
    const val FORMAT_ASSUMED = "format.assumed"
    const val FORMAT_UNKNOWN_FIELDS = "format.unknown-fields"
    const val FORMAT_EMPTY_ENTRIES = "book.empty-entries"
    const val FORMAT_CHARACTER_BOOK_MISSING = "format.character-book-missing"

    const val ENV_VECTOR_UNAVAILABLE = "env.vector-unavailable"
    const val ENV_AUTOMATION_NOT_EXECUTED = "env.automation-not-executed"
    const val ENV_PERSONA_UNAVAILABLE = "env.persona-unavailable"
    const val ENV_AUTHOR_NOTE_UNAVAILABLE = "env.author-note-unavailable"
    const val ENV_GROUP_UNAVAILABLE = "env.group-unavailable"
    const val ENV_REGEX_UNSUPPORTED_FLAG = "env.regex-unsupported-flag"

    const val NOTICES_TRUNCATED = "notices.truncated"
}
