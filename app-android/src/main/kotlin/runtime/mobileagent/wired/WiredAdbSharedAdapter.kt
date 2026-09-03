// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.bridge.BridgeCodec
import runtime.mobileagent.bridge.BridgeErrorCodes
import runtime.mobileagent.bridge.BridgeDecodedFrame
import runtime.mobileagent.bridge.BridgeDirection
import runtime.mobileagent.bridge.BridgeFrameType
import runtime.mobileagent.bridge.BridgePairCommitAck
import runtime.mobileagent.bridge.BridgePairChallenge
import runtime.mobileagent.bridge.BridgePairFinished
import runtime.mobileagent.bridge.BridgePairResponse
import runtime.mobileagent.bridge.BridgePairingClient
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.BridgeProtocolException
import runtime.mobileagent.bridge.BridgeRequestEnvelope
import runtime.mobileagent.bridge.BridgeResponseEnvelope
import runtime.mobileagent.bridge.BridgeStatusEnvelope
import runtime.mobileagent.bridge.BridgeSession
import runtime.mobileagent.bridge.BridgeSessionHandshake
import runtime.mobileagent.bridge.BridgeSessionHello
import runtime.mobileagent.bridge.BridgeSessionWelcome
import runtime.mobileagent.bridge.BridgeWorkspaceAttachRequest
import runtime.mobileagent.bridge.BridgeWorkspaceReopenRequest
import runtime.mobileagent.bridge.PairingToken

/**
 * Android-only typed adapter over the shared bridge-protocol module.
 *
 * This file intentionally contains no wire codec or cryptographic primitive.
 * Control messages are encoded by [BridgeCodec], authenticated frames by
 * [BridgeSession]/[BridgeFrameCodec], and pairing/session keys by the shared
 * implementations. The loopback channel only supplies length-delimited byte
 * frames.
 */
internal object WiredAdbSharedAdapter {
    fun newPairingClient(
        appInstanceId: String,
        token: ByteArray,
        issuedAtMs: Long,
        expiresAtMs: Long,
    ): BridgePairingClient {
        requireIdentityPart(appInstanceId, "appInstanceId")
        require(token.size == BridgeProtocol.TOKEN_BYTES)
        val pairingToken = PairingToken.create(issuedAtMs, expiresAtMs, token)
        return try {
            BridgePairingClient(appInstanceId, pairingToken)
        } finally {
            pairingToken.close()
        }
    }

    fun decodePairChallenge(bytes: ByteArray): BridgePairChallenge = BridgeCodec.decodePairChallenge(bytes)

    fun decodePairFinished(bytes: ByteArray): BridgePairFinished = BridgeCodec.decodePairFinished(bytes)

    /**
     * Pairing starts with the Android-generated token and hello in one strict
     * control envelope.  The token is never logged or persisted by this
     * adapter; [BridgePairingClient.startEnvelope] returns a transient copy.
     */
    fun encodePairStart(client: BridgePairingClient): ByteArray =
        BridgeCodec.encodePairStart(client.startEnvelope())

    fun encodePairResponse(response: BridgePairResponse): ByteArray = BridgeCodec.encodePairResponse(response)

    fun encodePairCommitAck(ack: BridgePairCommitAck): ByteArray = BridgeCodec.encodePairCommitAck(ack)

    fun encodeSessionHello(hello: BridgeSessionHello): ByteArray = BridgeCodec.encodeSessionHello(hello)

    fun decodeSessionWelcome(bytes: ByteArray): BridgeSessionWelcome = BridgeCodec.decodeSessionWelcome(bytes)

    fun newSessionHello(): BridgeSessionHello = runtime.mobileagent.bridge.BridgeSessionHandshake.newHello()

    fun completeSession(
        hello: BridgeSessionHello,
        welcome: BridgeSessionWelcome,
        persistentTrust: ByteArray,
        transcriptHash: ByteArray,
    ): BridgeSession {
        val trust = runtime.mobileagent.bridge.SecretBytes.from(persistentTrust)
        return try {
            BridgeSessionHandshake.complete(hello, welcome, trust, transcriptHash)
        } finally {
            trust.close()
        }
    }

