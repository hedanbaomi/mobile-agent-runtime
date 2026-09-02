// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.bridge

import android.os.Process
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.wired.PrivilegedFileEngine
import runtime.mobileagent.wired.WiredAdbEntryType
import runtime.mobileagent.wired.WiredAdbFileEngineResult
import runtime.mobileagent.wired.WiredAdbFileOperation
import runtime.mobileagent.wired.WiredAdbFileRequest
import runtime.mobileagent.wired.WiredAdbFileResult
import runtime.mobileagent.wired.WiredAdbRequestId
import runtime.mobileagent.wired.newWiredAdbRequestId
import runtime.mobileagent.wired.WIRED_MAX_FILE_BYTES
import runtime.mobileagent.wired.WIRED_MAX_READ_BYTES
import runtime.mobileagent.wired.WIRED_MAX_PATCH_BYTES
import runtime.mobileagent.wired.WIRED_MAX_CURSOR_BYTES
import runtime.mobileagent.wired.WIRED_MAX_DIRECTORY_ENTRIES
import runtime.mobileagent.wired.WiredAdbPathPolicy
import runtime.mobileagent.wired.WIRED_WORKSPACE_ID

private val SAFE_WORKSPACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val BINDING_HEX = Regex("[0-9a-fA-F]{64}")

/**
 * Shell-UID entry point for typed workspace file operations only.
 *
 * The companion invokes this class through a fixed `adb shell` command. The
 * helper has no secret, no endpoint, no serial, and no command execution API.
 * It consumes and emits the shared bridge JSON envelopes with a strict
 * four-byte big-endian length prefix. Dangerous shell requests are rejected
 * before dispatch and are handled by the companion's fixed adb shell path.
 */
object AdbHelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        AdbHelperServer(
            input = System.`in`,
            output = System.out,
            engine = runtime.mobileagent.wired.NioPrivilegedFileEngine(),
        ).run()
    }
}

