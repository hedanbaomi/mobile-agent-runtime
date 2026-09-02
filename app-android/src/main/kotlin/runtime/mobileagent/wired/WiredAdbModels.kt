// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import runtime.mobileagent.bridge.BridgeProtocol

/**
 * Lifecycle is deliberately independent from trust and transport
 * availability. A trusted desktop may be disconnected without being
 * forgotten, and a disabled user intent never causes an automatic fallback.
 */
enum class WiredAdbLifecycleState {
    UNPAIRED,
    PAIRING,
    TRUSTED,
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    READY,
    REAUTH_REQUIRED,
}

enum class WiredAdbUserIntent { ENABLED, DISABLED }

enum class WiredAdbPlatformGrant { UNKNOWN, GRANTED, DENIED, REVOKED }

enum class WiredAdbAvailability { READY, TEMPORARILY_UNAVAILABLE, UNSUPPORTED }

enum class WiredAdbConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DEGRADED }

/** Only binding metadata and a Keystore reference are persisted. */
data class WiredAdbTrustRecord(
    val desktopId: String,
    val appInstanceId: String,
    val serialFingerprint: String,
    val protocolVersion: Int,
    val secretRef: String,
    /** Non-secret pairing transcript hash required to derive fresh session keys. */
    val transcriptHash: String,
) {
    init {
        requireIdentityPart(desktopId, "desktopId")
        requireIdentityPart(appInstanceId, "appInstanceId")
        requireFingerprint(serialFingerprint)
        require(protocolVersion == BridgeProtocol.VERSION) { "unsupported bridge protocol version" }
        requireIdentityPart(secretRef, "secretRef")
        requireFingerprint(transcriptHash)
    }
}

/** UI/policy status never contains an endpoint, serial, command, path, or secret. */
data class WiredAdbStatus(
    val state: WiredAdbLifecycleState,
    val userIntent: WiredAdbUserIntent,
    val platformGrant: WiredAdbPlatformGrant,
    val availability: WiredAdbAvailability,
    val connection: WiredAdbConnectionState,
    val trusted: Boolean,
    val desktopId: String? = null,
    val appInstanceId: String? = null,
    val serialFingerprint: String? = null,
    val protocolVersion: Int? = null,
    val lastError: WiredAdbErrorCode? = null,
)

/** A foreground-only value; only its redacted display representation crosses the UI port. */
class WiredAdbPairingPrompt internal constructor(
    private val displayToken: String,
    val expiresAtEpochMs: Long,
    val remainingAttempts: Int,
) {
    init {
        require(displayToken.length == PAIR_TOKEN_BYTES * 2)
        require(displayToken.all { it in "0123456789abcdef" })
        require(expiresAtEpochMs > 0)
        require(remainingAttempts in 1..PAIR_MAX_ATTEMPTS)
    }

    fun tokenDisplay(): String = displayToken

    override fun toString(): String =
        "WiredAdbPairingPrompt(expiresAtEpochMs=$expiresAtEpochMs, remainingAttempts=$remainingAttempts)"
}

