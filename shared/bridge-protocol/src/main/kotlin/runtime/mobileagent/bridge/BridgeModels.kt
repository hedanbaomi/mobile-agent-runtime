// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Constants shared by both ends of the loopback bridge. */
object BridgeProtocol {
    /**
     * Version 3 adds connection-scoped typed workspace attachments. Android
     * may submit a user-selected device absolute path only to the encrypted
     * bridge; the model-facing workspace API still exposes an opaque handle
     * and workspace-relative paths. This is intentionally a wire break so a
     * v2 peer can never silently use the new binding semantics.
     */
    const val VERSION: Int = 3
    const val TOKEN_BYTES: Int = 32
    const val NONCE_BYTES: Int = 32
    const val SERIAL_FINGERPRINT_BYTES: Int = 32
    const val SESSION_ID_BYTES: Int = 16
    const val GCM_TAG_BYTES: Int = 16
    const val GCM_KEY_BYTES: Int = 32
    const val GCM_NONCE_PREFIX_BYTES: Int = 4
    const val MAX_FRAME_BYTES: Int = 1_048_576
    const val MAX_PAYLOAD_BYTES: Int = MAX_FRAME_BYTES - 128
    const val MAX_REQUEST_ID_BYTES: Int = 128
    const val MAX_COMMAND_BYTES: Int = 128 * 1024
    const val MAX_WORKSPACE_ID_BYTES: Int = 128
    const val MAX_WORKSPACE_DISPLAY_NAME_BYTES: Int = 256
    const val MAX_DEVICE_PATH_BYTES: Int = 4 * 1024
    const val WORKSPACE_BINDING_BYTES: Int = 32
    const val PAIRING_TTL_MILLIS: Long = 5 * 60 * 1_000L
    const val PAIRING_MAX_ATTEMPTS: Int = 5
    const val PAIRING_RATE_WINDOW_MILLIS: Long = 60 * 1_000L
    const val PAIRING_MAX_ATTEMPTS_PER_WINDOW: Int = 8
    /** A Desktop registration may never extend the App-issued five-minute window. */
    const val PAIRING_MAX_REGISTRATION_WINDOW_MILLIS: Long = PAIRING_TTL_MILLIS
    const val MAX_STATUS_BYTES: Int = 8 * 1024
    const val DOMAIN: String = "MAR-BRIDGE-V3"

    internal val MAGIC: ByteArray = byteArrayOf(0x4d, 0x41, 0x52, 0x42) // MARB
    internal val FRAME_MAGIC: ByteArray = byteArrayOf(0x4d, 0x42, 0x46, 0x33) // MBF3
}

open class BridgeProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

open class BridgeAuthenticationException(message: String, cause: Throwable? = null) :
    SecurityException(message, cause)

class BridgeSequenceException(message: String) : BridgeProtocolException(message)

/** The two independent encryption directions. */
enum class BridgeDirection(val wireValue: Int) {
    C2S(0),
    S2C(1),
    ;

    companion object {
        fun fromWire(value: Int): BridgeDirection = entries.firstOrNull { it.wireValue == value }
            ?: throw BridgeProtocolException("unknown bridge direction")
    }
}

enum class BridgeRole {
    CLIENT,
    SERVER,
    ;

    fun sendDirection(): BridgeDirection = if (this == CLIENT) BridgeDirection.C2S else BridgeDirection.S2C

    fun receiveDirection(): BridgeDirection = if (this == CLIENT) BridgeDirection.S2C else BridgeDirection.C2S
}

/** Frame type is an intentionally closed set. Unknown types are rejected. */
enum class BridgeFrameType(val wireValue: Int) {
    PAIR_HELLO(1),
    PAIR_CHALLENGE(2),
    PAIR_RESPONSE(3),
    PAIR_FINISHED(4),
    SESSION_HELLO(5),
    SESSION_WELCOME(6),
    REQUEST(7),
    RESPONSE(8),
    ERROR(9),
    STATUS(10),
    CANCEL(11),
    ;

    companion object {
        fun fromWire(value: Int): BridgeFrameType = entries.firstOrNull { it.wireValue == value }
            ?: throw BridgeProtocolException("unknown bridge frame type")
    }
}

