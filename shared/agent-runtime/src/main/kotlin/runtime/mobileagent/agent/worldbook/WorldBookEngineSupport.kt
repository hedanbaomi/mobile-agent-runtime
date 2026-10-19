// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookDecision
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookScanState

/** 单条目裁决结果。 */
internal class Verdict(
    val status: WorldBookDecisionStatus,
    val reason: String,
    val matchedKeys: List<String> = emptyList(),
)

/** 已注入正文的替换结果（正文 + 估算量 + CCv3 hidden_key 值）。 */
internal class SubstitutedContent(
    val text: String,
    val units: Int,
    val hiddenKeys: List<String>,
)

internal const val WORLD_BOOK_MAX_WARNINGS = 256

/**
 * 记录单条目裁决。激活是终态：不会再用后续轮次的 skip 覆盖。
 * 每个条目最多一条裁决（诊断体积与条目数同阶），且**不含正文**。
 */
internal fun recordDecision(
    decisions: LinkedHashMap<String, WorldBookDecision>,
    entry: ScanEntry,
    status: WorldBookDecisionStatus,
    reason: String,
    matchedKeys: List<String>,
    units: Int,
    loop: Int,
    scanState: WorldBookScanState,
) {
    if (decisions[entry.key]?.status == WorldBookDecisionStatus.ACTIVATED) return
    decisions[entry.key] = WorldBookDecision(
        bookId = entry.bookId,
        entryUid = entry.entry.uid,
        title = entry.entry.title,
        status = status,
        reason = reason,
        matchedKeys = matchedKeys,
        estimatedUnits = units,
        loop = loop,
        scanState = scanState,
    )
}

/** 去重、有界的告警收集。 */
internal fun addWarning(warnings: MutableList<String>, message: String) {
    if (message in warnings) return
    if (warnings.size >= WORLD_BOOK_MAX_WARNINGS) {
        if ("warnings.truncated" !in warnings) warnings += "warnings.truncated"
        return
    }
    warnings += message
}