    fun encodeRequest(
        session: BridgeSession,
        requestId: WiredAdbRequestId,
        operation: String,
        payload: JsonObject,
    ): ByteArray {
        val envelope = BridgeRequestEnvelope(
            protocolVersion = BridgeProtocol.VERSION,
            requestId = requestId.value,
            operation = operation,
            payload = payload,
        )
        return session.encrypt(
            type = BridgeFrameType.REQUEST,
            requestId = requestId.value,
            plaintext = BridgeCodec.encodeRequest(envelope),
        )
    }

    fun encodeCancel(session: BridgeSession, requestId: WiredAdbRequestId, target: WiredAdbRequestId): ByteArray {
        val cancel = runtime.mobileagent.bridge.BridgeCancelRequest(
            protocolVersion = BridgeProtocol.VERSION,
            requestId = requestId.value,
            targetRequestId = target.value,
        )
        return session.encrypt(
            type = BridgeFrameType.CANCEL,
            requestId = requestId.value,
            plaintext = BridgeCodec.encodeCancel(cancel),
        )
    }

    /**
     * Decrypt exactly once and leave frame-type dispatch to the caller.  A
     * session receive sequence advances during decrypt, so callers must not
     * probe a frame with decodeResponse and then retry it as STATUS.
     */
    fun decodeEncryptedFrame(session: BridgeSession, frame: ByteArray): BridgeDecodedFrame {
        val decoded = session.decrypt(frame)
        require(decoded.direction == BridgeDirection.S2C) { "bridge response direction is invalid" }
        return decoded
    }

    fun decodeResponse(session: BridgeSession, frame: ByteArray): BridgeResponseEnvelope =
        decodeResponse(decodeEncryptedFrame(session, frame))

    fun decodeResponse(decoded: BridgeDecodedFrame): BridgeResponseEnvelope {
        require(decoded.type == BridgeFrameType.RESPONSE) { "bridge response type is invalid" }
        require(decoded.requestId == decodedRequestId(decoded.payload))
        return BridgeCodec.decodeResponse(decoded.payload)
    }

    fun decodeStatus(decoded: BridgeDecodedFrame): BridgeStatusEnvelope {
        require(decoded.type == BridgeFrameType.STATUS) { "bridge status type is invalid" }
        val status = try {
            BridgeCodec.decodeStatus(decoded.payload)
        } catch (error: Throwable) {
            throw BridgeProtocolException("invalid bridge status", error)
        }
        if (decoded.requestId != status.requestId) {
            throw BridgeProtocolException("bridge status request identity mismatch")
        }
        return status
    }

    /** The request ID is decoded before envelope validation to bind frame AAD to its body. */
    private fun decodedRequestId(bytes: ByteArray): String = runCatching {
        BridgeCodec.decodeResponse(bytes).requestId
    }.getOrElse {
        // Never expose the parse exception to diagnostics; the caller maps it to protocol failure.
        throw BridgeProtocolException("invalid bridge response")
    }

    fun fileOperation(request: WiredAdbFileRequest): Pair<String, JsonObject> =
        fileOperation(request, WIRED_WORKSPACE_ID, null)

