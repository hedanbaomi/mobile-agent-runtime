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

/**
 * [maxRuntimeMs] is an admission deadline: once it passes, the Runtime starts no new model
 * request, summary, or tool call.  It never cuts off a model stream or tool that has already
 * been dispatched; those end on their own terminal result or on the caller's cancellation.
 * [stallTimeoutMs] bounds the rest: a model stream with no event or transport chunk for that
 * long, or a single tool call that has not returned within it, becomes an unknown outcome that
 * is never replayed.
 */
data class RunBudget(
    val maxModelRounds: Int = 8,
    val maxToolCalls: Int = 20,
    val maxRuntimeMs: Long = 180_000,
    val stallTimeoutMs: Long = 180_000,
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
    /** Included in modelRounds; retained separately so user-visible totals explain extra calls. */
    var compactionRequests: Int = 0,
)
