// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays

/** Authenticated binary frame before it is written to the loopback stream. */
data class BridgeWireFrame(
    val protocolVersion: Int,
    val sessionId: ByteArray,
    val direction: BridgeDirection,
    val sequence: Long,
    val requestId: String,
    val type: BridgeFrameType,
    val ciphertext: ByteArray,
) {
    init {
        require(protocolVersion == BridgeProtocol.VERSION)
        require(sessionId.size == BridgeProtocol.SESSION_ID_BYTES)
        require(sequence >= 0)
        val requestBytes = strictUtf8(requestId, "requestId")
        require(requestBytes.isNotEmpty()) { "requestId must not be empty" }
        require(requestBytes.size <= BridgeProtocol.MAX_REQUEST_ID_BYTES)
        require(requestId.none { it.code < 0x20 || it == '\u007f' || it.isWhitespace() }) {
            "requestId contains whitespace/control characters"
        }
        require(ciphertext.size >= BridgeProtocol.GCM_TAG_BYTES) { "ciphertext is missing GCM tag" }
        require(ciphertext.size <= BridgeProtocol.MAX_PAYLOAD_BYTES + BridgeProtocol.GCM_TAG_BYTES) {
            "ciphertext is too large"
        }
    }
}

/**
 * Fixed binary frame codec.  There is intentionally no compression bit or
 * extension field: unknown bytes and unknown types are rejected.
 */
object BridgeFrameCodec {
    private const val HEADER_BYTES = 4 + 2 + 1 + 1 + 8 + BridgeProtocol.SESSION_ID_BYTES + 2 + 4

    fun encode(frame: BridgeWireFrame): ByteArray {
        val requestId = strictUtf8(frame.requestId, "requestId")
        val total = HEADER_BYTES + requestId.size + frame.ciphertext.size
        require(total <= BridgeProtocol.MAX_FRAME_BYTES) { "bridge frame is too large" }
        return BridgeBinaryWriter().apply {
            writeBytes(BridgeProtocol.FRAME_MAGIC)
            writeU16(frame.protocolVersion)
            writeU8(frame.direction.wireValue)
            writeU8(frame.type.wireValue)
            writeU64(frame.sequence)
            writeFixed(frame.sessionId, BridgeProtocol.SESSION_ID_BYTES)
            writeU16(requestId.size)
            writeU32(frame.ciphertext.size)
            writeBytes(requestId)
            writeBytes(frame.ciphertext)
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): BridgeWireFrame {
        require(bytes.size in HEADER_BYTES..BridgeProtocol.MAX_FRAME_BYTES) {
            "bridge frame length is invalid"
        }
        val reader = BridgeBinaryReader(bytes)
        require(reader.readFixed(BridgeProtocol.MAGIC.size).contentEquals(BridgeProtocol.FRAME_MAGIC)) {
            "bridge frame magic is invalid"
        }
        val version = reader.readU16()
        require(version == BridgeProtocol.VERSION) { "unsupported bridge protocol version" }
        val direction = BridgeDirection.fromWire(reader.readU8())
        val type = BridgeFrameType.fromWire(reader.readU8())
        val sequence = reader.readU64()
        require(sequence >= 0) { "bridge sequence is invalid" }
        val sessionId = reader.readFixed(BridgeProtocol.SESSION_ID_BYTES)
        val requestLength = reader.readU16()
        require(requestLength <= BridgeProtocol.MAX_REQUEST_ID_BYTES) { "requestId is too large" }
        val ciphertextLength = reader.readU32()
        require(ciphertextLength >= BridgeProtocol.GCM_TAG_BYTES)
        require(ciphertextLength <= BridgeProtocol.MAX_PAYLOAD_BYTES + BridgeProtocol.GCM_TAG_BYTES)
        val requestId = decodeRequestId(reader.readFixed(requestLength))
        val ciphertext = reader.readFixed(ciphertextLength)
        if (reader.remaining != 0) throw BridgeProtocolException("trailing bridge frame bytes")
        return BridgeWireFrame(version, sessionId, direction, sequence, requestId, type, ciphertext)
    }

    private fun decodeRequestId(bytes: ByteArray): String {
        val value = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw BridgeProtocolException("requestId is not UTF-8", error)
        }
        require(value.isNotEmpty()) { "requestId must not be empty" }
        require(value.none { it.code < 0x20 || it == '\u007f' || it.isWhitespace() }) {
            "requestId contains whitespace/control characters"
        }
        return value
    }
}

