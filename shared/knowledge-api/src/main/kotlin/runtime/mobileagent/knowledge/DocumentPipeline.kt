// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/** Local retrieval representation version, independent of planner and provider result versions. */
const val PIPELINE_CHUNK_VERSION = "retrieval-chunks-v1"

enum class PipelineAttemptState { READY, DISPATCHED, SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME }

/** V1 intentionally executes serially. Reservations are safety limits, never reported usage. */
data class PipelinePolicy(
    val maxConcurrency: Int = 1,
    val consecutiveFailureLimit: Int = 3,
    val tokenDispatchCeiling: Long? = null,
    val reservationTokensPerRequest: Long? = null,
) {
    init {
        require(maxConcurrency == 1) { "This executor supports maximum concurrency 1" }
        require(consecutiveFailureLimit > 0)
        require(tokenDispatchCeiling == null || tokenDispatchCeiling > 0)
        require(reservationTokensPerRequest == null || reservationTokensPerRequest > 0)
        require(tokenDispatchCeiling == null || reservationTokensPerRequest != null) {
            "A token dispatch ceiling requires an explicit conservative per-request reservation"
        }
    }
}

data class PipelineUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** A subset of outputTokens; excluded from totalTokens. */
    val reasoningTokens: Long? = null,
    val attempts: Int = 0,
    val unknownUsageAttempts: Int = 0,
    val reservedTokens: Long = 0,
) {
    /** Null if any attempt lacks input/output usage; observed sums remain visible separately. */
    val totalTokens: Long? get() = if (unknownUsageAttempts == 0 && inputTokens != null && outputTokens != null)
        inputTokens + outputTokens else null
}

data class PipelineProgress(
    val files: Int = 0,
    val completedFiles: Int = 0,
    val pages: Int = 0,
    val units: Int = 0,
    val pending: Int = 0,
    val inFlight: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val unknown: Int = 0,
    val published: Int = 0,
    val legacyUnplannedFiles: Int = 0,
    val usage: PipelineUsage = PipelineUsage(),
)

data class PipelineReuseSummary(
    val directReuse: Int = 0,
    val localRebuild: Int = 0,
    val newRequests: Int = 0,
    val unknown: Int = 0,
    val unplannedFiles: Int = 0,
    val plannerVersion: String = "",
    val chunkVersion: String = PIPELINE_CHUNK_VERSION,
)