    fun fileOperation(
        request: WiredAdbFileRequest,
        workspaceId: String,
        workspaceBinding: String?,
    ): Pair<String, JsonObject> = when (request.operation) {
        WiredAdbFileOperation.LIST -> "file_list" to filePayload(request, workspaceId, workspaceBinding) {
            put("max_entries", request.maxEntries)
            request.cursor?.let { put("cursor", it) }
        }
        WiredAdbFileOperation.STAT -> "file_stat" to filePayload(request, workspaceId, workspaceBinding) { }
        WiredAdbFileOperation.READ_TEXT -> "file_read_text" to filePayload(request, workspaceId, workspaceBinding) {
            put("max_bytes", request.maxBytes)
            put("offset_bytes", request.offsetBytes)
        }
        WiredAdbFileOperation.WRITE_TEXT -> "file_write_text" to filePayload(request, workspaceId, workspaceBinding) {
            val content = request.contentUtf8 ?: throw BridgeProtocolException("file content is required")
            put("content", decodeUtf8(content))
            put("overwrite", request.replaceExisting)
        }
        WiredAdbFileOperation.APPLY_PATCH -> "file_apply_patch" to filePayload(request, workspaceId, workspaceBinding) {
            val patch = request.patchUtf8 ?: throw BridgeProtocolException("file patch is required")
            put("patch", decodeUtf8(patch))
            put("expected_version", request.expectedVersion ?: throw BridgeProtocolException("file version is required"))
            put("format", if (request.patchFormat == WiredAdbPatchFormat.REPLACE) "replace" else "unified_diff")
        }
        WiredAdbFileOperation.CREATE_DIRECTORY -> "file_create_directory" to filePayload(request, workspaceId, workspaceBinding) {
            put("recursive", false)
        }
        WiredAdbFileOperation.MOVE -> "file_move" to filePayload(request, workspaceId, workspaceBinding) {
            put("destination_relative_path", request.destinationRelativePath)
            put("overwrite", request.replaceExisting)
        }
        WiredAdbFileOperation.DELETE -> "file_delete" to filePayload(request, workspaceId, workspaceBinding) {
            put("recursive", false)
        }
    }

    fun workspaceAttachOperation(
        workspaceId: String,
        binding: String,
        displayName: String,
        absolutePath: String,
        scope: WiredAdbWorkspaceScope,
        grantRevision: Long,
        confirmedByUser: Boolean,
    ): Pair<String, JsonObject> = "workspace_attach" to BridgeCodec.encodeWorkspaceAttachPayload(
        BridgeWorkspaceAttachRequest(
            workspaceId = workspaceId,
            workspaceBinding = binding,
            displayName = displayName,
            absolutePath = absolutePath,
            scope = scope.wireName(),
            grantRevision = grantRevision,
            confirmedByUser = confirmedByUser,
        ),
    )

    fun workspaceReopenOperation(
        workspaceId: String,
        binding: String,
        recoveryLocator: String,
        scope: WiredAdbWorkspaceScope,
    ): Pair<String, JsonObject> = "workspace_reopen" to BridgeCodec.encodeWorkspaceReopenPayload(
        BridgeWorkspaceReopenRequest(
            workspaceId = workspaceId,
            workspaceBinding = binding,
            recoveryLocator = recoveryLocator,
            scope = scope.wireName(),
        ),
    )

    fun workspaceBrowseOperation(
        workspaceId: String,
        binding: String,
        relativePath: String,
        maxEntries: Int,
        cursor: String? = null,
    ): Pair<String, JsonObject> = "workspace_browse" to buildJsonObject {
        put("workspace_id", workspaceId)
        put("workspace_binding", binding)
        put("relative_path", relativePath)
        put("max_entries", maxEntries)
        cursor?.let { put("cursor", it) }
    }

    fun workspaceReleaseOperation(workspaceId: String, binding: String): Pair<String, JsonObject> =
        "workspace_release" to buildJsonObject {
            put("workspace_id", workspaceId)
            put("workspace_binding", binding)
        }

    fun shellOperation(request: WiredAdbShellRequest): Pair<String, JsonObject> =
        "shell_exec" to buildJsonObject {
            put("command", request.command)
            request.cwd?.let { put("cwd", it) }
            put("timeout_ms", request.timeoutMs)
            put("max_output_bytes", request.maxOutputBytes)
        }

