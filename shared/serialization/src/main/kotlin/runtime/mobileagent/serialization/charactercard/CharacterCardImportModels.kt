// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import kotlinx.serialization.Serializable
import runtime.mobileagent.domain.charactercard.CharacterCardDocument
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.serialization.worldbook.WorldBookImportLimits

/**
 * 导入过程中产生的非致命说明。
 *
 * [code] 是稳定标识（见 [CharacterCardNoticeCodes]）；[message] 只允许包含安全代码、
 * 字段名与计数，**不得**包含卡内正文、名字、作者注等原始私密内容。
 */
@Serializable
data class CharacterCardImportNotice(
    val code: String,
    val message: String,
)

/**
 * 一次导入的完整结果。
 *
 * @param worldBook 卡内 `character_book` 解析出的世界书；没有内嵌书时为 null。
 *   存在但结构损坏时**不会**静默丢弃，而是抛 [CharacterCardImportException]。
 * @param container 载体类型，只取 [CharacterCardContainers.JSON] 或 [CharacterCardContainers.PNG]。
 */
@Serializable
data class CharacterCardImportResult(
    val document: CharacterCardDocument,
    val worldBook: WorldBookDocument? = null,
    val notices: List<CharacterCardImportNotice> = emptyList(),
    val container: String = CharacterCardContainers.JSON,
)

/** 载体常量：JSON 文件或 PNG 元数据容器。 */
object CharacterCardContainers {
    const val JSON: String = "JSON"
    const val PNG: String = "PNG"
}

/**
 * 致命导入错误。抛出即代表“零写入”：调用方不得持久化任何部分结果。
 *
 * 消息只包含稳定 code、字段名与数量；不包含卡内原始内容。
 */
class CharacterCardImportException(
    val code: String,
    message: String,
) : IllegalArgumentException("[character-card:$code] $message") {
    /** 便于调用方按类型判断，无需解析消息文本。 */
    val importCode: String get() = code
}

/** 稳定 notice code 清单（非致命）。 */
object CharacterCardNoticeCodes {
    const val JSON_BOM = "json.bom-stripped"

    /** 使用 `chara` tEXt 元数据（V1/V2 回填）。 */
    const val PNG_CHARA_USED = "png.chara-used"

    /** 使用 `ccv3` tEXt 元数据。 */
    const val PNG_CCV3_USED = "png.ccv3-used"

    /** `chara` 与 `ccv3` 同时存在，按规范优先 `ccv3`。 */
    const val PNG_CCV3_PREFERRED = "png.ccv3-preferred"

    /** PNG chunk 名与内嵌对象 spec 不一致（按内嵌对象识别，仅提示）。 */
    const val PNG_CONTAINER_SPEC_MISMATCH = "png.container-spec-mismatch"

    /** PNG 头像像素未抽取/保存。 */
    const val PNG_AVATAR_NOT_IMPORTED = "png.avatar-not-imported"

    /** PNG 中的 `chara-ext-asset_:*` 等内嵌资源未导入。 */
    const val PNG_ASSETS_NOT_IMPORTED = "png.assets-not-imported"

    /** 卡内脚本/模板未执行。 */
    const val CARD_SCRIPTS_NOT_EXECUTED = "card.scripts-not-executed"

    /** 卡内引用的外部 assets 未下载。 */
    const val CARD_ASSETS_NOT_FETCHED = "card.assets-not-fetched"

    /** 未知字段只保存在 originalJson，不参与运行。 */
    const val CARD_UNKNOWN_FIELDS_PRESERVED = "card.unknown-fields-preserved"

    /** 载体没有可用名字，使用了回退名。 */
    const val CARD_NAME_FALLBACK = "card.name-fallback"

    /** 名字超过长度上限，已截断（原值仍在 originalJson）。 */
    const val CARD_NAME_TRUNCATED = "card.name-truncated"

    /** V2/V3 缺少 `spec_version`。 */
    const val CARD_SPEC_VERSION_MISSING = "card.spec-version-missing"

    /** `spec_version` 的 major 与 `spec` 不一致（按 spec 识别，仅提示）。 */
    const val CARD_SPEC_VERSION_MISMATCH = "card.spec-version-mismatch"

    /** 已知字段显式为 null，按“未提供”处理。 */
    const val CARD_FIELD_NULL = "card.field-null"

    /** 内嵌 character_book 已解析。 */
    const val CARD_WORLDBOOK_IMPORTED = "card.worldbook-imported"

    /** notice 数量超过上限后的汇总。 */
    const val NOTICES_TRUNCATED = "notices.truncated"
}

/** 稳定致命错误 code 清单。 */
object CharacterCardErrorCodes {
    const val FILE_TOO_LARGE = "card.file-too-large"
    const val ENCODING_INVALID = "card.encoding-invalid"

