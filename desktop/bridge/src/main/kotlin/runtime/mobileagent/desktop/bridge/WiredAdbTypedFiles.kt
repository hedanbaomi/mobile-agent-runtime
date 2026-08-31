// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.bridge.BridgeCodec
import runtime.mobileagent.bridge.BridgeErrorCodes
import runtime.mobileagent.bridge.BridgeOperation
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.BridgeRequestEnvelope
import runtime.mobileagent.bridge.BridgeResponseEnvelope

/**
 * The desktop-side closed enum for the helper protocol.  It deliberately does
 * not contain shell, transport, or host-file operations.
 */
enum class WiredAdbTypedFileOperation(val wireName: String) {
    LIST(BridgeOperation.FILE_LIST.wireName),
    STAT(BridgeOperation.FILE_STAT.wireName),
    READ_TEXT(BridgeOperation.FILE_READ_TEXT.wireName),
    WRITE_TEXT(BridgeOperation.FILE_WRITE_TEXT.wireName),
    CREATE_DIRECTORY(BridgeOperation.FILE_CREATE_DIRECTORY.wireName),
    MOVE(BridgeOperation.FILE_MOVE.wireName),
    DELETE(BridgeOperation.FILE_DELETE.wireName),
    ;

    fun bridgeOperation(): BridgeOperation = BridgeOperation.parse(wireName)

    companion object {
        fun parse(operation: BridgeOperation): WiredAdbTypedFileOperation = when (operation) {
            BridgeOperation.FILE_LIST -> LIST
            BridgeOperation.FILE_STAT -> STAT
            BridgeOperation.FILE_READ_TEXT -> READ_TEXT
            BridgeOperation.FILE_WRITE_TEXT -> WRITE_TEXT
            BridgeOperation.FILE_CREATE_DIRECTORY -> CREATE_DIRECTORY
            BridgeOperation.FILE_MOVE -> MOVE
            BridgeOperation.FILE_DELETE -> DELETE
            else -> throw IllegalArgumentException("typed file operation is unsupported")
        }
    }
}

/**
 * Strict, transport-neutral DTO handed to the fixed ADB executor.  The
 * desktop never accepts serial, endpoint, adb path, or command fields here.
 */
