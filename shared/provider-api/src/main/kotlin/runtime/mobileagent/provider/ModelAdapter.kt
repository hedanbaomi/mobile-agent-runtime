// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import runtime.mobileagent.domain.ModelProfile

/**
 * A provider connection check is deliberately separate from capability
 * discovery.  It answers only whether the current model configuration can
 * complete one minimal chat request; it does not claim anything about
 * streaming, tool calls, or image input.
 */
sealed interface ProviderConnectionResult {
    data class Success(
        val latencyMs: Long,
        val providerReachable: Boolean = true,
        val authenticated: Boolean = true,
        val modelAccepted: Boolean = true,
        /** A successful chat POST may still have incurred a small provider charge. */
        val charged: Boolean = true,
    ) : ProviderConnectionResult

    data class Failure(
        val code: ProviderConnectionErrorCode,
        val httpStatus: Int? = null,
        val retryable: Boolean,
        /** True only when a request may have crossed the provider boundary. */
        val charged: Boolean = false,
    ) : ProviderConnectionResult
}

enum class ProviderConnectionErrorCode {
    NETWORK_UNREACHABLE,
    TLS_FAILURE,
    TIMEOUT,
    AUTH_FAILED,
    ENDPOINT_UNSUPPORTED,
    MODEL_NOT_FOUND,
    RATE_LIMITED,
    FEATURE_UNSUPPORTED,
    PROVIDER_REJECTED,
    INVALID_RESPONSE,
    CONFIG_INVALID,
    CREDENTIAL_UNAVAILABLE,
    UNKNOWN,
}

enum class CapabilityCheck {
    METADATA,
    STREAM,
    TOOLS,
    IMAGE,
}

enum class CapabilityCheckStatus {
    VERIFIED,
    UNSUPPORTED,
    NOT_DECLARED,
    NOT_RUN,
    FAILED,
    UNKNOWN,
}

data class CapabilityCheckResult(
    val capability: CapabilityCheck,
    val status: CapabilityCheckStatus,
    val httpStatus: Int? = null,
)

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
    /** Structured per-capability results; [source] remains a legacy audit summary. */
    val checks: List<CapabilityCheckResult> = emptyList(),
)

enum class CapabilityProbeStatus { PROFILE_ONLY, SUCCEEDED, PARTIAL, FAILED }

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

/**
 * Provider-private continuation payload for stateless multi-round runs.
 *
 * This is transport data, not model output: it carries items such as an
 * OpenAI Responses `reasoning` output item with `encrypted_content` so the
 * next tool-loop round can replay them verbatim.  It must never enter
 * assistant visible text, reasoning summary UI, request previews,
 * diagnostics, persisted history, or logs.  Adapters that do not understand
 * the channel (for example Chat Completions) ignore it.
 */
data class ProviderContinuationItem(
    val itemId: String? = null,
    val encryptedContent: String,
) {
    init {
        require(encryptedContent.isNotBlank()) { "Continuation content must not be blank" }
        require(encryptedContent.length <= MAX_CONTINUATION_CHARS) {
            "Continuation content exceeds the transport limit"
        }
    }

    override fun toString(): String = "ProviderContinuationItem(<redacted>)"

    companion object {
        const val MAX_CONTINUATION_CHARS = 32 * 1024
    }
}