/** A live, one-direction-sequenced AES-GCM session. */
class BridgeSession internal constructor(
    val role: BridgeRole,
    sessionId: ByteArray,
    private val secrets: BridgeSessionSecrets,
) : AutoCloseable {
    private val sessionIdBytes: ByteArray = sessionId.copyOf()
    val sessionId: ByteArray get() = sessionIdBytes.copyOf()
    private var sendSequence = 0L
    private var receiveSequence = 0L
    private var closed = false

    @Synchronized
    fun encrypt(
        type: BridgeFrameType,
        requestId: String,
        plaintext: ByteArray,
    ): ByteArray {
        ensureOpen()
        require(plaintext.size <= BridgeProtocol.MAX_PAYLOAD_BYTES) { "bridge payload is too large" }
        val sequence = sendSequence
        val direction = role.sendDirection()
        val aad = BridgeCrypto.aad(BridgeProtocol.VERSION, sessionIdBytes, direction, sequence, requestId, type)
        val encrypted = try {
            useSendKey(direction) { key, prefix ->
                BridgeCrypto.encrypt(key, BridgeCrypto.nonce(prefix, sequence), aad, plaintext)
            }
        } finally {
            Arrays.fill(aad, 0)
        }
        val wire = BridgeFrameCodec.encode(
            BridgeWireFrame(
                BridgeProtocol.VERSION,
                sessionIdBytes,
                direction,
                sequence,
                requestId,
                type,
                encrypted,
            ),
        ).also { if (it.size > BridgeProtocol.MAX_FRAME_BYTES) throw BridgeProtocolException("bridge frame is too large") }
        sendSequence = nextSequence(sequence)
        return wire
    }

    @Synchronized
    fun decrypt(wireBytes: ByteArray): BridgeDecodedFrame {
        ensureOpen()
        val wire = BridgeFrameCodec.decode(wireBytes)
        require(wire.protocolVersion == BridgeProtocol.VERSION)
        if (!wire.sessionId.contentEquals(sessionIdBytes)) throw BridgeProtocolException("bridge session id mismatch")
        if (wire.direction != role.receiveDirection()) throw BridgeProtocolException("bridge direction mismatch")
        val expected = receiveSequence
        if (wire.sequence != expected) {
            throw BridgeSequenceException("bridge sequence mismatch")
        }
        val aad = BridgeCrypto.aad(
            wire.protocolVersion,
            sessionIdBytes,
            wire.direction,
            wire.sequence,
            wire.requestId,
            wire.type,
        )
        val plaintext = try {
            useReceiveKey(wire.direction) { key, prefix ->
                BridgeCrypto.decrypt(
                    key,
                    BridgeCrypto.nonce(prefix, wire.sequence),
                    aad,
                    wire.ciphertext,
                )
            }
        } catch (error: Exception) {
            // Do not advance receive state on authentication or framing errors.
            throw BridgeAuthenticationException("bridge frame authentication failed", error)
        } finally {
            Arrays.fill(aad, 0)
        }
        require(plaintext.size <= BridgeProtocol.MAX_PAYLOAD_BYTES) { "bridge payload is too large" }
        receiveSequence = nextSequence(expected)
        return BridgeDecodedFrame(
            wire.protocolVersion,
            sessionIdBytes.copyOf(),
            wire.direction,
            wire.sequence,
            wire.requestId,
            wire.type,
            plaintext,
        )
    }

    fun encryptRequest(request: BridgeRequestEnvelope): ByteArray =
        encrypt(BridgeFrameType.REQUEST, request.requestId, BridgeCodec.encodeRequest(request))

    fun decryptRequest(frame: BridgeDecodedFrame): BridgeRequestEnvelope =
        frame.requireType(BridgeFrameType.REQUEST).let {
            BridgeCodec.decodeRequest(it.payload).also { request ->
                require(request.requestId == it.requestId) { "requestId does not match frame" }
            }
        }

    fun encryptResponse(response: BridgeResponseEnvelope): ByteArray =
        encrypt(BridgeFrameType.RESPONSE, response.requestId, BridgeCodec.encodeResponse(response))

    fun decryptResponse(frame: BridgeDecodedFrame): BridgeResponseEnvelope =
        frame.requireType(BridgeFrameType.RESPONSE).let {
            BridgeCodec.decodeResponse(it.payload).also { response ->
                require(response.requestId == it.requestId) { "requestId does not match frame" }
            }
        }

    fun encryptError(error: BridgeErrorEnvelope): ByteArray =
        encrypt(BridgeFrameType.ERROR, error.requestId, BridgeCodec.encodeError(error))

    fun decryptError(frame: BridgeDecodedFrame): BridgeErrorEnvelope =
        frame.requireType(BridgeFrameType.ERROR).let {
            BridgeCodec.decodeError(it.payload).also { error ->
                require(error.requestId == it.requestId) { "requestId does not match frame" }
            }
        }

    fun encryptCancel(cancel: BridgeCancelRequest): ByteArray =
        encrypt(BridgeFrameType.CANCEL, cancel.requestId, BridgeCodec.encodeCancel(cancel))

    fun decryptCancel(frame: BridgeDecodedFrame): BridgeCancelRequest =
        frame.requireType(BridgeFrameType.CANCEL).let {
            BridgeCodec.decodeCancel(it.payload).also { cancel ->
                require(cancel.requestId == it.requestId) { "requestId does not match frame" }
            }
        }

    fun encryptStatus(status: BridgeStatusEnvelope): ByteArray =
        encrypt(BridgeFrameType.STATUS, status.requestId, BridgeCodec.encodeStatus(status))

    fun decryptStatus(frame: BridgeDecodedFrame): BridgeStatusEnvelope =
        frame.requireType(BridgeFrameType.STATUS).let {
            BridgeCodec.decodeStatus(it.payload).also { status ->
                require(status.requestId == it.requestId) { "requestId does not match frame" }
            }
        }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            secrets.close()
            Arrays.fill(sessionIdBytes, 0)
        }
    }

    private fun ensureOpen() {
        check(!closed) { "bridge session is closed" }
    }

    private fun nextSequence(value: Long): Long {
        require(value < Long.MAX_VALUE) { "bridge sequence exhausted" }
        return value + 1
    }

    private inline fun <T> useSendKey(
        direction: BridgeDirection,
        crossinline block: (ByteArray, ByteArray) -> T,
    ): T = if (direction == BridgeDirection.C2S) {
        secrets.clientToServerKey.use { key -> secrets.clientToServerPrefix.use { prefix -> block(key, prefix) } }
    } else {
        secrets.serverToClientKey.use { key -> secrets.serverToClientPrefix.use { prefix -> block(key, prefix) } }
    }

    private inline fun <T> useReceiveKey(
        direction: BridgeDirection,
        crossinline block: (ByteArray, ByteArray) -> T,
    ): T = if (direction == BridgeDirection.C2S) {
        secrets.clientToServerKey.use { key -> secrets.clientToServerPrefix.use { prefix -> block(key, prefix) } }
    } else {
        secrets.serverToClientKey.use { key -> secrets.serverToClientPrefix.use { prefix -> block(key, prefix) } }
    }
}

