// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.nio.charset.CharacterCodingException
import java.util.Locale
import runtime.mobileagent.data.WorldBookRepository
import runtime.mobileagent.domain.worldbook.StoredWorldBook
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy
import runtime.mobileagent.domain.worldbook.WorldBookHead
import runtime.mobileagent.domain.worldbook.WorldBookStorageUsage
import runtime.mobileagent.domain.worldbook.WorldBookSummary
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.serialization.worldbook.WorldBookBatchCandidate
import runtime.mobileagent.serialization.worldbook.WorldBookBatchPlan
import runtime.mobileagent.serialization.worldbook.WorldBookBatchPlanner
import runtime.mobileagent.serialization.worldbook.WorldBookBatchRow
import runtime.mobileagent.serialization.worldbook.WorldBookCodec
import runtime.mobileagent.serialization.worldbook.WorldBookProbe

/**
 * One staged batch import between "choose files" and "confirm". It owns the staged bytes and the
 * repository facts the plan was computed against; the ViewModel only threads UI state through it.
 * Both [prepare] and [write] block and must run off the main thread.
 */
internal class WorldBookImportSession private constructor(
    val staging: WorldBookStaging,
    private var candidates: List<WorldBookBatchCandidate>,
    private val heads: List<WorldBookHead>,
    private val usage: WorldBookStorageUsage,
) {
    var plan: WorldBookBatchPlan = WorldBookBatchPlanner.plan(candidates, heads, usage, WorldBookConflictPolicy.KEEP_BOTH)
        private set

    fun replan(policy: WorldBookConflictPolicy) {
        plan = WorldBookBatchPlanner.plan(candidates, heads, usage, policy)
    }

    /** @return false when [key] is unknown or cannot be selected. */
    fun toggle(key: String): Boolean {
        plan.rows.firstOrNull { it.candidate.key == key }?.takeIf { it.selectable } ?: return false
        candidates = candidates.map { if (it.key == key) it.copy(selected = !it.selected) else it }
        replan(plan.policy)
        return true
    }

    fun discard() = staging.discard()

    /** Cleanup belongs to the IO operation, even if its caller is cancelled before resumption. */
    fun writeAndDiscard(repository: WorldBookRepository, chinese: Boolean, stop: () -> Boolean,
        progress: (String) -> Unit): WorldBookImportOutcome = try {
        write(repository, chinese, stop, progress)
    } finally {
        discard()
    }

    /**
     * Writes each selected book in its own repository transaction, so a long batch never holds the
     * database writer for long. A failure is recorded per book and later books still run; [stop] is
     * polled between books, so the book being written always finishes.
     */
    fun write(repository: WorldBookRepository, chinese: Boolean, stop: () -> Boolean, progress: (String) -> Unit): WorldBookImportOutcome {
        val rows = plan.rows.filter { it.writes }
        val files = staging.files.associateBy { it.key }
        val outcome = WorldBookImportOutcome(selected = rows.size, notWritten = plan.rows.size - rows.size)
        for ((index, row) in rows.withIndex()) {
            if (stop()) { outcome.stopped = true; break }
            progress(if (chinese) "正在导入 ${index + 1}/${rows.size}：${row.targetName}" else "Importing ${index + 1}/${rows.size}: ${row.targetName}")
            try {
                val stored = writeRow(repository, row, checkNotNull(files[row.candidate.key]) { "The import preview expired; choose the files again" })
                if (stored == null) { outcome.unchanged += row; continue }
                outcome.stored += row to stored.summary
                // Only a one-file import reopens the editor, so only it keeps a full document.
                if (plan.rows.size == 1) outcome.single = stored
            } catch (error: Exception) {
                outcome.failed += row to SecretRedactor.redact(error.message ?: error::class.simpleName.orEmpty()).take(200)
            }
        }
        return outcome
    }

    /** @return null when a replacement would store exactly the current revision again. */
    private fun writeRow(repository: WorldBookRepository, row: WorldBookBatchRow, file: WorldBookStagedFile): StoredWorldBook? {
        val decoded = WorldBookCodec.decode(WorldBookFileIO.readStaged(file.file, file.sha256), file.fallbackName).document
        val document = decoded.copy(name = row.targetName)
        if (row.action != WorldBookBatchAction.REPLACE) return repository.save(document)
        return repository.replaceIfChanged(document, checkNotNull(row.targetId), row.targetRevision, row.targetEnabled)
    }

    companion object {
        fun prepare(
            staging: WorldBookStaging,
            repository: WorldBookRepository,
            chinese: Boolean,
            checkpoint: () -> Unit,
            progress: (String) -> Unit,
        ): WorldBookImportSession = try {
            val heads = repository.heads()
            val total = staging.files.size
            val candidates = staging.files.mapIndexed { index, file ->
                checkpoint()
                progress(if (chinese) "正在解析 ${index + 1}/$total：${file.sourcePath}" else "Reading ${index + 1}/$total: ${file.sourcePath}")
                val probe = probe(file, heads)
                // A card's embedded book is rarely the point of a multi-file import; leave it opt-in.
                val embedded = probe is WorldBookProbe.Book && probe.fromCharacterCard && total > 1
                WorldBookBatchCandidate(file.key, file.sourcePath, probe, selected = probe is WorldBookProbe.Book && !embedded)
            }.sortedBy { it.sourcePath.lowercase(Locale.ROOT) }
            reject(candidates, staging, chinese)
            WorldBookImportSession(staging, candidates, heads, repository.storageUsage())
        } catch (error: Throwable) {
            staging.discard()
            throw error
        }

        private fun probe(file: WorldBookStagedFile, heads: List<WorldBookHead>): WorldBookProbe {
            val text = try {
                WorldBookFileIO.readStaged(file.file, file.sha256)
            } catch (_: CharacterCodingException) {
                return WorldBookProbe.Invalid("The file is not UTF-8 text")
            }
            return WorldBookBatchPlanner.probe(text, file.fallbackName, existing = heads)
        }

        /** Nothing to preview, or one unreadable file: report it directly like the single-file import did. */
        private fun reject(candidates: List<WorldBookBatchCandidate>, staging: WorldBookStaging, chinese: Boolean) {
            if (staging.skips.isNotEmpty()) return
            val message = when (val probe = candidates.singleOrNull()?.probe) {
                null -> if (candidates.isEmpty()) {
                    if (chinese) "所选位置没有可读取的世界书文件。" else "No world book files were found in the selection."
                } else null
                is WorldBookProbe.Invalid -> (if (chinese) "无法导入：" else "Cannot import: ") + probe.detail
                is WorldBookProbe.NotWorldBook -> (if (chinese) "不是世界书文件：" else "Not a world book file: ") + probe.detail
                is WorldBookProbe.Book -> null
            }
            if (message != null) throw WorldBookBatchException(message)
        }

        /** One file identical to a stored book: say so instead of showing a preview that cannot be confirmed. */
        fun identicalMessage(session: WorldBookImportSession, chinese: Boolean): String? {
            val row = session.plan.rows.singleOrNull()?.takeIf { it.action == WorldBookBatchAction.SKIP_IDENTICAL } ?: return null
            if (session.staging.skips.isNotEmpty()) return null
            val name = row.conflictWith.orEmpty()
            return if (chinese) "「$name」已存在且内容相同，无需再次导入。" else "“$name” already exists with the same content; nothing to import."
        }
    }
}

