// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.skills.ToolBroker
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.toolSpecsAsMaps

class AgentRuntime(
    private val adapter: ModelAdapter,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tools: ToolBroker? = null,
    private val secretsForRedaction: () -> List<String> = { emptyList() },
) {
    fun run(
        run: AgentRun,
        prompt: EffectivePrompt,
        modelId: String,
        secret: CharArray,
        toolsEnabled: Boolean,
    ): Flow<ModelEvent> = flow {
        run.startedAtMs = clock()
        run.state = RunState.VALIDATING
        if (modelId.isBlank()) {
            run.state = RunState.FAILED
            emit(
                ModelEvent.Failed(
                    AppError(
                        ErrorCode.INVALID_CONFIG,
                        "Chat model is not configured",
                        RetryClass.USER_ACTION,
                        "validating",
                        run.runId,
                    ).userMessage,
                ),
            )
            return@flow
        }
        run.state = RunState.ASSEMBLING
        val messages = prompt.asMessages().map { it.toMutableMap() }.toMutableList()
        val toolMaps = if (toolsEnabled && tools != null) toolSpecsAsMaps() else emptyList()
        if (!toolsEnabled && toolMaps.isNotEmpty()) {
            run.state = RunState.FAILED
            emit(ModelEvent.Failed("This model cannot execute tools"))
            return@flow
        }
        while (true) {
            if (clock() - run.startedAtMs > run.budget.maxRuntimeMs) {
                run.state = RunState.BUDGET_EXHAUSTED
                run.stopReason = "time"
                emit(ModelEvent.Failed("Run budget exhausted"))
                return@flow
            }
            if (run.modelRounds >= run.budget.maxModelRounds) {
                run.state = RunState.BUDGET_EXHAUSTED
                emit(ModelEvent.Failed("Model round budget exhausted"))
                return@flow
            }
            run.modelRounds += 1
            run.state = RunState.MODEL_STREAMING
            val request = ModelRequest(
                modelId = modelId,
                messages = messages.map { it.toMap() },
                tools = toolMaps,
                stream = true,
            )
            val pendingTools = linkedMapOf<String, ToolCall>()
            var terminal: ModelEvent? = null
            adapter.stream(request, secret).collect { event ->
                val outgoing = when (event) {
                    is ModelEvent.Failed ->
                        ModelEvent.Failed(SecretRedactor.redact(event.sanitizedMessage, secretsForRedaction() + String(secret)))
                    else -> event
                }
                when (outgoing) {
                    is ModelEvent.ToolCallDelta -> {
                        pendingTools[outgoing.callId] = ToolCall(outgoing.callId, outgoing.name, outgoing.argumentsJson)
                    }
                    ModelEvent.Completed -> terminal = outgoing
                    is ModelEvent.Failed -> terminal = outgoing
                    else -> emit(outgoing)
                }
            }
            val ended = terminal
            if (ended is ModelEvent.Failed) {
                run.state = RunState.FAILED
                emit(ended)
                return@flow
            }
            if (pendingTools.isEmpty()) {
                run.state = RunState.COMPLETED
                emit(ModelEvent.Completed)
                return@flow
            }
            if (run.toolCalls + pendingTools.size > run.budget.maxToolCalls) {
                run.state = RunState.BUDGET_EXHAUSTED
                emit(ModelEvent.Failed("Tool call budget exhausted"))
                return@flow
            }
            val broker = tools
            if (broker == null) {
                run.state = RunState.FAILED
                emit(ModelEvent.Failed("This model cannot execute tools"))
                return@flow
            }
            val toolMessages = mutableListOf<Map<String, String>>()
            for (call in pendingTools.values) {
                run.toolCalls += 1
                run.state = RunState.TOOL_EXECUTING
                when (val result = broker.invoke(call)) {
                    ToolResult.NeedsApproval -> {
                        run.state = RunState.WAITING_TOOL_APPROVAL
                        emit(ModelEvent.Failed("Tool ${call.name} needs user confirmation"))
                        return@flow
                    }
                    is ToolResult.Denied -> {
                        run.state = RunState.FAILED
                        emit(ModelEvent.Failed(result.reason))
                        return@flow
                    }
                    is ToolResult.Invalid -> {
                        toolMessages += mapOf("role" to "tool", "content" to result.reason, "tool_call_id" to call.callId)
                    }
                    is ToolResult.Value -> {
                        toolMessages += mapOf(
                            "role" to "tool",
                            "content" to SecretRedactor.redact(result.json, secretsForRedaction()),
                            "tool_call_id" to call.callId,
                        )
                    }
                }
            }
            messages += toolMessages.map { it.toMutableMap() }
        }
    }
}
