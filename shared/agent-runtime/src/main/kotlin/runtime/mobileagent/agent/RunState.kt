// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

enum class RunState {
    CREATED,
    VALIDATING,
    RETRIEVING,
    ASSEMBLING,
    MODEL_STREAMING,
    WAITING_TOOL_APPROVAL,
    TOOL_EXECUTING,
    WAITING_FOR_CONFIGURATION,
    WAITING_FOR_USER,
    COMPLETED,
    CANCELLED,
    FAILED,
    UNKNOWN_OUTCOME,
    BUDGET_EXHAUSTED,
}

data class RunBudget(
    val maxModelRounds: Int = 8,
    val maxToolCalls: Int = 20,
    val maxRuntimeMs: Long = 180_000,
)

data class AgentRun(
    val runId: String,
    val snapshotId: String,
    val conversationId: String,
    var state: RunState = RunState.CREATED,
    val budget: RunBudget = RunBudget(),
    var modelRounds: Int = 0,
    var toolCalls: Int = 0,
    var startedAtMs: Long = 0,
    var stopReason: String? = null,
)