/** Lifecycle states are closed so a peer cannot smuggle an execution state. */
enum class BridgeRequestState(val wireName: String, val terminal: Boolean) {
    ACCEPTED("accepted", false),
    RUNNING("running", false),
    CANCEL_REQUESTED("cancel_requested", false),
    CANCEL_ACK("cancel_ack", false),
    COMPLETED("completed", true),
    FAILED("failed", true),
    CANCELLED("cancelled", true),
    UNKNOWN_OUTCOME("unknown_outcome", true),
    ;

    companion object {
        fun parse(value: String): BridgeRequestState = entries.firstOrNull { it.wireName == value }
            ?: throw BridgeProtocolException("unknown bridge request state")
    }
}

/** Stable error names shared by Android and Desktop request handlers. */
object BridgeErrorCodes {
    const val REQUEST_REPLAYED = "REQUEST_REPLAYED"
    const val REQUEST_IN_FLIGHT = "REQUEST_IN_FLIGHT"
    const val REQUEST_INVALID = "REQUEST_INVALID"
    const val REQUEST_CAPACITY = "REQUEST_CAPACITY"
    const val REQUEST_CANCELLED = "REQUEST_CANCELLED"
    const val UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME"
    const val UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION"
    const val BRIDGE_DISABLED = "BRIDGE_DISABLED"
    const val BRIDGE_CLOSED = "BRIDGE_CLOSED"
}

/** Only fixed operations are accepted over a bridge request. */
enum class BridgeOperation(val wireName: String) {
    WORKSPACE_LIST("workspace_list"),
    WORKSPACE_ATTACH("workspace_attach"),
    WORKSPACE_BROWSE("workspace_browse"),
    WORKSPACE_RELEASE("workspace_release"),
    FILE_LIST("file_list"),
    FILE_STAT("file_stat"),
    FILE_READ_TEXT("file_read_text"),
    FILE_WRITE_TEXT("file_write_text"),
    FILE_CREATE_DIRECTORY("file_create_directory"),
    FILE_MOVE("file_move"),
    FILE_DELETE("file_delete"),
    MEMORY_READ("memory_read"),
    MEMORY_SEARCH("memory_search"),
    MEMORY_APPEND("memory_append"),
    MEMORY_REPLACE("memory_replace"),
    SHELL_EXEC("shell_exec"),
    ;

    companion object {
        fun parse(value: String): BridgeOperation = entries.firstOrNull { it.wireName == value }
            ?: throw BridgeProtocolException("unknown bridge operation")
    }
}

/** A serial fingerprint is intentionally a digest, never a serial in a request. */
class SerialFingerprint private constructor(private val value: ByteArray) : AutoCloseable {
    init {
        require(value.size == BridgeProtocol.SERIAL_FINGERPRINT_BYTES)
    }

    val size: Int get() = value.size

    fun copyBytes(): ByteArray = synchronized(this) {
        check(!cleared) { "serial fingerprint is cleared" }
        value.copyOf()
    }

    fun withBytes(block: (ByteArray) -> Unit) = synchronized(this) {
        check(!cleared) { "serial fingerprint is cleared" }
        block(value)
    }

    override fun close() = synchronized(this) {
        if (!cleared) {
            Arrays.fill(value, 0)
            cleared = true
        }
    }

    override fun toString(): String = "SerialFingerprint(${BridgeEncoding.hex(copyBytes()).take(12)}…)"

    private var cleared = false

    companion object {
        fun fromSerial(serial: String): SerialFingerprint {
            requireValidSerial(serial)
            return SerialFingerprint(BridgeCrypto.sha256(serial.toByteArray(StandardCharsets.UTF_8)))
        }

        fun fromBytes(bytes: ByteArray): SerialFingerprint = SerialFingerprint(bytes.copyOf()).also {
            require(bytes.size == BridgeProtocol.SERIAL_FINGERPRINT_BYTES)
        }

        internal fun unsafeCopy(bytes: ByteArray): SerialFingerprint = fromBytes(bytes)
    }
}