internal class WorldBookImportOutcome(val selected: Int, val notWritten: Int) {
    val stored = ArrayList<Pair<WorldBookBatchRow, WorldBookSummary>>()
    val failed = ArrayList<Pair<WorldBookBatchRow, String>>()
    /** Replacements whose content equals the current revision; nothing was written for them. */
    val unchanged = ArrayList<WorldBookBatchRow>()
    /** The stored book of a one-file import, which reopens in the editor like before. */
    var single: StoredWorldBook? = null
    var stopped = false

    val replaced: Int get() = stored.count { it.first.action == WorldBookBatchAction.REPLACE }

    fun summary(chinese: Boolean): String {
        val created = stored.size - replaced
        val remaining = selected - stored.size - failed.size - unchanged.size
        return if (chinese) buildString {
            append("导入完成：新建 $created 本，覆盖 $replaced 本")
            if (unchanged.isNotEmpty()) append("，${unchanged.size} 本内容未变无需覆盖")
            if (failed.isNotEmpty()) append("，失败 ${failed.size} 本")
            if (stopped) append("；已按要求停止，剩余 $remaining 本未导入")
            if (notWritten > 0) append("；另有 $notWritten 个文件按预览未导入")
            append("。在 Agent 或对话中选择后才会发送。")
        } else buildString {
            append("Import finished: $created new, $replaced replaced")
            if (unchanged.isNotEmpty()) append(", ${unchanged.size} already up to date")
            if (failed.isNotEmpty()) append(", ${failed.size} failed")
            if (stopped) append("; stopped as requested, $remaining not imported")
            if (notWritten > 0) append("; $notWritten other files were not imported, as previewed")
            append(". Select a book in an Agent or chat before it is sent.")
        }
    }

    fun failures(): String? = failed.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { (row, reason) -> "${row.candidate.sourcePath}: $reason" }?.take(600)
}