/** Runtime-owned request ID. There is no public constructor accepting model input. */
@JvmInline
value class WiredAdbRequestId internal constructor(val value: String) {
    init {
        require(value.length in 1..BridgeProtocol.MAX_REQUEST_ID_BYTES)
        require(value.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' })
    }

    override fun toString(): String = value
}

internal fun newWiredAdbRequestId(): WiredAdbRequestId = WiredAdbRequestId(UUID.randomUUID().toString())

enum class WiredAdbFileOperation {
    LIST,
    STAT,
    READ_TEXT,
    WRITE_TEXT,
    APPLY_PATCH,
    CREATE_DIRECTORY,
    MOVE,
    DELETE,
}

/** Typed request; authority/transport details are intentionally absent. */
class WiredAdbFileRequest internal constructor(
    val requestId: WiredAdbRequestId,
    val operation: WiredAdbFileOperation,
    val relativePath: String?,
    val destinationRelativePath: String? = null,
    val contentUtf8: ByteArray? = null,
    val replaceExisting: Boolean = false,
    val maxBytes: Int = WIRED_DEFAULT_READ_BYTES,
    /** Opaque continuation token; it is never decoded outside the helper. */
    val cursor: String? = null,
    val maxEntries: Int = WIRED_MAX_DIRECTORY_ENTRIES,
    val offsetBytes: Long = 0L,
    val patchUtf8: ByteArray? = null,
    val expectedVersion: Long? = null,
    val patchFormat: WiredAdbPatchFormat = WiredAdbPatchFormat.UNIFIED_DIFF,
    /** Authenticated binding supplied only by the private helper envelope. */
    internal val workspaceBinding: String? = null,
) {
    init {
        require(relativePath != null || operation == WiredAdbFileOperation.LIST)
        require(destinationRelativePath == null || destinationRelativePath.isNotEmpty())
        require(contentUtf8 == null || contentUtf8.size <= WIRED_MAX_FILE_BYTES)
        require(maxBytes in 1..WIRED_MAX_READ_BYTES)
        require(cursor == null || cursor.length in 1..WIRED_MAX_CURSOR_BYTES)
        require(cursor == null || cursor.all { it.code in 0x21..0x7e })
        require(maxEntries in 1..WIRED_MAX_DIRECTORY_ENTRIES)
        require(offsetBytes >= 0L)
        require(patchUtf8 == null || patchUtf8.size <= WIRED_MAX_PATCH_BYTES)
        require(expectedVersion == null || expectedVersion >= 0L)
        require(
            (operation == WiredAdbFileOperation.APPLY_PATCH) ==
                (patchUtf8 != null && expectedVersion != null),
        )
        require(operation == WiredAdbFileOperation.READ_TEXT || offsetBytes == 0L)
        require(operation == WiredAdbFileOperation.LIST || cursor == null)
    }

    fun contentCopy(): ByteArray? = contentUtf8?.copyOf()

    override fun toString(): String =
        "WiredAdbFileRequest(operation=$operation, requestIdHash=${requestId.value.hashCode()}, maxBytes=$maxBytes)"
}

/** Shell text exists only inside an encrypted shared-protocol request payload. */
class WiredAdbShellRequest internal constructor(
    val requestId: WiredAdbRequestId,
    val command: String,
    val cwd: String?,
    val timeoutMs: Long,
    val maxOutputBytes: Long,
) {
    init {
        require(command.isNotEmpty() && !command.contains('\u0000'))
        require(command.toByteArray(StandardCharsets.UTF_8).size <= WIRED_ADB_MAX_COMMAND_BYTES)
        require(cwd == null || (!cwd.contains('\u0000') && cwd.toByteArray(StandardCharsets.UTF_8).size <= WIRED_ADB_MAX_CWD_BYTES))
        require(timeoutMs in 1..WIRED_ADB_MAX_SHELL_TIMEOUT_MS)
        require(maxOutputBytes in 1..WIRED_ADB_MAX_SHELL_OUTPUT_BYTES)
    }

    override fun toString(): String =
        "WiredAdbShellRequest(requestIdHash=${requestId.value.hashCode()}, timeoutMs=$timeoutMs, maxOutputBytes=$maxOutputBytes)"
}

data class WiredAdbFileEntry(
    val relativePath: String,
    val type: WiredAdbEntryType,
    val bytes: Long? = null,
    val version: Long? = null,
)

enum class WiredAdbEntryType { FILE, DIRECTORY }

data class WiredAdbFileResult(
    val operation: WiredAdbFileOperation,
    val relativePath: String?,
    val entries: List<WiredAdbFileEntry> = emptyList(),
    val text: String? = null,
    val bytes: Long? = null,
    val created: Boolean? = null,
    val replaced: Boolean? = null,
    val deleted: Boolean? = null,
    /** A bounded directory page may have more entries on the device. */
    val truncated: Boolean = false,
    /** Opaque continuation token, valid only for the same authenticated workspace binding. */
    val nextCursor: String? = null,
    /** Stable metadata/version token for stat/read/conditional patch. */
    val version: Long? = null,
    /** Byte offset and total size for a chunked text read. */
    val offsetBytes: Long = 0L,
    val totalBytes: Long? = null,
    val eof: Boolean = true,
)

enum class WiredAdbPatchFormat { UNIFIED_DIFF, REPLACE }

data class WiredAdbShellResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val durationMs: Long,
) {
    fun stdoutCopy(): ByteArray = stdout.copyOf()
    fun stderrCopy(): ByteArray = stderr.copyOf()

    override fun toString(): String =
        "WiredAdbShellResult(exitCode=$exitCode, timedOut=$timedOut, cancelled=$cancelled, durationMs=$durationMs)"
}

