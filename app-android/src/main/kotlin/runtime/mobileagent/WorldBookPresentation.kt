// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookEvaluation
import runtime.mobileagent.domain.worldbook.WorldBookSummary
import runtime.mobileagent.feature.worldbooks.WorldBookCardUi
import runtime.mobileagent.feature.worldbooks.WorldBookMatchUi
import runtime.mobileagent.feature.worldbooks.WorldBookPreviewUi

internal fun worldBookCardUi(book: WorldBookSummary) = WorldBookCardUi(book.id, book.name, book.entryCount, book.enabled, book.revision, book.description)

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
