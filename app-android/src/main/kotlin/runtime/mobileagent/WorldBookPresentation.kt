// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookEvaluation
import runtime.mobileagent.domain.worldbook.WorldBookSummary
import runtime.mobileagent.feature.worldbooks.WorldBookCardUi
import runtime.mobileagent.feature.worldbooks.WorldBookImportItemUi
import runtime.mobileagent.feature.worldbooks.WorldBookImportSkipUi
import runtime.mobileagent.feature.worldbooks.WorldBookImportUi
import runtime.mobileagent.feature.worldbooks.WorldBookMatchUi
import runtime.mobileagent.feature.worldbooks.WorldBookPreviewUi
import runtime.mobileagent.serialization.worldbook.WorldBookBatchPlan
import runtime.mobileagent.serialization.worldbook.WorldBookProbe

internal fun worldBookCardUi(book: WorldBookSummary) = WorldBookCardUi(book.id, book.name, book.entryCount, book.enabled, book.revision, book.description)

private const val IMPORT_NOTICES_PER_ITEM = 50

/** Preview rows in planner order; notices stay bounded so a huge book cannot flood the dialog. */
internal fun worldBookImportUi(plan: WorldBookBatchPlan, staging: WorldBookStaging, chinese: Boolean): WorldBookImportUi {
    val items = plan.rows.map { row ->
        val probe = row.candidate.probe
        val book = probe as? WorldBookProbe.Book
        val notices = book?.notices.orEmpty().map { it.message }
        WorldBookImportItemUi(
            key = row.candidate.key,
            source = row.candidate.sourcePath,
            name = row.targetName.ifBlank { book?.name.orEmpty() },
            entryCount = book?.entryCount ?: 0,
            action = row.action,
            selected = row.candidate.selected,
            selectable = row.selectable,
            conflictWith = row.conflictWith,
            detail = when (probe) {
                is WorldBookProbe.Invalid -> probe.detail
                is WorldBookProbe.NotWorldBook -> probe.detail
                is WorldBookProbe.Book -> ""
            },
            notices = notices.take(IMPORT_NOTICES_PER_ITEM) + if (notices.size > IMPORT_NOTICES_PER_ITEM) {
                listOf(if (chinese) "另有 ${notices.size - IMPORT_NOTICES_PER_ITEM} 条提示未列出" else "${notices.size - IMPORT_NOTICES_PER_ITEM} more notes not listed")
            } else emptyList(),
        )
    }
    val single = items.singleOrNull()?.takeIf { staging.skips.isEmpty() }
    return WorldBookImportUi(
        name = single?.name ?: staging.label,
        entryCount = if (single != null) single.entryCount else plan.rows.filter { it.writes }.sumOf { (it.candidate.probe as WorldBookProbe.Book).entryCount },
        notices = single?.notices.orEmpty(),
        items = items,
        skipped = staging.skips.map { WorldBookImportSkipUi(it.sourcePath, it.reason) },
        policy = plan.policy,
        conflicts = plan.conflicts,
        writeCount = plan.writeCount,
        bytesToWrite = plan.bytesToWrite,
        storedBytes = plan.usage.storedBytes,
        maxStoredBytes = plan.usage.maxStoredBytes,
        overStorage = plan.overStorageLimit,
        overBooks = plan.overBookLimit,
        maxBooks = plan.usage.maxBooks,
        canConfirm = plan.canConfirm,
    )
}

internal fun worldBookPreviewUi(value: WorldBookEvaluation, chinese: Boolean): WorldBookPreviewUi = WorldBookPreviewUi(
    matches = value.decisions.map { decision -> WorldBookMatchUi(decision.title.ifBlank { decision.entryUid },
        when (decision.status) {
            WorldBookDecisionStatus.ACTIVATED -> "included"
            WorldBookDecisionStatus.NEEDS_EXTERNAL -> "warning"
            WorldBookDecisionStatus.DENIED -> "rejected"
            else -> "excluded"
        }, worldBookReason(decision.reason, chinese), decision.estimatedUnits) },
    usedUnits = value.estimatedUnits, budgetUnits = value.budgetUnits, warnings = value.warnings,
)

private fun worldBookReason(reason: String, chinese: Boolean): String {
    if (!chinese) return reason
    return when (reason.uppercase()) {
        "REMOVED" -> "世界书已删除"
        "DISABLED" -> "世界书或条目已停用"
        "CONSTANT" -> "常驻条目"
        "KEYWORD", "PRIMARY_KEY", "KEY_MATCH", "KEYWORD_MATCH" -> "关键词命中"
        "NO_MATCH", "NO_KEY_MATCH", "NO_PRIMARY_MATCH", "KEYS_NOT_MATCHED" -> "未命中关键词"
        "BUDGET", "BUDGET_EXCEEDED", "SOFT_BUDGET", "HARD_BUDGET" -> "预算不足，未加入请求"
        "PROBABILITY", "PROBABILITY_FAILED" -> "本轮未通过概率条件"
        "COOLDOWN" -> "条目仍在冷却期"
        "DELAY" -> "尚未达到延迟消息数"
        "STICKY" -> "条目仍在持续生效期"
        else -> reason
    }
}