enum class WiredAdbErrorCode {
    PAIRING_REQUIRED,
    PAIRING_EXPIRED,
    PAIRING_ATTEMPTS_EXCEEDED,
    PAIRING_REJECTED,
    BRIDGE_NOT_PAIRED,
    BRIDGE_DISCONNECTED,
    BRIDGE_PROTOCOL_MISMATCH,
    BRIDGE_AUTH_FAILED,
    BRIDGE_BINDING_MISMATCH,
    BRIDGE_SECRET_UNAVAILABLE,
    BRIDGE_ALREADY_CONNECTED,
    AUTHORITY_USER_DISABLED,
    AUTHORITY_NOT_SELECTED,
    AUTHORITY_TEMPORARILY_UNAVAILABLE,
    AUTHORITY_UNSUPPORTED,
    SHELL_CAPABILITY_DENIED,
    REQUEST_INVALID,
    REQUEST_IN_FLIGHT,
    REQUEST_CANCELLED,
    PROTOCOL_FRAME_TOO_LARGE,
    PROTOCOL_FRAME_INVALID,
    PROTOCOL_REPLAY,
    PROTOCOL_AUTH_FAILED,
    PROTOCOL_NO_COMPRESSION,
    WORKSPACE_BINDING_INVALID,
    WORKSPACE_BINDING_REPLAYED,
    WORKSPACE_LOCATOR_INVALID,
    WORKSPACE_NOT_FOUND,
    CONFLICT,
    OFFSET_OUT_OF_RANGE,
    INVALID_PATCH,
    INVALID_CURSOR,
    ATOMIC_REPLACE_UNAVAILABLE,
    FULL_DEVICE_GRANT_REQUIRED,
    ROOT_BACKEND_UNAVAILABLE,
    ROOT_PATH_INVALID,
    UNKNOWN_OUTCOME,
    TIMEOUT,
    IO_ERROR,
    INTERNAL_ERROR,
}

sealed interface WiredAdbResult<out T> {
    data class Success<T>(val value: T) : WiredAdbResult<T>
    data class Failure(val code: WiredAdbErrorCode, val retryable: Boolean = false) : WiredAdbResult<Nothing>
}

interface WiredAdbTrustStore {
    fun load(): WiredAdbTrustRecord?
    fun save(record: WiredAdbTrustRecord)
    fun clear()
}

interface WiredAdbIntentStore {
    fun load(): WiredAdbUserIntent
    fun save(intent: WiredAdbUserIntent)
}

class InMemoryWiredAdbIntentStore(initial: WiredAdbUserIntent = WiredAdbUserIntent.DISABLED) : WiredAdbIntentStore {
    @Volatile private var value = initial

    override fun load(): WiredAdbUserIntent = value
    override fun save(intent: WiredAdbUserIntent) { value = intent }
}

/** Implementations store bytes in an independent Keystore alias, never in a DB row. */
interface WiredAdbSecretStore {
    suspend fun put(secretRef: String, secret: ByteArray)
    suspend fun resolve(secretRef: String): ByteArray?
    suspend fun remove(secretRef: String)
}

data class WiredAdbSecretBinding(
    val appInstanceId: String,
    val desktopId: String,
    val serialFingerprint: String,
    val protocolVersion: Int,
    val transcriptHash: String,
) {
    init {
        requireIdentityPart(appInstanceId, "appInstanceId")
        requireIdentityPart(desktopId, "desktopId")
        requireFingerprint(serialFingerprint)
        require(protocolVersion == BridgeProtocol.VERSION) { "unsupported bridge protocol version" }
        requireFingerprint(transcriptHash)
    }
}

/** Optional stronger contract used by the Android Keystore adapter for AAD binding. */
interface WiredAdbBoundSecretStore : WiredAdbSecretStore {
    suspend fun putBound(secretRef: String, secret: ByteArray, binding: WiredAdbSecretBinding)
    suspend fun resolveBound(secretRef: String, binding: WiredAdbSecretBinding): ByteArray?
}

interface WiredAdbChannel : AutoCloseable {
    fun readFrame(): ByteArray
    fun writeFrame(frame: ByteArray)

    /**
     * Installs an absolute read deadline for the next frame. Queue/test
     * channels may ignore it; the production socket enforces it per read.
     */
    fun setReadDeadline(deadlineEpochMs: Long) = Unit

    override fun close()
}

fun interface WiredAdbLoopbackConnector {
    fun connect(address: String, port: Int): WiredAdbChannel
}

