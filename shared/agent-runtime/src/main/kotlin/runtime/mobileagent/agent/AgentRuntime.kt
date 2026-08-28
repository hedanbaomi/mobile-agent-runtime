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

class AgentRuntime(
    private val adapter: ModelAdapter,
    private val clock: () -> Long = { System.currentTimeMillis() },
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
        val request = ModelRequest(
            modelId = modelId,
            messages = prompt.asMessages(),
            stream = true,
        )
        if (!toolsEnabled && request.tools.isNotEmpty()) {
            run.state = RunState.FAILED
            emit(ModelEvent.Failed("This model cannot execute tools"))
            return@flow
        }
        run.state = RunState.MODEL_STREAMING
        run.modelRounds += 1
        adapter.stream(request, secret).collect { event ->
            if (clock() - run.startedAtMs > run.budget.maxRuntimeMs) {
                run.state = RunState.BUDGET_EXHAUSTED
                run.stopReason = "time"
                emit(ModelEvent.Failed("Run budget exhausted"))
                return@collect
            }
            if (run.modelRounds > run.budget.maxModelRounds) {
                run.state = RunState.BUDGET_EXHAUSTED
                emit(ModelEvent.Failed("Model round budget exhausted"))
                return@collect
            }
            when (event) {
                ModelEvent.Completed -> run.state = RunState.COMPLETED
                is ModelEvent.Failed -> run.state = RunState.FAILED
                else -> Unit
            }
            emit(event)
        }
    }
}
