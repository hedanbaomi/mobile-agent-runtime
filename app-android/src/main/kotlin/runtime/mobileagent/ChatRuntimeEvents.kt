// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.app.Application
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RuntimeEvent
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.agent.toDiffPartOrNull
import runtime.mobileagent.agent.toMessagePartOrNull
import runtime.mobileagent.agent.toSafeErrorPart
import runtime.mobileagent.domain.*
import runtime.mobileagent.diagnostics.DiagnosticApprovalState
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticCompactionState
import runtime.mobileagent.diagnostics.DiagnosticTerminalState
import runtime.mobileagent.diagnostics.DiagnosticToolRunState
import runtime.mobileagent.diagnostics.ContextCompactionStateRecord
import runtime.mobileagent.diagnostics.RunStateRecord
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.tooling.InternalRequestIds
import java.util.concurrent.atomic.AtomicLong

internal interface ChatRunEventState {
    var record: RunRecord
    var round: Int
    var modelInFlight: Boolean
    var approvedToolInFlight: Boolean
    var approvedToolCallId: String?
    var toolCallInFlight: Boolean
    var toolWaitingApproval: Boolean
    var preparationStage: String?
}

/** Serial runtime event checkpointing; UI projection remains owner-gated by the supplied port. */
internal class ChatRuntimeEvents(
    private val application: Application,
    private val container: AppContainer,
    private val ui: ChatExecutionUi,
    private val owner: ChatRunOwner,
    private val resources: ChatRunSession,
    private val run: AgentRun,
    private val snapshot: AgentSnapshot,
    private val toolExecutor: ToolExecutor,
    private val runTools: RunTools,
    private val runCitations: MutableMap<String, Pair<Citation, String>>,
    private val invocations: MutableMap<String, ToolInvocation>,
    private val compactionUsage: RunCompactionUsage,
    private val secret: CharArray?,
    private val warning: String?,
    private val progress: ChatRunEventState,
    private val response: ChatRunResponse,
    private val lastProgressAt: AtomicLong,
    private val invocationId: (String, String) -> String?,
    private val captureRequestPreview: (String?, Iterable<String>) -> Unit,
    private val recordInvocation: (String, String, DiagnosticToolRunState, String, String?) -> Unit,
    private val approvals: ChatRunApprovals,
    private val headerNames: Set<String>,
) : ChatExecutionUi by ui {
    private val conversationId get() = owner.conversationId
    private fun recordToolInvocation(callId: String, name: String, state: DiagnosticToolRunState,
        requestRef: String, errorCode: String? = null) = recordInvocation(callId, name, state, requestRef, errorCode)
    private fun citationMetadata(bound: List<Citation>, warning: String?, coverage: RetrievalCoverage? = null,
        source: Map<String, Pair<Citation, String>>) = chatCitationMetadata(bound, warning, coverage, source)
    suspend fun accept(event: RuntimeEvent) = with(response) { with(progress) {
                        lastProgressAt.set(SystemClock.elapsedRealtime())
                        var persistRun = false
                        when (event) {
                            is RuntimeEvent.RunStarted -> {
                                record = record.copy(state = RunStatus.VALIDATING)
                                persistRun = true
                            }
                            is RuntimeEvent.ContextCompactionChanged -> {
                                record = compactionUsage.reconcile(record, listOf(event.record))
                                runCatching {
                                    (application as MobileAgentApp).diagnostics.recordContextCompactionState(
                                        ContextCompactionStateRecord(
                                            state = DiagnosticCompactionState.entries.firstOrNull {
                                                it.name == event.record.state.name
                                            } ?: DiagnosticCompactionState.FAILED,
                                            requestRef = event.record.id,
                                            sessionRef = conversationId,
                                            reasonCode = if (event.record.state == ContextCompactionState.FAILED)
                                                "summary_" + event.record.reason.substringAfter(':', "failed")
                                                    .removePrefix("summary-").replace(' ', '_').replace('-', '_')
                                                else event.record.state.name.lowercase(),
                                        ),
                                    )
                                }
                                if (event.record.state == ContextCompactionState.PREPARED) {
                                    // Finish the prior assistant checkpoint before releasing its id. A
                                    // summary failure must get its own error row, never rewrite it.
                                    if (assistantId != null) checkpoint("COMPLETE")
                                    assistantId = null; answer = ""; reasoning = ""; observed.clear(); terminalError = null
                                }
                                modelInFlight = event.record.state == ContextCompactionState.DISPATCHED
                                record = record.copy(
                                    state = if (modelInFlight) RunStatus.MODEL_STREAMING else RunStatus.ASSEMBLING,
                                    modelRounds = run.modelRounds,
                                )
                                publishRunState(owner, state.value.copy(
                                    compactions = withContext(Dispatchers.IO) { container.contextCompactions.list(conversationId) }.map(::compactionUi),
                                    status = when (event.record.state) {
                                        ContextCompactionState.PREPARED, ContextCompactionState.DISPATCHED ->
                                            application.hostChatText(R.string.chat_context_compaction_in_progress)
                                        ContextCompactionState.SUCCEEDED -> application.hostChatText(R.string.chat_context_compaction_succeeded)
                                        ContextCompactionState.UNKNOWN_OUTCOME -> application.hostChatText(R.string.chat_context_compaction_unknown)
                                        else -> application.hostChatText(R.string.chat_context_compaction_incomplete)
                                    },
                                ))
                                persistRun = true
                            }
                            is RuntimeEvent.RequestPrepared -> {
                                preparationStage = null
                                modelInFlight = true
                                if (assistantId != null) checkpoint("COMPLETE")
                                observed.clear(); reasoning = ""; terminalError = null
                                answer = if (round++ == 0 && warning != null) "$warning\n\n" else ""
                                assistantId = withContext(Dispatchers.IO) { container.conversations.append(conversationId, MessageRole.ASSISTANT,
                                    answer, status = "STREAMING", metadataJson = metadata,
                                    messageId = event.assistantMessageId ?: EntityId.random().value).id }
                                record = record.copy(state = RunStatus.MODEL_STREAMING, modelRounds = run.modelRounds)
                                captureRequestPreview(event.requestPreview, event.headerNames)
                                refreshMessages(owner)
                                persistRun = true
                            }
                            is RuntimeEvent.ModelEvent -> when (val e = event.event) {
                                is ModelEvent.Completed -> {
                                    modelInFlight = false
                                    flushStreamingAnswer(assistantId, answer, force = true)
                                    persistRun = true
                                }
                                is ModelEvent.TextDelta -> {
                                    appendText(e.text, listOf(String(secret!!)))
                                }
                                is ModelEvent.ReasoningDelta -> {
                                    appendReasoning(e.text, listOf(String(secret!!)))
                                }
                                // A refusal is readable assistant output: it joins the
                                // answer stream like ordinary text, never reasoning.
                                is ModelEvent.RefusalDelta -> {
                                    val projected = e.toMessagePartOrNull() as? RefusalPart
                                    if (projected != null) {
                                        appendText(projected.text, listOf(String(secret!!)))
                                    }
                                }
                                is ModelEvent.Failed -> {
                                    val projected = e.toMessagePartOrNull() as? ErrorPart ?: toSafeErrorPart(e.sanitizedMessage)
                                    val safeMessage = projected.message
                                    answer = if (answer.isBlank()) safeMessage else answer.trimEnd() + "\n" + safeMessage
                                    terminalError = projected
                                    flushStreamingAnswer(assistantId, answer, force = true)
                                    if (assistantId == null) persistTerminalError(projected) else checkpoint("ERROR")
                                    record = record.copy(errorCode = projected.code.name)
                                    publishRunState(owner, state.value.copy(status = safeMessage, statusKind = "error"))
                                    persistRun = true
                                }
                                is ModelEvent.Usage -> {
                                    record = record.copy(inputTokens = record.inputTokens + e.inputTokens, outputTokens = record.outputTokens + e.outputTokens)
                                    persistRun = true
                                }
                                else -> Unit
                            }
                            is RuntimeEvent.ToolCallObserved -> {
                                toolCallInFlight = true
                                recordToolInvocation(event.callId, event.name, DiagnosticToolRunState.DISPATCHED, run.runId)
                                if (runCatching { Json.parseToJsonElement(event.argumentsJson) is JsonObject }.getOrDefault(false))
                                    observed[event.callId] = ToolCallPart(event.callId, event.name, event.argumentsJson)
                            }
                            is RuntimeEvent.ToolApprovalRequested -> {
                                toolWaitingApproval = true
                                approvals.rememberPendingApproval(
                                    ChatRunApprovals.PendingApprovalAudit(
                                        runId = run.runId,
                                        callId = event.callId,
                                        invocationId = invocationId(event.callId, event.name),
                                        agentId = snapshot.agentId,
                                        sessionRef = conversationId,
                                        capability = approvals.diagnosticToolCapability(event.name, toolExecutor.specs),
                                        authority = DiagnosticAuthority.NONE,
                                    ),
                                )
                                recordToolInvocation(
                                    event.callId, event.name, DiagnosticToolRunState.WAITING_APPROVAL, run.runId,
                                )
                                record = record.copy(state = RunStatus.WAITING_TOOL_APPROVAL)
                                // Arguments here are presentation-only. The trusted onApprove
                                // callback persists the original call before any approval decision.
                                persistRun = true
                            }
                            is RuntimeEvent.ToolResultProduced -> {
                                recordToolInvocation(
                                    event.callId,
                                    event.name,
                                    when (event.status) {
                                        "VALUE" -> DiagnosticToolRunState.VALUE
                                        "DENIED" -> DiagnosticToolRunState.DENIED
                                        "COMPLETED_WITHHELD" -> DiagnosticToolRunState.COMPLETED_WITHHELD
                                        "INVALID" -> DiagnosticToolRunState.INVALID
                                        "UNKNOWN_OUTCOME" -> DiagnosticToolRunState.UNKNOWN_OUTCOME
                                        else -> DiagnosticToolRunState.FAILED
                                    },
                                    run.runId,
                                    errorCode = runCatching {
                                        Json.parseToJsonElement(event.resultJson).jsonObject["errorCode"]
                                            ?.jsonPrimitive?.contentOrNull
                                    }.getOrNull(),
                                )
                                val approvalAudit = approvals.pendingApprovalFor(run.runId)?.takeIf { it.callId == event.callId }
                                when (event.status) {
                                    "DENIED" -> approvalAudit?.let {
                                        approvals.recordToolApprovalState(it, DiagnosticApprovalState.INVALIDATED, "permission")
                                    }
                                    "INVALID" -> approvalAudit?.let {
                                        approvals.recordToolApprovalState(it, DiagnosticApprovalState.INVALIDATED, "validation")
                                    }
                                    "UNKNOWN_OUTCOME" -> approvalAudit?.let {
                                        approvals.recordToolApprovalState(it, DiagnosticApprovalState.UNKNOWN, "unknown")
                                    }
                                }
                                toolCallInFlight = false
                                toolWaitingApproval = false
                                approvedToolInFlight = false
                                approvedToolCallId = null
                                runTools.evidence().forEach { (citation, excerpt) -> runCitations[citation.citationId] = citation to excerpt }
                                metadata = citationMetadata(runCitations.values.filter { it.first.runId == run.runId }.map { it.first },
                                    (listOfNotNull(warning) + runTools.warnings()).joinToString("\n").ifBlank { null }, source = runCitations)
                                checkpoint()
                                val runtimeInvocationId = invocationId(event.callId, event.name) ?: InternalRequestIds.new()
                                val persistedDecision = withContext(Dispatchers.IO) {
                                    container.runs.invocations(run.runId)
                                        .firstOrNull { it.invocationId == runtimeInvocationId }?.permissionDecision
                                }
                                val invocation = (invocations[runtimeInvocationId] ?: ToolInvocation(runtimeInvocationId, run.runId,
                                    event.callId, event.name, observed[event.callId]?.argumentsJson ?: "{}", createdAt = Utc.nowIso()))
                                    .copy(state = when (event.status) { "VALUE" -> "SUCCEEDED"; "COMPLETED_WITHHELD" -> "COMPLETED_WITHHELD"; "UNKNOWN_OUTCOME" -> "UNKNOWN_OUTCOME"; else -> "FAILED" },
                                        resultJson = event.resultJson,
                                        permissionDecision = persistedDecision ?: "NOT_REQUESTED",
                                        updatedAt = Utc.nowIso())
                                withContext(Dispatchers.IO) {
                                    container.runs.observeRuntimeInvocation(invocation)
                                    val diffPart = event.toDiffPartOrNull()
                                    container.conversations.append(conversationId, MessageRole.TOOL, event.resultJson,
                                        messageId = event.messageId ?: EntityId.random().value,
                                        metadataJson = buildJsonObject { put("toolName", event.name) }.toString(),
                                        parts = buildList {
                                            add(ToolResultPart(event.callId, event.resultJson, invocation.state))
                                            // A diff is persisted only when the tool returned an
                                            // explicit structured diff envelope; ordinary output or
                                            // a patch argument is never guessed to be a diff.
                                            diffPart?.let(::add)
                                        })
                                }
                                invocations[runtimeInvocationId] = invocation
                                approvals.forgetPendingApproval(run.runId, event.callId)
                                publishRunCitations(owner, runCitations)
                                publishRunState(owner, state.value.copy(pendingTool = null, citations = citationUis(runCitations)))
                                persistRun = true
                            }
                            is RuntimeEvent.VisualBatchStarted -> {
                                if (assistantId != null) checkpoint("COMPLETE")
                                assistantId = null
                                observed.clear(); reasoning = ""
                                modelInFlight = true
                                record = record.copy(state = RunStatus.MODEL_STREAMING, modelRounds = run.modelRounds)
                                event.requestPreview?.let { captureRequestPreview(it, headerNames) }
                                publishRunState(owner, state.value.copy(status = application.hostChatText(R.string.chat_reading_images, event.imageCount)))
                                persistRun = true
                            }
                            is RuntimeEvent.VisualBatchRejected -> {
                                modelInFlight = false
                                publishRunState(owner, state.value.copy(status = application.hostChatText(R.string.chat_reducing_image_evidence)))
                                persistRun = true
                            }
                            is RuntimeEvent.VisualBatchAnalyzed -> {
                                modelInFlight = false
                                withContext(NonCancellable + Dispatchers.IO) {
                                    val batchMetadata = buildJsonObject {
                                        val ids = event.assets.map { it.assetId }.toSet()
                                        val provenance = runCitations.values.map { it.first }.filter { it.assetId in ids }
                                        Json.parseToJsonElement(citationMetadata(provenance, null, source = runCitations)).jsonObject
                                            .forEach { (key, value) -> put(key, value) }
                                        put("toolEvidence", true)
                                        put("visualBatchAnalysis", true)
                                        put("batchId", event.batchId)
                                        putJsonObject("sourceHashes") { event.assets.forEach { asset ->
                                            asset.sha256?.let { put(asset.assetId, it) }
                                        } }
                                    }.toString()
                                    val receipt = buildJsonObject {
                                        put("sourceIds", JsonArray(event.assets.map { JsonPrimitive(it.assetId) }))
                                        put("analysis", event.analysis)
                                    }.toString()
                                    container.conversations.append(conversationId, MessageRole.USER,
                                        "<untrusted-visual-evidence-analysis>\n" + receipt + "\n</untrusted-visual-evidence-analysis>",
                                        parts = event.assets.map { ImagePart(it.assetId, it.mediaType) },
                                        metadataJson = batchMetadata)
                                }
                                refreshMessages(owner)
                                persistRun = true
                            }
                            is RuntimeEvent.ToolImagesAttached -> withContext(Dispatchers.IO) {
                                container.conversations.append(conversationId, MessageRole.USER, "Tool visual evidence: ${event.callId}",
                                    parts = event.assets.map { ImagePart(it.assetId, it.mediaType) }, metadataJson = buildJsonObject {
                                        val ids = event.assets.map { it.assetId }.toSet()
                                        val provenance = runCitations.values.map { it.first }.filter { it.assetId in ids }
                                        Json.parseToJsonElement(citationMetadata(provenance, null, source = runCitations)).jsonObject
                                            .forEach { (key, value) -> put(key, value) }
                                        put("toolEvidence", true)
                                    }.toString(),
                                    messageId = event.messageId ?: EntityId.random().value)
                            }
                            is RuntimeEvent.RunFinished -> {
                                runCatching {
                                    (application as MobileAgentApp).diagnostics.recordRunState(
                                        RunStateRecord(
                                            state = when (event.state.name) {
                                                "COMPLETED" -> DiagnosticTerminalState.SUCCEEDED
                                                "CANCELLED" -> DiagnosticTerminalState.CANCELLED
                                                "BUDGET_EXHAUSTED" -> DiagnosticTerminalState.TIMED_OUT
                                                "UNKNOWN_OUTCOME" -> DiagnosticTerminalState.UNKNOWN
                                                else -> DiagnosticTerminalState.FAILED
                                            },
                                            requestRef = run.runId,
                                            sessionRef = conversationId,
                                            reasonCode = event.state.name.lowercase(),
                                            // Typed classification set by the terminal
                                            // ModelEvent (R2 QA P2): stream truncation,
                                            // malformed provider response and tool
                                            // validation failures are distinguishable
                                            // from the log alone.
                                            failureCode = record.errorCode,
                                            modelRounds = event.modelRounds,
                                            toolCalls = event.toolCalls,
                                        ),
                                    )
                                }
                                approvals.pendingApprovalFor(run.runId)?.let { pending ->
                                    val outcome = when (event.state) {
                                        RunState.BUDGET_EXHAUSTED -> ChatApprovalOutcome.EXPIRED
                                        RunState.CANCELLED -> ChatApprovalOutcome.INVALIDATED
                                        // Rejection finishes the run without producing a tool result.
                                        RunState.FAILED -> ChatApprovalOutcome.DENIED
                                        else -> null
                                    }
                                    if (outcome != null) {
                                        approvals.settleActiveApproval(resources, pending, outcome)
                                        approvals.recordToolApprovalState(pending, outcome.auditState, outcome.reasonCode)
                                        approvals.terminalizePendingApproval(pending.runId, outcome, pending)?.let {
                                            invocations[it.invocationId] = it
                                        }
                                    }
                                }
                                flushStreamingAnswer(assistantId, answer, force = true)
                                record = record.copy(state = RunStatus.valueOf(event.state.name), modelRounds = event.modelRounds,
                                    toolCalls = event.toolCalls, stopReason = event.stopReason)
                                persistRun = true
                            }
                        }
                        if (persistRun) {
                            record = record.copy(updatedAt = Utc.nowIso())
                            withContext(Dispatchers.IO) { container.runs.save(record) }
                        }
    } }
}