fun interface WiredAdbClock { fun nowEpochMs(): Long }

fun interface WiredAdbRandom { fun nextBytes(size: Int): ByteArray }

fun interface WiredAdbDiagnosticSink { fun record(event: WiredAdbDiagnosticEvent) }

/** Only typed, non-sensitive diagnostic dimensions are exposed. */
data class WiredAdbDiagnosticEvent(
    val state: WiredAdbLifecycleState? = null,
    val operation: String,
    val outcome: String,
    val error: WiredAdbErrorCode? = null,
    val requestIdHash: String? = null,
    val durationMs: Long? = null,
    val bytes: Long? = null,
)

internal fun WiredAdbRequestId.safeHash(): String = safeHash(value)

internal fun safeHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .copyOfRange(0, 8)
    .toHex()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun requireIdentityPart(value: String, field: String) {
    require(value.isNotBlank() && value.length <= 256) { "$field is invalid" }
    require(value.none { it.code < 0x20 || it == '\u007f' || it == '\u0000' || it.isWhitespace() }) {
        "$field contains control/whitespace"
    }
}

internal fun requireFingerprint(value: String) {
    require(value.length == BridgeProtocol.SERIAL_FINGERPRINT_BYTES * 2) { "serial fingerprint is invalid" }
    require(value.all { it in "0123456789abcdefABCDEF" }) { "serial fingerprint is invalid" }
}

internal const val WIRED_ADB_PROTOCOL_VERSION = BridgeProtocol.VERSION
internal const val WIRED_WORKSPACE_ID = "wired-adb"
internal const val WIRED_ADB_LOOPBACK_ADDRESS = "127.0.0.1"
internal const val WIRED_ADB_LOOPBACK_PORT = 38_765
internal const val WIRED_ADB_MAX_FRAME_BYTES = BridgeProtocol.MAX_FRAME_BYTES
internal const val WIRED_ADB_MAX_PAYLOAD_BYTES = BridgeProtocol.MAX_PAYLOAD_BYTES
internal const val WIRED_ADB_HANDSHAKE_TIMEOUT_MS = 15_000L
internal const val WIRED_ADB_FILE_READ_DEADLINE_MS = 30_000L
internal const val WIRED_ADB_READ_DEADLINE_GRACE_MS = 10_000L
internal const val WIRED_ADB_MAX_COMMAND_BYTES = 64 * 1024
internal const val WIRED_ADB_MAX_CWD_BYTES = 4 * 1024
internal const val WIRED_ADB_MAX_SHELL_TIMEOUT_MS = 5 * 60 * 1000L
internal const val WIRED_ADB_MAX_SHELL_OUTPUT_BYTES = 1L * 1024 * 1024
internal const val WIRED_MAX_FILE_BYTES = 256 * 1024
internal const val WIRED_MAX_READ_BYTES = 256 * 1024
internal const val WIRED_MAX_PATCH_BYTES = 768 * 1024
internal const val WIRED_MAX_CURSOR_BYTES = 512
internal const val WIRED_DEFAULT_READ_BYTES = WIRED_MAX_READ_BYTES
internal const val WIRED_MAX_TOTAL_BYTES = 4L * 1024 * 1024
internal const val WIRED_MAX_FILES = 128
internal const val WIRED_MAX_ENTRIES = 512
internal const val WIRED_MAX_DIRECTORY_ENTRIES = 256
internal const val WIRED_MAX_REQUEST_TOMBSTONES = 4_096
internal const val WIRED_MAX_PATH_BYTES = 512
internal const val WIRED_MAX_SEGMENT_BYTES = 120
internal const val WIRED_MAX_PATH_DEPTH = 16
internal const val PAIR_TOKEN_BYTES = BridgeProtocol.TOKEN_BYTES
internal const val PAIR_TOKEN_TTL_MS = BridgeProtocol.PAIRING_TTL_MILLIS
/** Pairing policy is owned by shared:bridge-protocol; it must be five for v3. */
internal const val PAIR_MAX_ATTEMPTS = BridgeProtocol.PAIRING_MAX_ATTEMPTS

internal val DEFAULT_WIRED_CLOCK = WiredAdbClock { System.currentTimeMillis() }
internal val DEFAULT_WIRED_RANDOM = WiredAdbRandom { size ->
    require(size >= 0)
    ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }
}
internal val NOOP_WIRED_DIAGNOSTICS = WiredAdbDiagnosticSink { }
