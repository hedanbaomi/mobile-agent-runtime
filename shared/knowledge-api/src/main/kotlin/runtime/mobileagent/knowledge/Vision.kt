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
    data class Success(val result: VisionSuccess) : VisionOutcome
    data object UnknownOutcome : VisionOutcome
    data class Failed(val message: String) : VisionOutcome
}

fun interface VisionBackend {
    fun process(input: VisionInput): VisionOutcome
}

object VisionCacheKey {
    fun contextHash(surroundingText: String, page: Int?, section: String?): String =
        sha256Hex("${page ?: ""}|${section.orEmpty()}|$surroundingText".toByteArray(Charsets.UTF_8))
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
