// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookFormat
import runtime.mobileagent.domain.worldbook.WorldBookHead
import runtime.mobileagent.domain.worldbook.WorldBookStorageUsage

/** 一个已解析候选 JSON 的轻量摘要；正文在探测后丢弃，确认时从暂存文件重新解码。 */
sealed interface WorldBookProbe {
    data class Book(
        /** 规范化后的书名（空名回退、超长截断）；冲突改名在规划阶段决定。 */
        val name: String,
        /** [name] 与文件内书名不同。 */
        val normalizedName: Boolean,
        val entryCount: Int,
        val format: WorldBookFormat,
        /** 以 [name] 编码的原生 JSON 字节数与 SHA-256，与仓储保存时的值一致。 */
        val nativeBytes: Long,
        val nativeHash: String,
        val notices: List<WorldBookImportNotice>,
        /** 来自完整角色卡的内嵌书；多文件批次中默认不勾选。 */
        val fromCharacterCard: Boolean,
        /** Exact hash under the unique existing target's spelling, computed while the document is in memory. */
        val replacementName: String? = null,
        val replacementHash: String? = null,
    ) : WorldBookProbe

    /** 合法 JSON，但没有声明世界书条目（报告、配置、无内嵌书的角色卡等）。 */
    data class NotWorldBook(val detail: String) : WorldBookProbe

    data class Invalid(val detail: String) : WorldBookProbe
}

/** 规划输入：一个候选 JSON。[key] 在批次内唯一，[sourcePath] 是展示用的相对路径。 */
data class WorldBookBatchCandidate(
    val key: String,
    val sourcePath: String,
    val probe: WorldBookProbe,
    val selected: Boolean,
)

data class WorldBookBatchRow(
    val candidate: WorldBookBatchCandidate,
    val action: WorldBookBatchAction,
    /** 写入时使用的书名；替换时为被替换书的现名。 */
    val targetName: String,
    val targetId: String? = null,
    val targetRevision: Int = 0,
    /** 被替换书当前的启停状态；覆盖时沿用，避免意外撤销或恢复运行。 */
    val targetEnabled: Boolean = true,
    /** 被替换书当前修订的 SHA-256；写入前若与新内容相同则不再写新修订。 */
    val targetHash: String? = null,
    /** 与之冲突的现有书或同批文件名，用于展示。 */
    val conflictWith: String? = null,
    val estimatedBytes: Long = 0,
) {
    val selectable: Boolean get() = candidate.probe is WorldBookProbe.Book &&
        action != WorldBookBatchAction.SKIP_IDENTICAL && action != WorldBookBatchAction.TOO_LARGE
    val writes: Boolean get() = candidate.selected && action.writes
}

data class WorldBookBatchPlan(
    val rows: List<WorldBookBatchRow>,
    val policy: WorldBookConflictPolicy,
    /** 已勾选候选中与现有书或同批文件同名的数量；为 0 时界面不必显示冲突选项。 */
    val conflicts: Int,
    val writeCount: Int,
    val newBooks: Int,
    val bytesToWrite: Long,
    val usage: WorldBookStorageUsage,
) {
    val overBookLimit: Boolean get() = usage.books + newBooks > usage.maxBooks
    val overStorageLimit: Boolean get() = usage.storedBytes + bytesToWrite > usage.maxStoredBytes
    val canConfirm: Boolean get() = writeCount > 0 && !overBookLimit && !overStorageLimit
}

object WorldBookBatchPlanner {
    const val DEFAULT_NAME = "Imported world book"
    private const val BOM = "\uFEFF"