    const val JSON_SIZE_LIMIT = "card.json-size-limit"
    const val JSON_DEPTH_LIMIT = "card.json-depth-limit"
    const val JSON_NODE_LIMIT = "card.json-node-limit"
    const val JSON_OBJECT_KEYS_LIMIT = "card.json-object-keys-limit"
    const val JSON_STRING_LIMIT = "card.json-string-limit"
    const val JSON_SYNTAX = "card.json-syntax"
    const val JSON_DUPLICATE_KEY = "card.json-duplicate-key"
    const val ROOT_NOT_OBJECT = "card.root-not-object"

    const val UNRECOGNIZED = "card.unrecognized"
    const val UNKNOWN_SPEC = "card.unknown-spec"
    const val INVALID_SPEC_VERSION = "card.invalid-spec-version"
    const val UNSUPPORTED_SPEC_VERSION = "card.unsupported-spec-version"
    const val DATA_NOT_OBJECT = "card.data-not-object"
    const val FIELD_TYPE = "card.field-type"
    const val LIST_LIMIT = "card.list-limit"

    const val CHARACTER_BOOK_INVALID = "card.character-book-invalid"
    const val WORLDBOOK_INVALID = "card.worldbook-invalid"

    const val PNG_CHUNK_TRUNCATED = "png.chunk-truncated"
    const val PNG_CHUNK_LENGTH = "png.chunk-length-invalid"
    const val PNG_CHUNK_COUNT = "png.chunk-count-limit"
    const val PNG_CHUNK_TYPE = "png.chunk-type-invalid"
    const val PNG_CRC = "png.crc-mismatch"
    const val PNG_IHDR = "png.ihdr-invalid"
    const val PNG_MISSING_IEND = "png.missing-iend"

    const val PNG_METADATA_MISSING = "png.metadata-missing"
    const val PNG_DUPLICATE_METADATA = "png.duplicate-metadata"
    const val PNG_UNSUPPORTED_TEXT_CHUNK = "png.unsupported-text-chunk"
    const val PNG_BASE64_INVALID = "png.base64-invalid"
    const val PNG_METADATA_TOO_LARGE = "png.metadata-too-large"
}

/**
 * 有界导入限额。全部上限由调用方固定，**不能**被输入 JSON/PNG 提高；
 * 超限一律失败或截断并给出 notice，不静默丢弃。
 *
 * 缺省值与公开契约一致：文件 32 MiB、JSON 8 MiB 字符、单字段 1 MiB 字符、嵌套 32 层。
 */
internal data class CharacterCardLimits(
    val maxFileBytes: Int = CharacterCardCodec.MAX_FILE_BYTES,
    val maxJsonChars: Int = 8 * 1024 * 1024,
    val maxJsonDepth: Int = 32,
    val maxJsonNodes: Int = 400_000,
    val maxObjectKeys: Int = 8_192,
    val maxStringChars: Int = 1 shl 20,
    val maxListItems: Int = 4_096,
    val maxNameChars: Int = 512,
    val maxBookEntries: Int = 5_000,
    val maxPngChunks: Int = 4_096,
    val maxPngTextChars: Int = 12 * 1024 * 1024,
    val maxPngMetadataBytes: Int = 8 * 1024 * 1024,
    val maxNotices: Int = 64,
) {
    /** 世界书解析复用同一组有界限额（entry 上限由 [maxBookEntries] 控制）。 */
    fun toWorldBookLimits(): WorldBookImportLimits = WorldBookImportLimits(
        maxChars = maxJsonChars,
        maxDepth = maxJsonDepth,
        maxNodes = maxJsonNodes,
        maxObjectKeys = maxObjectKeys,
        maxEntries = maxBookEntries,
        maxStringChars = maxStringChars,
        maxNameChars = maxNameChars,
        maxNotices = maxNotices,
    )
}

/** 有界 notice 收集器：超过 [CharacterCardLimits.maxNotices] 后只保留一条汇总。 */
internal class CharacterCardNoticeSink(
    private val limits: CharacterCardLimits,
    initial: List<CharacterCardImportNotice> = emptyList(),
) {
    private val items = ArrayList<CharacterCardImportNotice>()
    private var suppressed = 0

    init {
        initial.forEach { add(it.code, it.message) }
    }

    fun add(code: String, message: String) {
        if (items.size >= limits.maxNotices) {
            suppressed++
            return
        }
        items += CharacterCardImportNotice(code = code, message = message)
    }

    fun build(): List<CharacterCardImportNotice> {
        if (suppressed <= 0) return items.toList()
        return items + CharacterCardImportNotice(
            code = CharacterCardNoticeCodes.NOTICES_TRUNCATED,
            message = "$suppressed further notices suppressed by limit ${limits.maxNotices}.",
        )
    }
}