class WiredAdbTypedFileRequest private constructor(
    val requestId: String,
    val operation: WiredAdbTypedFileOperation,
    val relativePath: String?,
    val destinationRelativePath: String?,
    val contentUtf8: ByteArray?,
    val overwrite: Boolean,
    val maxBytes: Int,
) {
    fun contentCopy(): ByteArray? = contentUtf8?.copyOf()

    /** Rebuilds the exact helper payload, dropping any bridge-only extras. */
    fun toBridgeRequest(): BridgeRequestEnvelope = BridgeRequestEnvelope(
        protocolVersion = BridgeProtocol.VERSION,
        requestId = requestId,
        operation = operation.wireName,
        payload = buildJsonObject {
            put("workspace_id", WIRED_WORKSPACE_ID)
            relativePath?.let { put("relative_path", it) }
            when (operation) {
                WiredAdbTypedFileOperation.LIST,
                WiredAdbTypedFileOperation.STAT -> Unit
                WiredAdbTypedFileOperation.READ_TEXT -> put("max_bytes", maxBytes)
                WiredAdbTypedFileOperation.WRITE_TEXT -> {
                    put("content", decodeUtf8(contentUtf8 ?: error("content is required")))
                    put("overwrite", overwrite)
                }
                WiredAdbTypedFileOperation.CREATE_DIRECTORY -> put("recursive", false)
                WiredAdbTypedFileOperation.MOVE -> {
                    put("destination_relative_path", destinationRelativePath)
                    put("overwrite", overwrite)
                }
                WiredAdbTypedFileOperation.DELETE -> put("recursive", false)
            }
        },
    )

    companion object {
        /**
         * Parses the shared bridge envelope before a process is dispatched.
         * This is intentionally stricter than the generic shared codec because
         * the helper accepts only the fields below.
         */
        fun parse(request: BridgeRequestEnvelope): WiredAdbTypedFileRequest {
            // The authenticated reader already ran the shared codec, but the
            // handler is also a public test seam; validate the envelope again
            // before accepting a request ID or payload for dispatch.
            require(runCatching { BridgeCodec.encodeRequest(request) }.isSuccess)
            require(request.protocolVersion == BridgeProtocol.VERSION)
            val operation = WiredAdbTypedFileOperation.parse(BridgeOperation.parse(request.operation))
            val payload = request.payload
            require(payload.keys.all { it in allowedKeys(operation) })
            require(payload.stringRequired("workspace_id") == WIRED_WORKSPACE_ID)

            val relativePath = payload.stringOrNull("relative_path")
            WiredAdbDesktopPathPolicy.parse(
                relativePath,
                allowRoot = operation == WiredAdbTypedFileOperation.LIST,
            )
            val destination = payload.stringOrNull("destination_relative_path")
            if (operation == WiredAdbTypedFileOperation.MOVE) {
                WiredAdbDesktopPathPolicy.parse(destination, allowRoot = false)
            } else {
                require(destination == null)
            }

            val content = payload.stringOrNull("content")?.let(::strictUtf8)
            if (operation == WiredAdbTypedFileOperation.WRITE_TEXT) {
                require(content != null)
                require(content.size <= WIRED_MAX_FILE_BYTES)
            } else {
                require(content == null)
            }
            val maxBytes = payload.longOrNull("max_bytes")?.also {
                require(it in 1..WIRED_MAX_READ_BYTES.toLong())
            }?.toInt() ?: WIRED_MAX_READ_BYTES
            val overwrite = payload.booleanOrNull("overwrite") ?: false
            if (operation != WiredAdbTypedFileOperation.WRITE_TEXT &&
                operation != WiredAdbTypedFileOperation.MOVE
            ) {
                require(payload.containsKey("overwrite").not())
            }
            if (operation != WiredAdbTypedFileOperation.READ_TEXT) {
                require(payload.containsKey("max_bytes").not())
            }
            if (operation == WiredAdbTypedFileOperation.CREATE_DIRECTORY ||
                operation == WiredAdbTypedFileOperation.DELETE
            ) {
                // The Android helper intentionally has no recursive mode.
                require(payload.booleanOrNull("recursive") == false)
            }

            return WiredAdbTypedFileRequest(
                requestId = request.requestId,
                operation = operation,
                relativePath = relativePath,
                destinationRelativePath = destination,
                contentUtf8 = content,
                overwrite = overwrite,
                maxBytes = maxBytes,
            )
        }

        private fun allowedKeys(operation: WiredAdbTypedFileOperation): Set<String> = when (operation) {
            WiredAdbTypedFileOperation.LIST,
            WiredAdbTypedFileOperation.STAT -> setOf("workspace_id", "relative_path")
            WiredAdbTypedFileOperation.READ_TEXT -> setOf("workspace_id", "relative_path", "max_bytes")
            WiredAdbTypedFileOperation.WRITE_TEXT -> setOf("workspace_id", "relative_path", "content", "overwrite")
            WiredAdbTypedFileOperation.CREATE_DIRECTORY,
            WiredAdbTypedFileOperation.DELETE -> setOf("workspace_id", "relative_path", "recursive")
            WiredAdbTypedFileOperation.MOVE -> setOf(
                "workspace_id",
                "relative_path",
                "destination_relative_path",
                "overwrite",
            )
        }
    }
}

/** A typed file executor supplied by the authenticated Desktop handler. */
fun interface WiredAdbTypedFileExecutor {
    fun execute(request: WiredAdbTypedFileRequest, cancellation: BridgeCancellation): BridgeResponseEnvelope
}

/**
 * Invokes the Android shell-UID helper through official adb.  The only
 * command argument is the fixed launcher below; model payload values are
 * encoded as length-delimited JSON on stdin and are never shell syntax.
 */
