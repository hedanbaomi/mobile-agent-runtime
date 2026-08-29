// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ipc

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable, API-26-compatible wire contract between the host and Python service. */
object PythonIpcProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "runtime.mobileagent.ipc.PythonRuntime"

    // Binder transaction codes.  Payloads larger than this are sent through
    // ParcelFileDescriptor streams, never as a Binder transaction.
    const val TRANSACTION_PING = 1
    const val TRANSACTION_START = 2
    const val TRANSACTION_CANCEL = 3
    /** Kill the one-shot worker immediately when an authenticated channel is violated. */
    const val TRANSACTION_ABORT = 4
    const val MAX_CONTROL_FRAME_BYTES = 64 * 1024
    const val MAX_OUTPUT_BYTES = 1 * 1024 * 1024
    const val MAX_LOG_BYTES = 512 * 1024
    const val MAX_INPUT_BYTES = 256 * 1024
    const val MAX_BROKER_CALLS = 20
    const val MAX_BROKER_ARGUMENT_BYTES = 48 * 1024
    const val MAX_BROKER_VALUE_BYTES = 8 * 1024 * 1024
    const val MAX_ERROR_BYTES = 4 * 1024
    const val MAX_BROKER_CHUNKS = 1024
    const val CHANNEL_NONCE_BYTES = 32
    const val CHANNEL_NONCE_LENGTH = 43

    const val ACK_ACCEPTED = 1
    const val ACK_REJECTED = 0

    const val RESULT_SUCCEEDED = "SUCCEEDED"
    const val RESULT_FAILED = "FAILED"
    const val RESULT_CANCELLED = "CANCELLED"
    const val RESULT_TIMED_OUT = "TIMED_OUT"
    const val RESULT_UNKNOWN = "UNKNOWN_OUTCOME"

    fun validateEntrypoint(value: String): Boolean {
        if (value.length !in 3..256 || value.count { it == ':' } != 1) return false
        val pieces = value.split(':', limit = 2)
        val module = pieces[0]
        val function = pieces[1]
        if (module.isBlank() || function.isBlank() || module.startsWith('.') || module.endsWith('.')) return false
        if (module.contains("..") || function.contains('.') || module.any { !(it.isLetterOrDigit() || it == '_' || it == '.') }) return false
        if (function.first() != '_' && !function.first().isLetter()) return false
        return function.all { it == '_' || it.isLetterOrDigit() }
    }

    fun validateLimits(limits: PythonLimits): Boolean =
        limits.timeoutMs in 1..30_000 &&
            limits.maxOutputBytes in 1..MAX_OUTPUT_BYTES &&
            limits.maxLogBytes in 1..MAX_LOG_BYTES &&
            limits.maxInputBytes in 1..MAX_INPUT_BYTES &&
            limits.maxBrokerCalls in 1..MAX_BROKER_CALLS

    fun validateCapability(value: String): Boolean =
        value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "._:/-" }

    fun validateRequestId(value: String): Boolean =
        value.length in 1..96 && value.all { it.isLetterOrDigit() || it in "._:-" }

    /**
     * Nonces are URL-safe base64 without padding for exactly 32 random bytes.
     * This is intentionally a shape check; entropy is supplied by the host's
     * SecureRandom and the value is never accepted from Python input.
     */
    fun validateChannelNonce(value: String): Boolean =
        value.length == CHANNEL_NONCE_LENGTH && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
        }

    /** A deliberately conservative set of host-enforced resource limits. */
    data class PythonLimits(
        val timeoutMs: Int = 30_000,
        val maxOutputBytes: Int = MAX_OUTPUT_BYTES,
        val maxLogBytes: Int = MAX_LOG_BYTES,
        val maxInputBytes: Int = MAX_INPUT_BYTES,
        val maxBrokerCalls: Int = MAX_BROKER_CALLS,
    )

    data class BrokerRequest(
        val ticket: InvocationTicket,
        val requestId: String,
        val capability: String,
        val argumentsJson: String,
    )

    /** Decoded frame including the native-only channel authenticator. */
    class BrokerRequestFrame(
        val request: BrokerRequest,
        val channelNonce: String,
    )

    data class BrokerResponse(
        val requestId: String,
        val status: String,
        val valueJson: String = "null",
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    @Serializable
    private data class BrokerRequestWire(
        val version: Int,
        val kind: String,
        val invocationId: String,
        val runId: String,
        val packageHash: String,
        val grantRevision: Int,
        val oneTimeToken: String,
        val requestId: String,
        val capability: String,
        val argumentsJson: String,
        val channelNonce: String = "",
    )

    @Serializable
    private data class BrokerResponseWire(
        val version: Int,
        val kind: String,
        val requestId: String,
        val status: String,
        val valueJson: String = "null",
        val chunkIndex: Int = 0,
        val chunkCount: Int = 1,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    data class BrokerResponseChunk(
        val response: BrokerResponse,
        val chunkIndex: Int,
        val chunkCount: Int,
    )

    @Serializable
    data class ResultHeader(
        val version: Int,
        val kind: String,
        val status: String,
        val outputBytes: Int = 0,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val channelNonce: String = "",
    ) {
        /** Do not leak the native-only nonce through accidental logging. */
        override fun toString(): String =
            "ResultHeader(version=$version, kind=$kind, status=$status, outputBytes=$outputBytes, " +
                "errorCode=$errorCode, errorMessage=$errorMessage)"
    }

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encodeBrokerRequest(request: BrokerRequest): ByteArray {
        require(request.ticket.validate()) { "Invalid invocation ticket" }
        require(validateRequestId(request.requestId)) { "Invalid broker request id" }
        require(validateCapability(request.capability)) { "Invalid broker capability" }
        require(request.argumentsJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_BROKER_ARGUMENT_BYTES) {
            "Broker arguments exceed limit"
        }
        val wire = BrokerRequestWire(
            version = VERSION,
            kind = "request",
            invocationId = request.ticket.invocationId,
            runId = request.ticket.runId,
            packageHash = request.ticket.packageHash,
            grantRevision = request.ticket.grantRevision,
            oneTimeToken = request.ticket.oneTimeToken,
            requestId = request.requestId,
            capability = request.capability,
            argumentsJson = request.argumentsJson,
        )
        return json.encodeToString(BrokerRequestWire.serializer(), wire).toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Decode the legacy/unit-test form without requiring the native channel
     * nonce. Production broker loops must call [decodeBrokerRequestFrame].
     */
    fun decodeBrokerRequest(bytes: ByteArray): BrokerRequest =
        decodeBrokerRequestFrameInternal(bytes, requireNonce = false).request

    /** Decode a native frame and require its private host-issued nonce. */
    fun decodeBrokerRequestFrame(bytes: ByteArray): BrokerRequestFrame =
        decodeBrokerRequestFrameInternal(bytes, requireNonce = true)

    private fun decodeBrokerRequestFrameInternal(bytes: ByteArray, requireNonce: Boolean): BrokerRequestFrame {
        require(bytes.size <= MAX_CONTROL_FRAME_BYTES) { "Broker request exceeds limit" }
        val wire = json.decodeFromString(BrokerRequestWire.serializer(), bytes.toString(StandardCharsets.UTF_8))
        require(wire.version == VERSION && wire.kind == "request") { "Unsupported broker request" }
        val ticket = InvocationTicket(
            invocationId = wire.invocationId,
            runId = wire.runId,
            packageHash = wire.packageHash,
            grantRevision = wire.grantRevision,
            oneTimeToken = wire.oneTimeToken,
        )
        require(ticket.validate()) { "Invalid invocation ticket" }
        require(validateRequestId(wire.requestId)) { "Invalid broker request id" }
        require(validateCapability(wire.capability)) { "Invalid broker capability" }
        require(wire.argumentsJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_BROKER_ARGUMENT_BYTES) {
            "Broker arguments exceed limit"
        }
        // Parsing the argument value here rejects concatenated values and
        // malformed JSON before it can reach an application capability.
        json.parseToJsonElement(wire.argumentsJson)
        if (requireNonce) require(validateChannelNonce(wire.channelNonce)) { "Invalid broker channel nonce" }
        return BrokerRequestFrame(
            request = BrokerRequest(ticket, wire.requestId, wire.capability, wire.argumentsJson),
            channelNonce = wire.channelNonce,
        )
    }

    fun encodeBrokerResponse(response: BrokerResponse): ByteArray {
        require(validateRequestId(response.requestId)) { "Invalid broker request id" }
        require(response.status in setOf("OK", "DENIED", "ERROR")) { "Invalid broker status" }
        val valueBytes = response.valueJson.toByteArray(StandardCharsets.UTF_8)
        require(valueBytes.size <= MAX_BROKER_ARGUMENT_BYTES) {
            "Broker response exceeds one control frame; use encodeBrokerResponseChunks"
        }
        json.parseToJsonElement(response.valueJson)
        return encodeBrokerResponseChunk(response, 0, 1).also {
            require(it.size <= MAX_CONTROL_FRAME_BYTES) { "Broker response exceeds control frame" }
        }
    }

    fun encodeBrokerResponseChunks(response: BrokerResponse): List<ByteArray> {
        require(validateRequestId(response.requestId)) { "Invalid broker request id" }
        require(response.status in setOf("OK", "DENIED", "ERROR")) { "Invalid broker status" }
        val valueBytes = response.valueJson.toByteArray(StandardCharsets.UTF_8)
        require(valueBytes.size <= MAX_BROKER_VALUE_BYTES) { "Broker response exceeds stream limit" }
        json.parseToJsonElement(response.valueJson)
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < valueBytes.size) {
            val requestedEnd = minOf(offset + MAX_BROKER_ARGUMENT_BYTES, valueBytes.size)
            // valueJson is itself a JSON string in the wire envelope, so
            // quotes, slashes and control characters can expand. Size the
            // chunk by the encoded frame, not only by its raw JSON bytes.
            // UTF-8 boundaries are monotonic, and JSON escaping makes the
            // encoded frame length monotonic with the raw candidate. Binary
            // search avoids an attacker turning a near-capacity response into
            // an O(n^2) byte-at-a-time backoff.
            val fittingEnd = largestFittingEnd(response, valueBytes, offset, requestedEnd)
            require(fittingEnd > offset) { "Broker response cannot fit one control frame" }
            chunks += valueBytes.copyOfRange(offset, fittingEnd).toString(StandardCharsets.UTF_8)
            offset = fittingEnd
        }
        val count = chunks.size
        require(count in 1..MAX_BROKER_CHUNKS) { "Broker response has too many chunks" }
        return chunks.mapIndexed { index, value ->
            encodeBrokerResponseChunk(response.copy(valueJson = value), index, count).also {
                require(it.size <= MAX_CONTROL_FRAME_BYTES) { "Broker response exceeds control frame" }
            }
        }
    }

    /** Return an end offset which is not in the middle of a UTF-8 sequence. */
    private fun utf8Boundary(bytes: ByteArray, start: Int, requestedEnd: Int): Int {
        var end = requestedEnd.coerceIn(start, bytes.size)
        while (end > start && end < bytes.size && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return end
    }

    private fun largestFittingEnd(
        response: BrokerResponse,
        valueBytes: ByteArray,
        start: Int,
        requestedEnd: Int,
    ): Int {
        var low = start + 1
        var high = utf8Boundary(valueBytes, start, requestedEnd)
        var best = start
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidateEnd = utf8Boundary(valueBytes, start, middle)
            if (candidateEnd <= start) {
                low = middle + 1
                continue
            }
            val candidate = valueBytes.copyOfRange(start, candidateEnd).toString(StandardCharsets.UTF_8)
            val fits = encodeBrokerResponseChunk(
                response.copy(valueJson = candidate),
                MAX_BROKER_CHUNKS - 1,
                MAX_BROKER_CHUNKS,
            ).size <= MAX_CONTROL_FRAME_BYTES
            if (fits) {
                best = candidateEnd
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun encodeBrokerResponseChunk(response: BrokerResponse, chunkIndex: Int, chunkCount: Int): ByteArray {
        val wire = BrokerResponseWire(
            version = VERSION,
            kind = "response",
            requestId = response.requestId,
            status = response.status,
            valueJson = response.valueJson,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            errorCode = response.errorCode?.takeUtf8Bytes(MAX_ERROR_BYTES),
            errorMessage = response.errorMessage?.takeUtf8Bytes(MAX_ERROR_BYTES),
        )
        return json.encodeToString(BrokerResponseWire.serializer(), wire).toByteArray(StandardCharsets.UTF_8)
    }

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        val encoded = toByteArray(StandardCharsets.UTF_8)
        if (encoded.size <= maxBytes) return this
        var end = maxBytes.coerceAtLeast(0)
        while (end > 0 && (encoded[end].toInt() and 0xc0) == 0x80) end--
        return encoded.copyOf(end).toString(StandardCharsets.UTF_8)
    }

    fun decodeBrokerResponse(bytes: ByteArray): BrokerResponse {
        val chunk = decodeBrokerResponseChunk(bytes)
        require(chunk.chunkCount == 1 && chunk.chunkIndex == 0) { "Broker response is chunked" }
        json.parseToJsonElement(chunk.response.valueJson)
        return chunk.response
    }

    fun decodeBrokerResponseChunk(bytes: ByteArray): BrokerResponseChunk {
        require(bytes.size <= MAX_CONTROL_FRAME_BYTES) { "Broker response exceeds limit" }
        val wire = json.decodeFromString(BrokerResponseWire.serializer(), bytes.toString(StandardCharsets.UTF_8))
        require(wire.version == VERSION && wire.kind == "response") { "Unsupported broker response" }
        require(validateRequestId(wire.requestId)) { "Invalid broker request id" }
        require(wire.status in setOf("OK", "DENIED", "ERROR")) { "Invalid broker status" }
        require(wire.chunkCount in 1..MAX_BROKER_CHUNKS && wire.chunkIndex in 0 until wire.chunkCount) {
            "Invalid broker response chunk"
        }
        require(wire.valueJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_BROKER_ARGUMENT_BYTES) {
            "Broker response chunk exceeds limit"
        }
        require((wire.errorCode?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) <= MAX_ERROR_BYTES) {
            "Broker response error code exceeds limit"
        }
        require((wire.errorMessage?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) <= MAX_ERROR_BYTES) {
            "Broker response error message exceeds limit"
        }
        return BrokerResponseChunk(
            response = BrokerResponse(wire.requestId, wire.status, wire.valueJson, wire.errorCode, wire.errorMessage),
            chunkIndex = wire.chunkIndex,
            chunkCount = wire.chunkCount,
        )
    }

    fun encodeResultHeader(header: ResultHeader): ByteArray {
        require(header.version == VERSION && header.kind == "result") { "Invalid result header" }
        require(header.status in setOf(RESULT_SUCCEEDED, RESULT_FAILED, RESULT_CANCELLED, RESULT_TIMED_OUT, RESULT_UNKNOWN)) {
            "Invalid result status"
        }
        require(header.outputBytes in 0..MAX_OUTPUT_BYTES) { "Invalid result size" }
        require(header.channelNonce.isEmpty() || validateChannelNonce(header.channelNonce)) {
            "Invalid result channel nonce"
        }
        return json.encodeToString(ResultHeader.serializer(), header).toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeResultHeader(bytes: ByteArray): ResultHeader {
        require(bytes.size <= MAX_CONTROL_FRAME_BYTES) { "Result header exceeds limit" }
        val header = json.decodeFromString(ResultHeader.serializer(), bytes.toString(StandardCharsets.UTF_8))
        require(header.version == VERSION && header.kind == "result") { "Unsupported result header" }
        require(header.status in setOf(RESULT_SUCCEEDED, RESULT_FAILED, RESULT_CANCELLED, RESULT_TIMED_OUT, RESULT_UNKNOWN)) {
            "Invalid result status"
        }
        require(header.outputBytes in 0..MAX_OUTPUT_BYTES) { "Invalid result size" }
        require(header.channelNonce.isEmpty() || validateChannelNonce(header.channelNonce)) {
            "Invalid result channel nonce"
        }
        return header
    }

    /** Decode a result only when it carries the exact nonce for this call. */
    fun decodeResultHeader(bytes: ByteArray, expectedNonce: String): ResultHeader {
        require(validateChannelNonce(expectedNonce)) { "Invalid expected result nonce" }
        val header = decodeResultHeader(bytes)
        require(header.channelNonce == expectedNonce) { "Invalid result channel nonce" }
        return header
    }

    /** Length-prefixed frames used only on the two private pipe pairs. */
    object Frames {
        fun write(output: OutputStream, payload: ByteArray, maxBytes: Int = MAX_CONTROL_FRAME_BYTES) {
            require(payload.size <= maxBytes) { "IPC frame exceeds limit" }
            val header = byteArrayOf(
                ((payload.size ushr 24) and 0xff).toByte(),
                ((payload.size ushr 16) and 0xff).toByte(),
                ((payload.size ushr 8) and 0xff).toByte(),
                (payload.size and 0xff).toByte(),
            )
            output.write(header)
            output.write(payload)
            output.flush()
        }

        fun read(input: InputStream, maxBytes: Int = MAX_CONTROL_FRAME_BYTES): ByteArray? {
            val header = ByteArray(4)
            val first = input.read()
            if (first < 0) return null
            header[0] = first.toByte()
            readFully(input, header, 1, 3)
            val length = ((header[0].toInt() and 0xff) shl 24) or
                ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
            require(length in 0..maxBytes) { "IPC frame exceeds limit" }
            return ByteArray(length).also { readFully(input, it, 0, length) }
        }

        private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int) {
            var cursor = offset
            val end = offset + length
            while (cursor < end) {
                val read = input.read(target, cursor, end - cursor)
                if (read < 0) throw EOFException("Unexpected end of IPC frame")
                if (read == 0) continue
                cursor += read
            }
        }
    }
}
