// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.worldbook

/*
 * 世界书批量导入（多文件 / 文件夹 / ZIP）的纯数据契约。
 *
 * 规划在 serialization 模块（WorldBookBatchPlanner），文件枚举与暂存在 Host，展示在 feature 模块；
 * 三处共用这些枚举，避免各自维护一份互相映射的状态集合。
 */

/** 与已有世界书同名时的处理方式。默认保留两者，与单文件导入的历史行为一致。 */
enum class WorldBookConflictPolicy {
    /** 新建，并以「名称 (2)」等后缀区分。 */
    KEEP_BOTH,

    /** 覆盖唯一的同名书：保留其 ID、启停状态和 Agent/对话绑定，写入新修订。 */
    REPLACE,

    /** 不导入同名文件。 */
    SKIP,
}

/** 预览中每个候选 JSON 的结论。只有 [writes] 为真的动作会在确认后落库。 */
enum class WorldBookBatchAction(val writes: Boolean) {
    CREATE(true),
    CREATE_RENAMED(true),
    REPLACE(true),
    SKIP_EXISTING(false),
    SKIP_IDENTICAL(false),
    SKIP_DUPLICATE_IN_BATCH(false),
    AMBIGUOUS_TARGET(false),
    NOT_WORLD_BOOK(false),
    INVALID(false),
    TOO_LARGE(false),
}

/** 未成为候选的文件（没有解析成世界书 JSON 的原因）。 */
enum class WorldBookFileSkip {
    /** 不是 JSON/ZIP（文本、脚本、说明文档等）。 */
    UNSUPPORTED_TYPE,

    /** 图片；PNG 角色卡请使用 Agent 列表的角色卡导入。 */
    IMAGE,

    /** 压缩包内的压缩包只展开一层。 */
    NESTED_ARCHIVE,

    /** 不是普通 ZIP（7z、RAR、分卷、ZIP64）或压缩包结构损坏。 */
    UNSUPPORTED_ARCHIVE,
    ENCRYPTED,
    UNSUPPORTED_COMPRESSION,
    LINK,
    UNSAFE_PATH,
    DUPLICATE_PATH,
    TOO_LARGE,
    CORRUPT,
    UNREADABLE,
}

/** 现有世界书的当前头版本，供冲突与重复检测使用；不含正文。 */
data class WorldBookHead(
    val id: String,
    val name: String,
    val revision: Int,
    val enabled: Boolean,
    /** 当前修订原生 JSON 的 SHA-256，与仓储 `content_hash` 一致。 */
    val contentHash: String,
)

/** 仓储容量现状与固定上限，用于确认前的预检；实际保存仍逐本执行同样的检查。 */
data class WorldBookStorageUsage(
    val books: Int,
    val storedBytes: Long,
    val maxBooks: Int,
    val maxStoredBytes: Long,
    val maxRevisionBytes: Long,
)
