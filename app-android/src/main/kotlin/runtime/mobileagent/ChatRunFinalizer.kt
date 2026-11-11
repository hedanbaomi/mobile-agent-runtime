// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.ContextCompactionRepository
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.Utc

/** Single durable terminalization path, preserving uncertainty and settling observed usage once. */
internal class ChatRunFinalizer(
    private val runs: RunRepository,
    private val compactions: ContextCompactionRepository,
) {
    suspend fun finish(
        original: RunRecord,
        resources: ChatRunSession,
        usage: RunCompactionUsage,
        admitted: Boolean,
        settleApproval: suspend (RunRecord) -> Unit,
        checkpoint: suspend (String) -> Unit,
        onUnknown: () -> Unit,
        onRecord: (RunRecord) -> Unit,
    ): RunRecord {
        resources.productivity?.closeChildren()
        var record = if (resources.productivity?.hasUnknownOutcomes == true) {
            onUnknown()
            original.copy(state = RunStatus.UNKNOWN_OUTCOME, errorCode = "UNKNOWN_OUTCOME",
                stopReason = "background-tool-outcome-unknown")
        } else original
        onRecord(record)
        val persisted = withContext(Dispatchers.IO) { runs.get(record.runId) }
        if (persisted?.state == RunStatus.UNKNOWN_OUTCOME) record = record.copy(
            state = RunStatus.UNKNOWN_OUTCOME, errorCode = "UNKNOWN_OUTCOME",
            stopReason = persisted.stopReason ?: record.stopReason, retryAcknowledgedAt = persisted.retryAcknowledgedAt)
        onRecord(record)
        settleApproval(record)
        checkpoint(if (record.state == RunStatus.COMPLETED) "COMPLETE" else record.state.name)
        record = usage.reconcile(record, withContext(Dispatchers.IO) { compactions.list(record.conversationId) })
        onRecord(record)
        record = record.copy(finishedAt = Utc.nowIso(), updatedAt = Utc.nowIso())
        onRecord(record)
        if (admitted) withContext(Dispatchers.IO) { runs.save(record) }
        return record
    }
}