private fun BridgeDecodedFrame.requireType(expected: BridgeFrameType): BridgeDecodedFrame {
    require(type == expected) { "unexpected bridge frame type" }
    return this
}

data class BridgeSessionHandshakeResult(
    val welcome: BridgeSessionWelcome,
    val session: BridgeSession,
)

/**
 * Session handshake over an already authenticated loopback connection.  Each
 * session has fresh client/server nonces and keys; persistent trust is never
 * reused as an AEAD key directly.
 */
object BridgeSessionHandshake {
    fun newHello(random: SecureRandom = SecureRandom()): BridgeSessionHello {
        val nonce = BridgeCrypto.randomBytes(BridgeProtocol.NONCE_BYTES, random)
        return try {
            BridgeSessionHello(
                BridgeProtocol.VERSION,
                BridgeEncoding.hex(nonce),
            )
        } finally {
            Arrays.fill(nonce, 0)
        }
    }

    fun accept(
        hello: BridgeSessionHello,
        persistentTrust: SecretBytes,
        transcriptHash: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): BridgeSessionHandshakeResult {
        BridgeCodec.decodeSessionHello(BridgeCodec.encodeSessionHello(hello))
        val clientNonce = hexBytes(hello.clientNonceHex, "clientNonce", BridgeProtocol.NONCE_BYTES)
        val serverNonce = BridgeCrypto.randomBytes(BridgeProtocol.NONCE_BYTES, random)
        var sessionId = ByteArray(0)
        var secrets: BridgeSessionSecrets? = null
        var proof = ByteArray(0)
        var ownershipTransferred = false
        return try {
            require(!clientNonce.contentEquals(serverNonce))
            sessionId = BridgeCrypto.randomBytes(BridgeProtocol.SESSION_ID_BYTES, random)
            secrets = persistentTrust.use {
                BridgeCrypto.sessionSecrets(it, transcriptHash, sessionId, clientNonce, serverNonce)
            }
            proof = secrets!!.serverProof.copyBytes()
            val session = BridgeSession(BridgeRole.SERVER, sessionId, secrets!!)
            ownershipTransferred = true
            BridgeSessionHandshakeResult(
                BridgeSessionWelcome(
                    BridgeProtocol.VERSION,
                    BridgeEncoding.hex(serverNonce),
                    BridgeEncoding.hex(sessionId),
                    BridgeEncoding.hex(proof),
                ),
                session,
            )
        } catch (error: Exception) {
            if (!ownershipTransferred) secrets?.close()
            throw error
        } finally {
            Arrays.fill(clientNonce, 0)
            Arrays.fill(serverNonce, 0)
            Arrays.fill(sessionId, 0)
            Arrays.fill(proof, 0)
        }
    }

