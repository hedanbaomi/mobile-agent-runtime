// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import kotlinx.serialization.Serializable

/**
 * Stable, transport-neutral errors returned by the tooling boundary.
 *
 * Error codes are part of the model/runtime protocol.  They deliberately do
 * not contain implementation exception names, host paths, commands, tokens,
 * or stack traces.
 */
@Serializable
enum class ToolErrorCode {
    CAPABILITY_DENIED,
    APPROVAL_REQUIRED,
    APPROVAL_DENIED,

    WORKSPACE_NOT_FOUND,
    WORKSPACE_READ_ONLY,
    PATH_OUT_OF_SCOPE,
    SYMLINK_FORBIDDEN,
    ROOT_OPERATION_FORBIDDEN,
    FILE_TOO_LARGE,
    QUOTA_EXCEEDED,
    CONFLICT,

    AUTHORITY_NOT_GRANTED,
    AUTHORITY_PROVIDER_NOT_SELECTED,
    AUTHORITY_TEMPORARILY_UNAVAILABLE,

    SHIZUKU_PERMISSION_DENIED,
    SHIZUKU_SERVICE_UNAVAILABLE,

    BRIDGE_NOT_PAIRED,
    BRIDGE_DISCONNECTED,
    BRIDGE_PROTOCOL_MISMATCH,

    ADB_DEVICE_UNAUTHORIZED,
    ADB_DEVICE_OFFLINE,
    ADB_DEVICE_DISCONNECTED,
    ADB_APP_NOT_INSTALLED,

    DANGEROUS_MODE_DISABLED,
    SHELL_CAPABILITY_DENIED,
    SHELL_HIGH_RISK_APPROVAL_REQUIRED,
    SHELL_EXECUTION_FAILED,
    SHELL_TIMED_OUT,
    SHELL_CANCELLED,
    SHELL_OUTPUT_TRUNCATED,

    TIMEOUT,
    IO_ERROR,
    INTERNAL_ERROR,
    UNKNOWN_OUTCOME,

    SNAPSHOT_STALE,
    CALL_ID_REPLAY,
    INVALID_REQUEST,
    AUDIT_UNAVAILABLE,
    AUDIT_FUSE_OPEN,
}

/**
 * A stable error value.  [message] is already safe for user/model display;
 * callers should use [fromThrowable] for untrusted failures so exception
 * messages never cross the tooling boundary.
 */
@Serializable
data class ToolError(
    val code: ToolErrorCode,
    val message: String = code.name,
    val retryable: Boolean = false,
    val userAction: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(message.isNotBlank()) { "Tool error message must not be blank" }
        require(message.length <= MAX_MESSAGE_LENGTH) { "Tool error message is too long" }
        require(userAction == null || userAction.length <= MAX_MESSAGE_LENGTH) { "Tool error action is too long" }
        require(details.size <= MAX_DETAILS) { "Too many tool error details" }
        require(details.keys.all { it.matches(SAFE_DETAIL_KEY) }) {
            "Tool error detail keys must be stable identifiers"
        }
        require(details.values.all { it.length <= MAX_DETAIL_LENGTH }) {
            "Tool error detail values are too long"
        }
    }

    /** Domain-compatible spelling used by persistence adapters. */
    val userMessage: String
        get() = message

    val wireCode: String
        get() = code.name

    /** Convert this error to the stable wire-shaped envelope. */
    fun envelope(): ErrorEnvelope = ErrorEnvelope(
        success = false,
        error = code,
        retryable = retryable,
        userAction = userAction,
        details = details.toMap(),
    )

    companion object {
        private const val MAX_MESSAGE_LENGTH = 512
        private const val MAX_DETAIL_LENGTH = 256
        private const val MAX_DETAILS = 32
        private val SAFE_DETAIL_KEY = Regex("[a-z][a-z0-9_]{0,63}")

        /**
         * Convert an untrusted Throwable without exposing its message or
         * stack.  The type is intentionally reduced to a short category.
         */
        fun fromThrowable(
            throwable: Throwable,
            code: ToolErrorCode = ToolErrorCode.INTERNAL_ERROR,
            retryable: Boolean = false,
        ): ToolError {
            val category = throwable::class.simpleName
                ?.lowercase()
                ?.replace(Regex("[^a-z0-9]+"), "_")
                ?.trim('_')
                ?.takeIf { it.isNotBlank() }
                ?.take(96)
                ?: "unknown_failure"
            return ToolError(code, message = "Tool operation failed", retryable = retryable, details = mapOf("failure" to category))
        }

        fun capabilityDenied() = ToolError(ToolErrorCode.CAPABILITY_DENIED)

        fun approvalRequired() = ToolError(ToolErrorCode.APPROVAL_REQUIRED, retryable = false)

        fun approvalDenied() = ToolError(ToolErrorCode.APPROVAL_DENIED)

        fun unknownOutcome() = ToolError(ToolErrorCode.UNKNOWN_OUTCOME, retryable = false)
    }
}

/**
 * Stable response envelope.  It intentionally contains no backend-specific
 * fields and is safe to serialize by a platform adapter.
 */
@Serializable
data class ErrorEnvelope(
    val success: Boolean,
    val error: ToolErrorCode,
    val retryable: Boolean,
    val userAction: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(!success) { "ErrorEnvelope must represent a failed operation" }
        require(userAction == null || userAction.length <= 512)
        require(details.size <= 32)
        require(details.keys.all { it.matches(SAFE_DETAIL_KEY) })
    }

    /** Protocol spelling used by JSON adapters. */
    val user_action: String?
        get() = userAction

    companion object {
        private val SAFE_DETAIL_KEY = Regex("[a-z][a-z0-9_]{0,63}")

        fun of(error: ToolError): ErrorEnvelope = error.envelope()
    }
}