data class ChatMessage(
    val role: String,
    val text: String = "",
    val images: List<InlineImage> = emptyList(),
    val toolCallId: String? = null,
    val toolCalls: List<AssistantToolCall> = emptyList(),
    /**
     * Provider-private continuation items replayed verbatim on the next
     * request of the same run.  Never rendered, previewed, logged, or
     * persisted; only the owning provider adapter encodes them.
     */
    val providerContinuationItems: List<ProviderContinuationItem> = emptyList(),
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
    /**
     * A reasoning delta is emitted only when the provider explicitly sends a
     * `reasoning_content` or `reasoning` field.  Adapters must not synthesize
     * hidden reasoning from ordinary answer text.
     */
    data class ReasoningDelta(val text: String) : ModelEvent
    /**
     * A model refusal is assistant/provider output, not a transport failure.
     * It renders as readable assistant output and persists like ordinary
     * answer text; it is never mixed into reasoning.
     */
    data class RefusalDelta(val text: String) : ModelEvent
    /**
     * Provider-private continuation captured from one round and replayed on
     * the next request of the same run.  Runtimes forward it to the owning
     * adapter's transport encoding; it is never user-visible.
     */
    data class ProviderContinuation(val item: ProviderContinuationItem) : ModelEvent
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
     * Test the same configured adapter path with one minimal chat request.
     * Implementations should override this when they can preserve typed HTTP
     * and transport error semantics. The default is intentionally conservative
     * and uses the adapter's regular streaming path, so a third-party adapter
     * cannot accidentally grow a second HTTP client just for diagnostics.
     */
    suspend fun testConnection(
        profile: ModelProfile,
        secret: CharArray,
        operationId: String = "connection-test",
    ): ProviderConnectionResult {
        if (secret.isEmpty()) {
            return ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE,
                retryable = false,
            )
        }
        val request = ModelRequest(
            modelId = profile.modelId,
            messages = listOf(ChatMessage(role = "user", text = "Reply with ok.")),
            stream = true,
            operationId = operationId,
            outputTokenLimit = profile.outputLimit.coerceAtLeast(1),
        )
        val started = System.nanoTime()
        return try {
            val events = mutableListOf<ModelEvent>()
            withTimeoutForConnection {
                events += stream(request, secret).toList()
            }
            val failed = events.filterIsInstance<ModelEvent.Failed>().firstOrNull()
            if (failed != null) {
                ProviderConnectionResult.Failure(
                    code = failed.sanitizedMessage.toProviderConnectionErrorCode(),
                    retryable = failed.sanitizedMessage == ProviderConnectionErrorCode.RATE_LIMITED.name,
                    charged = true,
                )
            } else if (events.any { it is ModelEvent.Completed }) {
                ProviderConnectionResult.Success(elapsedMillis(started))
            } else {
                ProviderConnectionResult.Failure(
                    code = ProviderConnectionErrorCode.INVALID_RESPONSE,
                    retryable = false,
                    charged = true,
                )
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.TIMEOUT,
                retryable = true,
                charged = true,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            ProviderConnectionResult.Failure(
                code = ProviderConnectionErrorCode.UNKNOWN,
                retryable = false,
                charged = true,
            )
        }
    }

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

private const val DEFAULT_CONNECTION_TIMEOUT_MS = 15_000L

private suspend fun <T> withTimeoutForConnection(block: suspend () -> T): T =
    kotlinx.coroutines.withTimeout(DEFAULT_CONNECTION_TIMEOUT_MS) { block() }

private fun elapsedMillis(started: Long): Long =
    ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

private fun String.toProviderConnectionErrorCode(): ProviderConnectionErrorCode =
    when (this) {
        ProviderConnectionErrorCode.AUTH_FAILED.name,
        "PROVIDER_UNAUTHORIZED",
        -> ProviderConnectionErrorCode.AUTH_FAILED
        ProviderConnectionErrorCode.RATE_LIMITED.name -> ProviderConnectionErrorCode.RATE_LIMITED
        ProviderConnectionErrorCode.TIMEOUT.name -> ProviderConnectionErrorCode.TIMEOUT
        ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED.name -> ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED
        ProviderConnectionErrorCode.MODEL_NOT_FOUND.name -> ProviderConnectionErrorCode.MODEL_NOT_FOUND
        ProviderConnectionErrorCode.FEATURE_UNSUPPORTED.name -> ProviderConnectionErrorCode.FEATURE_UNSUPPORTED
        ProviderConnectionErrorCode.NETWORK_UNREACHABLE.name,
        "NETWORK_UNAVAILABLE",
        -> ProviderConnectionErrorCode.NETWORK_UNREACHABLE
        ProviderConnectionErrorCode.INVALID_RESPONSE.name -> ProviderConnectionErrorCode.INVALID_RESPONSE
        else -> ProviderConnectionErrorCode.UNKNOWN
    }
