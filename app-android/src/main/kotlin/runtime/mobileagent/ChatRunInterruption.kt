// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*

    /** Completed usage line; the completion status appends run-specific disclosures to it. */
internal fun completedUsageNotice(application: Application, eventRecord: RunRecord) =
        application.hostChatText(R.string.chat_completed_usage, eventRecord.inputTokens, eventRecord.outputTokens)

    /** Interrupted-run copy shared by the collector and the non-cancellable cleanup path. */
internal fun interruptionNotice(application: Application, state: RunStatus): String? = when (state) {
        RunStatus.UNKNOWN_OUTCOME -> application.hostChatText(R.string.chat_cancelled_unknown_outcome)
        RunStatus.CANCELLED -> application.hostChatText(R.string.chat_cancelled_partial)
        else -> null
    }

/** Cancellation preserves confirmed device actions and already-observed terminal facts. */
internal class ChatRunInterruption(
    private val application: Application,
    private val container: AppContainer,
    private val ui: ChatExecutionUi,
    private val owner: ChatRunOwner,
    private val resources: ChatRunSession,
    private val runId: String,
    private val progress: ChatRunEventState,
    private val response: ChatRunResponse,
    private val invocations: MutableMap<String, ToolInvocation>,
    private val approvals: ChatRunApprovals,
) : ChatExecutionUi by ui {
    private val conversationId get() = owner.conversationId
    suspend fun settle() = with(response) { with(progress) {
                withContext(NonCancellable) {
                    approvals.pendingApprovalFor(runId)?.invocationId?.let { id -> resources.confirmedDeviceAction(id) }
                        ?.let { receipt -> withContext(Dispatchers.IO) {
                            runCatching { ConfirmedDeviceActionCancellation.persist(container.db, runId, conversationId, receipt) }.getOrNull()
                        } }?.let { completed ->
                            invocations[completed.invocationId] = completed
                            approvals.forgetPendingApproval(runId, completed.callId)
                            approvedToolInFlight = false; approvedToolCallId = null
                            toolCallInFlight = false; toolWaitingApproval = false
                            publishRunState(owner, state.value.copy(pendingTool = null))
                        }
                    val persisted = withContext(Dispatchers.IO) { container.runs.get(runId) }
                    val cancelledBeforeDispatch = withContext(Dispatchers.IO) {
                        approvedToolCallId?.let { callId -> container.runs.invocations(runId).any {
                            it.callId == callId && it.state == "CANCELLED" && it.errorCode == "CANCELLED_BEFORE_DISPATCH"
                        } } ?: false
                    }
                    approvals.pendingApprovalFor(runId)?.let { pending ->
                        // Cancellation can race the runtime's RunFinished event.  Close an
                        // approval wait here as well, keeping an approved-but-undiscovered tool
                        // UNKNOWN rather than falsely claiming it never reached the executor.
                        val terminal = when {
                            record.state == RunStatus.BUDGET_EXHAUSTED ||
                                persisted?.state == RunStatus.BUDGET_EXHAUSTED ->
                                ChatApprovalOutcome.EXPIRED
                            approvedToolInFlight || approvedToolCallId != null ->
                                ChatApprovalOutcome.UNKNOWN
                            else -> ChatApprovalOutcome.INVALIDATED
                        }
                        approvals.settleInterruptedApproval(resources, pending, terminal)
                    }
                    // A late Cancel cannot erase an already observed terminal event. A child's
                    // durable UNKNOWN still takes precedence if its event did not reach us.
                    when {
                        persisted?.state == RunStatus.UNKNOWN_OUTCOME -> record = persisted
                        record.state in TERMINAL -> Unit
                        persisted?.state in TERMINAL -> record = checkNotNull(persisted)
                        else -> {
                            val unknown = modelInFlight || approvedToolInFlight ||
                                (toolCallInFlight && !toolWaitingApproval && !cancelledBeforeDispatch)
                            record = record.copy(state = if (unknown) RunStatus.UNKNOWN_OUTCOME else RunStatus.CANCELLED,
                                stopReason = if (unknown) "UNKNOWN_OUTCOME: cancelled after a provider or tool may have accepted the request" else "user-cancelled",
                                errorCode = if (unknown) "UNKNOWN_OUTCOME" else null)
                        }
                    }
                    val interruption = interruptionNotice(application, record.state)
                    publishRunState(owner, state.value.copy(status = when (record.state) {
                        RunStatus.COMPLETED -> completedUsageNotice(application, record)
                        else -> interruption ?: record.stopReason ?: state.value.status
                    }))
                    if (assistantId != null && interruption != null) {
                        // The in-memory delta can be newer than the periodic checkpoint. Persist
                        // it with an explicit terminal marker before reload replaces the UI row.
                        terminalError = if (record.state == RunStatus.UNKNOWN_OUTCOME) {
                            ErrorPart(MessageErrorCode.UNKNOWN_OUTCOME,
                                application.hostChatText(R.string.chat_cancelled_partial_unknown_outcome_message))
                        } else {
                            ErrorPart(MessageErrorCode.CANCELLED,
                                application.hostChatText(R.string.chat_cancelled_partial_message))
                        }
                        checkpoint(record.state.name)
                    }
                }
    } }
    private companion object {
        val TERMINAL = setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.BUDGET_EXHAUSTED, RunStatus.UNKNOWN_OUTCOME)
    }
}

internal fun chatRunCompletionNotice(
    application: Application,
    record: RunRecord,
    run: AgentRun,
    answer: String,
    previousStatus: String,
): String = when (record.state) {
                    RunStatus.COMPLETED -> buildString {
                        append(completedUsageNotice(application, record))
                        if (run.compactionRequests > 0) append(
                            application.hostChatText(R.string.chat_compaction_request_count, run.compactionRequests),
                        )
                        // R3 QA P3: a completed run with no visible answer must not read
                        // as a silent non-reply.  UI-only notice; fabricated assistant
                        // text would become durable history and be replayed to the model.
                        emptyCompletedAnswerNotice(
                            answer,
                            application.hostChatText(R.string.chat_empty_completed_answer),
                        )?.let { append(" $it") }
                    }
                    RunStatus.BUDGET_EXHAUSTED -> if (record.stopReason == "time") {
                        application.hostChatText(R.string.chat_runtime_limit_reached)
                    } else {
                        application.hostChatText(R.string.chat_budget_exhausted)
                    }
                    else -> previousStatus
}