    fun decodeFileResult(response: BridgeResponseEnvelope, operation: WiredAdbFileOperation): WiredAdbFileResult {
        if (!response.success) throw BridgeProtocolException("file request failed")
        val payload = response.payload ?: throw BridgeProtocolException("file response payload is missing")
        require(payload.keys.all { it in FILE_RESULT_KEYS })
        val responseOperation = payload.string("operation")?.let(::parseFileOperation)
        require(responseOperation == null || responseOperation == operation)
        val resultPath = payload.stringOrNull("relative_path")
        WiredAdbPathPolicy.parse(resultPath, allowRoot = true)
        val entries = payload.array("entries")?.map { element ->
            val item = element as? JsonObject ?: throw BridgeProtocolException("file entry is invalid")
            val path = item.string("relative_path") ?: throw BridgeProtocolException("file entry path is missing")
            val type = when (item.string("type")) {
                "file" -> WiredAdbEntryType.FILE
                "directory", "dir" -> WiredAdbEntryType.DIRECTORY
                else -> throw BridgeProtocolException("file entry type is invalid")
            }
            WiredAdbPathPolicy.parse(path, allowRoot = false)
            WiredAdbFileEntry(path, type, item.long("bytes"), item.long("version"))
        } ?: emptyList()
        require(entries.size <= WIRED_MAX_ENTRIES)
        val nextCursor = payload.stringOrNull("next_cursor")?.also(::validateCursor)
        val skippedEntries = payload.int("skipped_entries") ?: 0
        require(skippedEntries in 0..runtime.mobileagent.skills.tooling.WorkspaceListing.MAX_SKIPPED_ENTRIES)
        val listingWarnings = decodeListingWarnings(payload.array("warnings"))
        require(listingWarnings.sumOf { it.count } == skippedEntries)
        val version = payload.long("version")?.also { require(it >= 0L) }
        val offsetBytes = payload.long("offset_bytes") ?: 0L
        require(offsetBytes >= 0L)
        val totalBytes = payload.long("total_bytes")?.also {
            require(it >= 0L && offsetBytes <= it)
        }
        val eof = payload.boolean("eof") ?: true
        require(!eof || totalBytes == null || payload.long("bytes")?.let { it >= totalBytes - offsetBytes } == true)
        return WiredAdbFileResult(
            operation = operation,
            relativePath = resultPath,
            entries = entries,
            text = payload.string("text"),
            bytes = payload.long("bytes"),
            created = payload.boolean("created"),
            replaced = payload.boolean("replaced"),
            deleted = payload.boolean("deleted"),
            truncated = payload.boolean("truncated") ?: false,
            nextCursor = nextCursor,
            skippedEntries = skippedEntries,
            listingWarnings = listingWarnings,
            version = version,
            offsetBytes = offsetBytes,
            totalBytes = totalBytes,
            eof = eof,
        )
    }

    private fun decodeListingWarnings(
        array: JsonArray?,
    ): List<runtime.mobileagent.skills.tooling.WorkspaceListingWarning> {
        if (array == null) return emptyList()
        require(array.size <= runtime.mobileagent.skills.tooling.WorkspaceListing.MAX_WARNING_TYPES)
        val seen = HashSet<runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode>()
        return array.map { element ->
            val item = element as? JsonObject ?: throw BridgeProtocolException("listing warning is invalid")
            require(item.keys == setOf("code", "count"))
            val code = runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode.valueOf(
                item.string("code") ?: throw BridgeProtocolException("listing warning code is missing"),
            )
            require(seen.add(code))
            val count = item.int("count") ?: throw BridgeProtocolException("listing warning count is missing")
            runtime.mobileagent.skills.tooling.WorkspaceListingWarning(code, count)
        }
    }

    internal data class DecodedWorkspacePage(
        val relativePath: String,
        val entries: List<WiredAdbFileEntry>,
        val truncated: Boolean,
        val nextCursor: String? = null,
        val version: Long? = null,
        val recoveryLocator: String? = null,
    )

    fun decodeWorkspaceAttachment(
        response: BridgeResponseEnvelope,
        workspaceId: String,
        binding: String,
        scope: WiredAdbWorkspaceScope,
    ): DecodedWorkspacePage {
        return decodeWorkspaceAttachment(response, workspaceId, binding, scope, "workspace_attach")
    }

    fun decodeWorkspaceReopened(
        response: BridgeResponseEnvelope,
        workspaceId: String,
        binding: String,
        scope: WiredAdbWorkspaceScope,
    ): DecodedWorkspacePage = decodeWorkspaceAttachment(response, workspaceId, binding, scope, "workspace_reopen")

