// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

const val VISION_PROMPT_VERSION = "vision-prompt-v1"
const val VISION_SCHEMA_VERSION = "vision-result-v2"
const val VISION_PREPROCESS_VERSION = "vision-pre-v1"

data class VisionInput(
    val assetHash: String,
    val contextHash: String,
    val modelFingerprint: String,
    val bytes: ByteArray,
    val mediaType: String,
    val surroundingText: String,
    val page: Int?,
    val section: String?,
    /** Correlation only; deliberately excluded from [cacheKey]. */
    val requestId: String = "",
    /** One-based external dispatch attempt; deliberately excluded from [cacheKey]. */
    val attempt: Int = 1,
    /** Progress/terminal diagnostics. Sink failures never affect vision processing. */
    val diagnostics: (VisionDiagnosticMetadata) -> Unit = {},
    /** Explicit local DEBUG opt-in; excluded from [cacheKey]. */
    val captureDiagnosticContent: Boolean = false,
    /** Final pause/cancel gate, invoked immediately before external dispatch; excluded from [cacheKey]. */
    val beforeDispatch: () -> Boolean = { true },
) {
    val cacheKey: String
        get() = sha256Hex(
            "$assetHash|$contextHash|$modelFingerprint|$VISION_PROMPT_VERSION|$VISION_SCHEMA_VERSION|$VISION_PREPROCESS_VERSION"
                .toByteArray(Charsets.UTF_8),
        )
}

data class VisionSuccess(
    val ocrText: String,
    val semanticDescription: String,
    val tableMarkdown: String = "",
    val type: String = "image",
)

sealed interface VisionOutcome {
    data class Success(
        val result: VisionSuccess,
        val metadata: VisionDiagnosticMetadata = VisionDiagnosticMetadata(),
    ) : VisionOutcome
    data object UnknownOutcome : VisionOutcome
    /** Detailed unknown result for a request that may have reached the provider. */
    data class Unknown(val metadata: VisionDiagnosticMetadata) : VisionOutcome
    data class Failed(
        val message: String,
        val metadata: VisionDiagnosticMetadata = VisionDiagnosticMetadata(errorCode = message),
    ) : VisionOutcome
}

enum class VisionDiagnosticPhase {
    VALIDATION,
    REQUEST_READY,
    DISPATCH,
    RESPONSE,
    PARSE,
    TERMINAL,
}

/**
 * Fixed, provider-neutral vision diagnostics. Opt-in [content] may contain the
 * request or response body, but never header/credential values or
 * provider-private reasoning continuation.
 */
data class VisionDiagnosticMetadata(
    val phase: VisionDiagnosticPhase = VisionDiagnosticPhase.VALIDATION,
    val dispatched: Boolean = false,
    val responseReceived: Boolean = false,
    val httpStatus: Int? = null,
    val durationMs: Long = 0,
    val errorCode: String? = null,
    val stage: String? = null,
    val exceptionType: String? = null,
    val finishReason: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val requestId: String = "",
    val attempt: Int = 1,
    val assetHash: String? = null,
    val modelFingerprint: String? = null,
    val contentKind: String? = null,
    val content: String? = null,
    val contentChars: Long? = null,
    val contentBytes: Long? = null,
    val originalContentChars: Long? = null,
    val originalContentBytes: Long? = null,
    val contentTruncated: Boolean = false,
    /** Provider fact; a subset of output, never added to it. Absent stays unknown. */
    val reasoningTokens: Int? = null,
)

fun interface VisionBackend {
    fun process(input: VisionInput): VisionOutcome
}

object VisionCacheKey {
    fun contextHash(surroundingText: String, page: Int?, section: String?): String =
        sha256Hex("${page ?: ""}|${section.orEmpty()}|$surroundingText".toByteArray(Charsets.UTF_8))
}

data class VisionBinding(
    val providerId: String,
    val modelId: String,
    val endpoint: String,
    val revision: Int,
    /** Provider profile revision; defaults to the legacy combined revision. */
    val providerRevision: Int = revision,
    /** Model profile revision; defaults to the legacy combined revision. */
    val modelRevision: Int = revision,
    /** Stable row identity; absent for legacy bindings. */
    val modelProfileId: String? = null,
    /** Hash of the effective non-secret provider/model transport configuration. */
    val configurationHash: String? = null,
) {
    val fingerprint: String
        // Keep path spelling significant.  Only discard redundant trailing
        // separators; callers that want scheme/host canonicalization must do
        // so before constructing the binding.
        get() = buildString {
            append("$providerId|$modelId|${endpoint.trimEnd('/')}|provider:$providerRevision|model:$modelRevision")
            if (modelProfileId != null || configurationHash != null) {
                append("|profile:")
                append(modelProfileId.orEmpty())
                append("|config:")
                append(configurationHash.orEmpty())
            }
        }
}

data class LoadedVisual(
    val assetId: String,
    val mediaType: String,
    val bytes: ByteArray,
)

sealed interface VisualAttachmentPlan {
    data class Complete(val images: List<LoadedVisual>) : VisualAttachmentPlan
    data class Incomplete(val reason: String) : VisualAttachmentPlan
}

object VisualAttachmentPolicy {
    const val MAX_IMAGES = 4
    const val MAX_BYTES = 2 * 1024 * 1024

    fun plan(
        assetIds: List<String>,
        load: (String) -> Pair<String, ByteArray>?,
    ): VisualAttachmentPlan {
        val ids = assetIds.distinct()
        if (ids.isEmpty()) return VisualAttachmentPlan.Complete(emptyList())
        if (ids.size > MAX_IMAGES) {
            return VisualAttachmentPlan.Incomplete(
                "Strict mode cannot silently omit visual hits (${ids.size} images, max $MAX_IMAGES).",
            )
        }
        val images = mutableListOf<LoadedVisual>()
        for (id in ids) {
            val loaded = load(id)
                ?: return VisualAttachmentPlan.Incomplete("Visual asset $id is missing from CAS.")
            if (loaded.second.size > MAX_BYTES) {
                return VisualAttachmentPlan.Incomplete("Visual asset $id exceeds 2 MiB.")
            }
            images += LoadedVisual(id, loaded.first, loaded.second)
        }
        return VisualAttachmentPlan.Complete(images)
    }
}

object StrictVisualPolicy {
    fun allow(
        hasVisualEvidence: Boolean,
        chatSupportsImages: Boolean,
        textDegradationEnabled: Boolean,
    ): StrictVisualDecision {
        if (!hasVisualEvidence) return StrictVisualDecision.Allow(warning = null)
        if (chatSupportsImages) return StrictVisualDecision.Allow(warning = null)
        if (!textDegradationEnabled) {
            return StrictVisualDecision.Reject(
                "Strict mode requires an image-capable chat model for visual evidence. Enable text-only degradation to continue without originals.",
            )
        }
        return StrictVisualDecision.Allow(
            warning = "Original images were not sent. Visual evidence may be incomplete.",
        )
    }
}

sealed interface StrictVisualDecision {
    data class Allow(val warning: String?) : StrictVisualDecision
    data class Reject(val reason: String) : StrictVisualDecision
}