/** Identity is supplied by the configured desktop/app side, not by an Agent request. */
data class BridgeIdentity(
    val desktopId: String,
    val appInstanceId: String,
    val serialFingerprint: ByteArray,
) {
    init {
        requireIdentityPart(desktopId, "desktopId")
        requireIdentityPart(appInstanceId, "appInstanceId")
        require(serialFingerprint.size == BridgeProtocol.SERIAL_FINGERPRINT_BYTES) {
            "serial fingerprint must be 32 bytes"
        }
    }

    fun copySerialFingerprint(): ByteArray = serialFingerprint.copyOf()

    fun stableKey(): String = buildString {
        append(desktopId)
        append('\u0000')
        append(appInstanceId)
        append('\u0000')
        append(BridgeEncoding.hex(serialFingerprint))
    }

    override fun equals(other: Any?): Boolean = other is BridgeIdentity &&
        desktopId == other.desktopId &&
        appInstanceId == other.appInstanceId &&
        serialFingerprint.contentEquals(other.serialFingerprint)

    override fun hashCode(): Int = 31 * (31 * desktopId.hashCode() + appInstanceId.hashCode()) +
        serialFingerprint.contentHashCode()

    companion object {
        fun forSerial(desktopId: String, appInstanceId: String, serial: String): BridgeIdentity =
            BridgeIdentity(desktopId, appInstanceId, SerialFingerprint.fromSerial(serial).copyBytes())
    }
}

/** Both nonces and every identity field are included in this canonical transcript. */
data class PairingTranscript(
    val protocolVersion: Int,
    val desktopId: String,
    val appInstanceId: String,
    val serialFingerprint: ByteArray,
    val desktopNonce: ByteArray,
    val appNonce: ByteArray,
) {
    init {
        require(protocolVersion == BridgeProtocol.VERSION) { "unsupported bridge protocol version" }
        requireIdentityPart(desktopId, "desktopId")
        requireIdentityPart(appInstanceId, "appInstanceId")
        require(serialFingerprint.size == BridgeProtocol.SERIAL_FINGERPRINT_BYTES)
        require(desktopNonce.size == BridgeProtocol.NONCE_BYTES) { "desktop nonce must be 32 bytes" }
        require(appNonce.size == BridgeProtocol.NONCE_BYTES) { "app nonce must be 32 bytes" }
        require(!desktopNonce.contentEquals(appNonce)) { "pairing nonces must differ" }
    }

    fun canonicalBytes(): ByteArray = BridgeBinaryWriter().apply {
        writeBytes(BridgeProtocol.MAGIC)
        writeU16(protocolVersion)
        writeUtf8(desktopId)
        writeUtf8(appInstanceId)
        writeFixed(serialFingerprint, BridgeProtocol.SERIAL_FINGERPRINT_BYTES)
        writeFixed(desktopNonce, BridgeProtocol.NONCE_BYTES)
        writeFixed(appNonce, BridgeProtocol.NONCE_BYTES)
    }.toByteArray()

    fun hash(): ByteArray = BridgeCrypto.sha256(canonicalBytes())

    fun hashHex(): String = BridgeEncoding.hex(hash())

    companion object {
        fun from(identity: BridgeIdentity, desktopNonce: ByteArray, appNonce: ByteArray): PairingTranscript =
            PairingTranscript(
                BridgeProtocol.VERSION,
                identity.desktopId,
                identity.appInstanceId,
                identity.serialFingerprint.copyOf(),
                desktopNonce.copyOf(),
                appNonce.copyOf(),
            )
    }
}

/** Pairing token bytes are mutable and must be closed by the holder. */
class PairingToken private constructor(
    bytes: ByteArray,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
) : AutoCloseable {
    private val secret = SecretBytes(bytes)

    init {
        require(bytes.size == BridgeProtocol.TOKEN_BYTES) { "pairing token must be 256 bits" }
        require(expiresAtMillis > issuedAtMillis)
        val latestExpiry = if (issuedAtMillis > Long.MAX_VALUE - BridgeProtocol.PAIRING_TTL_MILLIS) {
            Long.MAX_VALUE
        } else {
            issuedAtMillis + BridgeProtocol.PAIRING_TTL_MILLIS
        }
        require(expiresAtMillis <= latestExpiry) { "pairing token TTL exceeds protocol limit" }
    }

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    fun copyBytes(): ByteArray = secret.copyBytes()

    fun <T> use(block: (ByteArray) -> T): T = secret.use(block)

    override fun close() = secret.close()

    override fun toString(): String = "PairingToken(expiresAtMillis=$expiresAtMillis)"

    companion object {
        fun create(
            issuedAtMillis: Long,
            expiresAtMillis: Long,
            randomBytes: ByteArray,
        ): PairingToken {
            require(randomBytes.size == BridgeProtocol.TOKEN_BYTES) { "pairing token must be 256 bits" }
            return PairingToken(randomBytes.copyOf(), issuedAtMillis, expiresAtMillis)
        }

    }
}

