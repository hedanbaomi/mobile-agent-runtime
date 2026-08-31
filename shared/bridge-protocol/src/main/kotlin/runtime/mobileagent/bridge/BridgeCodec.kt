// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict wire codec.  JSON is used only for the small control/request
 * envelopes; encrypted session framing is binary ([BridgeFrameCodec]).
 */
object BridgeCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowStructuredMapKeys = false
    }

    private val forbiddenRequestKeys = setOf(
        "serial", "serial_number", "adb_path", "adbPath", "desktop_id", "desktopId",
        "app_instance_id", "appInstanceId", "endpoint", "host", "hostname", "port",
        "bridge_host", "bridgeHost", "bridge_port", "bridgePort", "executable",
        "program", "argv", "args", "command_line", "commandLine", "service",
        "transport", "tcpip", "connect", "wireless", "socket_path", "socketPath",
    )

    private val payloadKeys: Map<BridgeOperation, Set<String>> = mapOf(
        BridgeOperation.WORKSPACE_LIST to setOf("workspace_id", "relative_path"),
        BridgeOperation.FILE_LIST to setOf("workspace_id", "relative_path", "include_hidden"),
        BridgeOperation.FILE_STAT to setOf("workspace_id", "relative_path"),
        BridgeOperation.FILE_READ_TEXT to setOf("workspace_id", "relative_path", "max_bytes"),
        BridgeOperation.FILE_WRITE_TEXT to setOf(
            "workspace_id", "relative_path", "content", "overwrite", "expected_sha256",
        ),
        BridgeOperation.FILE_CREATE_DIRECTORY to setOf("workspace_id", "relative_path", "recursive"),
        BridgeOperation.FILE_MOVE to setOf(
            "workspace_id", "relative_path", "destination_relative_path", "overwrite",
        ),
        BridgeOperation.FILE_DELETE to setOf("workspace_id", "relative_path", "recursive"),
        BridgeOperation.MEMORY_READ to setOf("memory_id", "offset", "limit"),
        BridgeOperation.MEMORY_SEARCH to setOf("query", "limit", "offset"),
        BridgeOperation.MEMORY_APPEND to setOf("memory_id", "content"),
        BridgeOperation.MEMORY_REPLACE to setOf("memory_id", "query", "replacement"),
        BridgeOperation.SHELL_EXEC to setOf("command", "cwd", "timeout_ms", "max_output_bytes"),
    )

    fun encodePairStart(value: BridgePairStart): ByteArray = encode(value).also { validatePairStart(value) }

    fun decodePairStart(bytes: ByteArray): BridgePairStart = decode<BridgePairStart>(bytes).also { validatePairStart(it) }

    fun encodePairChallenge(value: BridgePairChallenge): ByteArray =
        encode(value).also { validatePairChallenge(value) }

    fun decodePairChallenge(bytes: ByteArray): BridgePairChallenge = decode<BridgePairChallenge>(bytes).also(::validatePairChallenge)

    fun encodePairResponse(value: BridgePairResponse): ByteArray =
        encode(value).also { validatePairResponse(value) }

    fun decodePairResponse(bytes: ByteArray): BridgePairResponse = decode<BridgePairResponse>(bytes).also(::validatePairResponse)

    fun encodePairFinished(value: BridgePairFinished): ByteArray =
        encode(value).also { validatePairFinished(value) }

    fun decodePairFinished(bytes: ByteArray): BridgePairFinished = decode<BridgePairFinished>(bytes).also(::validatePairFinished)

    fun encodePairCommitAck(value: BridgePairCommitAck): ByteArray =
        encode(value).also { validatePairCommitAck(value) }

    fun decodePairCommitAck(bytes: ByteArray): BridgePairCommitAck =
        decode<BridgePairCommitAck>(bytes).also { validatePairCommitAck(it) }

    fun encodeSessionHello(value: BridgeSessionHello): ByteArray =
        encode(value).also { validateSessionHello(value) }

    fun decodeSessionHello(bytes: ByteArray): BridgeSessionHello = decode<BridgeSessionHello>(bytes).also { validateSessionHello(it) }

    fun encodeSessionWelcome(value: BridgeSessionWelcome): ByteArray =
        encode(value).also { validateSessionWelcome(value) }

    fun decodeSessionWelcome(bytes: ByteArray): BridgeSessionWelcome = decode<BridgeSessionWelcome>(bytes).also { validateSessionWelcome(it) }

    fun encodeRequest(value: BridgeRequestEnvelope): ByteArray =
        encode(value).also { validateRequest(value) }

    fun decodeRequest(bytes: ByteArray): BridgeRequestEnvelope = decode<BridgeRequestEnvelope>(bytes).also(::validateRequest)

    fun encodeResponse(value: BridgeResponseEnvelope): ByteArray =
        encode(value).also { validateResponse(value) }

    fun decodeResponse(bytes: ByteArray): BridgeResponseEnvelope = decode<BridgeResponseEnvelope>(bytes).also(::validateResponse)

    fun encodeError(value: BridgeErrorEnvelope): ByteArray =
        encode(value).also { validateError(value) }

    fun decodeError(bytes: ByteArray): BridgeErrorEnvelope = decode<BridgeErrorEnvelope>(bytes).also { validateError(it) }

    fun encodeCancel(value: BridgeCancelRequest): ByteArray = encode(value).also { validateCancel(value) }

    fun decodeCancel(bytes: ByteArray): BridgeCancelRequest = decode<BridgeCancelRequest>(bytes).also { validateCancel(it) }

    fun encodeStatus(value: BridgeStatusEnvelope): ByteArray = encode(value).also { validateStatus(value) }

    fun decodeStatus(bytes: ByteArray): BridgeStatusEnvelope = decode<BridgeStatusEnvelope>(bytes).also { validateStatus(it) }

    /** Validate an operation payload before routing it to an implementation. */
    fun validatePayload(operation: BridgeOperation, payload: JsonObject) {
        if (!payload.keys.all { it in payloadKeys.getValue(operation) }) {
            throw BridgeProtocolException("unknown field in ${operation.wireName} payload")
        }
        validateJsonTree(payload, "payload", 0)
        when (operation) {
            BridgeOperation.SHELL_EXEC -> {
                val command = payload["command"]?.asString("command")
                    ?: throw BridgeProtocolException("shell_exec command is required")
                val commandBytes = strictUtf8(command, "command")
                require(commandBytes.size <= BridgeProtocol.MAX_COMMAND_BYTES) {
                    "shell command is too large"
                }
                require(!command.contains('\u0000')) { "shell command contains NUL" }
                payload["cwd"]?.asString("cwd")?.let { strictUtf8(it, "cwd") }
                payload["timeout_ms"]?.asLong("timeout_ms")?.also {
                    require(it in 1..24 * 60 * 60 * 1_000L) { "timeout_ms is out of range" }
                }
                payload["max_output_bytes"]?.asLong("max_output_bytes")?.also {
                    require(it in 1..BridgeProtocol.MAX_FRAME_BYTES.toLong()) {
                        "max_output_bytes is out of range"
                    }
                }
            }
            else -> Unit
        }
    }

    private inline fun <reified T> encode(value: T): ByteArray {
        val text = try {
            json.encodeToString(value)
        } catch (error: SerializationException) {
            throw BridgeProtocolException("cannot encode bridge JSON", error)
        }
        val bytes = strictUtf8(text, "JSON")
        require(bytes.size <= BridgeProtocol.MAX_PAYLOAD_BYTES) { "bridge JSON payload is too large" }
        return bytes
    }

    private inline fun <reified T> decode(bytes: ByteArray): T {
        require(bytes.size <= BridgeProtocol.MAX_PAYLOAD_BYTES) { "bridge JSON payload is too large" }
        val text = decodeUtf8(bytes)
        return try {
            json.decodeFromString<T>(text)
        } catch (error: SerializationException) {
            throw BridgeProtocolException("invalid bridge JSON", error)
        } catch (error: IllegalArgumentException) {
            throw BridgeProtocolException("invalid bridge JSON", error)
        }
    }

    private fun validatePairStart(value: BridgePairStart) {
        requireVersion(value.protocolVersion)
        requireIdentityPart(value.appInstanceId, "appInstanceId")
        hexBytes(value.appNonceHex, "appNonce", BridgeProtocol.NONCE_BYTES)
        hexBytes(value.tokenHex, "pairingToken", BridgeProtocol.TOKEN_BYTES)
    }

    private fun validatePairChallenge(value: BridgePairChallenge) {
        requireVersion(value.protocolVersion)
        requireIdentityPart(value.desktopId, "desktopId")
        requireIdentityPart(value.appInstanceId, "appInstanceId")
        hexBytes(value.serialFingerprintHex, "serialFingerprint", BridgeProtocol.SERIAL_FINGERPRINT_BYTES)
        hexBytes(value.desktopNonceHex, "desktopNonce", BridgeProtocol.NONCE_BYTES)
        hexBytes(value.appNonceHex, "appNonce", BridgeProtocol.NONCE_BYTES)
        hexBytes(value.transcriptHashHex, "transcriptHash", 32)
    }

    private fun validatePairResponse(value: BridgePairResponse) {
        requireVersion(value.protocolVersion)
        hexBytes(value.transcriptHashHex, "transcriptHash", 32)
        hexBytes(value.clientFinishedHex, "clientFinished", 32)
    }

    private fun validatePairFinished(value: BridgePairFinished) {
        requireVersion(value.protocolVersion)
        hexBytes(value.transcriptHashHex, "transcriptHash", 32)
        hexBytes(value.serverFinishedHex, "serverFinished", 32)
        hexBytes(value.persistentTrustFingerprintHex, "trustFingerprint", 32)
    }

    private fun validatePairCommitAck(value: BridgePairCommitAck) {
        requireVersion(value.protocolVersion)
        hexBytes(value.transcriptHashHex, "transcriptHash", 32)
        hexBytes(value.persistentTrustFingerprintHex, "trustFingerprint", 32)
        require(value.accepted) { "pairing commit must be explicitly accepted" }
    }

    private fun validateSessionHello(value: BridgeSessionHello) {
        requireVersion(value.protocolVersion)
        hexBytes(value.clientNonceHex, "clientNonce", BridgeProtocol.NONCE_BYTES)
    }

    private fun validateSessionWelcome(value: BridgeSessionWelcome) {
        requireVersion(value.protocolVersion)
        hexBytes(value.serverNonceHex, "serverNonce", BridgeProtocol.NONCE_BYTES)
        hexBytes(value.sessionIdHex, "sessionId", BridgeProtocol.SESSION_ID_BYTES)
        hexBytes(value.serverProofHex, "serverProof", 32)
    }

    private fun validateRequest(value: BridgeRequestEnvelope) {
        requireVersion(value.protocolVersion)
        val requestId = strictUtf8(value.requestId, "requestId")
        require(requestId.isNotEmpty() && requestId.size <= BridgeProtocol.MAX_REQUEST_ID_BYTES) {
            "requestId is invalid"
        }
        require(value.requestId.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' }) {
            "requestId contains whitespace/control characters"
        }
        val operation = BridgeOperation.parse(value.operation)
        validatePayload(operation, value.payload)
    }

    private fun validateResponse(value: BridgeResponseEnvelope) {
        requireVersion(value.protocolVersion)
        val requestId = strictUtf8(value.requestId, "requestId")
        require(requestId.isNotEmpty() && requestId.size <= BridgeProtocol.MAX_REQUEST_ID_BYTES)
        require(value.requestId.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' })
        if (value.success) {
            require(value.payload != null) { "successful response requires payload" }
            require(value.errorCode == null && value.errorMessage == null) {
                "successful response cannot contain an error"
            }
        } else {
            require(value.payload == null) { "failed response cannot contain payload" }
            require(!value.errorCode.isNullOrBlank()) { "failed response requires errorCode" }
            value.errorCode?.let { requireIdentityPart(it, "errorCode") }
            value.errorMessage?.let { require(strictUtf8(it, "errorMessage").size <= 4 * 1024) }
        }
        value.payload?.let { validateJsonTree(it, "response payload", 0) }
    }

    private fun validateError(value: BridgeErrorEnvelope) {
        requireVersion(value.protocolVersion)
        val requestId = strictUtf8(value.requestId, "requestId")
        require(requestId.isNotEmpty() && requestId.size <= BridgeProtocol.MAX_REQUEST_ID_BYTES)
        requireIdentityPart(value.code, "code")
    }

    private fun validateCancel(value: BridgeCancelRequest) {
        requireVersion(value.protocolVersion)
        listOf(value.requestId to "requestId", value.targetRequestId to "targetRequestId").forEach { (id, field) ->
            val bytes = strictUtf8(id, field)
            require(bytes.isNotEmpty() && bytes.size <= BridgeProtocol.MAX_REQUEST_ID_BYTES) {
                "$field is invalid"
            }
            require(id.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' }) {
                "$field contains whitespace/control characters"
            }
        }
        require(value.requestId != value.targetRequestId) { "cancel request cannot target itself" }
    }

    private fun validateStatus(value: BridgeStatusEnvelope) {
        requireVersion(value.protocolVersion)
        val requestId = strictUtf8(value.requestId, "requestId")
        require(requestId.isNotEmpty() && requestId.size <= BridgeProtocol.MAX_REQUEST_ID_BYTES) {
            "requestId is invalid"
        }
        require(value.requestId.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' }) {
            "requestId contains whitespace/control characters"
        }
        val state = BridgeRequestState.parse(value.state)
        require(value.terminal == state.terminal) { "status terminal flag does not match state" }
        value.outcome?.let {
            val outcomeBytes = strictUtf8(it, "outcome")
            require(outcomeBytes.size <= BridgeProtocol.MAX_STATUS_BYTES) { "status outcome is too large" }
        }
    }

    private fun requireVersion(version: Int) {
        if (version != BridgeProtocol.VERSION) {
            throw BridgeProtocolException("unsupported bridge protocol version")
        }
    }

    private fun validateJsonTree(element: JsonElement, field: String, depth: Int) {
        require(depth <= 16) { "$field is too deeply nested" }
        require(element.toString().toByteArray(StandardCharsets.UTF_8).size <= BridgeProtocol.MAX_PAYLOAD_BYTES) {
            "$field is too large"
        }
        when (element) {
            is JsonObject -> element.forEach { (key, child) ->
                require(key !in forbiddenRequestKeys) { "forbidden bridge field: $key" }
                strictUtf8(key, "JSON key")
                validateJsonTree(child, "$field.$key", depth + 1)
            }
            is JsonArray -> element.forEachIndexed { index, child ->
                validateJsonTree(child, "$field[$index]", depth + 1)
            }
            JsonNull -> Unit
            is JsonPrimitive -> {
                if (element.isString) strictUtf8(element.content, field)
            }
        }
    }

    private fun JsonElement.asString(field: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BridgeProtocolException("$field must be a string")

    private fun JsonElement.asLong(field: String): Long =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
            ?: throw BridgeProtocolException("$field must be an integer")

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw BridgeProtocolException("bridge payload is not UTF-8", error)
    }
}