    /** 解析一个候选文本。异常只转为结论，不向调用方抛出导入数据造成的错误。 */
    fun probe(text: String, fallbackName: String, limits: WorldBookImportLimits = WorldBookImportLimits(),
        existing: List<WorldBookHead> = emptyList()): WorldBookProbe {
        val result = try {
            WorldBookCodec.decode(text, fallbackName.ifBlank { DEFAULT_NAME }, limits)
        } catch (error: WorldBookImportException) {
            // The codec reuses this code for a malformed `entries` container inside an object root;
            // only a non-object root (array, string, number) means "some other JSON".
            val otherJson = error.code == WorldBookNoticeCodes.JSON_ROOT_NOT_OBJECT && !text.removePrefix(BOM).trimStart().startsWith('{')
            return if (otherJson) WorldBookProbe.NotWorldBook(detail(error)) else WorldBookProbe.Invalid(detail(error))
        }
        val document = result.document
        val codes = result.notices.map { it.code }.toSet()
        if (WorldBookNoticeCodes.FORMAT_EMPTY_ENTRIES in codes) {
            return WorldBookProbe.NotWorldBook("No world book entries were declared")
        }
        if (WorldBookNoticeCodes.FORMAT_CHARACTER_BOOK_MISSING in codes) {
            return WorldBookProbe.NotWorldBook("Character card has no embedded world book")
        }
        if (document.description.length > WorldBookDocument.MAX_DESCRIPTION_CHARS) {
            return WorldBookProbe.Invalid("Description exceeds ${WorldBookDocument.MAX_DESCRIPTION_CHARS} characters")
        }
        val name = normalizedName(document.name)
        val nativeText = WorldBookCodec.encodeNative(if (name == document.name) document else document.copy(name = name))
        // The repository validates every save through the same bounded decoder; checking it here
        // keeps the preview from promising a book that would only fail after confirmation.
        val storable = try {
            WorldBookCodec.decode(nativeText, name, limits)
            null
        } catch (error: WorldBookImportException) {
            detail(error)
        }
        if (storable != null) return WorldBookProbe.Invalid("Too large to store after conversion: $storable")
        val native = nativeText.toByteArray(Charsets.UTF_8)
        val targetName = existing.filter { key(it.name) == key(name) }.singleOrNull()?.name
        val replacementHash = targetName?.let {
            if (it == name) sha256(native)
            else sha256(WorldBookCodec.encodeNative(document.copy(name = it)).toByteArray(Charsets.UTF_8))
        }
        return WorldBookProbe.Book(
            name = name,
            normalizedName = name != document.name,
            entryCount = document.entries.size,
            format = document.format,
            nativeBytes = native.size.toLong(),
            nativeHash = sha256(native),
            notices = result.notices,
            fromCharacterCard = document.format == WorldBookFormat.CHARACTER_CARD_V2 ||
                document.format == WorldBookFormat.CHARACTER_CARD_V3,
            replacementName = targetName,
            replacementHash = replacementHash,
        )
    }

    /**
     * 按 [policy] 规划整批写入。按 [candidates] 顺序分配名称，因此结果确定；未勾选的候选
     * 仍给出结论供展示，但不占用名称、不计入容量。
     */
    fun plan(
        candidates: List<WorldBookBatchCandidate>,
        existing: List<WorldBookHead>,
        usage: WorldBookStorageUsage,
        policy: WorldBookConflictPolicy,
    ): WorldBookBatchPlan {
        val byName = existing.groupBy { key(it.name) }
        val claimed = HashMap<String, String>()
        val hashes = HashSet<String>()
        var conflicts = 0
        val rows = candidates.map { candidate ->
            val probe = candidate.probe
            if (probe !is WorldBookProbe.Book) {
                val action = if (probe is WorldBookProbe.Invalid) WorldBookBatchAction.INVALID else WorldBookBatchAction.NOT_WORLD_BOOK
                return@map WorldBookBatchRow(candidate, action, "")
            }
            val row = resolve(candidate, probe, byName, claimed, hashes, policy, usage)
            if (!candidate.selected) return@map row
            if (row.conflictWith != null && row.action != WorldBookBatchAction.SKIP_IDENTICAL) conflicts += 1
            if (row.action.writes) {
                hashes += probe.nativeHash
                claimed.putIfAbsent(key(row.targetName), candidate.sourcePath)
            }
            row
        }
        val writes = rows.filter { it.writes }
        return WorldBookBatchPlan(
            rows = rows,
            policy = policy,
            conflicts = conflicts,
            writeCount = writes.size,
            newBooks = writes.count { it.action != WorldBookBatchAction.REPLACE },
            bytesToWrite = writes.sumOf { it.estimatedBytes },
            usage = usage,
        )
    }