/** Mutable secret holder used for token, trust, and session keys. */
class SecretBytes internal constructor(bytes: ByteArray) : AutoCloseable {
    private var cleared = false
    private val bytes = bytes.copyOf()

    val size: Int get() = synchronized(this) { bytes.size }

    fun copyBytes(): ByteArray = synchronized(this) {
        check(!cleared) { "secret is cleared" }
        bytes.copyOf()
    }

    fun <T> use(block: (ByteArray) -> T): T = synchronized(this) {
        check(!cleared) { "secret is cleared" }
        block(bytes)
    }

    override fun close() = synchronized(this) {
        if (!cleared) {
            Arrays.fill(bytes, 0)
            cleared = true
        }
    }

    override fun toString(): String = "SecretBytes(size=${bytes.size})"

    companion object {
        /** Copies caller-owned bytes into a clearable secret holder. */
        fun from(bytes: ByteArray): SecretBytes = SecretBytes(bytes)
    }
}

/**
 * Android's first-pair envelope.  The desktop identity is deliberately not
 * accepted from this message: it is selected and returned by the Companion's
 * challenge.  [tokenHex] is transient wire material and must never be logged
 * or persisted by either endpoint.
 */
@Serializable
data class BridgePairStart(
    val protocolVersion: Int,
    val appInstanceId: String,
    val appNonceHex: String,
    val tokenHex: String,
) {
    override fun toString(): String =
        "BridgePairStart(protocolVersion=$protocolVersion, appInstanceId=$appInstanceId, appNonceHex=<redacted>, tokenHex=<redacted>)"
}

/**
 * Desktop challenge.  All returned identity fields and both nonces are in
 * the canonical transcript hash, so Android never accepts an identity that
 * was not authenticated by the response/finished proof exchange.
 */
@Serializable
data class BridgePairChallenge(
    val protocolVersion: Int,
    val desktopId: String,
    val appInstanceId: String,
    val serialFingerprintHex: String,
    val desktopNonceHex: String,
    val appNonceHex: String,
    val transcriptHashHex: String,
)

@Serializable
data class BridgePairResponse(
    val protocolVersion: Int,
    val transcriptHashHex: String,
    val clientFinishedHex: String,
)

@Serializable
data class BridgePairFinished(
    val protocolVersion: Int,
    val transcriptHashHex: String,
    val serverFinishedHex: String,
    val persistentTrustFingerprintHex: String,
)

/** Client acknowledgement sent only after it has durably staged trust. */
@Serializable
data class BridgePairCommitAck(
    val protocolVersion: Int,
    val transcriptHashHex: String,
    val persistentTrustFingerprintHex: String,
    val accepted: Boolean,
)

@Serializable
data class BridgeSessionHello(
    val protocolVersion: Int,
    val clientNonceHex: String,
)

@Serializable
data class BridgeSessionWelcome(
    val protocolVersion: Int,
    val serverNonceHex: String,
    val sessionIdHex: String,
    val serverProofHex: String,
)

/** Strict, closed request envelope. The payload has operation-specific validation in [BridgeCodec]. */
@Serializable
data class BridgeRequestEnvelope(
    val protocolVersion: Int,
    val requestId: String,
    val operation: String,
    val payload: JsonObject,
)

/**
 * Desktop-to-shell-helper envelope. This is deliberately a distinct type
 * from [BridgeRequestEnvelope]: root_path is used only inside the already
 * authenticated desktop-to-device helper invocation and can never be
 * accepted as a normal model/Android bridge request field.
 */
@Serializable
data class BridgeHelperRequestEnvelope(
    val protocolVersion: Int,
    val workspaceRootPath: String,
    val workspaceBinding: String,
    val fullDevice: Boolean = false,
    val request: BridgeRequestEnvelope,
)

@Serializable
data class BridgeResponseEnvelope(
    val protocolVersion: Int,
    val requestId: String,
    val success: Boolean,
    val payload: JsonObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    /** shell_v2 stderr may include diagnostics emitted by the adb client itself. */
    val stderrMayContainAdbDiagnostics: Boolean = false,
)