    private fun decodeWorkspaceAttachment(
        response: BridgeResponseEnvelope,
        workspaceId: String,
        binding: String,
        scope: WiredAdbWorkspaceScope,
        expectedOperation: String,
    ): DecodedWorkspacePage {
        require(response.success)
        val payload = response.payload ?: throw BridgeProtocolException("workspace attachment payload is missing")
        require(payload.keys.all { it in WORKSPACE_ATTACH_RESULT_KEYS })
        require(payload.string("operation") == expectedOperation)
        require(payload.string("workspace_id") == workspaceId)
        require(payload.string("workspace_binding") == binding)
        require(payload.string("scope") == scope.wireName())
        val locator = payload.string("recovery_locator")
            ?: throw BridgeProtocolException("workspace recovery locator is missing")
        return decodeWorkspacePagePayload(payload, workspaceId, binding, recoveryLocator = locator)
    }

    fun decodeWorkspacePage(
        response: BridgeResponseEnvelope,
        workspaceId: String,
        binding: String,
    ): DecodedWorkspacePage {
        require(response.success)
        val payload = response.payload ?: throw BridgeProtocolException("workspace page payload is missing")
        require(payload.keys.all { it in WORKSPACE_BROWSE_RESULT_KEYS })
        require(payload.string("operation") == "workspace_browse")
        return decodeWorkspacePagePayload(payload, workspaceId, binding)
    }

    fun decodeWorkspaceRelease(
        response: BridgeResponseEnvelope,
        workspaceId: String,
        binding: String,
    ) {
        require(response.success)
        val payload = response.payload ?: throw BridgeProtocolException("workspace release payload is missing")
        require(payload.keys.all { it in WORKSPACE_RELEASE_RESULT_KEYS })
        require(payload.string("operation") == "workspace_release")
        require(payload.string("workspace_id") == workspaceId)
        require(payload.string("workspace_binding") == binding)
    }

    private fun decodeWorkspacePagePayload(
        payload: JsonObject,
        workspaceId: String,
        binding: String,
        recoveryLocator: String? = null,
    ): DecodedWorkspacePage {
        val allowedKeys = if (recoveryLocator == null) WORKSPACE_PAGE_RESULT_KEYS else WORKSPACE_ATTACH_RESULT_KEYS
        require(payload.keys.all { it in allowedKeys })
        require(payload.string("workspace_id") == workspaceId)
        require(payload.string("workspace_binding") == binding)
        val path = payload.stringOrNull("relative_path") ?: ""
        WiredAdbPathPolicy.parse(path, allowRoot = true)
        val entries = decodeEntries(payload.array("entries"))
        require(entries.size <= WIRED_MAX_DIRECTORY_ENTRIES)
        return DecodedWorkspacePage(
            relativePath = path,
            entries = entries,
            truncated = payload.boolean("truncated") ?: false,
            nextCursor = payload.stringOrNull("next_cursor")?.also(::validateCursor),
            version = payload.long("version")?.also { require(it >= 0L) },
            recoveryLocator = recoveryLocator,
        )
    }

    private fun decodeEntries(array: JsonArray?): List<WiredAdbFileEntry> = array?.map { element ->
        val item = element as? JsonObject ?: throw BridgeProtocolException("workspace entry is invalid")
        val path = item.string("relative_path") ?: throw BridgeProtocolException("workspace entry path is missing")
        WiredAdbPathPolicy.parse(path, allowRoot = false)
        val type = when (item.string("type")) {
            "file" -> WiredAdbEntryType.FILE
            "directory", "dir" -> WiredAdbEntryType.DIRECTORY
            else -> throw BridgeProtocolException("workspace entry type is invalid")
        }
        WiredAdbFileEntry(path, type, item.long("bytes"), item.long("version"))
    } ?: emptyList()

