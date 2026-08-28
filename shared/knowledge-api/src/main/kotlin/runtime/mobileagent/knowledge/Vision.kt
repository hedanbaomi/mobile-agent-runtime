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

data class VisionBinding(
    val providerId: String,
    val modelId: String,
    val endpoint: String,
    val revision: Int,
) {
    val fingerprint: String
        get() = "$providerId|$modelId|${endpoint.trimEnd('/').lowercase()}|$revision"
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