@Serializable
data class BridgeErrorEnvelope(
    val protocolVersion: Int,
    val requestId: String,
    val code: String,
    val retryable: Boolean,
)

@Serializable
data class BridgeCancelRequest(
    val protocolVersion: Int,
    val requestId: String,
    val targetRequestId: String,
)

/** Encrypted control/status message used for cancel acknowledgement and terminal state. */
@Serializable
data class BridgeStatusEnvelope(
    val protocolVersion: Int,
    val requestId: String,
    val state: String,
    val terminal: Boolean,
    val accepted: Boolean = true,
    val outcome: String? = null,
)

/** A decoded, authenticated frame. Its payload is copied before being returned. */
data class BridgeDecodedFrame(
    val protocolVersion: Int,
    val sessionId: ByteArray,
    val direction: BridgeDirection,
    val sequence: Long,
    val requestId: String,
    val type: BridgeFrameType,
    val payload: ByteArray,
) {
    init {
        require(sessionId.size == BridgeProtocol.SESSION_ID_BYTES)
    }
}

internal fun requireIdentityPart(value: String, field: String) {
    val bytes = strictUtf8(value, field)
    require(bytes.isNotEmpty()) { "$field must not be empty" }
    require(bytes.size <= 256) { "$field is too long" }
    require(value.none { it.code < 0x20 || it == '\u007f' || it == '\u0000' }) {
        "$field contains a control character"
    }
    require(Normalizer.normalize(value, Normalizer.Form.NFC) == value) {
        "$field must be NFC normalized"
    }
}

internal fun requireValidSerial(serial: String) {
    requireIdentityPart(serial, "serial")
    require(serial.none { it.isWhitespace() }) { "serial must not contain whitespace" }
    require(serial.length <= 256) { "serial is too long" }
}

internal fun strictUtf8(value: String, field: String = "value"): ByteArray {
    require(!value.contains('\u0000')) { "$field contains NUL" }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val decoded = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw BridgeProtocolException("$field is not valid UTF-8", error)
    }
    require(decoded == value) { "$field is not valid UTF-8" }
    return bytes
}

internal fun hexBytes(value: String, field: String, expectedBytes: Int): ByteArray {
    require(value.length == expectedBytes * 2) { "$field must be $expectedBytes bytes" }
    require(value.all { it in "0123456789abcdefABCDEF" }) { "$field is not hexadecimal" }
    return BridgeEncoding.unhex(value).also {
        require(it.size == expectedBytes)
    }
}

internal fun constantTimeHexEquals(actual: String, expected: ByteArray): Boolean = runCatching {
    BridgeCrypto.constantTimeEquals(hexBytes(actual, "value", expected.size), expected)
}.getOrDefault(false)

/** Encoding helpers are kept in the shared module so desktop and Android use identical bytes. */
object BridgeEncoding {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            val high = Character.digit(value[index * 2], 16)
            val low = Character.digit(value[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex" }
            ((high shl 4) or low).toByte()
        }
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64(value: String, maxBytes: Int = BridgeProtocol.MAX_PAYLOAD_BYTES): ByteArray {
        require(value.length <= ((maxBytes + 2) / 3) * 4 + 4) { "base64 value is too large" }
        return Base64.getDecoder().decode(value).also { require(it.size <= maxBytes) }
    }
}

/** Canonical binary writer used by transcript and frame AAD. */
internal class BridgeBinaryWriter {
    private val output = java.io.ByteArrayOutputStream()
    private val data = java.io.DataOutputStream(output)

    fun writeU8(value: Int) {
        require(value in 0..255)
        data.writeByte(value)
    }

    fun writeU16(value: Int) {
        require(value in 0..65_535)
        data.writeShort(value)
    }

    fun writeU32(value: Int) {
        require(value >= 0)
        data.writeInt(value)
    }

    fun writeU64(value: Long) {
        require(value >= 0)
        data.writeLong(value)
    }

    fun writeBytes(value: ByteArray) = data.write(value)

    fun writeFixed(value: ByteArray, expectedBytes: Int) {
        require(value.size == expectedBytes)
        data.write(value)
    }

    fun writeUtf8(value: String) {
        val bytes = strictUtf8(value)
        require(bytes.size <= 65_535)
        writeU16(bytes.size)
        writeBytes(bytes)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}
