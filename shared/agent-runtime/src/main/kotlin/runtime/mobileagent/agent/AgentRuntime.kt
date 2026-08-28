// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.toolSpecsAsMaps

class AgentRuntime(
    private val adapter: ModelAdapter,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tools: ToolBroker? = null,
    private val secretsForRedaction: () -> List<String> = { emptyList() },
    private val onApprove: suspend (ToolCall) -> Boolean = { false },
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
        val messages = prompt.asMessages().toMutableList()
        val toolMaps = if (toolsEnabled && tools != null) toolSpecsAsMaps() else emptyList()
        while (true) {
            if (budgetExhausted(run)) {
                emitBudget(run)
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
                messages = messages.toList(),
                tools = toolMaps,
                stream = true,
            )
            val pendingTools = linkedMapOf<String, ToolCall>()
            val assistantText = StringBuilder()
            var terminal: ModelEvent? = null
            adapter.stream(request, secret).collect { event ->
                if (terminal is ModelEvent.Failed) return@collect
                if (budgetExhausted(run)) {
                    terminal = ModelEvent.Failed("Run budget exhausted")
                    return@collect
                }
                val outgoing = when (event) {
                    is ModelEvent.Failed ->
                        ModelEvent.Failed(redact(event.sanitizedMessage, secret))
                    else -> event
                }
                when (outgoing) {
                    is ModelEvent.ToolCallDelta -> {
                        if (!toolsEnabled || tools == null) {
                            terminal = ModelEvent.Failed("This model cannot execute tools")
                            return@collect
                        }
                        pendingTools[outgoing.callId] = ToolCall(outgoing.callId, outgoing.name, outgoing.argumentsJson)
                    }
                    is ModelEvent.TextDelta -> {
                        assistantText.append(outgoing.text)
                        emit(outgoing)
                    }
                    ModelEvent.Completed -> if (terminal !is ModelEvent.Failed) terminal = outgoing
                    is ModelEvent.Failed -> terminal = outgoing
                    else -> emit(outgoing)
                }
            }
            if (budgetExhausted(run)) {
                run.state = RunState.BUDGET_EXHAUSTED
                run.stopReason = "time"
                emit(ModelEvent.Failed("Run budget exhausted"))
                return@flow
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
            if (!toolsEnabled || tools == null) {
                run.state = RunState.FAILED
                emit(ModelEvent.Failed("This model cannot execute tools"))
                return@flow
            }
            if (run.toolCalls + pendingTools.size > run.budget.maxToolCalls) {
                run.state = RunState.BUDGET_EXHAUSTED
                emit(ModelEvent.Failed("Tool call budget exhausted"))
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
                    emitBudget(run)
                    return@flow
                }
                run.toolCalls += 1
                run.state = RunState.TOOL_EXECUTING
                val result = when (val first = tools.invoke(call)) {
                    ToolResult.NeedsApproval -> {
                        run.state = RunState.WAITING_TOOL_APPROVAL
                        emit(ModelEvent.ToolApprovalRequired(call.callId, call.name, call.argumentsJson))
                        if (!onApprove(call)) {
                            run.state = RunState.FAILED
                            emit(ModelEvent.Failed(redact("Tool ${call.name} was rejected", secret)))
                            return@flow
                        }
                        if (budgetExhausted(run)) {
                            emitBudget(run)
                            return@flow
                        }
                        tools.approve(call.callId)
                    }
                    else -> first
                }
                when (result) {
                    is ToolResult.Denied -> {
                        messages += ChatMessage(
                            role = "tool",
                            text = redact(result.reason, secret),
                            toolCallId = call.callId,
                        )
                    }
                    is ToolResult.Invalid -> {
                        messages += ChatMessage(
                            role = "tool",
                            text = redact(result.reason, secret),
                            toolCallId = call.callId,
                        )
                    }
                    is ToolResult.Value -> {
                        messages += ChatMessage(
                            role = "tool",
                            text = redact(result.json, secret),
                            toolCallId = call.callId,
                        )
                    }
                    ToolResult.NeedsApproval -> {
                        run.state = RunState.FAILED
                        emit(ModelEvent.Failed("Tool ${call.name} needs user confirmation"))
                        return@flow
                    }
                }
            }
        }
    }

    private fun budgetExhausted(run: AgentRun): Boolean =
        clock() - run.startedAtMs > run.budget.maxRuntimeMs

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ModelEvent>.emitBudget(run: AgentRun) {
        run.state = RunState.BUDGET_EXHAUSTED
        run.stopReason = "time"
        emit(ModelEvent.Failed("Run budget exhausted"))
    }

    private fun redact(text: String, secret: CharArray): String =
        SecretRedactor.redact(text, secretsForRedaction() + String(secret))
}
