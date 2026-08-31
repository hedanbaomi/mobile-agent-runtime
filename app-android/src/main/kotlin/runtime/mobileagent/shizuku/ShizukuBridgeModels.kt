// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

/**
 * A snapshot of the authority checks that are safe to expose to the UI.
 *
 * A snapshot is informational only.  The bridge obtains a fresh snapshot and
 * evaluates it immediately before every UserService dispatch.
 */
data class ShizukuBridgeStatus(
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val serverUid: Int?,
    val serverVersion: Int?,
    val userServiceAlive: Boolean,
    val preV11: Boolean,
    val reason: String,
    /** UID reported by the UserService's Process.myUid() handshake. */
    val userServiceUid: Int? = null,
    /** Protocol version reported by the UserService handshake. */
    val userServiceProtocolVersion: Int? = null,
    /** Opaque per-UserService session identifier from the handshake. */
    val userServiceSessionId: String? = null,
    /** Binder caller UID captured by the UserService handshake. */
    val userServiceCallerUid: Int? = null,
) {
    val trustedServerUid: Boolean
        get() = serverUid == ShizukuBridgePolicy.SHELL_UID

    val trustedUserServiceUid: Boolean
        get() = userServiceUid == ShizukuBridgePolicy.SHELL_UID

    val protocolReady: Boolean
        get() = userServiceProtocolVersion == ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION &&
            !userServiceSessionId.isNullOrBlank() &&
            userServiceCallerUid == android.os.Process.myUid()

    val serverReady: Boolean
        get() = binderAlive && permissionGranted && trustedServerUid &&
            (serverVersion ?: -1) >= ShizukuBridgePolicy.MIN_SERVER_VERSION

    val dispatchReady: Boolean
        get() = serverReady && userServiceAlive && trustedUserServiceUid && protocolReady
}

sealed interface ShizukuGateDecision {
    data object Allowed : ShizukuGateDecision

    data class Denied(val reason: String) : ShizukuGateDecision
}

/** Result of a command dispatched to the optional remote UserService. */
sealed interface ShizukuDispatchResult {
    data class Success(val payload: String) : ShizukuDispatchResult

    /** The operation was not dispatched because a live authority could not be proven. */
    data class Denied(val reason: String) : ShizukuDispatchResult

    /** The operation may have reached the UserService; callers must not replay it. */
    data class Failed(
        val reason: String,
        val unknownOutcome: Boolean,
        /** Bounded provider code from a well-formed rejected operation, if any. */
        val errorCode: String? = null,
    ) : ShizukuDispatchResult
}

/** Permission result delivered by the explicit user-triggered request entry point. */
data class ShizukuPermissionResult(
    val requestCode: Int,
    val granted: Boolean,
)

/**
 * Low-level one-shot shell request.  This is deliberately not an Agent-facing
 * ToolSpec: the upper runtime owns capability, approval and snapshot checks.
 */
data class ShizukuShellRequest(
    val callId: String,
    val command: String,
    val cwd: String? = null,
    val timeoutMs: Long = ShizukuShellLimits.DEFAULT_TIMEOUT_MS,
    val maxStdoutBytes: Int = ShizukuShellLimits.DEFAULT_OUTPUT_BYTES,
    val maxStderrBytes: Int = ShizukuShellLimits.DEFAULT_OUTPUT_BYTES,
)

/** A bounded result assembled from PFD streams and the small completion envelope. */
data class ShizukuShellResult(
    val callId: String,
    val state: State,
    val exitCode: Int? = null,
    val stdout: ByteArray = byteArrayOf(),
    val stderr: ByteArray = byteArrayOf(),
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    /** True means the remote process outcome cannot safely be replayed. */
    val unknownOutcome: Boolean = false,
) {
    enum class State {
        COMPLETED,
        TIMED_OUT,
        CANCELLED,
        DENIED,
        UNKNOWN,
    }
}

/** Shared low-level shell limits; each remote output stream has its own cap. */
object ShizukuShellLimits {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 300_000L
    const val DEFAULT_OUTPUT_BYTES = 1024 * 1024
    const val MAX_OUTPUT_BYTES = 1024 * 1024
    /**
     * The upper runtime receives both streams as one result object.  Leave
     * headroom below its 1 MiB serialization budget for the result envelope
     * and object framing; PFD transport itself is never a Binder byte array.
     */
    const val MAX_SERIALIZED_OUTPUT_BYTES = 1024 * 1024 - 64 * 1024
    const val MAX_COMMAND_BYTES = 256 * 1024
    const val MAX_CWD_BYTES = 4 * 1024
    const val MAX_CALL_ID_BYTES = 256
    const val MAX_GLOBAL_CONCURRENCY = 2
    const val IPC_GRACE_MS = 5_000L

    const val INVALID_REQUEST = "SHELL_INVALID_REQUEST"
    const val INVALID_CWD = "SHELL_INVALID_CWD"
    const val UNAVAILABLE = "SHELL_SERVICE_UNAVAILABLE"
    const val UID_UNTRUSTED = "SHELL_SERVICE_UID_UNTRUSTED"
    const val PROTOCOL_MISMATCH = "SHELL_PROTOCOL_MISMATCH"
    const val CALLER_UNTRUSTED = "SHELL_CALLER_UNTRUSTED"
    const val SESSION_INVALID = "SHELL_SESSION_INVALID"
    const val CONCURRENCY_LIMIT = "SHELL_CONCURRENCY_LIMIT"
    const val REPLAY_DENIED = "SHELL_CALL_REPLAY_DENIED"
    const val TIMED_OUT = "SHELL_TIMED_OUT"
    const val CANCELLED = "SHELL_CANCELLED"
    const val OUTPUT_TRUNCATED = "SHELL_OUTPUT_TRUNCATED"
    const val EXECUTION_FAILED = "SHELL_EXECUTION_FAILED"
}
