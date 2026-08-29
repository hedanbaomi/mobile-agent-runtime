// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.provider.ModelEvent as ProviderModelEvent
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult

/** Inputs for the structured runtime boundary. */
data class AgentRuntimeRequest(
    val run: AgentRun,
    val prompt: EffectivePrompt,
    val modelId: String,
    val secret: CharArray,
    val toolsEnabled: Boolean,
    val parameters: ParameterLayers = ParameterLayers(),
    val headers: Map<String, RequestHeaderValue> = emptyMap(),
    val operationId: String = run.runId,
    val emitRequestPreview: Boolean = false,
    /** Optional executor for this run; it takes precedence over constructor compatibility bridges. */
    val executor: ToolExecutor? = null,
    /**
     * Optional visual evidence returned by a tool.  The runtime places every
     * returned image in a following user multimodal message.  The callback is
     * intentionally owned by the caller so policy, asset references and byte
     * budgets remain outside the protocol adapter.
     */
    val toolImages: suspend (ToolCall, ToolResult) -> List<InlineImage> = { _, _ -> emptyList() },
    /** Conservative text UTF-8 units plus 4096 units per image; checked every round. */
    val maxInputBudgetUnits: Long? = null,
    val maxImagesPerRequest: Int = 4,
    val beforeModelRequest: suspend () -> Unit = {},
    val outputTokenLimit: Int? = null,
)

/** A bounded summary safe for persistence and inspector lists. */
data class RuntimeMessageSummary(
    val role: String,
    val textChars: Int,
    val imageAssetIds: List<String>,
    val toolCallIds: List<String>,
)

/** Structured, persistence-friendly runtime events.  Bodies and secrets are opt-in only. */
sealed interface RuntimeEvent {
    data class RunStarted(
        val runId: String,
        val snapshotId: String,
        val conversationId: String,
    ) : RuntimeEvent

    data class RequestPrepared(
        val operationId: String,
        val modelId: String,
        val messages: List<RuntimeMessageSummary>,
        val toolNames: List<String>,
        val parameterKeys: List<String>,
        val headerNames: List<String>,
        /** Null unless the caller explicitly opts into an in-memory inspector preview. */
        val requestPreview: String? = null,
    ) : RuntimeEvent

    data class ModelEvent(
        val event: ProviderModelEvent,
    ) : RuntimeEvent

    data class ToolApprovalRequested(
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : RuntimeEvent

    data class ToolCallObserved(
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : RuntimeEvent

    data class ToolResultProduced(
        val callId: String,
        val name: String,
        val status: String,
        val resultSummary: String,
        /** Complete bounded, redacted result for typed persistence/replay. */
        val resultJson: String = resultSummary,
    ) : RuntimeEvent

    data class ToolImagesAttached(val callId: String, val assets: List<RuntimeImageReference>) : RuntimeEvent

    data class RunFinished(
        val runId: String,
        val state: RunState,
        val stopReason: String?,
        val modelRounds: Int,
        val toolCalls: Int,
    ) : RuntimeEvent
}

data class RuntimeImageReference(val assetId: String, val mediaType: String)

internal fun ChatMessage.toRuntimeSummary(): RuntimeMessageSummary = RuntimeMessageSummary(
    role = role,
    textChars = text.length,
    imageAssetIds = images.mapNotNull { it.assetId },
    toolCallIds = toolCalls.map { it.id } + listOfNotNull(toolCallId),
)

internal fun ParameterLayers.allKeys(): List<String> = buildList {
    addAll(adapterDefaults.keys)
    addAll(modelParameters.keys)
    addAll(agentOverrides.keys)
    if (!customJson.isNullOrBlank()) add("<custom-json>")
}.distinct().sorted()
