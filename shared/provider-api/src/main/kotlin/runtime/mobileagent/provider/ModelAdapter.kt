// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import runtime.mobileagent.domain.ModelProfile

data class CapabilityReport(
    val modelId: String,
    val supportsStream: Boolean,
    val supportsTools: Boolean,
    val supportsImages: Boolean,
    val source: String,
    val probedAt: String,
    /** Whether a live request was actually sent and may have incurred provider cost. */
    val charged: Boolean = false,
    /** `PROFILE_ONLY` is the safe result when the user did not grant a live probe. */
    val status: CapabilityProbeStatus = CapabilityProbeStatus.PROFILE_ONLY,
    val operationId: String = "",
)

enum class CapabilityProbeStatus { PROFILE_ONLY, SUCCEEDED, FAILED }

/** A live capability probe is potentially billable and therefore opt-in. */
enum class ProbeConsent { NOT_GRANTED, GRANTED }

/**
 * Values accepted for a user-configured request header.
 *
 * A [SecretRef] is only a reference.  It is resolved by the platform adapter for
 * the concrete host immediately before sending a request and is never serialized
 * into a request body or an inspector event.
 */
sealed interface RequestHeaderValue {
    data class Plain(val value: String) : RequestHeaderValue
    data class SecretRef(val ref: String) : RequestHeaderValue
}

/** Platform boundary for resolving a custom secret header for one destination host. */
fun interface HeaderSecretResolver {
    suspend fun resolve(host: String, secretRef: String): CharArray
}

/**
 * The four user-controlled parameter layers.  Runtime protocol fields are kept
 * separate and are supplied by [ParameterMerger] at the final step.
 */
data class ParameterLayers(
    val adapterDefaults: Map<String, JsonElement> = emptyMap(),
    val modelParameters: Map<String, JsonElement> = emptyMap(),
    val agentOverrides: Map<String, JsonElement> = emptyMap(),
    val customJson: String? = null,
)

data class InlineImage(
    val mediaType: String,
    val base64: String,
    val assetId: String? = null,
)

data class AssistantToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ChatMessage(
    val role: String,
    val text: String = "",
    val images: List<InlineImage> = emptyList(),
    val toolCallId: String? = null,
    val toolCalls: List<AssistantToolCall> = emptyList(),
)

data class ModelRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val tools: List<Map<String, String>> = emptyList(),
    val stream: Boolean = true,
    val extra: Map<String, Any?> = emptyMap(),
    /** Validated parameter layers.  [extra] remains as a source-compatible legacy layer. */
    val parameters: ParameterLayers = ParameterLayers(),
    /** Non-secret headers and secret references; values are resolved at send time. */
    val headers: Map<String, RequestHeaderValue> = emptyMap(),
    val operationId: String = "model-request",
    /** Optional runtime output budget.  Kept last with a default for source compatibility. */
    val outputTokenLimit: Int? = null,
)

sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ToolCallDelta(val callId: String, val name: String, val argumentsJson: String) : ModelEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ModelEvent
    data class ToolApprovalRequired(val callId: String, val name: String, val argumentsJson: String) : ModelEvent
    data object Completed : ModelEvent
    data class Failed(val sanitizedMessage: String) : ModelEvent
}

data class EmbeddingRequest(val modelId: String, val inputs: List<String>)
data class EmbeddingBatch(val vectors: List<FloatArray>, val dimension: Int)

fun interface SecretStore {
    suspend fun resolveForHost(ref: String): CharArray
}

interface ModelAdapter {
    suspend fun probe(profile: ModelProfile): CapabilityReport

    /**
     * Return the same request representation that an adapter would put on the
     * wire, with secrets and inline image bytes removed.  The default keeps
     * third party adapters source compatible; adapters that can provide a
     * preview should override it.  Callers must opt in before retaining it.
     */
    fun previewRequest(request: ModelRequest): String? = null

    /**
     * Perform a capability probe only when the caller has explicit consent.
     * Implementations must not send network traffic for [ProbeConsent.NOT_GRANTED].
     * The default preserves compatibility for adapters that only have a static
     * profile probe.
     */
    suspend fun probe(
        profile: ModelProfile,
        secret: CharArray,
        consent: ProbeConsent,
        operationId: String = "probe",
    ): CapabilityReport {
        val report = probe(profile)
        return if (consent == ProbeConsent.GRANTED) {
            report.copy(operationId = operationId)
        } else {
            report.copy(
                source = "profile-only; explicit probe consent required",
                charged = false,
                status = CapabilityProbeStatus.PROFILE_ONLY,
                operationId = operationId,
            )
        }
    }

    fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent>
    suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch
}