    private fun resolve(
        candidate: WorldBookBatchCandidate,
        probe: WorldBookProbe.Book,
        byName: Map<String, List<WorldBookHead>>,
        claimed: Map<String, String>,
        hashes: Set<String>,
        policy: WorldBookConflictPolicy,
        usage: WorldBookStorageUsage,
    ): WorldBookBatchRow {
        fun row(action: WorldBookBatchAction, name: String = probe.name, target: WorldBookHead? = null, conflict: String? = null): WorldBookBatchRow {
            val bytes = if (action.writes) estimate(probe, name) else 0L
            val boundedAction = if (bytes > usage.maxRevisionBytes) WorldBookBatchAction.TOO_LARGE else action
            return WorldBookBatchRow(candidate, boundedAction, name, target?.id, target?.revision ?: 0, target?.enabled ?: true,
                target?.contentHash, conflict, if (boundedAction.writes) bytes else 0L)
        }

        if (probe.nativeHash in hashes) return row(WorldBookBatchAction.SKIP_DUPLICATE_IN_BATCH)
        val sameName = byName[key(probe.name)].orEmpty()
        sameName.firstOrNull { it.name == probe.name && it.contentHash == probe.nativeHash }?.let {
            return row(WorldBookBatchAction.SKIP_IDENTICAL, target = it, conflict = it.name)
        }
        val batchClaim = claimed[key(probe.name)]
        if (sameName.isEmpty() && batchClaim == null) {
            return row(if (probe.normalizedName) WorldBookBatchAction.CREATE_RENAMED else WorldBookBatchAction.CREATE)
        }
        val conflict = sameName.firstOrNull()?.name ?: batchClaim
        return when (policy) {
            WorldBookConflictPolicy.KEEP_BOTH ->
                row(WorldBookBatchAction.CREATE_RENAMED, uniqueName(probe.name, byName, claimed), conflict = conflict)
            WorldBookConflictPolicy.SKIP ->
                row(if (batchClaim != null && sameName.isEmpty()) WorldBookBatchAction.SKIP_DUPLICATE_IN_BATCH
                    else WorldBookBatchAction.SKIP_EXISTING, conflict = conflict)
            WorldBookConflictPolicy.REPLACE -> when {
                // A second file must not overwrite a book this batch already writes.
                batchClaim != null -> row(WorldBookBatchAction.SKIP_DUPLICATE_IN_BATCH, conflict = batchClaim)
                sameName.size == 1 -> sameName.single().let {
                    val identical = it.name == probe.replacementName && it.contentHash == probe.replacementHash
                    row(if (identical) WorldBookBatchAction.SKIP_IDENTICAL else WorldBookBatchAction.REPLACE, it.name, it, it.name)
                }
                else -> row(WorldBookBatchAction.AMBIGUOUS_TARGET, conflict = conflict)
            }
        }
    }

    /** 名称比较忽略首尾空白、Unicode 组合形式和大小写。 */
    fun key(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun normalizedName(name: String): String {
        val base = name.ifBlank { DEFAULT_NAME }
        return if (base.length <= WorldBookDocument.MAX_NAME_CHARS) base else safeTake(base, WorldBookDocument.MAX_NAME_CHARS)
    }

    /** Truncates without leaving half of a surrogate pair at the end. */
    private fun safeTake(text: String, count: Int): String {
        val cut = text.take(count)
        return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    private fun uniqueName(name: String, byName: Map<String, List<WorldBookHead>>, claimed: Map<String, String>): String {
        var index = 2
        while (true) {
            val suffix = " ($index)"
            val candidate = safeTake(name, WorldBookDocument.MAX_NAME_CHARS - suffix.length).trimEnd() + suffix
            if (byName[key(candidate)].isNullOrEmpty() && key(candidate) !in claimed) return candidate
            index += 1
        }
    }

    private fun estimate(probe: WorldBookProbe.Book, name: String): Long {
        // The name appears once in the native envelope; compare the encoded JSON strings so quotes,
        // backslashes and control characters are counted exactly as the repository will store them.
        val delta = encodedLength(name) - encodedLength(probe.name)
        return (probe.nativeBytes + delta).coerceAtLeast(0)
    }

    private fun encodedLength(text: String): Int = JsonPrimitive(text).toString().toByteArray(Charsets.UTF_8).size

    private fun detail(error: WorldBookImportException): String =
        (error.message ?: error.code).removePrefix("[worldbook-import:${error.code}] ").take(300)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