    fun decodeShellResult(response: BridgeResponseEnvelope): WiredAdbShellResult {
        if (!response.success) throw BridgeProtocolException("shell request failed")
        val payload = response.payload ?: throw BridgeProtocolException("shell response payload is missing")
        require(payload.keys.all { it in SHELL_RESULT_KEYS })
        val stdout = decodeBase64(payload, "stdout_base64", WIRED_ADB_MAX_SHELL_OUTPUT_BYTES)
        val stderr = decodeBase64(payload, "stderr_base64", WIRED_ADB_MAX_SHELL_OUTPUT_BYTES)
        return WiredAdbShellResult(
            exitCode = payload.int("exit_code"),
            stdout = stdout,
            stderr = stderr,
            timedOut = payload.boolean("timed_out") ?: false,
            cancelled = payload.boolean("cancelled") ?: false,
            stdoutTruncated = payload.boolean("stdout_truncated") ?: false,
            stderrTruncated = payload.boolean("stderr_truncated") ?: false,
            durationMs = payload.long("duration_ms") ?: 0L,
        )
    }

    fun mapError(response: BridgeResponseEnvelope): WiredAdbErrorCode = when (response.errorCode) {
        "FILE_NOT_FOUND", BridgeErrorCodes.WORKSPACE_NOT_FOUND -> WiredAdbErrorCode.WORKSPACE_NOT_FOUND
        "FILE_INVALID_PATH", "FILE_OUTSIDE_ROOT" -> WiredAdbErrorCode.PATH_OUT_OF_SCOPE
        "FILE_SYMLINK_FORBIDDEN" -> WiredAdbErrorCode.SYMLINK_FORBIDDEN
        "FILE_INVALID_CONTENT" -> WiredAdbErrorCode.INVALID_CONTENT
        "FILE_TARGET_EXISTS" -> WiredAdbErrorCode.TARGET_EXISTS
        "FILE_NON_EMPTY_DIRECTORY" -> WiredAdbErrorCode.NON_EMPTY_DIRECTORY
        "FILE_UNSUPPORTED_ENTRY" -> WiredAdbErrorCode.UNSUPPORTED_ENTRY
        "FILE_OPERATION_UNAVAILABLE" -> WiredAdbErrorCode.OPERATION_UNAVAILABLE
        "FILE_WRITE_UNVERIFIED" -> WiredAdbErrorCode.WRITE_UNVERIFIED
        "FILE_TOO_LARGE", "WORKSPACE_FILE_TOO_LARGE" -> WiredAdbErrorCode.FILE_TOO_LARGE
        "FILE_LIMIT" -> WiredAdbErrorCode.QUOTA_EXCEEDED
        "FILE_PERMISSION_DENIED", "WORKSPACE_PERMISSION_DENIED" -> WiredAdbErrorCode.PERMISSION_DENIED
        "FILE_CONFLICT", BridgeErrorCodes.WORKSPACE_CONFLICT -> WiredAdbErrorCode.CONFLICT
        "FILE_OFFSET_OUT_OF_RANGE", BridgeErrorCodes.WORKSPACE_OFFSET_OUT_OF_RANGE ->
            WiredAdbErrorCode.OFFSET_OUT_OF_RANGE
        "FILE_INVALID_PATCH", BridgeErrorCodes.WORKSPACE_INVALID_PATCH -> WiredAdbErrorCode.INVALID_PATCH
        "FILE_ATOMIC_REPLACE_UNAVAILABLE", BridgeErrorCodes.WORKSPACE_ATOMIC_REPLACE_UNAVAILABLE ->
            WiredAdbErrorCode.ATOMIC_REPLACE_UNAVAILABLE
        "FILE_INVALID_CURSOR" -> WiredAdbErrorCode.INVALID_CURSOR
        else -> runCatching { WiredAdbErrorCode.valueOf(response.errorCode.orEmpty()) }
            .getOrDefault(WiredAdbErrorCode.IO_ERROR)
    }

    private fun filePayload(
        request: WiredAdbFileRequest,
        workspaceId: String,
        workspaceBinding: String?,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("workspace_id", workspaceId)
        workspaceBinding?.let { put("workspace_binding", it) }
        request.relativePath?.let { put("relative_path", it) }
        extra()
    }