class WiredAdbTypedFileExecutorImpl(
    private val adb: AdbProcessManager,
    private val deadlineMs: Long = WIRED_ADB_FILE_DEADLINE_MS,
    private val stdoutCapBytes: Int = BridgeProtocol.MAX_FRAME_BYTES,
    private val stderrCapBytes: Int = WIRED_ADB_HELPER_STDERR_CAP_BYTES,
) : WiredAdbTypedFileExecutor {
    init {
        require(deadlineMs in 1..5 * 60 * 1_000L)
        require(stdoutCapBytes in 1..BridgeProtocol.MAX_FRAME_BYTES)
        require(stderrCapBytes in 0..BridgeProtocol.MAX_FRAME_BYTES)
    }

    override fun execute(
        request: WiredAdbTypedFileRequest,
        cancellation: BridgeCancellation,
    ): BridgeResponseEnvelope {
        if (cancellation.isRequested) return unknown(request.requestId)
        val frame = try {
            WiredAdbHelperFrameCodec.encode(request.toBridgeRequest())
        } catch (_: Throwable) {
            // The DTO has already been validated. A local protocol failure is
            // not a remote execution result and is deliberately sanitized.
            return unknown(request.requestId)
        }
        val result = try {
            adb.runTypedFiles(
                frame = frame,
                timeoutMs = deadlineMs,
                stdoutCapBytes = stdoutCapBytes,
                stderrCapBytes = stderrCapBytes,
                cancelRequested = { cancellation.isRequested },
            )
        } catch (_: Throwable) {
            return unknown(request.requestId)
        } finally {
            java.util.Arrays.fill(frame, 0)
        }
        val process = result.process
        if (process.outcome != ProcessOutcome.COMPLETE ||
            process.exitCode != 0 ||
            process.timedOut || process.cancelled || process.stdoutTruncated || cancellation.isRequested
        ) {
            return unknown(request.requestId)
        }
        return try {
            WiredAdbHelperFrameCodec.decodeResponse(process.stdout, request)
        } catch (_: Throwable) {
            // A malformed/truncated helper response after dispatch is
            // conservative UNKNOWN_OUTCOME; do not permit an automatic replay.
            unknown(request.requestId)
        }
    }

    private fun unknown(requestId: String): BridgeResponseEnvelope = BridgeResponseEnvelope(
        protocolVersion = BridgeProtocol.VERSION,
        requestId = requestId,
        success = false,
        errorCode = BridgeErrorCodes.UNKNOWN_OUTCOME,
        errorMessage = "typed file outcome is unknown",
    )
}

/** Fixed helper response framing and strict result validation. */
internal object WiredAdbHelperFrameCodec {
    fun encode(request: BridgeRequestEnvelope): ByteArray {
        val body = BridgeCodec.encodeRequest(request)
        require(body.size in 1..BridgeProtocol.MAX_FRAME_BYTES - 4)
        val output = ByteArray(4 + body.size)
        output[0] = (body.size ushr 24).toByte()
        output[1] = (body.size ushr 16).toByte()
        output[2] = (body.size ushr 8).toByte()
        output[3] = body.size.toByte()
        body.copyInto(output, 4)
        return output
    }

    fun decodeResponse(bytes: ByteArray, request: WiredAdbTypedFileRequest): BridgeResponseEnvelope {
        require(bytes.size >= 5)
        val length = ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        require(length in 1..BridgeProtocol.MAX_FRAME_BYTES - 4)
        require(bytes.size == length + 4)
        val response = BridgeCodec.decodeResponse(bytes.copyOfRange(4, bytes.size))
        require(response.requestId == request.requestId)
        require(response.protocolVersion == BridgeProtocol.VERSION)
        if (!response.success) {
            require(response.payload == null)
            require(response.errorMessage == null)
            require(response.errorCode in WIRED_ADB_HELPER_ERROR_CODES)
            return response
        }
        val payload = response.payload ?: throw IllegalArgumentException("response payload is missing")
        validateResultPayload(payload, request)
        return response.copy(errorMessage = null, stderrMayContainAdbDiagnostics = true)
    }

