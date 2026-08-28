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
    PERMISSION_DENIED,
    UNSUPPORTED_DEPENDENCY,
    RESOURCE_LIMIT,
    UNKNOWN_OUTCOME,
    INDEX_NOT_READY,
    SCHEMA_UNSUPPORTED,
    VISION_REQUIRED,
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
