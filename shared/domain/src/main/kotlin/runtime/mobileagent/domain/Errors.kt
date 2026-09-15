// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

enum class ErrorCode {
    INVALID_CONFIG,
    CAPABILITY_MISMATCH,
    SECRET_UNAVAILABLE,
    PROVIDER_UNAUTHORIZED,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    CONTEXT_OVERFLOW,
    /** The provider rejected the request because the input window is too small. */
    INPUT_OVERFLOW,
    /** The output budget ran out with text already produced (partial or complete). */
    OUTPUT_TRUNCATED,
    /** The output budget was consumed by reasoning with no final content. */
    REASONING_EXHAUSTED,
    PERMISSION_DENIED,
    UNSUPPORTED_DEPENDENCY,
    RESOURCE_LIMIT,
    UNKNOWN_OUTCOME,
    INDEX_NOT_READY,
    SCHEMA_UNSUPPORTED,
    VISION_REQUIRED,
    TRANSFER_INVALID,
}

/**
 * Single source of truth for classifying a provider `finish_reason=length` /
 * `incomplete_details.reason=max_output_tokens` stop across both protocols.
 *
 * [visibleAnswer] is true when assistant text or a refusal was already
 * produced.  `reasoningTokens` and `outputTokens` are the provider's own
 * numbers and may be unknown (`null`): unknown is not zero, and hidden
 * reasoning is only blamed when the provider actually reported it.
 */
enum class LengthStopKind { OUTPUT_TRUNCATED, REASONING_EXHAUSTED, EMPTY_RESPONSE }

fun classifyLengthStop(
    visibleAnswer: Boolean,
    reasoningTokens: Int?,
    outputTokens: Int?,
): LengthStopKind = when {
    visibleAnswer -> LengthStopKind.OUTPUT_TRUNCATED
    // Only a positive, actually reported reasoning spend may be called reasoning
    // exhaustion; `null` and `0` both mean "not reported".
    reasoningTokens == null || reasoningTokens <= 0 -> LengthStopKind.EMPTY_RESPONSE
    outputTokens != null && reasoningTokens >= outputTokens -> LengthStopKind.REASONING_EXHAUSTED
    else -> LengthStopKind.OUTPUT_TRUNCATED
}

enum class RetryClass { NONE, USER_ACTION, TRANSIENT, NEVER }

data class AppError(
    val code: ErrorCode,
    val userMessage: String,
    val retryClass: RetryClass,
    val stage: String,
    val operationId: String,
    val sanitizedDetails: String = "",
) {
    fun asException(): AppException = AppException(this)
}

class AppException(val error: AppError) : RuntimeException(error.userMessage)