    private fun validateResultPayload(
        payload: JsonObject,
        request: WiredAdbTypedFileRequest,
    ) {
        require(payload.keys.all { it in resultKeys(request.operation) })
        require(payload.stringRequired("operation") == request.operation.wireName)
        val resultPath = payload.stringRequired("relative_path")
        WiredAdbDesktopPathPolicy.parse(
            resultPath,
            allowRoot = request.operation == WiredAdbTypedFileOperation.LIST,
        )
        val entries = payload.arrayOrNull("entries")
        if (entries != null) {
            require(entries.size <= WIRED_MAX_ENTRIES)
            entries.forEach { element ->
                val item = element as? JsonObject ?: throw IllegalArgumentException("entry is invalid")
                require(item.keys.all { it in ENTRY_KEYS })
                val path = item.stringRequired("relative_path")
                WiredAdbDesktopPathPolicy.parse(path, allowRoot = false)
                require(item.stringRequired("type") in setOf("file", "directory", "dir"))
                // Existing files are allowed to be observed by list/stat;
                // the helper applies max-file/quota limits to writes and
                // reads, not to metadata reporting.
                item.longOrNull("bytes")?.also { require(it >= 0L) }
            }
        }
        val text = payload.stringOrNull("text")?.also {
            val textBytes = strictUtf8(it)
            require(textBytes.size <= WIRED_MAX_READ_BYTES)
            if (request.operation == WiredAdbTypedFileOperation.READ_TEXT) {
                require(textBytes.size <= request.maxBytes)
            }
        }
        payload.longOrNull("bytes")?.also {
            require(it >= 0L)
            if (request.operation == WiredAdbTypedFileOperation.READ_TEXT && text != null) {
                require(it == strictUtf8(text).size.toLong())
            }
            if (request.operation == WiredAdbTypedFileOperation.WRITE_TEXT) {
                require(it == request.contentUtf8?.size?.toLong())
            }
        }
        listOf("created", "replaced", "deleted").forEach { payload.booleanOrNull(it) }
        when (request.operation) {
            WiredAdbTypedFileOperation.LIST -> Unit
            WiredAdbTypedFileOperation.STAT -> {
                val statEntries = entries ?: throw IllegalArgumentException("stat entries are missing")
                require(statEntries.size == 1)
                val item = statEntries.single() as? JsonObject ?: throw IllegalArgumentException("entry is invalid")
                require(item.stringRequired("relative_path") == resultPath)
            }
            WiredAdbTypedFileOperation.READ_TEXT -> {
                require(payload.stringOrNull("text") != null)
                require(payload.longOrNull("bytes") != null)
            }
            WiredAdbTypedFileOperation.WRITE_TEXT -> {
                require(payload.longOrNull("bytes") != null)
                require(payload.booleanOrNull("created") != null)
                require(payload.booleanOrNull("replaced") != null)
            }
            WiredAdbTypedFileOperation.CREATE_DIRECTORY -> require(payload.booleanOrNull("created") != null)
            WiredAdbTypedFileOperation.MOVE -> require(payload.booleanOrNull("replaced") != null)
            WiredAdbTypedFileOperation.DELETE -> require(payload.booleanOrNull("deleted") != null)
        }
    }

    private fun resultKeys(operation: WiredAdbTypedFileOperation): Set<String> = when (operation) {
        WiredAdbTypedFileOperation.LIST -> setOf("operation", "relative_path", "entries")
        WiredAdbTypedFileOperation.STAT -> setOf("operation", "relative_path", "entries", "bytes")
        WiredAdbTypedFileOperation.READ_TEXT -> setOf("operation", "relative_path", "text", "bytes")
        WiredAdbTypedFileOperation.WRITE_TEXT -> setOf("operation", "relative_path", "bytes", "created", "replaced")
        WiredAdbTypedFileOperation.CREATE_DIRECTORY -> setOf("operation", "relative_path", "created")
        WiredAdbTypedFileOperation.MOVE -> setOf("operation", "relative_path", "bytes", "replaced")
        WiredAdbTypedFileOperation.DELETE -> setOf("operation", "relative_path", "deleted")
    }

    private val ENTRY_KEYS = setOf("relative_path", "type", "bytes")
}

/**
 * The fixed command uses only Android system utilities and the immutable app
 * package/main class.  `$` is appended as a character to make it impossible
 * for Kotlin interpolation or a request value to enter this command.
 */
internal val WIRED_ADB_TYPED_HELPER_COMMAND: String = buildString {
    append("apk=")
    append('$')
    append("(pm path runtime.mobileagent | sed -n 's/^package://p' | head -n 1); ")
    append("[ -n \"")
    append('$')
    append("apk\" ] || exit 127; ")
    append("CLASSPATH=\"")
    append('$')
    append("apk\" exec app_process /system/bin runtime.mobileagent.bridge.AdbHelperMain")
}