class AdbHelperServer(
    private val input: InputStream,
    private val output: OutputStream,
    private val engine: PrivilegedFileEngine,
    private val uidProvider: () -> Int = { Process.myUid() },
    private val maxFrameBytes: Int = BridgeProtocol.MAX_FRAME_BYTES,
) {
    init {
        require(maxFrameBytes in 1..BridgeProtocol.MAX_FRAME_BYTES)
    }

    /** Runs until EOF or the first malformed/unauthorized frame. */
    fun run(): Int {
        if (uidProvider() != Process.SHELL_UID) {
            writeFailure(newWiredAdbRequestId(), ERR_SHELL_UID_REQUIRED)
            return EXIT_UNAUTHORIZED
        }
        return try {
            while (true) {
                val frame = readFrame() ?: break
                val response = process(frame)
                writeResponse(response)
            }
            EXIT_OK
        } catch (_: Throwable) {
            // The stream is intentionally fail-closed. No exception text is emitted.
            EXIT_PROTOCOL_ERROR
        }
    }

    private fun process(frame: ByteArray): BridgeResponseEnvelope {
        val helperEnvelope = runCatching { BridgeCodec.decodeHelperRequest(frame) }.getOrNull()
        val request = helperEnvelope?.request ?: try {
            BridgeCodec.decodeRequest(frame)
        } catch (_: Throwable) {
            return failure(newWiredAdbRequestId().value, ERR_PROTOCOL_FRAME_INVALID)
        }
        val dispatchEngine = if (helperEnvelope == null) {
            engine
        } else {
            val scoped = engine as? runtime.mobileagent.wired.RootScopedPrivilegedFileEngine
                ?: return failure(request.requestId, ERR_ROOT_BACKEND_UNAVAILABLE)
            runCatching {
                scoped.forRoot(
                    helperEnvelope.workspaceRootPath,
                    helperEnvelope.fullDevice,
                    helperEnvelope.workspaceBinding,
                )
            }.getOrElse { return failure(request.requestId, ERR_ROOT_PATH_INVALID) }
        }
        val operation = runCatching { BridgeOperation.parse(request.operation) }.getOrNull()
            ?: return failure(request.requestId, ERR_REQUEST_INVALID)
        val fileOperation = when (operation) {
            BridgeOperation.FILE_LIST -> WiredAdbFileOperation.LIST
            BridgeOperation.FILE_STAT -> WiredAdbFileOperation.STAT
            BridgeOperation.FILE_READ_TEXT -> WiredAdbFileOperation.READ_TEXT
            BridgeOperation.FILE_WRITE_TEXT -> WiredAdbFileOperation.WRITE_TEXT
            BridgeOperation.FILE_APPLY_PATCH -> WiredAdbFileOperation.APPLY_PATCH
            BridgeOperation.FILE_CREATE_DIRECTORY -> WiredAdbFileOperation.CREATE_DIRECTORY
            BridgeOperation.FILE_MOVE -> WiredAdbFileOperation.MOVE
            BridgeOperation.FILE_DELETE -> WiredAdbFileOperation.DELETE
            else -> return failure(request.requestId, ERR_TYPED_OPERATION_REQUIRED)
        }
        val typed = runCatching {
            val dynamicWorkspace = helperEnvelope != null
            validateFilePayloadKeys(fileOperation, request.payload, dynamicWorkspace)
            typedRequest(request.requestId, fileOperation, request.payload, dynamicWorkspace)
        }
            .getOrElse { return failure(request.requestId, ERR_REQUEST_INVALID) }
        return when (val result = dispatchEngine.execute(typed)) {
            is WiredAdbFileEngineResult.Success -> success(request.requestId, result.result)
            is WiredAdbFileEngineResult.Failure -> failure(request.requestId, "FILE_${result.code}")
        }
    }

    private fun typedRequest(
        requestId: String,
        operation: WiredAdbFileOperation,
        payload: JsonObject,
        allowArbitraryWorkspace: Boolean,
    ): WiredAdbFileRequest {
        val id = WiredAdbRequestId(requestId)
        val workspaceId = payload.stringOrNull("workspace_id")
        if (allowArbitraryWorkspace) {
            require(workspaceId != null && workspaceId.matches(SAFE_WORKSPACE_ID))
            require(payload.stringOrNull("workspace_binding")?.matches(BINDING_HEX) == true)
        } else {
            require(workspaceId == WIRED_WORKSPACE_ID)
        }
        val relativePath = payload.stringOrNull("relative_path")
        val destination = payload.stringOrNull("destination_relative_path")
        WiredAdbPathPolicy.parse(relativePath, allowRoot = operation == WiredAdbFileOperation.LIST)
        if (operation == WiredAdbFileOperation.MOVE) {
            WiredAdbPathPolicy.parse(destination, allowRoot = false)
        }
        val content = payload.stringOrNull("content")?.let(::strictUtf8)
        require(content == null || content.size <= WIRED_MAX_FILE_BYTES)
        val maxBytes = payload.longOrNull("max_bytes")?.also {
            require(it in 1..WIRED_MAX_READ_BYTES)
        }?.toInt() ?: WIRED_MAX_READ_BYTES
        val offsetBytes = payload.longOrNull("offset_bytes")?.also { require(it >= 0L) } ?: 0L
        if (operation != WiredAdbFileOperation.READ_TEXT) require(!payload.containsKey("offset_bytes"))
        val maxEntries = payload.longOrNull("max_entries")?.also {
            require(it in 1..WIRED_MAX_DIRECTORY_ENTRIES)
        }?.toInt() ?: WIRED_MAX_DIRECTORY_ENTRIES
        if (operation != WiredAdbFileOperation.LIST) require(!payload.containsKey("max_entries"))
        val cursor = payload.stringOrNull("cursor")?.also {
            require(it.length in 1..WIRED_MAX_CURSOR_BYTES && it.all { character -> character.code in 0x21..0x7e })
        }
        if (operation != WiredAdbFileOperation.LIST) require(!payload.containsKey("cursor"))
        val patch = payload.stringOrNull("patch")?.let(::strictUtf8)
        val expectedVersion = payload.longOrNull("expected_version")?.also { require(it >= 0L) }
        val patchFormat = payload.stringOrNull("format")?.let {
            when (it) {
                "unified_diff" -> runtime.mobileagent.wired.WiredAdbPatchFormat.UNIFIED_DIFF
                "replace" -> runtime.mobileagent.wired.WiredAdbPatchFormat.REPLACE
                else -> throw IllegalArgumentException("invalid patch format")
            }
        } ?: runtime.mobileagent.wired.WiredAdbPatchFormat.UNIFIED_DIFF
        if (operation == WiredAdbFileOperation.APPLY_PATCH) {
            require(patch != null && expectedVersion != null && patch.size <= WIRED_MAX_PATCH_BYTES)
        } else {
            require(patch == null && expectedVersion == null && !payload.containsKey("format"))
        }
        val overwrite = payload.booleanOrNull("overwrite") ?: false
        return WiredAdbFileRequest(
            requestId = id,
            operation = operation,
            relativePath = relativePath,
            destinationRelativePath = destination,
            contentUtf8 = content,
            replaceExisting = overwrite,
            maxBytes = maxBytes,
            cursor = cursor,
            maxEntries = maxEntries,
            offsetBytes = offsetBytes,
            patchUtf8 = patch,
            expectedVersion = expectedVersion,
            patchFormat = patchFormat,
            workspaceBinding = payload.stringOrNull("workspace_binding"),
        )
    }

    /** Do not silently ignore a field that could change the helper contract. */
    private fun validateFilePayloadKeys(
        operation: WiredAdbFileOperation,
        payload: JsonObject,
        allowArbitraryWorkspace: Boolean,
    ) {
        val workspaceKeys = if (allowArbitraryWorkspace) {
            setOf("workspace_id", "workspace_binding")
        } else {
            setOf("workspace_id")
        }
        val allowed = when (operation) {
            WiredAdbFileOperation.LIST -> workspaceKeys + setOf("relative_path", "max_entries", "cursor")
            WiredAdbFileOperation.STAT -> workspaceKeys + "relative_path"
            WiredAdbFileOperation.READ_TEXT -> workspaceKeys + setOf("relative_path", "max_bytes", "offset_bytes")
            WiredAdbFileOperation.APPLY_PATCH -> workspaceKeys + setOf(
                "relative_path", "patch", "expected_version", "format",
            )
            WiredAdbFileOperation.WRITE_TEXT -> workspaceKeys + setOf("relative_path", "content", "overwrite")
            WiredAdbFileOperation.CREATE_DIRECTORY,
            WiredAdbFileOperation.DELETE -> workspaceKeys + setOf("relative_path", "recursive")
            WiredAdbFileOperation.MOVE -> workspaceKeys + setOf(
                "relative_path",
                "destination_relative_path",
                "overwrite",
            )
        }
        require(payload.keys.all { it in allowed })
        if (operation == WiredAdbFileOperation.CREATE_DIRECTORY || operation == WiredAdbFileOperation.DELETE) {
            require(payload.booleanOrNull("recursive") == false)
        }
    }

    private fun success(requestId: String, result: WiredAdbFileResult): BridgeResponseEnvelope =
        BridgeResponseEnvelope(
            protocolVersion = BridgeProtocol.VERSION,
            requestId = requestId,
            success = true,
            payload = resultPayload(result),
        )

    private fun resultPayload(result: WiredAdbFileResult): JsonObject = buildJsonObject {
        put("operation", operationName(result.operation))
        result.relativePath?.let { put("relative_path", it) }
        result.text?.let { put("text", it) }
        result.bytes?.let { put("bytes", it) }
        result.created?.let { put("created", it) }
        result.replaced?.let { put("replaced", it) }
        result.deleted?.let { put("deleted", it) }
        if (result.truncated) put("truncated", true)
        result.nextCursor?.let { put("next_cursor", it) }
        result.version?.let { put("version", it) }
        if (result.operation == WiredAdbFileOperation.READ_TEXT) {
            put("offset_bytes", result.offsetBytes)
            result.totalBytes?.let { put("total_bytes", it) }
            put("eof", result.eof)
        }
        if (result.entries.isNotEmpty()) {
            kotlinx.serialization.json.buildJsonArray {
                result.entries.forEach { entry ->
                    add(buildJsonObject {
                        put("relative_path", entry.relativePath)
                        put("type", if (entry.type == WiredAdbEntryType.FILE) "file" else "directory")
                        entry.bytes?.let { put("bytes", it) }
                        entry.version?.let { put("version", it) }
                    })
                }
            }.also { array ->
                put("entries", array)
            }
        }
    }

    private fun operationName(operation: WiredAdbFileOperation): String = when (operation) {
        WiredAdbFileOperation.LIST -> "file_list"
        WiredAdbFileOperation.STAT -> "file_stat"
        WiredAdbFileOperation.READ_TEXT -> "file_read_text"
        WiredAdbFileOperation.WRITE_TEXT -> "file_write_text"
        WiredAdbFileOperation.APPLY_PATCH -> "file_apply_patch"
        WiredAdbFileOperation.CREATE_DIRECTORY -> "file_create_directory"
        WiredAdbFileOperation.MOVE -> "file_move"
        WiredAdbFileOperation.DELETE -> "file_delete"
    }

    private fun writeFailure(requestId: WiredAdbRequestId, code: String) {
        writeResponse(failure(requestId.value, code))
    }

    private fun failure(requestId: String, code: String): BridgeResponseEnvelope =
        BridgeResponseEnvelope(
            protocolVersion = BridgeProtocol.VERSION,
            requestId = requestId,
            success = false,
            errorCode = code,
            errorMessage = null,
        )

    private fun writeResponse(response: BridgeResponseEnvelope) {
        val bytes = runCatching { BridgeCodec.encodeResponse(response) }.getOrElse {
            BridgeCodec.encodeResponse(failure(response.requestId, ERR_RESPONSE_TOO_LARGE))
        }
        if (bytes.size > maxFrameBytes - 4) throw IOException("helper response is too large")
        val length = bytes.size
        val header = byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    private fun readFrame(): ByteArray? {
        val header = ByteArray(4)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readExactly(header, 1, 3)
        val length = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        if (length !in 1..maxFrameBytes - 4) throw IOException("helper frame is too large")
        return ByteArray(length).also { readExactly(it, 0, length) }
    }

    private fun readExactly(target: ByteArray, offset: Int, length: Int) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = input.read(target, position, end - position)
            if (count < 0) throw EOFException("helper frame ended")
            if (count == 0) continue
            position += count
        }
    }

    private fun strictUtf8(value: String): ByteArray = try {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        bytes
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("invalid UTF-8", error)
    }

    private fun JsonObject.stringOrNull(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("field is not a string")
        else -> throw IllegalArgumentException("field is not a string")
    }

    private fun JsonObject.longOrNull(name: String): Long? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeUnless { it.isString }?.content?.toLongOrNull()
            ?: throw IllegalArgumentException("field is not an integer")
        else -> throw IllegalArgumentException("field is not an integer")
    }

    private fun JsonObject.booleanOrNull(name: String): Boolean? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("field is not boolean")
        else -> throw IllegalArgumentException("field is not boolean")
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_UNAUTHORIZED = 126
        const val EXIT_PROTOCOL_ERROR = 2
        const val ERR_SHELL_UID_REQUIRED = "SHELL_UID_REQUIRED"
        const val ERR_PROTOCOL_FRAME_INVALID = "PROTOCOL_FRAME_INVALID"
        const val ERR_REQUEST_INVALID = "REQUEST_INVALID"
        const val ERR_TYPED_OPERATION_REQUIRED = "TYPED_OPERATION_REQUIRED"
        const val ERR_RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE"
        const val ERR_ROOT_BACKEND_UNAVAILABLE = "ROOT_BACKEND_UNAVAILABLE"
        const val ERR_ROOT_PATH_INVALID = "ROOT_PATH_INVALID"
    }
}
