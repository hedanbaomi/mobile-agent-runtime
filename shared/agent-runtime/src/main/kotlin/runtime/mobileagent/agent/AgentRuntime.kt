// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.ContextCompactionState
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ProviderContinuationItem
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.skills.ToolBroker
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.asToolExecutor
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolOutcome

/**
 * Executes one agent run while keeping model, tool, cancellation and budget
 * boundaries explicit.  The old [ToolBroker] constructor parameter remains a
 * compatibility bridge; new callers should provide a [ToolExecutor].
 */
class AgentRuntime(
    private val adapter: ModelAdapter,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tools: ToolBroker? = null,
    private val secretsForRedaction: () -> List<String> = { emptyList() },
    private val onApprove: suspend (ToolCall) -> Boolean = { false },
    private val executor: ToolExecutor? = null,
) {
    /**
     * Source-compatible stream containing the model-facing events that the
     * original runtime emitted.  Structured lifecycle events are available via
     * [run] with [AgentRuntimeRequest].
     */
    fun run(
        run: AgentRun,
        prompt: EffectivePrompt,
        modelId: String,
        secret: CharArray,
        toolsEnabled: Boolean,
    ): Flow<ModelEvent> = flow {
        run(
            AgentRuntimeRequest(
                run = run,
                prompt = prompt,
                modelId = modelId,
                secret = secret,
                toolsEnabled = toolsEnabled,
            ),
        ).collect { event ->
            // ToolCallObserved is deliberately structured-only.  The old API
            // did not expose partial tool calls to its consumers.
            if (event is RuntimeEvent.ModelEvent) emit(event.event)
        }
    }

    /** Structured runtime stream for persistence and Inspector consumers. */
    fun run(request: AgentRuntimeRequest): Flow<RuntimeEvent> = flow {
        val run = request.run
        val secret = request.secret
        val toolExecutor = request.executor ?: executor ?: tools?.asToolExecutor()
        var finishedEmitted = false
        var activeDispatch: DispatchKind? = null
        var activeCompaction: ContextCompactionRecord? = null

        suspend fun saveCompaction(record: ContextCompactionRecord): ContextCompactionRecord {
            val saved = request.context?.persist?.invoke(record) ?: record
            activeCompaction = saved.takeIf { it.state == ContextCompactionState.PREPARED || it.state == ContextCompactionState.DISPATCHED }
            emit(RuntimeEvent.ContextCompactionChanged(saved))
            return saved
        }

        suspend fun settleInterruptedCompaction(cancelled: Boolean) {
            val record = activeCompaction ?: return
            val state = when {
                activeDispatch != null -> ContextCompactionState.UNKNOWN_OUTCOME
                cancelled && record.state == ContextCompactionState.PREPARED -> ContextCompactionState.CANCELLED
                else -> ContextCompactionState.FAILED
            }
            // Durable recovery remains authoritative if the process dies during this write.
            withContext(NonCancellable) {
                request.context?.persist?.invoke(record.copy(state = state))
            }
            activeCompaction = null
        }

        suspend fun emitModel(event: ModelEvent) {
            emit(RuntimeEvent.ModelEvent(event))
        }

        suspend fun finish() {
            if (finishedEmitted) return
            finishedEmitted = true
            emit(
                RuntimeEvent.RunFinished(
                    runId = run.runId,
                    state = run.state,
                    stopReason = run.stopReason,
                    modelRounds = run.modelRounds,
                    toolCalls = run.toolCalls,
                    compactionRequests = run.compactionRequests,
                ),
            )
        }

        suspend fun emitBudget() {
            run.state = RunState.BUDGET_EXHAUSTED
            run.stopReason = "time"
            emitModel(ModelEvent.Failed("Run budget exhausted"))
            finish()
        }

        suspend fun emitUnknownModel() {
            activeDispatch = null
            run.state = RunState.UNKNOWN_OUTCOME
            run.stopReason = UNKNOWN_MODEL_OUTCOME
            emitModel(ModelEvent.Failed(UNKNOWN_MODEL_OUTCOME))
            finish()
        }

        suspend fun emitUnknownTool(call: ToolCall) {
            activeDispatch = null
            run.state = RunState.UNKNOWN_OUTCOME
            run.stopReason = UNKNOWN_TOOL_OUTCOME
            emit(
                RuntimeEvent.ToolResultProduced(
                    callId = call.callId,
                    name = call.name,
                    status = "UNKNOWN_OUTCOME",
                    resultSummary = "UNKNOWN_OUTCOME",
                    resultJson = UNKNOWN_TOOL_ENVELOPE,
                ),
            )
            emitModel(ModelEvent.Failed(UNKNOWN_TOOL_OUTCOME))
            finish()
        }

        try {
            run.startedAtMs = clock()
            run.state = RunState.VALIDATING
            emit(RuntimeEvent.RunStarted(run.runId, run.snapshotId, run.conversationId))

            if (request.modelId.isBlank()) {
                run.state = RunState.FAILED
                emitModel(
                    ModelEvent.Failed(
                        AppError(
                            code = ErrorCode.INVALID_CONFIG,
                            userMessage = "Chat model is not configured",
                            retryClass = RetryClass.USER_ACTION,
                            stage = "validating",
                            operationId = request.operationId,
                        ).userMessage,
                    ),
                )
                finish()
                return@flow
            }

            run.state = RunState.ASSEMBLING
            val window = ContextWindow(request.prompt, request.context)
            var segmentRounds = 0
            val toolSpecs = if (request.toolsEnabled && toolExecutor != null) {
                toolExecutor.specs.toList()
            } else {
                emptyList()
            }
            val invalidToolSpec = when {
                toolSpecs.map { it.name }.toSet().size != toolSpecs.size -> "Tool specifications contain duplicate names"
                else -> toolSpecs.firstNotNullOfOrNull { spec -> validateToolSpec(spec) }
            }
            if (invalidToolSpec != null) {
                run.state = RunState.FAILED
                emitModel(ModelEvent.Failed(invalidToolSpec))
                finish()
                return@flow
            }
            val toolMaps = if (request.toolsEnabled && toolExecutor != null) {
                toolSpecs.map { spec ->
                    mapOf(
                        "name" to spec.name,
                        "description" to spec.description,
                        "parameters" to spec.parametersJson,
                    )
                }
            } else {
                emptyList()
            }

            while (true) {
                if (budgetExhausted(run)) {
                    emitBudget()
                    return@flow
                }
                var modelRequest = ModelRequest(
                    modelId = request.modelId,
                    messages = window.messages(),
                    tools = toolMaps,
                    stream = true,
                    parameters = request.parameters,
                    headers = request.headers,
                    operationId = request.operationId,
                    outputTokenLimit = request.outputTokenLimit,
                    outputTokenField = request.outputTokenField,
                )
                val inputLimit = request.maxInputBudgetUnits
                val context = request.context?.takeIf { it.policy.autoCompact && inputLimit != null }
                if (context != null) {
                    val minimum = adapter.estimateInput(window.minimumRequest(modelRequest))
                    if (minimum.units > inputLimit!! || minimum.imageCount > request.maxImagesPerRequest) {
                        run.state = RunState.BUDGET_EXHAUSTED
                        run.stopReason = "CONTEXT_OVERFLOW: protected context exceeds the input or image limit"
                        emitModel(ModelEvent.Failed(run.stopReason!!))
                        finish()
                        return@flow
                    }
                    var reason = window.trigger(modelRequest, adapter, inputLimit, segmentRounds)
                    while (reason != null) {
                        val plan = window.plan(modelRequest, adapter, inputLimit, reason)
                        if (plan == null) {
                            if (reason == "model-rounds") {
                                run.state = RunState.BUDGET_EXHAUSTED
                                run.stopReason = "CONTEXT_OVERFLOW: no complete compressible exchange is available at the round limit"
                                emitModel(ModelEvent.Failed(run.stopReason!!))
                                finish()
                                return@flow
                            }
                            break // Soft pressure alone is not a reason to discard protected evidence.
                        }
                        if (run.compactionRequests >= context.policy.maxCompactionsPerRun ||
                            synchronized(run) { run.modelRounds + 2 > run.budget.maxModelRounds }
                        ) {
                            run.state = RunState.BUDGET_EXHAUSTED
                            run.stopReason = "context-compaction-request-budget"
                            emitModel(ModelEvent.Failed("BUDGET_EXHAUSTED: context compaction request limit reached"))
                            finish()
                            return@flow
                        }
                        if (budgetExhausted(run)) { emitBudget(); return@flow }
                        var checkpoint = saveCompaction(ContextCompactionRecord(
                            id = EntityId.random().value, conversationId = run.conversationId, snapshotId = run.snapshotId,
                            runId = run.runId, sourceMessageIds = plan.coveredMessageIds, inputHash = plan.inputHash,
                            modelId = request.modelId, modelFingerprint = context.modelFingerprint,
                            authorizationFingerprint = context.authorizationFingerprint, reason = plan.reason,
                            parentId = window.parentId, beforeUnits = plan.beforeUnits,
                        ))
                        val beforeSummary = withTimeoutOrNull(remainingMs(run)) { request.beforeModelRequest(); true }
                        if (beforeSummary != true || budgetExhausted(run)) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.CANCELLED))
                            emitBudget(); return@flow
                        }
                        val reserved = synchronized(run) {
                            if (run.modelRounds + 2 > run.budget.maxModelRounds) false
                            else { run.modelRounds++; run.compactionRequests++; true }
                        }
                        if (!reserved) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.CANCELLED))
                            run.state = RunState.BUDGET_EXHAUSTED
                            run.stopReason = "model-rounds"
                            emitModel(ModelEvent.Failed("BUDGET_EXHAUSTED: total model request limit reached"))
                            finish(); return@flow
                        }
                        run.state = RunState.MODEL_STREAMING
                        checkpoint = saveCompaction(checkpoint.copy(state = ContextCompactionState.DISPATCHED))
                        val summaryText = StringBuilder()
                        var summaryTerminal: ModelEvent? = null
                        var summaryTooLarge = false
                        val summaryCompleted = withTimeoutOrNull(remainingMs(run)) {
                            activeDispatch = DispatchKind.MODEL
                            adapter.stream(plan.request.copy(operationId = checkpoint.id), secret).cancellable().collect { event ->
                                when (event) {
                                    is ModelEvent.TextDelta -> {
                                        if (!summaryTooLarge) {
                                            summaryText.append(event.text)
                                            if (summaryText.length > context.policy.summaryMaxUnits ||
                                                summaryText.toString().toByteArray(Charsets.UTF_8).size > context.policy.summaryMaxUnits
                                            ) summaryTooLarge = true
                                        }
                                    }
                                    is ModelEvent.Usage -> {
                                        // Usage is one cumulative completion snapshot, not a sequence of increments.
                                        // Update the interruption checkpoint before any further suspension.
                                        checkpoint = checkpoint.copy(inputTokens = maxOf(0, event.inputTokens),
                                            outputTokens = maxOf(0, event.outputTokens))
                                        activeCompaction = checkpoint
                                    }
                                    is ModelEvent.Failed -> summaryTerminal = ModelEvent.Failed(redact(event.sanitizedMessage, secret))
                                    ModelEvent.Completed -> if (summaryTerminal !is ModelEvent.Failed) summaryTerminal = event
                                    is ModelEvent.ToolCallDelta, is ModelEvent.ToolApprovalRequired, is ModelEvent.RefusalDelta ->
                                        summaryTerminal = ModelEvent.Failed("CONTEXT_COMPACTION_FAILED: summary was not a data-only response")
                                    else -> Unit // Neither private continuation nor reasoning enters a durable summary.
                                }
                            }
                            true
                        }
                        // Compaction usage travels with its durable attempt, not the ordinary
                        // model Usage stream. Consumers reconcile by id, including on cancellation.
                        if (summaryCompleted != true || summaryTerminal == null ||
                            (summaryTerminal as? ModelEvent.Failed)?.sanitizedMessage?.contains("UNKNOWN_OUTCOME") == true
                        ) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.UNKNOWN_OUTCOME))
                            emitUnknownModel(); return@flow
                        }
                        activeDispatch = null
                        if (summaryTerminal is ModelEvent.Failed || summaryTooLarge) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.FAILED))
                            run.state = RunState.FAILED
                            run.stopReason = "CONTEXT_COMPACTION_FAILED: summary failed; original history retained, no automatic retry"
                            emitModel(ModelEvent.Failed(run.stopReason!!)); finish(); return@flow
                        }
                        val summaryJson = try {
                            ContextSummaryFormat.validate(redact(summaryText.toString(), secret), context.policy.summaryMaxUnits)
                        } catch (error: Exception) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.FAILED))
                            run.state = RunState.FAILED
                            run.stopReason = "CONTEXT_COMPACTION_FAILED: invalid summary (${summaryFailureClassification(error)}); original history retained, no automatic retry"
                            emitModel(ModelEvent.Failed(run.stopReason!!)); finish(); return@flow
                        }
                        val replacement = window.replacementRequest(modelRequest, plan, summaryJson)
                        val after = adapter.estimateInput(replacement)
                        if (plan.reason == "input-budget" && after.units >= plan.beforeUnits) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.FAILED))
                            run.state = RunState.BUDGET_EXHAUSTED
                            run.stopReason = "CONTEXT_OVERFLOW: summary did not reduce the request; no automatic retry"
                            emitModel(ModelEvent.Failed(run.stopReason!!)); finish(); return@flow
                        }
                        val stillAuthorized = withTimeoutOrNull(remainingMs(run)) { request.beforeModelRequest(); true }
                        if (stillAuthorized != true || budgetExhausted(run)) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.FAILED))
                            emitBudget(); return@flow
                        }
                        checkpoint = saveCompaction(checkpoint.copy(
                            state = ContextCompactionState.SUCCEEDED, summaryJson = summaryJson, afterUnits = after.units,
                        ))
                        window.commit(plan, checkpoint)
                        segmentRounds = 0
                        modelRequest = replacement
                        reason = window.trigger(modelRequest, adapter, inputLimit, segmentRounds)
                    }
                }
                val estimate = adapter.estimateInput(modelRequest)
                if (estimate.imageCount > request.maxImagesPerRequest || inputLimit?.let { estimate.units > it } == true) {
                    run.state = RunState.BUDGET_EXHAUSTED
                    run.stopReason = "context-or-image-budget"
                    emitModel(ModelEvent.Failed("CONTEXT_OVERFLOW: Context or image budget exceeded; no images were silently removed"))
                    finish()
                    return@flow
                }
                // Use withTimeoutOrNull so the runtime-owned deadline is
                // distinguishable from a caller cancellation.  Catching a
                // TimeoutCancellationException here would also catch a
                // parent timeout and incorrectly turn it into a budget
                // terminal state.
                val beforeRequestCompleted = withTimeoutOrNull(remainingMs(run)) {
                    request.beforeModelRequest()
                    true
                }
                if (beforeRequestCompleted != true) {
                    emitBudget()
                    return@flow
                }
                val modelReserved = synchronized(run) {
                    if (run.modelRounds >= run.budget.maxModelRounds) false else { run.modelRounds += 1; true }
                }
                if (!modelReserved) {
                    run.state = RunState.BUDGET_EXHAUSTED
                    run.stopReason = "model-rounds"
                    emitModel(ModelEvent.Failed("Model round budget exhausted"))
                    finish()
                    return@flow
                }
                segmentRounds++
                val modelRequestNumber = run.modelRounds
                run.state = RunState.MODEL_STREAMING
                emit(
                    RuntimeEvent.RequestPrepared(
                        operationId = request.operationId,
                        modelId = modelRequest.modelId,
                        messages = modelRequest.messages.map { it.toRuntimeSummary() },
                        toolNames = if (request.toolsEnabled) toolSpecs.map { it.name } else emptyList(),
                        parameterKeys = request.parameters.allKeys(),
                        headerNames = request.headers.keys.map { it.lowercase() }.sorted(),
                        requestPreview = if (request.emitRequestPreview) {
                            adapter.previewRequest(modelRequest)
                        } else {
                            null
                        },
                        assistantMessageId = RuntimeMessageIds.assistant(run.runId, modelRequestNumber),
                        estimatedInputUnits = estimate.units,
                    ),
                )

                val pendingTools = linkedMapOf<String, ToolCall>()
                val assistantText = StringBuilder()
                val pendingContinuation = mutableListOf<ProviderContinuationItem>()
                var terminal: ModelEvent? = null
                val modelStreamCompleted = try {
                    withTimeoutOrNull(remainingMs(run)) {
                        // ModelAdapter.stream is a lazy Flow in the provider contract.  The
                        // dispatch boundary is immediately before collection, so a timeout
                        // which fires before this point is a local budget result, while one
                        // after it must be treated as potentially billable/unknown.
                        val modelStream = adapter.stream(modelRequest, secret)
                        activeDispatch = DispatchKind.MODEL
                        modelStream.cancellable().collect { event ->
                            if (terminal is ModelEvent.Failed) return@collect
                            if (budgetExhausted(run)) throw CancellationException(BUDGET_CANCEL)

                            val outgoing = when (event) {
                                is ModelEvent.Failed -> ModelEvent.Failed(redact(event.sanitizedMessage, secret))
                                else -> event
                            }
                            when (outgoing) {
                                is ModelEvent.ToolCallDelta -> {
                                    if (!request.toolsEnabled || toolExecutor == null) {
                                        terminal = ModelEvent.Failed("This model cannot execute tools")
                                    } else {
                                        val call = ToolCall(
                                            callId = outgoing.callId,
                                            name = outgoing.name,
                                            argumentsJson = outgoing.argumentsJson,
                                        )
                                        val validationError = validateToolCall(call, toolSpecs, pendingTools)
                                        if (validationError != null) {
                                            terminal = ModelEvent.Failed(validationError)
                                        } else {
                                            pendingTools[outgoing.callId] = call
                                            emit(
                                                RuntimeEvent.ToolCallObserved(
                                                    callId = call.callId,
                                                    name = call.name,
                                                    argumentsJson = redact(call.argumentsJson, secret),
                                                ),
                                            )
                                        }
                                    }
                                }
                                is ModelEvent.TextDelta -> {
                                    assistantText.append(outgoing.text)
                                    emitModel(outgoing)
                                }
                                // A refusal is assistant output: it stays readable and
                                // persistable like answer text, never reasoning.
                                is ModelEvent.RefusalDelta -> {
                                    assistantText.append(outgoing.text)
                                    emitModel(outgoing)
                                }
                                // Provider-private continuation is captured for the next
                                // request of this run only.  It is never emitted to
                                // the UI, diagnostics, or persisted history.
                                is ModelEvent.ProviderContinuation -> {
                                    pendingContinuation += outgoing.item
                                    Unit
                                }
                                // Reasoning is an independent provider-owned channel.  Forward
                                // only the explicit event; it never enters assistantText or the
                                // next model prompt as inferred chain-of-thought.
                                is ModelEvent.ReasoningDelta -> emitModel(outgoing)
                                ModelEvent.Completed -> if (terminal !is ModelEvent.Failed) terminal = outgoing
                                is ModelEvent.Failed -> terminal = outgoing
                                else -> emitModel(outgoing)
                            }
                        }
                        true
                    }
                } catch (e: CancellationException) {
                    // BUDGET_CANCEL is runtime-owned and is only thrown from inside an
                    // already-started stream.  All other cancellation belongs to the caller
                    // (or the provider's cancellation boundary) and must propagate unchanged.
                    if (e.message == BUDGET_CANCEL) {
                        if (activeDispatch == DispatchKind.MODEL) {
                            emitUnknownModel()
                        } else {
                            emitBudget()
                        }
                        return@flow
                    }
                    throw e
                } catch (e: Exception) {
                    // A transport/connection exception after collection began may have
                    // reached the provider even when no terminal event was observed.
                    if (activeDispatch == DispatchKind.MODEL) {
                        emitUnknownModel()
                        return@flow
                    }
                    throw e
                }

                if (modelStreamCompleted != true) {
                    if (activeDispatch == DispatchKind.MODEL) {
                        emitUnknownModel()
                    } else {
                        emitBudget()
                    }
                    return@flow
                }
                activeDispatch = null

                if (budgetExhausted(run)) {
                    emitBudget()
                    return@flow
                }
                val ended = terminal
                if (ended is ModelEvent.Failed) {
                    run.state = if (ended.sanitizedMessage.contains("UNKNOWN_OUTCOME")) RunState.UNKNOWN_OUTCOME else RunState.FAILED
                    run.stopReason = ended.sanitizedMessage
                    emitModel(ended)
                    finish()
                    return@flow
                }
                if (pendingTools.isEmpty()) {
                    if (ended == ModelEvent.Completed) {
                        run.state = RunState.COMPLETED
                        run.stopReason = run.stopReason ?: "completed"
                        emitModel(ModelEvent.Completed)
                        finish()
                    } else {
                        run.state = RunState.FAILED
                        emitModel(ModelEvent.Failed("Model stream ended without a terminal event"))
                        finish()
                    }
                    return@flow
                }
                if (ended != ModelEvent.Completed) {
                    run.state = RunState.FAILED
                    emitModel(ModelEvent.Failed("Model stream ended before tool calls completed"))
                    finish()
                    return@flow
                }
                if (!request.toolsEnabled || toolExecutor == null) {
                    run.state = RunState.FAILED
                    emitModel(ModelEvent.Failed("This model cannot execute tools"))
                    finish()
                    return@flow
                }
                if (run.toolCalls + pendingTools.size > run.budget.maxToolCalls) {
                    run.state = RunState.BUDGET_EXHAUSTED
                    run.stopReason = "tool-calls"
                    emitModel(ModelEvent.Failed("Tool call budget exhausted"))
                    finish()
                    return@flow
                }

                window.append(ChatMessage(
                    role = "assistant",
                    text = assistantText.toString(),
                    toolCalls = pendingTools.values.map { call ->
                        AssistantToolCall(call.callId, call.name, call.argumentsJson)
                    },
                    // Replay captured provider-private items verbatim on the
                    // next request of this run; the owning adapter encodes
                    // them, previews and history never see them.
                    providerContinuationItems = pendingContinuation.toList(),
                ), RuntimeMessageIds.assistant(run.runId, modelRequestNumber))
                pendingContinuation.clear()
                for (call in pendingTools.values) {
                    if (budgetExhausted(run)) {
                        emitBudget()
                        return@flow
                    }
                    val toolReserved = synchronized(run) {
                        if (run.toolCalls >= run.budget.maxToolCalls) false else { run.toolCalls += 1; true }
                    }
                    if (!toolReserved) {
                        run.state = RunState.BUDGET_EXHAUSTED
                        run.stopReason = "tool-calls"
                        emitModel(ModelEvent.Failed("Tool call budget exhausted"))
                        finish()
                        return@flow
                    }
                    run.state = RunState.TOOL_EXECUTING
                    var approvalRejected = false
                    val result = try {
                        withTimeoutOrNull(remainingMs(run)) {
                            // invoke/approve are the executor dispatch boundary.  Once either
                            // has been entered, cancellation or a transport timeout cannot
                            // prove that the external operation did not happen.
                            activeDispatch = DispatchKind.TOOL
                            when (val first = toolExecutor.invoke(call)) {
                                ToolResult.NeedsApproval -> {
                                    // NeedsApproval is an authorization result, not an
                                    // external execution.  While waiting for the user, a
                                    // cancellation remains a known lifecycle cancellation.
                                    activeDispatch = null
                                    run.state = RunState.WAITING_TOOL_APPROVAL
                                    val safeArguments = redact(call.argumentsJson, secret)
                                    emit(
                                        RuntimeEvent.ToolApprovalRequested(
                                            callId = call.callId,
                                            name = call.name,
                                            argumentsJson = safeArguments,
                                        ),
                                    )
                                    emitModel(
                                        ModelEvent.ToolApprovalRequired(
                                            call.callId,
                                            call.name,
                                            safeArguments,
                                        ),
                                    )
                                    if (!onApprove(call)) {
                                        approvalRejected = true
                                        run.state = RunState.FAILED
                                        emitModel(ModelEvent.Failed("APPROVAL_DENIED"))
                                        null
                                    } else {
                                        if (budgetExhausted(run)) throw CancellationException(BUDGET_CANCEL)
                                        activeDispatch = DispatchKind.TOOL
                                        toolExecutor.approve(call.callId)
                                    }
                                }
                                else -> first
                            }
                        }
                    } catch (e: CancellationException) {
                        if (e.message == BUDGET_CANCEL) {
                            if (activeDispatch == DispatchKind.TOOL) {
                                emitUnknownTool(call)
                            } else {
                                emitBudget()
                            }
                            return@flow
                        }
                        // Do not swallow a lifecycle cancellation.  The outer handler keeps
                        // the run CANCELLED, adding an UNKNOWN_OUTCOME reason when dispatch
                        // had already begun.
                        throw e
                    } catch (e: Exception) {
                        if (activeDispatch == DispatchKind.TOOL) {
                            emitUnknownTool(call)
                            return@flow
                        }
                        throw e
                    }
                    if (result == null && approvalRejected) {
                        activeDispatch = null
                        // The rejection branch emits its user-facing failure,
                        // but still needs the structured terminal lifecycle
                        // event before leaving the flow.
                        finish()
                        return@flow
                    }
                    if (result == null) {
                        if (activeDispatch == DispatchKind.TOOL) {
                            emitUnknownTool(call)
                        } else {
                            emitBudget()
                        }
                        return@flow
                    }
                    activeDispatch = null

                    val (status, modelText) = when (result) {
                        // Every terminal outcome projects to a JSON-object ToolOutcome
                        // envelope. Denied/Invalid previously crossed this boundary as
                        // plain strings, which the conversation store (requiring a JSON
                        // object) rejected — turning a legitimate denial into a run
                        // INTERNAL error. They now stay DENIED/INVALID and durable.
                        is ToolResult.Denied -> {
                            val code = runCatching { ToolErrorCode.valueOf(result.reason) }
                                .getOrDefault(ToolErrorCode.PERMISSION_DENIED)
                            "DENIED" to ToolOutcome.denied(code = code, message = result.reason)
                        }
                        is ToolResult.Invalid -> "INVALID" to ToolOutcome.invalid(message = result.reason)
                        is ToolResult.Value -> "VALUE" to result.json
                        is ToolResult.Failure -> "FAILED" to safeToolFailure(result.error)
                        is ToolResult.UnknownOutcome -> "UNKNOWN_OUTCOME" to result.reason
                        ToolResult.NeedsApproval -> {
                            run.state = RunState.FAILED
                            emitModel(ModelEvent.Failed("Tool ${call.name} needs user confirmation"))
                            finish()
                            return@flow
                        }
                    }
                    // Unknown reasons can originate from an untrusted backend and are not
                    // safe to persist as a tool result (the transfer contract requires an
                    // object).  Keep the model-facing/persisted envelope fixed and bounded;
                    // never replay or expose the backend's raw exception text.
                    val safeText = if (result is ToolResult.UnknownOutcome) {
                        UNKNOWN_TOOL_ENVELOPE
                    } else {
                        redact(modelText, secret)
                    }
                    if (safeText.toByteArray(Charsets.UTF_8).size > TOOL_RESULT_MAX_BYTES) {
                        run.state = RunState.FAILED
                        emitModel(ModelEvent.Failed("Tool result exceeds the runtime output limit"))
                        finish()
                        return@flow
                    }
                    emit(
                        RuntimeEvent.ToolResultProduced(
                            callId = call.callId,
                            name = call.name,
                            status = status,
                            resultSummary = safeText.take(RESULT_SUMMARY_LIMIT),
                            resultJson = safeText,
                            messageId = RuntimeMessageIds.tool(run.runId, modelRequestNumber, call.callId),
                        ),
                    )
                    if (result is ToolResult.UnknownOutcome) {
                        run.state = RunState.UNKNOWN_OUTCOME
                        run.stopReason = UNKNOWN_TOOL_OUTCOME
                        emitModel(ModelEvent.Failed(run.stopReason!!))
                        finish()
                        return@flow
                    }
                    window.append(ChatMessage(
                        role = "tool",
                        text = untrustedToolResult(call.callId, safeText),
                        toolCallId = call.callId,
                    ), RuntimeMessageIds.tool(run.runId, modelRequestNumber, call.callId))
                    val images = try {
                        if (budgetExhausted(run)) {
                            emitBudget()
                            return@flow
                        }
                        val imagesOrNull = withTimeoutOrNull(remainingMs(run)) { request.toolImages(call, result) }
                        if (imagesOrNull == null) {
                            emitBudget()
                            return@flow
                        }
                        imagesOrNull
                    } catch (e: CancellationException) {
                        if (e.message == BUDGET_CANCEL) {
                            emitBudget()
                            return@flow
                        }
                        throw e
                    } catch (e: Exception) {
                        run.state = RunState.FAILED
                        emitModel(ModelEvent.Failed("Tool visual result is unavailable"))
                        finish()
                        return@flow
                    }
                    if (images.any { it.mediaType.isBlank() || it.base64.isBlank() }) {
                        run.state = RunState.FAILED
                        emitModel(ModelEvent.Failed("Tool visual result is invalid"))
                        finish()
                        return@flow
                    }
                    if (images.isNotEmpty()) {
                        emit(RuntimeEvent.ToolImagesAttached(call.callId,
                            images.mapNotNull { image -> image.assetId?.let { RuntimeImageReference(it, image.mediaType) } },
                            RuntimeMessageIds.images(run.runId, modelRequestNumber, call.callId)))
                        window.append(ChatMessage(
                            role = "user",
                            text = untrustedToolImages(call.callId),
                            images = images,
                        ), RuntimeMessageIds.images(run.runId, modelRequestNumber, call.callId))
                    }
                }
            }
        } catch (e: CancellationException) {
            runCatching { settleInterruptedCompaction(cancelled = true) }
            // A caller cancellation is terminal and must never be translated
            // into a retryable model/tool result.  The provider/transport sees
            // the same cancellation through its suspend boundary.
            run.state = RunState.CANCELLED
            run.stopReason = if (activeDispatch != null) {
                UNKNOWN_CANCELLED_OUTCOME
            } else {
                e.message?.takeIf { it.isNotBlank() } ?: "cancelled"
            }
            throw e
        } catch (e: Exception) {
            runCatching { settleInterruptedCompaction(cancelled = false) }
            if (activeDispatch != null) {
                run.state = RunState.UNKNOWN_OUTCOME
                run.stopReason = if (activeDispatch == DispatchKind.MODEL) UNKNOWN_MODEL_OUTCOME else UNKNOWN_TOOL_OUTCOME
                emitModel(ModelEvent.Failed(run.stopReason!!))
                finish()
            } else {
                run.state = RunState.FAILED
                emitModel(ModelEvent.Failed(redact(e.message ?: "Runtime failed", secret)))
                finish()
            }
        }
    }

    private fun remainingMs(run: AgentRun): Long =
        (run.budget.maxRuntimeMs - (clock() - run.startedAtMs)).coerceAtLeast(1)

    private fun budgetExhausted(run: AgentRun): Boolean =
        clock() - run.startedAtMs >= run.budget.maxRuntimeMs

    private fun redact(text: String, secret: CharArray): String =
        SecretRedactor.redact(text, secretsForRedaction() + String(secret))

    private fun untrustedToolResult(callId: String, text: String): String =
        "<untrusted-tool-result call_id=\"${callId.replace("\"", "") }\">\n$text\n</untrusted-tool-result>"

    /** Stable, actionable JSON for known tool failures; backend messages and paths never cross this boundary. */
    private fun safeToolFailure(error: ToolError): String {
        val message = when (error.code) {
            ToolErrorCode.FILE_TOO_LARGE -> "The selected file is too large to read as text."
            ToolErrorCode.INVALID_CURSOR -> "The directory changed. Enumerate it again from the first page."
            ToolErrorCode.PERMISSION_DENIED -> "The workspace provider denied access. Check the workspace permission."
            ToolErrorCode.SYMLINK_FORBIDDEN -> "Symbolic links cannot be followed from this workspace."
            ToolErrorCode.PATH_OUT_OF_SCOPE -> "The requested path is outside the authorized workspace."
            ToolErrorCode.WORKSPACE_NOT_FOUND -> "The selected workspace or entry is no longer available."
            ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            ToolErrorCode.BRIDGE_DISCONNECTED,
            ToolErrorCode.ADB_DEVICE_OFFLINE,
            ToolErrorCode.ADB_DEVICE_DISCONNECTED,
                -> "The workspace is temporarily unavailable. Try again after reconnecting it."
            ToolErrorCode.QUOTA_EXCEEDED -> "The workspace operation exceeded a configured size or output limit."
            ToolErrorCode.CONFLICT -> "The workspace changed. Read the latest state before trying again."
            ToolErrorCode.UNSUPPORTED_ENTRY -> "The workspace entry type is unsupported and was not opened."
            ToolErrorCode.OPERATION_UNAVAILABLE -> "This workspace operation is unavailable on the selected backend."
            else -> "The tool could not complete the request."
        }
        // Unified ToolOutcome envelope: FAILED carries the same ok/status/error
        // shape as DENIED/INVALID/UNKNOWN_OUTCOME so every durable consumer can
        // read a typed status/code instead of guessing from free text.
        return ToolOutcome.failed(error.copy(message = message))
    }

    private fun untrustedToolImages(callId: String): String =
        "<untrusted-tool-images call_id=\"${callId.replace("\"", "") }\">Visual evidence returned by an external tool.</untrusted-tool-images>"

    private fun validateToolSpec(spec: ToolSpec): String? {
        if (spec.name.isBlank()) return "Tool specification has no name"
        val element = runCatching { json.parseToJsonElement(spec.parametersJson) }.getOrNull()
            ?: return "Tool ${spec.name} has invalid parameter schema"
        return validateSchemaDefinition(element, "tool ${spec.name} schema")
    }

    private fun validateToolCall(
        call: ToolCall,
        specs: List<ToolSpec>,
        pending: Map<String, ToolCall>,
    ): String? {
        if (call.callId.isBlank()) return "Tool call ID is missing"
        if (pending.containsKey(call.callId)) return "Tool call ID was repeated"
        val spec = specs.firstOrNull { it.name == call.name }
            ?: return "Unknown tool ${call.name}"
        val arguments = runCatching { json.parseToJsonElement(call.argumentsJson) }.getOrNull()
            ?: return "Tool arguments are invalid JSON"
        val objectArguments = arguments as? JsonObject
            ?: return "Tool arguments must be a JSON object"
        return validateSchemaValue(parseSchema(spec.parametersJson), objectArguments, "tool ${call.name} arguments")
    }

    private fun parseSchema(raw: String): JsonElement =
        runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonObject(emptyMap()) }

    /** Validate the finite JSON-schema subset accepted at the runtime boundary. */
    private fun validateSchemaDefinition(element: JsonElement, path: String, depth: Int = 0): String? {
        if (depth > MAX_SCHEMA_DEPTH) return "$path is too deeply nested"
        val schema = element as? JsonObject ?: return "$path must be an object"
        val types = when (val parsed = parseSchemaTypes(schema, path)) {
            is SchemaTypeResult.Invalid -> return parsed.message
            is SchemaTypeResult.Valid -> parsed.types
        }
        val type = types.firstOrNull { it != "null" } ?: "null"
        schema["required"]?.let { requiredElement ->
            val required = requiredElement as? JsonArray ?: return "$path required must be an array"
            val names = required.map { value ->
                (value as? JsonPrimitive)?.contentOrNull ?: return "$path required contains a non-string"
            }
            if (names.size != names.toSet().size) return "$path required contains duplicates"
            if (type != "object") return "$path required is only valid for objects"
            val properties = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
            if (names.any { it !in properties }) return "$path required contains an unknown property"
        }
        if (type == "object") {
            schema["properties"]?.let { value ->
                val properties = value as? JsonObject ?: return "$path properties must be an object"
                properties.forEach { (name, child) ->
                    if (name.isBlank()) return "$path has a blank property name"
                    validateSchemaDefinition(child, "$path.$name", depth + 1)?.let { return it }
                }
            }
            schema["additionalProperties"]?.let { value ->
                if ((value as? JsonPrimitive)?.booleanOrNull == null && value !is JsonObject) {
                    return "$path additionalProperties must be boolean or schema"
                }
                if (value is JsonObject) {
                    validateSchemaDefinition(value, "$path.additionalProperties", depth + 1)?.let { return it }
                }
            }
        }
        if (type == "array") {
            val items = schema["items"] ?: return "$path array items schema is missing"
            validateSchemaDefinition(items, "$path.items", depth + 1)?.let { return it }
        }
        schema["enum"]?.let { value ->
            val values = value as? JsonArray ?: return "$path enum must be an array"
            if (values.isEmpty()) return "$path enum cannot be empty"
        }
        return null
    }

    private fun validateSchemaValue(schemaElement: JsonElement, value: JsonElement, path: String, depth: Int = 0): String? {
        if (depth > MAX_SCHEMA_DEPTH) return "$path is too deeply nested"
        val schema = schemaElement as? JsonObject ?: return "$path schema is invalid"
        val types = when (val parsed = parseSchemaTypes(schema, path)) {
            is SchemaTypeResult.Invalid -> return parsed.message
            is SchemaTypeResult.Valid -> parsed.types
        }
        val type = if (value is JsonNull && "null" in types) {
            "null"
        } else {
            types.firstOrNull { it != "null" }
        }
            ?: return "$path must be null"
        val primitive = value as? JsonPrimitive
        when (type) {
            "object" -> {
                val obj = value as? JsonObject ?: return "$path must be an object"
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val missing = required.filterNot(obj::containsKey)
                if (missing.isNotEmpty()) return "$path is missing ${missing.joinToString() }"
                val additional = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull
                if (additional == false && obj.keys.any { it !in properties.keys }) {
                    return "$path contains an unknown property"
                }
                obj.forEach { (name, child) ->
                    val childSchema = properties[name]
                    if (childSchema != null) {
                        validateSchemaValue(childSchema, child, "$path.$name", depth + 1)?.let { return it }
                    } else if (additional == null && schema["additionalProperties"] is JsonObject) {
                        validateSchemaValue(schema["additionalProperties"]!!, child, "$path.$name", depth + 1)?.let { return it }
                    }
                }
            }
            "array" -> {
                val array = value as? JsonArray ?: return "$path must be an array"
                schema["minItems"]?.let { min ->
                    val n = (min as? JsonPrimitive)?.content?.toIntOrNull() ?: return "$path minItems is invalid"
                    if (array.size < n) return "$path has too few items"
                }
                schema["maxItems"]?.let { max ->
                    val n = (max as? JsonPrimitive)?.content?.toIntOrNull() ?: return "$path maxItems is invalid"
                    if (array.size > n) return "$path has too many items"
                }
                val items = schema["items"] ?: return "$path array items schema is missing"
                array.forEachIndexed { index, child ->
                    validateSchemaValue(items, child, "$path[$index]", depth + 1)?.let { return it }
                }
            }
            "string" -> {
                if (primitive == null || !primitive.isString) return "$path must be a string"
                schema["minLength"]?.let { min ->
                    val n = (min as? JsonPrimitive)?.content?.toIntOrNull() ?: return "$path minLength is invalid"
                    if (primitive.content.length < n) return "$path is too short"
                }
                schema["maxLength"]?.let { max ->
                    val n = (max as? JsonPrimitive)?.content?.toIntOrNull() ?: return "$path maxLength is invalid"
                    if (primitive.content.length > n) return "$path is too long"
                }
            }
            "number", "integer" -> {
                if (primitive == null || primitive.isString) return "$path must be a number"
                val number = primitive.content.toDoubleOrNull() ?: return "$path must be a number"
                if (!number.isFinite()) return "$path must be finite"
                if (type == "integer" && number % 1.0 != 0.0) return "$path must be an integer"
                schema["minimum"]?.let { min ->
                    val n = (min as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return "$path minimum is invalid"
                    if (number < n) return "$path is below minimum"
                }
                schema["maximum"]?.let { max ->
                    val n = (max as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return "$path maximum is invalid"
                    if (number > n) return "$path is above maximum"
                }
            }
            "boolean" -> if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                return "$path must be boolean"
            }
            "null" -> if (value !is JsonNull) return "$path must be null"
        }
        schema["enum"]?.let { allowed ->
            val values = allowed as? JsonArray ?: return "$path enum is invalid"
            if (value !in values) return "$path is not an allowed value"
        }
        return null
    }

    /**
     * Resolve the finite schema type subset used by the runtime.  JSON Schema
     * permits a type array, but accepting arbitrary unions here would silently
     * widen model-call argument validation.  The only array form supported is
     * one value type plus `null`, which is the nullable encoding used by the
     * shell `cwd` field.
     */
    private fun parseSchemaTypes(schema: JsonObject, path: String): SchemaTypeResult {
        val raw = schema["type"] ?: return SchemaTypeResult.Invalid("$path type is missing")
        val types = when (raw) {
            is JsonPrimitive -> {
                if (!raw.isString) return SchemaTypeResult.Invalid("$path type must be a string or nullable type array")
                listOf(raw.content)
            }
            is JsonArray -> {
                if (raw.isEmpty()) return SchemaTypeResult.Invalid("$path type array cannot be empty")
                val values = mutableListOf<String>()
                raw.forEach { element ->
                    val primitive = element as? JsonPrimitive
                    if (primitive == null || !primitive.isString) {
                        return SchemaTypeResult.Invalid("$path type array must contain only strings")
                    }
                    values += primitive.content
                }
                values
            }
            else -> return SchemaTypeResult.Invalid("$path type must be a string or nullable type array")
        }
        if (raw is JsonArray && types.size != types.toSet().size) {
            return SchemaTypeResult.Invalid("$path type array contains duplicate types")
        }
        if (types.any { it !in SCHEMA_TYPES }) {
            return SchemaTypeResult.Invalid(
                if (raw is JsonArray) "$path type array contains unsupported type" else "$path has unsupported type",
            )
        }
        if (raw is JsonArray &&
            (types.size != 2 || "null" !in types || types.count { it != "null" } != 1)
        ) {
            return SchemaTypeResult.Invalid("$path type array must contain exactly one value type and null")
        }
        return SchemaTypeResult.Valid(types)
    }

    private sealed interface SchemaTypeResult {
        data class Valid(val types: List<String>) : SchemaTypeResult
        data class Invalid(val message: String) : SchemaTypeResult
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = false; isLenient = false }
        val SCHEMA_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
        const val MAX_SCHEMA_DEPTH = 16
        const val BUDGET_CANCEL = "agent-runtime-budget"
        const val UNKNOWN_MODEL_OUTCOME = "UNKNOWN_OUTCOME: Model dispatch may have started; do not automatically retry"
        /**
         * Bounded, non-sensitive classification for a rejected summary. The user-facing message
         * stays fixed in RuntimeEvents; this only sharpens the durable run stop reason so an
         * empty summary and a schema-invalid one can be told apart in diagnostics.
         */
        internal fun summaryFailureClassification(error: Exception): String {
            val detail = error.message.orEmpty()
                .removePrefix("CONTEXT_COMPACTION_FAILED:")
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .trim()
            return when {
                detail.contains("at least one non-blank entry") -> "empty summary"
                detail.contains("must contain exactly") -> "unexpected summary schema"
                detail.contains("must be an array") -> "invalid summary section"
                detail.contains("must contain only strings") -> "invalid summary entry"
                detail.contains("exceeds") -> "summary exceeds limit"
                detail.isBlank() -> "invalid summary"
                else -> detail.take(120)
            }
        }

        const val UNKNOWN_TOOL_OUTCOME = "UNKNOWN_OUTCOME: Tool dispatch may have started; do not automatically retry"
        const val UNKNOWN_CANCELLED_OUTCOME = "UNKNOWN_OUTCOME: Dispatch may have started before cancellation; do not automatically retry"
        const val UNKNOWN_TOOL_ENVELOPE = "{\"ok\":false,\"status\":\"UNKNOWN_OUTCOME\",\"error\":{\"code\":\"UNKNOWN_OUTCOME\",\"message\":\"Tool dispatch may have started; do not automatically retry\",\"retryable\":false},\"automaticReplayAllowed\":false}"
        const val RESULT_SUMMARY_LIMIT = 1024
        const val TOOL_RESULT_MAX_BYTES = 1_048_576
    }

    private enum class DispatchKind { MODEL, TOOL }
}
