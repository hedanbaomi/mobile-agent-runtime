// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * The one persisted Run budget.  It is fixed before the Run is created and read
 * back by `RunTools`/the Python broker, so the in-memory caps and the durable
 * ledger cannot drift.
 *
 * [modelInvokeTokens] is the Run's own `model.invoke` authorization resolved by
 * [modelInvokeRunTokens]; a null value means the Run carries no such
 * authorization and every model call from a Skill stays refused.
 */
fun runBudgetJson(
    maxModelRounds: Int,
    maxModelRoundsPerSegment: Int,
    maxCompactionsPerRun: Int,
    maxToolCalls: Int = 20,
    maxRuntimeMs: Int = 180_000,
    modelInvokeTokens: Int? = null,
): String {
    require(maxModelRounds > 0 && maxModelRoundsPerSegment > 0 && maxCompactionsPerRun > 0) {
        "Run budget caps must be positive"
    }
    require(maxToolCalls > 0 && maxRuntimeMs > 0) { "Run budget caps must be positive" }
    require(modelInvokeTokens == null || modelInvokeTokens > 0) { "A Run fee ceiling must be positive" }
    val fields = linkedMapOf<String, JsonPrimitive>(
        "maxModelRounds" to JsonPrimitive(maxModelRounds),
        "maxToolCalls" to JsonPrimitive(maxToolCalls),
        "maxRuntimeMs" to JsonPrimitive(maxRuntimeMs),
        "maxModelRoundsPerSegment" to JsonPrimitive(maxModelRoundsPerSegment),
        "maxCompactionsPerRun" to JsonPrimitive(maxCompactionsPerRun),
    )
    if (modelInvokeTokens != null) fields["maxModelTokens"] = JsonPrimitive(modelInvokeTokens)
    return JsonObject(fields).toString()
}

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
    /**
     * Frozen [RunManifest] JSON for this run.  `"{}"` for runs created before
     * manifests existed or before dispatch froze the facts.  Versions and
     * fingerprints only — never secrets, paths, or provider-private data.
     */
    val manifestJson: String = "{}",
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
