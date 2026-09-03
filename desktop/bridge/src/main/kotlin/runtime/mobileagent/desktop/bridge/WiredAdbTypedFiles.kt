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
import runtime.mobileagent.bridge.BridgeHelperRequestEnvelope
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
    APPLY_PATCH(BridgeOperation.FILE_APPLY_PATCH.wireName),
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
            BridgeOperation.FILE_APPLY_PATCH -> APPLY_PATCH
            BridgeOperation.FILE_CREATE_DIRECTORY -> CREATE_DIRECTORY
            BridgeOperation.FILE_MOVE -> MOVE
            BridgeOperation.FILE_DELETE -> DELETE
            else -> throw IllegalArgumentException("typed file operation is unsupported")
        }
    }
}

enum class WiredAdbTypedPatchFormat { UNIFIED_DIFF, REPLACE }

/**
 * Strict, transport-neutral DTO handed to the fixed ADB executor.  The
 * desktop never accepts serial, endpoint, adb path, or command fields here.
 */
class WiredAdbTypedFileRequest private constructor(
    val requestId: String,
    val operation: WiredAdbTypedFileOperation,
    val workspaceId: String,
    val workspaceBinding: String?,
    val relativePath: String?,
    val destinationRelativePath: String?,
    val contentUtf8: ByteArray?,
    val overwrite: Boolean,
    val maxBytes: Int,
    val cursor: String?,
    val offsetBytes: Long,
    val maxEntries: Int,
    val patchUtf8: ByteArray?,
    val expectedVersion: Long?,
    val patchFormat: WiredAdbTypedPatchFormat,
) {
    fun contentCopy(): ByteArray? = contentUtf8?.copyOf()

    /** Rebuilds the exact helper payload, dropping any bridge-only extras. */
    fun toBridgeRequest(): BridgeRequestEnvelope = BridgeRequestEnvelope(
        protocolVersion = BridgeProtocol.VERSION,
        requestId = requestId,
        operation = operation.wireName,
        payload = buildJsonObject {
            put("workspace_id", workspaceId)
            workspaceBinding?.let { put("workspace_binding", it) }
            relativePath?.let { put("relative_path", it) }
            when (operation) {
                WiredAdbTypedFileOperation.STAT -> Unit
                WiredAdbTypedFileOperation.LIST -> {
                    put("max_entries", maxEntries)
                    cursor?.let { put("cursor", it) }
                }
                WiredAdbTypedFileOperation.READ_TEXT -> {
                    put("max_bytes", maxBytes)
                    put("offset_bytes", offsetBytes)
                }
                WiredAdbTypedFileOperation.WRITE_TEXT -> {
                    put("content", decodeUtf8(contentUtf8 ?: error("content is required")))
                    put("overwrite", overwrite)
                }
                WiredAdbTypedFileOperation.APPLY_PATCH -> {
                    put("patch", decodeUtf8(patchUtf8 ?: error("patch is required")))
                    put("expected_version", expectedVersion ?: error("version is required"))
                    put("format", if (patchFormat == WiredAdbTypedPatchFormat.REPLACE) "replace" else "unified_diff")
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

    /** Private helper frame; the device root is never part of a bridge request. */
    fun toHelperRequest(rootPath: String, fullDevice: Boolean): BridgeHelperRequestEnvelope {
        val binding = workspaceBinding ?: error("workspace binding is required")
        WiredAdbDesktopAbsolutePathPolicy.parse(rootPath)
        return BridgeHelperRequestEnvelope(
            protocolVersion = BridgeProtocol.VERSION,
            workspaceRootPath = rootPath,
            workspaceBinding = binding,
            fullDevice = fullDevice,
            request = toBridgeRequest(),
        )
    }

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
            val workspaceId = payload.stringRequired("workspace_id")
            require(workspaceId.matches(SAFE_WORKSPACE_ID))
            val workspaceBinding = payload.stringOrNull("workspace_binding")?.also {
                require(it.length == 64 && it.all { character -> character in "0123456789abcdefABCDEF" })
            }

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
            val offsetBytes = payload.longOrNull("offset_bytes")?.also {
                require(it >= 0L)
            } ?: 0L
            if (operation != WiredAdbTypedFileOperation.READ_TEXT) {
                require(payload.containsKey("offset_bytes").not())
            }
            val maxEntries = payload.longOrNull("max_entries")?.also {
                require(it in 1..WIRED_MAX_DIRECTORY_ENTRIES.toLong())
            }?.toInt() ?: WIRED_MAX_DIRECTORY_ENTRIES
            if (operation != WiredAdbTypedFileOperation.LIST) {
                require(payload.containsKey("max_entries").not())
            }
            val cursor = payload.stringOrNull("cursor")?.also {
                require(it.length in 1..WIRED_MAX_CURSOR_BYTES)
                require(it.all { character -> character.code in 0x21..0x7e })
            }
            if (operation != WiredAdbTypedFileOperation.LIST) {
                require(payload.containsKey("cursor").not())
            }
            val patch = payload.stringOrNull("patch")?.let(::strictUtf8)
            val expectedVersion = payload.longOrNull("expected_version")?.also {
                require(it >= 0L)
            }
            val format = payload.stringOrNull("format")?.let {
                when (it) {
                    "unified_diff" -> WiredAdbTypedPatchFormat.UNIFIED_DIFF
                    "replace" -> WiredAdbTypedPatchFormat.REPLACE
                    else -> throw IllegalArgumentException("patch format is invalid")
                }
            } ?: WiredAdbTypedPatchFormat.UNIFIED_DIFF
            if (operation == WiredAdbTypedFileOperation.APPLY_PATCH) {
                require(patch != null && expectedVersion != null)
                require(patch.size <= WIRED_MAX_PATCH_BYTES)
            } else {
                require(patch == null && expectedVersion == null && !payload.containsKey("format"))
            }
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
                workspaceId = workspaceId,
                workspaceBinding = workspaceBinding,
                relativePath = relativePath,
                destinationRelativePath = destination,
                contentUtf8 = content,
                overwrite = overwrite,
                maxBytes = maxBytes,
                cursor = cursor,
                offsetBytes = offsetBytes,
                maxEntries = maxEntries,
                patchUtf8 = patch,
                expectedVersion = expectedVersion,
                patchFormat = format,
            )
        }

        private fun allowedKeys(operation: WiredAdbTypedFileOperation): Set<String> = when (operation) {
            WiredAdbTypedFileOperation.LIST,
            WiredAdbTypedFileOperation.STAT -> setOf("workspace_id", "workspace_binding", "relative_path") +
                if (operation == WiredAdbTypedFileOperation.LIST) setOf("max_entries", "cursor") else emptySet()
            WiredAdbTypedFileOperation.READ_TEXT -> setOf(
                "workspace_id", "workspace_binding", "relative_path", "max_bytes", "offset_bytes",
            )
            WiredAdbTypedFileOperation.WRITE_TEXT -> setOf("workspace_id", "workspace_binding", "relative_path", "content", "overwrite")
            WiredAdbTypedFileOperation.APPLY_PATCH -> setOf(
                "workspace_id", "workspace_binding", "relative_path", "patch", "expected_version", "format",
            )
            WiredAdbTypedFileOperation.CREATE_DIRECTORY,
            WiredAdbTypedFileOperation.DELETE -> setOf("workspace_id", "workspace_binding", "relative_path", "recursive")
            WiredAdbTypedFileOperation.MOVE -> setOf(
                "workspace_id",
                "workspace_binding",
                "relative_path",
                "destination_relative_path",
                "overwrite",
            )
        }

        internal fun forWorkspaceRoot(
            requestId: String,
            operation: WiredAdbTypedFileOperation,
            workspaceId: String,
            workspaceBinding: String,
            relativePath: String?,
            maxBytes: Int = WIRED_MAX_READ_BYTES,
            cursor: String? = null,
            maxEntries: Int = WIRED_MAX_DIRECTORY_ENTRIES,
            offsetBytes: Long = 0L,
            patchUtf8: ByteArray? = null,
            expectedVersion: Long? = null,
            patchFormat: WiredAdbTypedPatchFormat = WiredAdbTypedPatchFormat.UNIFIED_DIFF,
        ): WiredAdbTypedFileRequest {
            require(workspaceId.matches(SAFE_WORKSPACE_ID))
            require(workspaceBinding.length == 64 && workspaceBinding.all { it in "0123456789abcdefABCDEF" })
            return WiredAdbTypedFileRequest(
                requestId = requestId,
                operation = operation,
                workspaceId = workspaceId,
                workspaceBinding = workspaceBinding,
                relativePath = relativePath,
                destinationRelativePath = null,
                contentUtf8 = null,
                overwrite = false,
                maxBytes = maxBytes,
                cursor = cursor,
                offsetBytes = offsetBytes,
                maxEntries = maxEntries,
                patchUtf8 = patchUtf8,
                expectedVersion = expectedVersion,
                patchFormat = patchFormat,
            )
        }
    }
}

/** A typed file executor supplied by the authenticated Desktop handler. */
fun interface WiredAdbTypedFileExecutor {
    fun execute(request: WiredAdbTypedFileRequest, cancellation: BridgeCancellation): BridgeResponseEnvelope
}

/** Typed helper execution rooted at one authenticated, connection-local bind. */
fun interface WiredAdbBoundTypedFileExecutor {
    fun executeAtRoot(
        request: WiredAdbTypedFileRequest,
        rootPath: String,
        fullDevice: Boolean,
        cancellation: BridgeCancellation,
    ): BridgeResponseEnvelope
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
) : WiredAdbTypedFileExecutor, WiredAdbBoundTypedFileExecutor {
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

    override fun executeAtRoot(
        request: WiredAdbTypedFileRequest,
        rootPath: String,
        fullDevice: Boolean,
        cancellation: BridgeCancellation,
    ): BridgeResponseEnvelope {
        if (cancellation.isRequested) return unknown(request.requestId)
        require(request.workspaceBinding != null)
        val frame = try {
            WiredAdbHelperFrameCodec.encode(request.toHelperRequest(rootPath, fullDevice))
        } catch (_: Throwable) {
            return unknown(request.requestId)
        }
        return executeFrame(frame, request, cancellation)
    }

    private fun executeFrame(
        frame: ByteArray,
        request: WiredAdbTypedFileRequest,
        cancellation: BridgeCancellation,
    ): BridgeResponseEnvelope {
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
        ) return unknown(request.requestId)
        return try {
            WiredAdbHelperFrameCodec.decodeResponse(process.stdout, request)
        } catch (_: Throwable) {
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

    fun encode(request: BridgeHelperRequestEnvelope): ByteArray {
        val body = BridgeCodec.encodeHelperRequest(request)
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
        payload.longOrNull("version")?.also { require(it >= 0L) }
        payload.longOrNull("offset_bytes")?.also { require(it >= 0L) }
        payload.longOrNull("total_bytes")?.also { require(it >= 0L) }
        payload.booleanOrNull("eof")
        payload.stringOrNull("next_cursor")?.also {
            require(it.length in 1..WIRED_MAX_CURSOR_BYTES)
            require(it.all { character -> character.code in 0x21..0x7e })
        }
        val skippedEntries = payload.longOrNull("skipped_entries") ?: 0L
        require(skippedEntries in 0..100_000)
        val warnings = payload.arrayOrNull("warnings")
        if (warnings != null) {
            require(warnings.size <= 8)
            val seen = HashSet<String>()
            var warningCount = 0L
            warnings.forEach { element ->
                val warning = element as? JsonObject ?: throw IllegalArgumentException("listing warning is invalid")
                require(warning.keys == setOf("code", "count"))
                val code = warning.stringRequired("code")
                require(code in LISTING_WARNING_CODES && seen.add(code))
                val count = warning.longOrNull("count") ?: throw IllegalArgumentException("listing warning count is missing")
                require(count in 1..100_000)
                warningCount += count
            }
            require(warningCount == skippedEntries)
        } else {
            require(skippedEntries == 0L)
        }
        listOf("created", "replaced", "deleted").forEach { payload.booleanOrNull(it) }
        payload.booleanOrNull("truncated")
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
                require(payload.longOrNull("offset_bytes") != null)
                require(payload.longOrNull("total_bytes") != null)
                require(payload.booleanOrNull("eof") != null)
            }
            WiredAdbTypedFileOperation.WRITE_TEXT -> {
                require(payload.longOrNull("bytes") != null)
                require(payload.booleanOrNull("created") != null)
                require(payload.booleanOrNull("replaced") != null)
            }
            WiredAdbTypedFileOperation.APPLY_PATCH -> {
                require(payload.longOrNull("bytes") != null)
                require(payload.longOrNull("version") != null)
            }
            WiredAdbTypedFileOperation.CREATE_DIRECTORY -> require(payload.booleanOrNull("created") != null)
            WiredAdbTypedFileOperation.MOVE -> require(payload.booleanOrNull("replaced") != null)
            WiredAdbTypedFileOperation.DELETE -> require(payload.booleanOrNull("deleted") != null)
        }
    }

    private fun resultKeys(operation: WiredAdbTypedFileOperation): Set<String> = when (operation) {
        WiredAdbTypedFileOperation.LIST -> setOf(
            "operation", "relative_path", "entries", "truncated", "next_cursor", "skipped_entries", "warnings", "version",
        )
        WiredAdbTypedFileOperation.STAT -> setOf("operation", "relative_path", "entries", "bytes", "version")
        WiredAdbTypedFileOperation.READ_TEXT -> setOf(
            "operation", "relative_path", "text", "bytes", "version", "offset_bytes", "total_bytes", "eof",
        )
        WiredAdbTypedFileOperation.WRITE_TEXT -> setOf("operation", "relative_path", "bytes", "created", "replaced", "version")
        WiredAdbTypedFileOperation.APPLY_PATCH -> setOf("operation", "relative_path", "bytes", "version")
        WiredAdbTypedFileOperation.CREATE_DIRECTORY -> setOf("operation", "relative_path", "created", "version")
        WiredAdbTypedFileOperation.MOVE -> setOf("operation", "relative_path", "bytes", "replaced", "version")
        WiredAdbTypedFileOperation.DELETE -> setOf("operation", "relative_path", "deleted", "version")
    }

    private val ENTRY_KEYS = setOf("relative_path", "type", "bytes", "version")
    private val LISTING_WARNING_CODES = setOf(
        "SYMLINK_SKIPPED",
        "UNSUPPORTED_ENTRY_SKIPPED",
        "TRANSIENT_ENTRY_SKIPPED",
        "UNREADABLE_ENTRY_SKIPPED",
        "METADATA_UNAVAILABLE",
    )
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

/** Absolute device path accepted only in a user-originated workspace attach. */
internal object WiredAdbDesktopAbsolutePathPolicy {
    fun parse(raw: String): String {
        require(raw.isNotEmpty() && raw.startsWith('/'))
        require(!raw.contains('\u0000') && !raw.contains('\\') && !raw.contains(':'))
        require(Normalizer.normalize(raw, Normalizer.Form.NFC) == raw)
        require(raw == "/" || !raw.endsWith('/') && !raw.contains("//"))
        val pieces = raw.split('/').drop(1)
        require(raw == "/" || pieces.isNotEmpty())
        require(pieces.size <= 64)
        pieces.forEach { piece ->
            require(piece.isNotEmpty() && piece != "." && piece != "..")
            require(strictUtf8(piece).size <= WIRED_MAX_SEGMENT_BYTES)
            require(piece.none(Character::isISOControl))
        }
        require(strictUtf8(raw).size <= BridgeProtocol.MAX_DEVICE_PATH_BYTES)
        return raw
    }
}

internal const val WIRED_WORKSPACE_ID = "wired-adb"
internal const val WIRED_MAX_FILE_BYTES = 256 * 1024
internal const val WIRED_MAX_READ_BYTES = 256 * 1024
internal const val WIRED_MAX_PATCH_BYTES = 768 * 1024
internal const val WIRED_MAX_CURSOR_BYTES = 512
internal const val WIRED_MAX_DIRECTORY_ENTRIES = 256
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
    "FILE_TOO_LARGE",
    "FILE_CONFLICT",
    "FILE_OFFSET_OUT_OF_RANGE",
    "FILE_INVALID_PATCH",
    "FILE_INVALID_CURSOR",
    "ROOT_BACKEND_UNAVAILABLE",
    "ROOT_PATH_INVALID",
)

private val SAFE_WORKSPACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")

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