    private fun parseFileOperation(value: String): WiredAdbFileOperation = when (value) {
        "file_list" -> WiredAdbFileOperation.LIST
        "file_stat" -> WiredAdbFileOperation.STAT
        "file_read_text" -> WiredAdbFileOperation.READ_TEXT
        "file_write_text" -> WiredAdbFileOperation.WRITE_TEXT
        "file_apply_patch" -> WiredAdbFileOperation.APPLY_PATCH
        "file_create_directory" -> WiredAdbFileOperation.CREATE_DIRECTORY
        "file_move" -> WiredAdbFileOperation.MOVE
        "file_delete" -> WiredAdbFileOperation.DELETE
        else -> throw BridgeProtocolException("file operation is invalid")
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw BridgeProtocolException("file content is not UTF-8", error)
    }

    private fun decodeBase64(payload: JsonObject, field: String, maxBytes: Long): ByteArray {
        val value = payload[field]
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
            ?: throw BridgeProtocolException("base64 output field is missing or invalid")
        require(value.length <= ((maxBytes + 2) / 3 * 4 + 4).toInt()) {
            "base64 output is too large"
        }
        return try {
            Base64.getDecoder().decode(value).also { require(it.size <= maxBytes) }
        } catch (error: IllegalArgumentException) {
            throw BridgeProtocolException("base64 output is invalid", error)
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    private fun JsonObject.stringOrNull(name: String): String? = when (val element = this[name]) {
        null, JsonNull -> null
        else -> string(name) ?: throw BridgeProtocolException("response field is invalid")
    }

    private fun JsonObject.long(name: String): Long? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeUnless { it.isString }?.content?.toLongOrNull()
            ?: throw BridgeProtocolException("response field is invalid")
        else -> throw BridgeProtocolException("response field is invalid")
    }

    private fun JsonObject.int(name: String): Int? = long(name)?.let {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE)
        it.toInt()
    }

    private fun JsonObject.boolean(name: String): Boolean? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
            ?: throw BridgeProtocolException("response field is invalid")
        else -> throw BridgeProtocolException("response field is invalid")
    }

    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

    private fun validateCursor(value: String) {
        require(value.length in 1..WIRED_MAX_CURSOR_BYTES)
        require(value.all { it.code in 0x21..0x7e })
    }

    private val FILE_RESULT_KEYS = setOf(
        "operation", "relative_path", "entries", "text", "bytes", "created", "replaced", "deleted", "truncated",
        "next_cursor", "skipped_entries", "warnings", "version", "offset_bytes", "total_bytes", "eof",
    )
    private val WORKSPACE_PAGE_RESULT_KEYS = setOf(
        "operation", "workspace_id", "workspace_binding", "relative_path", "entries", "truncated", "next_cursor", "version",
    )
    private val WORKSPACE_ATTACH_RESULT_KEYS = WORKSPACE_PAGE_RESULT_KEYS + setOf("scope", "recovery_locator")
    private val WORKSPACE_BROWSE_RESULT_KEYS = WORKSPACE_PAGE_RESULT_KEYS + "max_entries"
    private val WORKSPACE_RELEASE_RESULT_KEYS = setOf("operation", "workspace_id", "workspace_binding")
    private val SHELL_RESULT_KEYS = setOf(
        "exit_code", "stdout_base64", "stderr_base64", "timed_out", "cancelled",
        "stdout_truncated", "stderr_truncated", "duration_ms",
    )
}

private fun WiredAdbWorkspaceScope.wireName(): String =
    if (this == WiredAdbWorkspaceScope.FULL_DEVICE_FILES) "full_device_files" else "selected_directory"

/** Byte-array seam keeps Android code from handling shared SecretBytes directly. */
internal fun interface WiredAdbSessionFactory {
    fun complete(
        hello: BridgeSessionHello,
        welcome: BridgeSessionWelcome,
        persistentTrust: ByteArray,
        transcriptHash: ByteArray,
    ): BridgeSession
}

internal val DEFAULT_WIRED_SESSION_FACTORY = WiredAdbSessionFactory { hello, welcome, trust, transcript ->
    WiredAdbSharedAdapter.completeSession(hello, welcome, trust, transcript)
}
