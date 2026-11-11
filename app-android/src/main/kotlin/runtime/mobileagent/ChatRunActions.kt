// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.diagnostics.DiagnosticApprovalState
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*

internal fun ChatExecutionController.approveTool(approved: Boolean) {
        val resources = activeRunResources ?: return
        if (!canProjectRun(resources.owner)) return
        val pending = state.value.pendingTool
        val deferred = resources.approval
        if (deferred != null && pending != null && resources.approvalCallId == pending.id) {
            val audit = approvals.pendingApprovalFor(resources.owner.runId)?.takeIf { it.callId == pending.id }
            resources.approval = null
            resources.approvalCallId = null
            if (approved) {
                audit?.let {
                    approvals.markPendingApprovalApproved(it)
                    approvals.recordToolApprovalState(it, DiagnosticApprovalState.APPROVED, "permission")
                }
                deferred.complete(true)
            } else if (audit != null) {
                // Complete the durable eventRecord before the runtime resumes from the deferred.  A
                // rejection normally produces RunFinished(FAILED), but the immediate write also
                // covers cancellation and event-delivery races.  The explicit executor rejection
                // clears its one-shot approval state using the runtime invocation id.
                approvals.closeApprovalFromUi(audit, ChatApprovalOutcome.DENIED)
                val executor = resources.executor
                val runtimeId = audit.invocationId ?: audit.callId
                viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                    runCatching { executor?.reject(runtimeId) }
                    deferred.complete(false)
                }
            } else {
                deferred.complete(false)
            }
            state.value = state.value.copy(pendingTool = null)
        }
    }
internal fun ChatExecutionController.cancel() {
        val resources = activeRunResources
        if (resources == null) {
            preflightJob?.cancel()
            state.value = state.value.copy(streaming = false, loading = false)
            return
        }
        val callId = resources.approvalCallId
        val deferred = resources.approval
        val executor = resources.executor
        var explicitRejectScheduled = false
        callId?.let {
            approvals.pendingApprovalFor(resources.owner.runId)?.takeIf { audit -> audit.callId == it }?.let { audit ->
                // Cancellation is terminal for an approval wait. Persist it before cancelling
                // the collector so reload cannot present or replay the same request.
                approvals.closeApprovalFromUi(audit, ChatApprovalOutcome.INVALIDATED)
                val runtimeId = audit.invocationId ?: audit.callId
                explicitRejectScheduled = true
                viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                    runCatching { executor?.reject(runtimeId) }
                    deferred?.complete(false)
                }
            }
        }
        if (deferred != null && !explicitRejectScheduled) deferred.complete(false)
        resources.approval = null
        resources.approvalCallId = null
        val preparing = preflightJob?.isActive == true && runJob?.isActive != true
        preflightJob?.cancel()
        resources.job?.cancel()
        if (preparing) publishRunState(resources.owner, state.value.copy(streaming = false))
    }
