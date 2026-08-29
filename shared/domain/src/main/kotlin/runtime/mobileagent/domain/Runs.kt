// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/** Persisted run state. The runtime's in-memory state is mapped to this enum by the data layer. */
@Serializable
enum class RunStatus {
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
    BUDGET_EXHAUSTED,
    UNKNOWN_OUTCOME,
}

/** A compatibility alias for clients that use the domain term RunState. */
typealias RunState = RunStatus

@Serializable
data class RunRecord(
    val runId: String,
    val snapshotId: String,
    val conversationId: String,
    val state: RunStatus = RunStatus.CREATED,
    val budgetJson: String = "{}",
    val stopReason: String? = null,
    val errorCode: String? = null,
    val modelRounds: Int = 0,
    val toolCalls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
    /** Set only after the user explicitly acknowledges a retry may duplicate a charge. */
    val retryAcknowledgedAt: String? = null,
)

@Serializable
data class ToolInvocation(
    val invocationId: String,
    val runId: String,
    val callId: String,
    val name: String,
    val argumentsJson: String = "{}",
    val permissionDecision: String = "NOT_REQUESTED",
    val state: String = "PENDING",
    val resultJson: String? = null,
    val errorCode: String? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
)

@Serializable
data class AuditEvent(
    val id: String,
    val runId: String? = null,
    val createdAt: String,
    val component: String,
    val action: String,
    val result: String,
    val errorCode: String? = null,
    val summary: String,
    val inputBytes: Long = 0,
    val outputBytes: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val metadataJson: String = "{}",
)