    fun complete(
        hello: BridgeSessionHello,
        welcome: BridgeSessionWelcome,
        persistentTrust: SecretBytes,
        transcriptHash: ByteArray,
    ): BridgeSession {
        BridgeCodec.decodeSessionHello(BridgeCodec.encodeSessionHello(hello))
        BridgeCodec.decodeSessionWelcome(BridgeCodec.encodeSessionWelcome(welcome))
        val clientNonce = hexBytes(hello.clientNonceHex, "clientNonce", BridgeProtocol.NONCE_BYTES)
        val serverNonce = hexBytes(welcome.serverNonceHex, "serverNonce", BridgeProtocol.NONCE_BYTES)
        val sessionId = hexBytes(welcome.sessionIdHex, "sessionId", BridgeProtocol.SESSION_ID_BYTES)
        val expectedProof = hexBytes(welcome.serverProofHex, "serverProof", 32)
        val secrets = persistentTrust.use {
            BridgeCrypto.sessionSecrets(it, transcriptHash, sessionId, clientNonce, serverNonce)
        }
        return try {
            val actualProof = secrets.serverProof.copyBytes()
            try {
                if (!BridgeCrypto.constantTimeEquals(actualProof, expectedProof)) {
                    throw BridgeAuthenticationException("session server proof is invalid")
                }
            } finally {
                Arrays.fill(actualProof, 0)
            }
            BridgeSession(BridgeRole.CLIENT, sessionId, secrets)
        } catch (error: Exception) {
            secrets.close()
            throw error
        } finally {
            Arrays.fill(clientNonce, 0)
            Arrays.fill(serverNonce, 0)
            Arrays.fill(sessionId, 0)
            Arrays.fill(expectedProof, 0)
        }
    }
}

private class BridgeBinaryReader(bytes: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(bytes))
    var remaining: Int = bytes.size
        private set

    fun readU8(): Int {
        require(remaining >= 1) { "truncated bridge frame" }
        remaining--
        return input.readUnsignedByte()
    }

    fun readU16(): Int {
        require(remaining >= 2) { "truncated bridge frame" }
        remaining -= 2
        return input.readUnsignedShort()
    }

    fun readU32(): Int {
        require(remaining >= 4) { "truncated bridge frame" }
        remaining -= 4
        val value = input.readInt()
        require(value >= 0) { "bridge frame length is invalid" }
        return value
    }

    fun readU64(): Long {
        require(remaining >= Long.SIZE_BYTES) { "truncated bridge frame" }
        remaining -= Long.SIZE_BYTES
        return input.readLong()
    }

    fun readFixed(size: Int): ByteArray {
        require(size >= 0 && size <= remaining) { "truncated bridge frame" }
        remaining -= size
        return ByteArray(size).also { input.readFully(it) }
    }
}
