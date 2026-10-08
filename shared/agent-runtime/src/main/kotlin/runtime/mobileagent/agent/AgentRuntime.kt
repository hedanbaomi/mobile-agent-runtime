// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.ContextCompactionState
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelDiagnosticEvent
import runtime.mobileagent.provider.ModelDiagnosticSink
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.RequestInputBudget
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
        var compactionUnavailable = false
        // Every transport diagnostic (one per SSE chunk) counts as stream progress, so a long
        // tool-argument or hidden-reasoning phase is never mistaken for a stall.
        val transportProgress = AtomicLong()
        val transportDiagnostics = object : ModelDiagnosticSink {
            override fun record(event: ModelDiagnosticEvent) {
                transportProgress.incrementAndGet()
                request.diagnostics?.record(event)
            }
            override val captureContent: Boolean get() = request.diagnostics?.captureContent ?: false
        }

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
            val visualDelivery = VisualBatchDelivery(request.maxImagesPerRun, request.maxImagesPerRequest,
                request.batchAllImages, request.prompt.currentUser)
            var segmentRounds = 0
            // Run-scoped bound on "rejected before dispatch" tool results fed
            // back to the model.  Each rejection costs one model round; the cap
            // keeps a model that cannot emit a valid call from looping forever.
            var undispatchedToolFeedback = 0
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
                    diagnostics = transportDiagnostics,
                )
                try {
                    modelRequest = visualDelivery.prepare(modelRequest, request.imageLoader) { batch, batchId, images ->
                        if (budgetExhausted(run) || run.modelRounds >= run.budget.maxModelRounds)
                            throw VisualDeliveryBudgetExceeded("Run model request budget exhausted before image group")
                        request.beforeModelRequest()
                        val groupEstimate = adapter.estimateInput(batch)
                        if (request.maxInputBudgetUnits?.let { groupEstimate.units > it } == true ||
                            images.size > request.maxImagesPerRequest || !RequestInputBudget.imageBytesWithinLimit(batch))
                            throw VisualDeliveryBudgetExceeded("CONTEXT_OVERFLOW: image group exceeds request budget")
                        run.modelRounds++
                        run.state = RunState.MODEL_STREAMING
                        emit(RuntimeEvent.VisualBatchStarted(batchId, images.size, groupEstimate.units,
                            if (request.emitRequestPreview) adapter.previewRequest(batch) else null))
                        request.beforeModelRequest()
                        request.beforeImageRequest(images)
                        if (budgetExhausted(run)) throw VisualDeliveryBudgetExceeded("Run deadline before image group")
                        val notes = StringBuilder()
                        var batchTerminal: ModelEvent? = null
                        var oversized = false
                        activeDispatch = DispatchKind.MODEL
                        val completed = adapter.stream(batch, secret).cancellable()
                            .collectUntilStalled(run.budget.stallTimeoutMs, transportProgress) { event ->
                                when (event) {
                                    is ModelEvent.TextDelta -> {
                                        if (notes.length + event.text.length > 16_000) oversized = true
                                        else if (!oversized) notes.append(event.text)
                                    }
                                    is ModelEvent.Usage -> emitModel(event)
                                    is ModelEvent.Failed -> batchTerminal = ModelEvent.Failed(redact(event.sanitizedMessage, secret))
                                    is ModelEvent.ToolCallDelta -> batchTerminal = ModelEvent.Failed("INVALID_RESPONSE: image analysis must not call tools")
                                    is ModelEvent.RefusalDelta -> batchTerminal = ModelEvent.Failed("VISUAL_BATCH_REFUSED: image group refused")
                                    ModelEvent.Completed -> if (batchTerminal !is ModelEvent.Failed) batchTerminal = event
                                    else -> Unit
                                }
                            }
                        if (completed != true || batchTerminal == null) throw VisualBatchDispatchFailure(true,
                            "UNKNOWN_OUTCOME: image group response incomplete; no automatic replay")
                        val failure = batchTerminal as? ModelEvent.Failed
                        if (failure != null) throw VisualBatchDispatchFailure(
                            failure.sanitizedMessage.contains("UNKNOWN_OUTCOME"), failure.sanitizedMessage)
                        if (oversized || !hasVisualEvidenceNotes(notes.toString())) throw VisualBatchDispatchFailure(false,
                            "INVALID_RESPONSE: image evidence notes absent or oversized")
                        emit(RuntimeEvent.VisualBatchAnalyzed(batchId,
                            images.mapNotNull { image -> image.assetId?.let { RuntimeImageReference(it, image.mediaType, image.sha256) } },
                            notes.toString()))
                        activeDispatch = null // Successful receipt collector has finished durable persistence.
                        notes.toString()
                    }
                    window.transformProjectedMessages(visualDelivery::rewriteMessages)
                } catch (failure: VisualDeliveryBudgetExceeded) {
                    run.state = RunState.BUDGET_EXHAUSTED; run.stopReason = failure.message
                    emitModel(ModelEvent.Failed(failure.message.orEmpty())); finish(); return@flow
                } catch (failure: VisualBatchDispatchFailure) {
                    run.state = if (failure.unknown) RunState.UNKNOWN_OUTCOME else RunState.FAILED
                    run.stopReason = failure.message
                    emitModel(ModelEvent.Failed(failure.message.orEmpty())); finish(); return@flow
                }
                val inputLimit = request.maxInputBudgetUnits
                val context = request.context?.takeIf { it.policy.autoCompact && inputLimit != null && !compactionUnavailable }
                if (context != null) {
                    val minimumRequest = window.minimumRequest(modelRequest)
                    val minimum = adapter.estimateInput(minimumRequest)
                    if (minimum.units > inputLimit!! || minimum.imageCount > request.maxImagesPerRequest ||
                        !RequestInputBudget.imageBytesWithinLimit(minimumRequest)) {
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
                        // A dispatched summary is received to its own end; only a stalled
                        // stream becomes unknown.  The deadline is checked again below.
                        activeDispatch = DispatchKind.MODEL
                        // Persisting DISPATCHED may suspend, but the provider has not been
                        // contacted yet. Recheck admission after that durable checkpoint.
                        if (budgetExhausted(run)) {
                            activeDispatch = null
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.FAILED))
                            emitBudget(); return@flow
                        }
                        val summaryCompleted = adapter.stream(plan.request.copy(operationId = checkpoint.id), secret)
                            .cancellable().collectUntilStalled(run.budget.stallTimeoutMs, transportProgress) { event ->
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
                        // Compaction usage travels with its durable attempt, not the ordinary
                        // model Usage stream. Consumers reconcile by id, including on cancellation.
                        if (summaryCompleted != true || summaryTerminal == null ||
                            (summaryTerminal as? ModelEvent.Failed)?.sanitizedMessage?.contains("UNKNOWN_OUTCOME") == true
                        ) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.UNKNOWN_OUTCOME))
                            emitUnknownModel(); return@flow
                        }
                        activeDispatch = null
                        suspend fun rejectSummary(classification: String): Boolean {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.FAILED,
                                reason = plan.reason + ":" + classification))
                            // A failed optimization must not terminate a request whose original
                            // evidence still fits. Suppress further summaries in this Run; ordinary
                            // dispatch still rechecks authority, total requests, tools and deadline.
                            // Unknown/cancelled dispatches never reach this recovery path.
                            val original = adapter.estimateInput(modelRequest)
                            if (inputLimit?.let { original.units <= it } == true && original.imageCount <= request.maxImagesPerRequest &&
                                RequestInputBudget.imageBytesWithinLimit(modelRequest)) {
                                compactionUnavailable = true
                                return true
                            }
                            run.state = RunState.FAILED
                            run.stopReason = "CONTEXT_COMPACTION_FAILED: $classification; original history retained, no automatic retry"
                            emitModel(ModelEvent.Failed(run.stopReason!!))
                            finish()
                            return false
                        }
                        if (summaryTerminal is ModelEvent.Failed || summaryTooLarge) {
                            val classification = if (summaryTooLarge) "summary-too-large" else
                                summaryResponseFailure((summaryTerminal as ModelEvent.Failed).sanitizedMessage)
                            if (rejectSummary(classification)) break else return@flow
                        }
                        val summaryJson = try {
                            ContextSummaryFormat.validate(redact(summaryText.toString(), secret), context.policy.summaryMaxUnits)
                        } catch (error: Exception) {
                            if (rejectSummary(summaryFailureClassification(error))) break else return@flow
                        }
                        val replacement = window.replacementRequest(modelRequest, plan, summaryJson)
                        val after = adapter.estimateInput(replacement)
                        if (plan.reason == "input-budget" && after.units >= plan.beforeUnits) {
                            if (rejectSummary("summary-did-not-reduce")) break else return@flow
                        }
                        // A summary received in full after the deadline is still kept (it was
                        // billed and a later turn may reuse it); only the next request is gated.
                        val pastDeadline = budgetExhausted(run)
                        val stillAuthorized = withTimeoutOrNull(
                            if (pastDeadline) run.budget.stallTimeoutMs else remainingMs(run),
                        ) { request.beforeModelRequest(); true }
                        if (stillAuthorized != true) {
                            saveCompaction(checkpoint.copy(state = ContextCompactionState.FAILED))
                            emitBudget(); return@flow
                        }
                        checkpoint = saveCompaction(checkpoint.copy(
                            state = ContextCompactionState.SUCCEEDED, summaryJson = summaryJson, afterUnits = after.units,
                        ))
                        if (pastDeadline || budgetExhausted(run)) { emitBudget(); return@flow }
                        window.commit(plan, checkpoint)
                        segmentRounds = 0
                        modelRequest = replacement
                        reason = window.trigger(modelRequest, adapter, inputLimit, segmentRounds)
                    }
                }
                val estimate = adapter.estimateInput(modelRequest)
                if (!RequestInputBudget.imageBytesWithinLimit(modelRequest)) {
                    run.state = RunState.BUDGET_EXHAUSTED
                    run.stopReason = "IMAGE_BYTES_BUDGET_EXCEEDED: originals exceed 16 MiB in total"
                    emitModel(ModelEvent.Failed(run.stopReason!!))
                    finish()
                    return@flow
                }
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
                // A rejected summary can fall through here after the deadline; recheck
                // admission immediately before the dispatch boundary.
                if (beforeRequestCompleted != true || budgetExhausted(run)) {
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
                // Calls that failed local validation before dispatch.  They are
                // still recorded on the assistant message (the model did emit
                // them) and get a synthetic INVALID tool result so the model can
                // resend a corrected call on the next round.
                val rejectedCalls = linkedMapOf<String, Pair<ToolCall, String>>()
                val assistantText = StringBuilder()
                val assistantReasoning = StringBuilder()
                val pendingContinuation = mutableListOf<ProviderContinuationItem>()
                var terminal: ModelEvent? = null
                // RequestPrepared is consumed by durable/UI collectors and can suspend.
                // No provider request has started yet; do not admit it on an expired check.
                if (budgetExhausted(run)) {
                    emitBudget()
                    return@flow
                }
                val modelStreamCompleted = try {
                    // ModelAdapter.stream is a lazy Flow in the provider contract; the dispatch
                    // boundary is immediately before collection.  A dispatched reply is received
                    // to its own end even past the run deadline, which only gates the next
                    // request.  Only a stream that stalls is abandoned as an unknown outcome.
                    val modelStream = adapter.stream(modelRequest, secret)
                    activeDispatch = DispatchKind.MODEL
                    modelStream.cancellable().collectUntilStalled(run.budget.stallTimeoutMs, transportProgress) { event ->
                        if (terminal is ModelEvent.Failed) return@collectUntilStalled

                        val outgoing = when (event) {
                            is ModelEvent.Failed -> ModelEvent.Failed(redact(event.sanitizedMessage, secret))
                            else -> event
                        }
                        when (outgoing) {
                            is ModelEvent.ToolCallDelta -> {
                                if (!request.toolsEnabled || toolExecutor == null) {
                                    terminal = ModelEvent.Failed("CONFIG_INVALID: model tools are not enabled for this model")
                                } else {
                                    val call = ToolCall(
                                        callId = outgoing.callId,
                                        name = outgoing.name,
                                        argumentsJson = outgoing.argumentsJson,
                                    )
                                    val validationError = validateToolCall(call, toolSpecs, pendingTools)
                                    if (validationError != null) {
                                        if (undispatchedToolFeedback < MAX_UNDISPATCHED_TOOL_FEEDBACK) {
                                            undispatchedToolFeedback++
                                            rejectedCalls[outgoing.callId] = call to validationError
                                        } else {
                                            // Typed prefix so a tool-call validation failure is
                                            // distinguishable from a truncated provider stream in
                                            // run_state (R2 QA P2); the detail stays in stopReason.
                                            terminal = ModelEvent.Failed(
                                                "${rejectedTerminalCode(validationError)}: $validationError",
                                            )
                                        }
                                    } else {
                                        rejectedCalls.remove(outgoing.callId)
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
                            // Only provider-declared reasoning is replayable, in its own protocol
                            // field. Never infer reasoning from ordinary assistant answer text.
                            is ModelEvent.ReasoningDelta -> {
                                assistantReasoning.append(outgoing.text)
                                emitModel(outgoing)
                            }
                            ModelEvent.Completed -> if (terminal !is ModelEvent.Failed) terminal = outgoing
                            is ModelEvent.Failed -> terminal = outgoing
                            else -> emitModel(outgoing)
                        }
                    }
                } catch (e: CancellationException) {
                    // Cancellation belongs to the caller (or the provider's cancellation
                    // boundary) and must propagate unchanged.
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

                // The reply has been received in full, so its own terminal result settles
                // first.  The deadline only decides whether this run starts more work.
                val ended = terminal
                if (ended is ModelEvent.Failed) {
                    run.state = if (ended.sanitizedMessage.contains("UNKNOWN_OUTCOME")) RunState.UNKNOWN_OUTCOME else RunState.FAILED
                    run.stopReason = ended.sanitizedMessage
                    emitModel(ended)
                    finish()
                    return@flow
                }
                if (pendingTools.isEmpty() && rejectedCalls.isEmpty()) {
                    if (ended == ModelEvent.Completed) {
                        run.state = RunState.COMPLETED
                        run.stopReason = run.stopReason ?: "completed"
                        emitModel(ModelEvent.Completed)
                        finish()
                    } else {
                        run.state = RunState.FAILED
                        // Typed prefix: the provider's stream ended without its own
                        // terminal event, which is a malformed/incomplete response and
                        // not an unknown external outcome (R2 QA P2 made this
                        // indistinguishable from a tool-validation failure).
                        emitModel(ModelEvent.Failed("INVALID_RESPONSE: model stream ended without a terminal event"))
                        finish()
                    }
                    return@flow
                }
                if (budgetExhausted(run)) {
                    emitBudget()
                    return@flow
                }
                if (ended != ModelEvent.Completed) {
                    run.state = RunState.FAILED
                    emitModel(ModelEvent.Failed("INVALID_RESPONSE: model stream ended before tool calls completed"))
                    finish()
                    return@flow
                }
                if (!request.toolsEnabled || toolExecutor == null) {
                    run.state = RunState.FAILED
                    emitModel(ModelEvent.Failed("CONFIG_INVALID: model tools are not enabled for this model"))
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
                    toolCalls = (pendingTools.values + rejectedCalls.values.map { it.first }).map { call ->
                        AssistantToolCall(call.callId, call.name, call.argumentsJson)
                    },
                    // Replay captured provider-private items verbatim on the
                    // next request of this run; the owning adapter encodes
                    // them, previews and history never see them.
                    providerContinuationItems = pendingContinuation.toList(),
                    reasoningContent = assistantReasoning.toString().takeIf { it.isNotEmpty() },
                ), RuntimeMessageIds.assistant(run.runId, modelRequestNumber))
                pendingContinuation.clear()
                // Rejected calls never reached the executor; feed each one back as
                // a typed INVALID tool result (paired with the assistant tool_call
                // above) so the model sees the local validation error and can
                // resend corrected arguments on the next round.
                for ((rejectedCall, validationError) in rejectedCalls.values) {
                    val rejectedJson = redact(
                        ToolOutcome.invalid(message = validationError),
                        secret,
                    )
                    emit(
                        RuntimeEvent.ToolResultProduced(
                            callId = rejectedCall.callId,
                            name = rejectedCall.name,
                            status = "INVALID",
                            resultSummary = rejectedJson.take(RESULT_SUMMARY_LIMIT),
                            resultJson = rejectedJson,
                            messageId = RuntimeMessageIds.tool(run.runId, modelRequestNumber, rejectedCall.callId),
                        ),
                    )
                    window.append(
                        ChatMessage(
                            role = "tool",
                            text = untrustedToolResult(rejectedCall.callId, rejectedJson),
                            toolCallId = rejectedCall.callId,
                        ),
                        RuntimeMessageIds.tool(run.runId, modelRequestNumber, rejectedCall.callId),
                    )
                }
                val visualResults = mutableListOf<Pair<String, List<runtime.mobileagent.provider.InlineImage>>>()
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
                        // invoke/approve are the executor dispatch boundary.  Once either has been
                        // entered, cancellation or a stall cannot prove that the external operation
                        // did not happen.  A dispatched tool is never cut off by the run deadline; it
                        // ends on its own result, caller cancellation, or the stall timeout.
                        activeDispatch = DispatchKind.TOOL
                        when (val first = withTimeoutOrNull(run.budget.stallTimeoutMs) { toolExecutor.invoke(call) }) {
                            ToolResult.NeedsApproval -> {
                                // NeedsApproval is an authorization result, not an external execution.
                                // While waiting for the user, a cancellation remains a known lifecycle
                                // cancellation and the deadline still bounds the wait.
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
                                when (withTimeoutOrNull(remainingMs(run)) { onApprove(call) }) {
                                    null -> null
                                    false -> {
                                        approvalRejected = true
                                        run.state = RunState.FAILED
                                        emitModel(ModelEvent.Failed("APPROVAL_DENIED"))
                                        null
                                    }
                                    true -> if (budgetExhausted(run)) null else {
                                        activeDispatch = DispatchKind.TOOL
                                        withTimeoutOrNull(run.budget.stallTimeoutMs) { toolExecutor.approve(call.callId) }
                                    }
                                }
                            }
                            else -> first
                        }
                    } catch (e: CancellationException) {
                        // Do not swallow a lifecycle cancellation.  The outer handler keeps the run
                        // CANCELLED, adding an UNKNOWN_OUTCOME reason when dispatch had already begun.
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
                            if (result.completion == ToolResult.Completion.COMPLETED_WITHHELD) {
                                "COMPLETED_WITHHELD" to ToolOutcome.completedWithheld(result.reason)
                            } else {
                                "DENIED" to ToolOutcome.denied(code = code, message = result.reason)
                            }
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
                        throw e
                    } catch (e: Exception) {
                        run.state = RunState.FAILED
                        emitModel(ModelEvent.Failed("Tool visual result is unavailable"))
                        finish()
                        return@flow
                    }
                    if (images.any { it.mediaType.isBlank() || (it.base64.isBlank() && (it.assetId.isNullOrBlank() || it.byteLength == null)) }) {
                        run.state = RunState.FAILED
                        emitModel(ModelEvent.Failed("Tool visual result is invalid"))
                        finish()
                        return@flow
                    }
                    if (images.isNotEmpty()) {
                        try { visualDelivery.reserve(images) } catch (failure: VisualDeliveryBudgetExceeded) {
                            run.state = RunState.BUDGET_EXHAUSTED; run.stopReason = failure.message
                            emitModel(ModelEvent.Failed(failure.message.orEmpty())); finish(); return@flow
                        }
                        // Legacy eager callbacks must not accumulate multiple full payloads.
                        val eager = (modelRequest.messages.flatMap { it.images } +
                            visualResults.flatMap { it.second } + images).filter { it.base64.isNotBlank() }
                        if (!RequestInputBudget.imageBytesWithinLimit(ModelRequest(request.modelId,
                                listOf(ChatMessage("user", images = eager))))) {
                            run.state = RunState.BUDGET_EXHAUSTED
                            run.stopReason = "CONTEXT_OVERFLOW: IMAGE_BYTES_BUDGET_EXCEEDED: eager originals exceed 16 MiB"
                            emitModel(ModelEvent.Failed(run.stopReason!!)); finish(); return@flow
                        }
                        visualResults += call.callId to images
                    }
                }
                // Every call in an assistant batch must have its tool response before a user
                // message can follow. Keep live requests and durable history in the same order.
                for ((callId, images) in visualResults) {
                        emit(RuntimeEvent.ToolImagesAttached(callId,
                            images.mapNotNull { image -> image.assetId?.let { RuntimeImageReference(it, image.mediaType) } },
                            RuntimeMessageIds.images(run.runId, modelRequestNumber, callId)))
                        window.append(ChatMessage(
                            role = "user",
                            text = untrustedToolImages(callId),
                            images = images,
                        ), RuntimeMessageIds.images(run.runId, modelRequestNumber, callId))
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

    /**
     * Collect a dispatched model stream to its own end.  The run deadline is deliberately not
     * applied here: a reply that is still arriving is received in full.  Progress is any
     * delivered event or any transport diagnostic (each SSE chunk, including tool-argument and
     * hidden-reasoning chunks that produce no event yet).  Only a stream with no progress for a
     * full [stallMs] window is abandoned, returning false so the caller records an unknown
     * outcome; time spent inside [accept] never counts as a stall.  Upstream failures and caller
     * cancellation propagate unchanged.
     */
    private suspend fun <T> Flow<T>.collectUntilStalled(
        stallMs: Long,
        transportProgress: AtomicLong,
        accept: suspend (T) -> Unit,
    ): Boolean = coroutineScope {
        // The producer closes the channel with an upstream failure instead of failing this
        // scope, so every event already produced is delivered, in order, before it rethrows.
        val events = Channel<T>(Channel.RENDEZVOUS)
        val producer = launch {
            try {
                this@collectUntilStalled.collect { events.send(it) }
                events.close()
            } catch (e: CancellationException) {
                events.cancel()
                throw e
            } catch (e: Throwable) {
                events.close(e)
            }
        }
        val delivered = AtomicLong()
        val accepting = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)
        val monitor = launch {
            var seen = delivered.get() + transportProgress.get()
            while (true) {
                delay(stallMs.coerceAtLeast(1))
                val now = delivered.get() + transportProgress.get()
                if (now == seen && !accepting.get()) {
                    stalled.set(true)
                    producer.cancel()
                    events.cancel()
                    return@launch
                }
                seen = now
            }
        }
        try {
            while (true) {
                val next = try {
                    events.receiveCatching()
                } catch (e: CancellationException) {
                    if (stalled.get()) return@coroutineScope false
                    throw e
                }
                if (stalled.get()) return@coroutineScope false
                if (next.isClosed) {
                    next.exceptionOrNull()?.let { throw it }
                    return@coroutineScope true
                }
                delivered.incrementAndGet()
                accepting.set(true)
                try {
                    accept(next.getOrThrow())
                } finally {
                    accepting.set(false)
                    delivered.incrementAndGet()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            monitor.cancel()
            producer.cancel()
        }
    }

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
            ToolErrorCode.ENTRY_NOT_FOUND -> "The workspace entry was not found; check the relative path."
            ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            ToolErrorCode.BRIDGE_DISCONNECTED,
            ToolErrorCode.ADB_DEVICE_OFFLINE,
            ToolErrorCode.ADB_DEVICE_DISCONNECTED,
                -> "The workspace is temporarily unavailable. Try again after reconnecting it."
            ToolErrorCode.QUOTA_EXCEEDED -> "The workspace operation exceeded a configured size or output limit."
            ToolErrorCode.CONFLICT -> "The requested entry changed. Read that entry's latest version before trying again."
            ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED ->
                "This workspace cannot enforce expected_version for this operation. Nothing was executed. Check workspace_list.expected_version_operations; do not repeat the same request."
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
            ?: return TOOL_ARGS_INVALID_JSON
        val objectArguments = arguments as? JsonObject
            ?: return TOOL_ARGS_NOT_OBJECT
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
                else -> "invalid summary"
            }
        }

        internal fun summaryResponseFailure(message: String): String = when {
            message.contains("REASONING_ONLY") -> "summary-reasoning-only"
            message.contains("CONTEXT_OVERFLOW") || message.contains("OUTPUT_TRUNCATED") -> "summary-output-truncated"
            message.contains("AUTH") || message.contains("SECRET_UNAVAILABLE") -> "summary-auth-failed"
            message.contains("RATE_LIMIT") -> "summary-rate-limited"
            message.contains("TIMEOUT") -> "summary-timeout"
            message.contains("not a data-only") -> "summary-not-data-only"
            else -> "summary-response-failed"
        }

        const val UNKNOWN_TOOL_OUTCOME = "UNKNOWN_OUTCOME: Tool dispatch may have started; do not automatically retry"
        const val UNKNOWN_CANCELLED_OUTCOME = "UNKNOWN_OUTCOME: Dispatch may have started before cancellation; do not automatically retry"
        const val UNKNOWN_TOOL_ENVELOPE = "{\"ok\":false,\"status\":\"UNKNOWN_OUTCOME\",\"error\":{\"code\":\"UNKNOWN_OUTCOME\",\"message\":\"Tool dispatch may have started; do not automatically retry\",\"retryable\":false},\"automaticReplayAllowed\":false}"
        const val RESULT_SUMMARY_LIMIT = 1024
        const val TOOL_RESULT_MAX_BYTES = 1_048_576
        const val TOOL_ARGS_INVALID_JSON = "Tool arguments are invalid JSON"
        const val TOOL_ARGS_NOT_OBJECT = "Tool arguments must be a JSON object"
        const val MAX_UNDISPATCHED_TOOL_FEEDBACK = 3

        /** Arguments that provably never reached dispatch are INVALID_RESPONSE;
         * other validation rejections stay TOOL_FAILED. */
        internal fun rejectedTerminalCode(validationError: String): String =
            if (validationError == TOOL_ARGS_INVALID_JSON || validationError == TOOL_ARGS_NOT_OBJECT) {
                "INVALID_RESPONSE"
            } else {
                "TOOL_FAILED"
            }
    }

    private enum class DispatchKind { MODEL, TOOL }
}