/** Desktop copy of the Android helper’s path policy; no canonical host path is produced. */
internal object WiredAdbDesktopPathPolicy {
    fun parse(raw: String?, allowRoot: Boolean): String {
        val value = raw ?: if (allowRoot) "" else throw IllegalArgumentException("invalid path")
        if (value.isEmpty()) {
            require(allowRoot)
            return value
        }
        require(!value.contains('\u0000'))
        require(!value.contains('\\'))
        require(!value.contains(':'))
        require(Normalizer.normalize(value, Normalizer.Form.NFC) == value)
        require(!value.startsWith('/') && !value.endsWith('/') && !value.contains("//"))
        require(strictUtf8(value).size <= WIRED_MAX_PATH_BYTES)
        val pieces = value.split('/')
        require(pieces.size <= WIRED_MAX_PATH_DEPTH)
        pieces.forEach { piece ->
            val bytes = strictUtf8(piece)
            require(piece.isNotBlank() && piece != "." && piece != "..")
            require(bytes.size <= WIRED_MAX_SEGMENT_BYTES)
            require(piece.none { Character.isISOControl(it) })
        }
        return value
    }

    fun isValid(raw: String?, allowRoot: Boolean): Boolean = runCatching {
        parse(raw, allowRoot)
    }.isSuccess
}

internal const val WIRED_WORKSPACE_ID = "wired-adb"
internal const val WIRED_MAX_FILE_BYTES = 256 * 1024
internal const val WIRED_MAX_READ_BYTES = 24 * 1024
internal const val WIRED_MAX_ENTRIES = 512
internal const val WIRED_MAX_PATH_BYTES = 512
internal const val WIRED_MAX_SEGMENT_BYTES = 120
internal const val WIRED_MAX_PATH_DEPTH = 16
internal const val WIRED_ADB_FILE_DEADLINE_MS = 30_000L
private const val WIRED_ADB_HELPER_STDERR_CAP_BYTES = 64 * 1024

private val WIRED_ADB_HELPER_ERROR_CODES = setOf(
    "SHELL_UID_REQUIRED",
    "PROTOCOL_FRAME_INVALID",
    "REQUEST_INVALID",
    "TYPED_OPERATION_REQUIRED",
    "RESPONSE_TOO_LARGE",
    "FILE_INVALID_PATH",
    "FILE_INVALID_CONTENT",
    "FILE_LIMIT",
    "FILE_OUTSIDE_ROOT",
    "FILE_SYMLINK_FORBIDDEN",
    "FILE_NOT_FOUND",
    "FILE_TARGET_EXISTS",
    "FILE_NON_EMPTY_DIRECTORY",
    "FILE_UNSUPPORTED_ENTRY",
    "FILE_PERMISSION_DENIED",
    "FILE_OPERATION_UNAVAILABLE",
    "FILE_ATOMIC_REPLACE_UNAVAILABLE",
    "FILE_WRITE_UNVERIFIED",
)

private fun strictUtf8(value: String): ByteArray = try {
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val encoded = encoder.encode(CharBuffer.wrap(value))
    ByteArray(encoded.remaining()).also { encoded.get(it) }
} catch (_: CharacterCodingException) {
    throw IllegalArgumentException("invalid UTF-8")
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    throw IllegalArgumentException("invalid UTF-8")
}

private fun JsonObject.stringRequired(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("invalid string field")

private fun JsonObject.stringOrNull(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("invalid string field")
    else -> throw IllegalArgumentException("invalid string field")
}

private fun JsonObject.longOrNull(name: String): Long? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeUnless { it.isString }?.content?.toLongOrNull()
        ?: throw IllegalArgumentException("invalid integer field")
    else -> throw IllegalArgumentException("invalid integer field")
}

private fun JsonObject.booleanOrNull(name: String): Boolean? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("invalid boolean field")
    else -> throw IllegalArgumentException("invalid boolean field")
}

private fun JsonObject.arrayOrNull(name: String): JsonArray? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonArray -> value
    else -> throw IllegalArgumentException("invalid array field")
}
