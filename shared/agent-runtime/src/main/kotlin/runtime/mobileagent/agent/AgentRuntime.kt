// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
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
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.skills.ToolBroker
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.asToolExecutor

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
                ),
            )
        }

        suspend fun emitBudget() {
            run.state = RunState.BUDGET_EXHAUSTED
            run.stopReason = "time"
            emitModel(ModelEvent.Failed("Run budget exhausted"))
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
            val messages = request.prompt.asMessages().toMutableList()
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

                run.state = RunState.MODEL_STREAMING
                val modelRequest = ModelRequest(
                    modelId = request.modelId,
                    messages = messages.toList(),
                    tools = toolMaps,
                    stream = true,
                    parameters = request.parameters,
                    headers = request.headers,
                    operationId = request.operationId,
                    outputTokenLimit = request.outputTokenLimit,
                )
                val imageCount = modelRequest.messages.sumOf { it.images.size }
                val inputUnits = modelRequest.messages.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() + it.images.size * 4096L } +
                    toolMaps.sumOf { it.toString().toByteArray(Charsets.UTF_8).size.toLong() }
                if (imageCount > request.maxImagesPerRequest || request.maxInputBudgetUnits?.let { inputUnits > it } == true) {
                    run.state = RunState.BUDGET_EXHAUSTED
                    run.stopReason = "context-or-image-budget"
                    emitModel(ModelEvent.Failed("Context or image budget exceeded; no images were silently removed"))
                    finish()
                    return@flow
                }
                withTimeout(remainingMs(run)) { request.beforeModelRequest() }
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
                    ),
                )

                val pendingTools = linkedMapOf<String, ToolCall>()
                val assistantText = StringBuilder()
                var terminal: ModelEvent? = null
                try {
                    withTimeout(remainingMs(run)) {
                        adapter.stream(modelRequest, secret).cancellable().collect { event ->
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
                                ModelEvent.Completed -> if (terminal !is ModelEvent.Failed) terminal = outgoing
                                is ModelEvent.Failed -> terminal = outgoing
                                else -> emitModel(outgoing)
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    emitBudget()
                    return@flow
                } catch (e: CancellationException) {
                    if (e.message == BUDGET_CANCEL || budgetExhausted(run)) {
                        emitBudget()
                        return@flow
                    }
                    throw e
                }

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

                messages += ChatMessage(
                    role = "assistant",
                    text = assistantText.toString(),
                    toolCalls = pendingTools.values.map { call ->
                        AssistantToolCall(call.callId, call.name, call.argumentsJson)
                    },
                )
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
                    val result = try {
                        withTimeout(remainingMs(run)) {
                            when (val first = toolExecutor.invoke(call)) {
                                ToolResult.NeedsApproval -> {
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
                                        run.state = RunState.FAILED
                                        emitModel(ModelEvent.Failed(redact("Tool ${call.name} was rejected", secret)))
                                        return@withTimeout null
                                    }
                                    if (budgetExhausted(run)) throw CancellationException(BUDGET_CANCEL)
                                    toolExecutor.approve(call.callId)
                                }
                                else -> first
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        emitBudget()
                        return@flow
                    } catch (e: CancellationException) {
                        if (e.message == BUDGET_CANCEL || budgetExhausted(run)) {
                            emitBudget()
                            return@flow
                        }
                        throw e
                    } ?: run {
                        // The rejection branch emits its user-facing failure,
                        // but still needs the structured terminal lifecycle
                        // event before leaving the flow.
                        finish()
                        return@flow
                    }

                    val (status, summary, modelText) = when (result) {
                        is ToolResult.Denied -> Triple("DENIED", result.reason, result.reason)
                        is ToolResult.Invalid -> Triple("INVALID", result.reason, result.reason)
                        is ToolResult.Value -> Triple("VALUE", result.json, result.json)
                        is ToolResult.UnknownOutcome -> Triple("UNKNOWN_OUTCOME", result.reason, result.reason)
                        ToolResult.NeedsApproval -> {
                            run.state = RunState.FAILED
                            emitModel(ModelEvent.Failed("Tool ${call.name} needs user confirmation"))
                            finish()
                            return@flow
                        }
                    }
                    val safeText = redact(modelText, secret)
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
                        ),
                    )
                    if (result is ToolResult.UnknownOutcome) {
                        run.state = RunState.UNKNOWN_OUTCOME
                        run.stopReason = "UNKNOWN_OUTCOME: $safeText"
                        emitModel(ModelEvent.Failed(run.stopReason!!))
                        finish()
                        return@flow
                    }
                    messages += ChatMessage(
                        role = "tool",
                        text = untrustedToolResult(call.callId, safeText),
                        toolCallId = call.callId,
                    )
                    val images = try {
                        if (budgetExhausted(run)) {
                            emitBudget()
                            return@flow
                        }
                        withTimeout(remainingMs(run)) { request.toolImages(call, result) }
                    } catch (e: TimeoutCancellationException) {
                        emitBudget()
                        return@flow
                    } catch (e: CancellationException) {
                        if (e.message == BUDGET_CANCEL || budgetExhausted(run)) {
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
                            images.mapNotNull { image -> image.assetId?.let { RuntimeImageReference(it, image.mediaType) } }))
                        messages += ChatMessage(
                            role = "user",
                            text = untrustedToolImages(call.callId),
                            images = images,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            // A caller cancellation is terminal and must never be translated
            // into a retryable model/tool result.  The provider/transport sees
            // the same cancellation through its suspend boundary.
            run.state = RunState.CANCELLED
            run.stopReason = e.message?.takeIf { it.isNotBlank() } ?: "cancelled"
            throw e
        } catch (e: Exception) {
            run.state = RunState.FAILED
            emitModel(ModelEvent.Failed(redact(e.message ?: "Runtime failed", secret)))
            finish()
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
        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
            ?: return "$path type is missing"
        if (type !in SCHEMA_TYPES) return "$path has unsupported type"
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
        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
            ?: return "$path schema type is missing"
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
            "boolean" -> if (primitive?.booleanOrNull == null) return "$path must be boolean"
            "null" -> if (value !is JsonNull) return "$path must be null"
        }
        schema["enum"]?.let { allowed ->
            val values = allowed as? JsonArray ?: return "$path enum is invalid"
            if (value !in values) return "$path is not an allowed value"
        }
        return null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = false; isLenient = false }
        val SCHEMA_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
        const val MAX_SCHEMA_DEPTH = 16
        const val BUDGET_CANCEL = "agent-runtime-budget"
        const val RESULT_SUMMARY_LIMIT = 1024
        const val TOOL_RESULT_MAX_BYTES = 1_048_576
    }
}
