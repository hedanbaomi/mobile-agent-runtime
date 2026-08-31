// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecResult
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.ShellLimits
import runtime.mobileagent.skills.tooling.ShellOutputLimiter
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceEntry
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceText
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest

/**
 * Public, backend-neutral shell adapter for the Android container.
 *
 * The adapter exposes only the shared [ShellExecutor] contract.  The bridge
 * retains Binder, session and one-shot call state; this class never forwards a
 * model call id as the provider call id and never logs command or output data.
 */
class ShizukuShellExecutor(
    private val bridge: ShizukuAuthorityBridge,
) : ShellExecutor {
    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
        if (request.selectedAuthority != Authority.SHIZUKU) {
            return failed(request, ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED)
        }

        // Runtime owns the broader contract.  The provider may only reduce its
        // limits and must use the Runtime-generated requestId as the one-shot
        // low-level call id.
        val bounded = try {
            request.clamped(
                maxTimeoutMs = minOf(ShellLimits.MAX_TIMEOUT_MS, ShizukuShellLimits.MAX_TIMEOUT_MS),
                maxOutputBytes = minOf(ShellLimits.MAX_OUTPUT_BYTES, ShizukuShellLimits.MAX_SERIALIZED_OUTPUT_BYTES.toLong()),
            )
        } catch (_: IllegalArgumentException) {
            return failed(request, ToolErrorCode.INVALID_REQUEST)
        }
        val lowLevelRequest = ShizukuShellRequest(
            callId = bounded.requestId,
            command = bounded.command,
            cwd = bounded.cwd,
            timeoutMs = bounded.timeoutMs,
            maxStdoutBytes = bounded.maxOutputBytes.toInt(),
            maxStderrBytes = bounded.maxOutputBytes.toInt(),
        )
        val lowLevel = try {
            bridge.execute(lowLevelRequest)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The bridge turns an interrupted post-dispatch wait into
            // UNKNOWN_OUTCOME.  Preserve coroutine cancellation before a
            // dispatch has happened; the caller can use cancel(requestId).
            throw cancellation
        } catch (_: RuntimeException) {
            // No exception text crosses the shared boundary.  A provider
            // failure after dispatch is conservatively non-replayable.
            return unknown(bounded)
        }
        return lowLevel.toSharedResult(bounded)
    }

    override suspend fun cancel(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        return runCatching { bridge.cancel(requestId) }.getOrDefault(false)
    }

    private fun ShizukuShellResult.toSharedResult(request: ShellExecRequest): ShellExecResult {
        val duration = (durationMs ?: 0L).coerceAtLeast(0L)
        if (unknownOutcome || state == ShizukuShellResult.State.UNKNOWN) {
            return unknown(
                request = request,
                stdout = decodeOutput(stdout),
                stderr = decodeOutput(stderr),
                timedOut = timedOut,
                cancelled = cancelled,
                durationMs = duration,
            )
        }

        return when (state) {
            ShizukuShellResult.State.TIMED_OUT -> ShellExecResult(
                status = ShellExecutionStatus.TIMED_OUT,
                timedOut = true,
                authority = Authority.SHIZUKU,
                durationMs = duration,
                requestId = request.requestId,
                error = ToolError(ToolErrorCode.SHELL_TIMED_OUT),
            )
            ShizukuShellResult.State.CANCELLED -> ShellExecResult(
                status = ShellExecutionStatus.CANCELLED,
                cancelled = true,
                authority = Authority.SHIZUKU,
                durationMs = duration,
                requestId = request.requestId,
                error = ToolError(ToolErrorCode.SHELL_CANCELLED),
            )
            ShizukuShellResult.State.DENIED -> failed(
                request = request,
                code = mapShellError(errorCode),
                durationMs = duration,
            )
            ShizukuShellResult.State.COMPLETED -> this.completed(request, duration)
            ShizukuShellResult.State.UNKNOWN -> error("unreachable shell state")
        }
    }

    private fun ShizukuShellResult.completed(request: ShellExecRequest, duration: Long): ShellExecResult {
        // The low-level bridge has already applied its PFD/aggregate budget.
        // Apply the shared Runtime budget again because this adapter converts
        // the independent byte streams to one String result.
        val stdoutLimited = ShellOutputLimiter.limit(decodeOutput(stdout), request.maxOutputBytes)
        val remaining = request.maxOutputBytes - stdoutLimited.bytes
        val stderrLimited = if (remaining > 0L) {
            ShellOutputLimiter.limit(decodeOutput(stderr), remaining)
        } else {
            runtime.mobileagent.skills.tooling.LimitedShellOutput(
                text = "",
                truncated = stderr.isNotEmpty(),
                bytes = 0L,
            )
        }
        val stdoutWasTruncated = stdoutTruncated || stdoutLimited.truncated
        val stderrWasTruncated = stderrTruncated || stderrLimited.truncated
        val outputWasTruncated = stdoutWasTruncated || stderrWasTruncated
        val exit = exitCode
        val executionFailed = exit == null || exit != 0
        val status = if (executionFailed || outputWasTruncated) {
            ShellExecutionStatus.FAILED
        } else {
            ShellExecutionStatus.SUCCEEDED
        }
        val error = when {
            outputWasTruncated -> ToolError(ToolErrorCode.SHELL_OUTPUT_TRUNCATED)
            executionFailed -> ToolError(ToolErrorCode.SHELL_EXECUTION_FAILED)
            else -> null
        }
        return ShellExecResult(
            status = status,
            exitCode = exit,
            stdout = stdoutLimited.text,
            stderr = stderrLimited.text,
            stdoutTruncated = stdoutWasTruncated,
            stderrTruncated = stderrWasTruncated,
            authority = Authority.SHIZUKU,
            durationMs = duration,
            requestId = request.requestId,
            error = error,
        )
    }

    private fun mapShellError(code: String?): ToolErrorCode = when (code) {
        ShizukuShellLimits.INVALID_REQUEST,
        ShizukuShellLimits.INVALID_CWD,
            -> ToolErrorCode.INVALID_REQUEST
        ShizukuShellLimits.REPLAY_DENIED -> ToolErrorCode.CALL_ID_REPLAY
        ShizukuShellLimits.PROTOCOL_MISMATCH -> ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH
        ShizukuShellLimits.CONCURRENCY_LIMIT -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
        ShizukuShellLimits.UNAVAILABLE,
        ShizukuShellLimits.UID_UNTRUSTED,
        ShizukuShellLimits.CALLER_UNTRUSTED,
        ShizukuShellLimits.SESSION_INVALID,
            -> ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE
        else -> ToolErrorCode.SHELL_EXECUTION_FAILED
    }

    private fun failed(
        request: ShellExecRequest,
        code: ToolErrorCode,
        durationMs: Long = 0L,
    ): ShellExecResult = ShellExecResult(
        status = ShellExecutionStatus.FAILED,
        authority = request.selectedAuthority,
        durationMs = durationMs.coerceAtLeast(0L),
        requestId = request.requestId,
        error = ToolError(code),
    )

    private fun unknown(
        request: ShellExecRequest,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
        cancelled: Boolean = false,
        durationMs: Long = 0L,
    ): ShellExecResult {
        val boundedOut = ShellOutputLimiter.limit(stdout, request.maxOutputBytes)
        val remaining = request.maxOutputBytes - boundedOut.bytes
        val boundedErr = if (remaining > 0L) {
            ShellOutputLimiter.limit(stderr, remaining)
        } else {
            runtime.mobileagent.skills.tooling.LimitedShellOutput("", stderr.isNotEmpty(), 0L)
        }
        return ShellExecResult(
            status = ShellExecutionStatus.UNKNOWN_OUTCOME,
            stdout = boundedOut.text,
            stderr = boundedErr.text,
            timedOut = timedOut,
            cancelled = cancelled,
            stdoutTruncated = boundedOut.truncated,
            stderrTruncated = boundedErr.truncated,
            authority = request.selectedAuthority,
            durationMs = durationMs.coerceAtLeast(0L),
            requestId = request.requestId,
            error = ToolError.unknownOutcome(),
        )
    }

    private fun decodeOutput(bytes: ByteArray): String =
        String(bytes, StandardCharsets.UTF_8).replace('\u0000', '\uFFFD')
}

/**
 * Public shared workspace adapter for the fixed, path-checked Shizuku store.
 *
 * The descriptor intentionally contains no absolute root or Binder identity.
 * Versioned mutation fields are rejected because the low-level typed RPC does
 * not implement optimistic versions; silently ignoring them would be unsafe.
 */
class ShizukuWorkspaceBackendAdapter(
    private val bridge: ShizukuAuthorityBridge,
    workspaceId: String = DEFAULT_WORKSPACE_ID,
    displayName: String = DEFAULT_DISPLAY_NAME,
) : WorkspaceBackend {
    init {
        require(isSafeWorkspaceId(workspaceId)) { "Shizuku workspace id is invalid" }
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Shizuku workspace display name is invalid"
        }
    }

    override val descriptor: WorkspaceDescriptor = WorkspaceDescriptor(
        id = workspaceId,
        displayName = displayName,
        backendType = WorkspaceBackendType.PRIVILEGED,
        rootReference = "",
        readable = true,
        writable = true,
        quotaBytes = ShizukuWorkspaceFileStore.MAX_TOTAL_BYTES,
        maxFileBytes = ShizukuWorkspaceFileStore.MAX_FILE_BYTES.toLong(),
        maxFiles = ShizukuWorkspaceFileStore.MAX_FILES,
        maxDirectoryEntries = ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES,
        enabled = true,
    )

    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
        CapabilityId(CapabilityId.FILE_LIST),
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
        CapabilityId(CapabilityId.FILE_WRITE_TEXT),
        CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
        CapabilityId(CapabilityId.FILE_MOVE),
        CapabilityId(CapabilityId.FILE_DELETE),
    )

    override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val normalized = normalizePath(request.relativePath, allowRoot = true)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return dispatchJson<WorkspaceListing>(
            operation = "list",
            dispatch = safeDispatch { bridge.dispatchList(normalized) },
            decode = { payload -> parseListPayload(payload, normalized, request.maxEntries) },
        )
    }

    override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val normalized = normalizePath(request.relativePath, allowRoot = false)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return dispatchJson<WorkspaceFileStat>(
            operation = "stat",
            dispatch = safeDispatch { bridge.dispatchStat(normalized) },
            decode = { payload -> parseStatPayload(payload, normalized) },
        )
    }

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val normalized = normalizePath(request.relativePath, allowRoot = false)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val maxBytes = request.maxBytes
        if (maxBytes !in 1L..ShizukuWorkspaceFileStore.MAX_READ_BYTES.toLong()) {
            return failure(ToolErrorCode.FILE_TOO_LARGE)
        }
        return dispatchJson<WorkspaceText>(
            operation = "read",
            dispatch = safeDispatch { bridge.dispatchRead(normalized, maxBytes.toInt()) },
            decode = { payload -> parseReadPayload(payload, normalized, maxBytes) },
        )
    }

    override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val source = normalizePath(request.sourcePath, allowRoot = false)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val destination = normalizePath(request.destinationPath, allowRoot = false)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return dispatchJson<WorkspaceMutation>(
            operation = "move",
            dispatch = safeDispatch { bridge.dispatchMove(source, destination, replaceExisting = false) },
            decode = { payload -> parseMovePayload(payload, source, destination) },
        )
    }

    override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val normalized = normalizePath(request.relativePath, allowRoot = false)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val content = strictUtf8(request.text)
            ?: return failure(ToolErrorCode.INVALID_REQUEST)
        if (content.size > ShizukuWorkspaceFileStore.MAX_FILE_BYTES) return failure(ToolErrorCode.FILE_TOO_LARGE)
        return dispatchJson<WorkspaceMutation>(
            operation = "write",
            dispatch = safeDispatch { bridge.dispatchWrite(normalized, content, request.replace) },
            decode = { payload -> parseWritePayload(payload, normalized, content.size.toLong()) },
        )
    }

    override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val normalized = normalizePath(request.relativePath, allowRoot = false)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return dispatchJson<WorkspaceMutation>(
            operation = "mkdir",
            dispatch = safeDispatch { bridge.dispatchMkdir(normalized) },
            decode = { payload -> parseCreateDirectoryPayload(payload, normalized) },
        )
    }

    override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val normalized = normalizePath(request.relativePath, allowRoot = false)
            ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return dispatchJson<WorkspaceMutation>(
            operation = "delete",
            dispatch = safeDispatch { bridge.dispatchDelete(normalized) },
            decode = { payload -> parseDeletePayload(payload, normalized) },
        )
    }

    private fun parseListPayload(
        payload: JSONObject,
        normalized: String,
        maxEntries: Int,
    ): WorkspaceListing? {
        val responsePath = normalizePath(payload.optString("path", ""), allowRoot = true)
        if (responsePath != normalized) return null
        val entriesJson = payload.optJSONArray("entries") ?: return null
        if (entriesJson.length() > ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES) return null
        val entries = ArrayList<WorkspaceEntry>(minOf(entriesJson.length(), maxEntries))
        for (index in 0 until entriesJson.length()) {
            val entry = entriesJson.optJSONObject(index) ?: return null
            val path = normalizePath(entry.optString("path", ""), allowRoot = false) ?: return null
            val type = when (entry.optString("type", "")) {
                "file" -> WorkspaceEntryType.FILE
                "directory" -> WorkspaceEntryType.DIRECTORY
                else -> return null
            }
            val bytes = if (type == WorkspaceEntryType.FILE) {
                entry.optLong("bytes", -1L)
            } else {
                0L
            }
            if (bytes < 0L || bytes > ShizukuWorkspaceFileStore.MAX_FILE_BYTES) return null
            if (!isChildOf(path, normalized)) return null
            if (index < maxEntries) entries += WorkspaceEntry(path, type, bytes)
        }
        return WorkspaceListing(
            relativePath = normalized.ifEmpty { ROOT_PATH },
            entries = entries,
            truncated = entriesJson.length() > entries.size,
        )
    }

    private fun parseReadPayload(
        payload: JSONObject,
        normalized: String,
        maxBytes: Long,
    ): WorkspaceText? {
        val text = payload.optString("text", "")
        val bytes = payload.optLong("bytes", -1L)
        val responsePath = normalizePath(payload.optString("path", ""), allowRoot = false)
        if (responsePath != normalized || bytes < 0L || bytes > maxBytes || strictUtf8(text)?.size?.toLong() != bytes) {
            return null
        }
        return WorkspaceText(normalized, text, byteSize = bytes)
    }

    private fun parseStatPayload(payload: JSONObject, normalized: String): WorkspaceFileStat? {
        val responsePath = normalizePath(payload.optString("path", ""), allowRoot = false)
        val type = when (payload.optString("type", "")) {
            "file" -> WorkspaceEntryType.FILE
            "directory" -> WorkspaceEntryType.DIRECTORY
            else -> return null
        }
        val bytes = payload.optLong("bytes", -1L)
        if (responsePath != normalized || bytes < 0L || bytes > ShizukuWorkspaceFileStore.MAX_FILE_BYTES ||
            (type == WorkspaceEntryType.DIRECTORY && bytes != 0L)
        ) return null
        return WorkspaceFileStat(normalized, type, bytes)
    }

    private fun parseWritePayload(
        payload: JSONObject,
        normalized: String,
        expectedBytes: Long,
    ): WorkspaceMutation? {
        val bytes = payload.optLong("bytes", -1L)
        val responsePath = normalizePath(payload.optString("path", ""), allowRoot = false)
        if (responsePath != normalized || bytes != expectedBytes) return null
        return WorkspaceMutation(
            relativePath = normalized,
            type = WorkspaceEntryType.FILE,
            byteSize = bytes,
        )
    }

    private fun parseCreateDirectoryPayload(payload: JSONObject, normalized: String): WorkspaceMutation? {
        val responsePath = normalizePath(payload.optString("path", ""), allowRoot = false)
        if (responsePath != normalized || !payload.has("created")) return null
        return WorkspaceMutation(normalized, WorkspaceEntryType.DIRECTORY)
    }

    private fun parseDeletePayload(payload: JSONObject, normalized: String): WorkspaceMutation? {
        val responsePath = normalizePath(payload.optString("path", ""), allowRoot = false)
        val type = when (payload.optString("type", "")) {
            "file" -> WorkspaceEntryType.FILE
            "directory" -> WorkspaceEntryType.DIRECTORY
            else -> return null
        }
        if (responsePath != normalized || !payload.optBoolean("deleted", false)) return null
        return WorkspaceMutation(normalized, type)
    }

    private fun parseMovePayload(
        payload: JSONObject,
        source: String,
        destination: String,
    ): WorkspaceMutation? {
        val responsePath = normalizePath(payload.optString("path", ""), allowRoot = false)
        val responseSource = normalizePath(payload.optString("sourcePath", ""), allowRoot = false)
        val responseDestination = normalizePath(payload.optString("destinationPath", ""), allowRoot = false)
        val type = when (payload.optString("type", "")) {
            "file" -> WorkspaceEntryType.FILE
            "directory" -> WorkspaceEntryType.DIRECTORY
            else -> return null
        }
        val bytes = payload.optLong("bytes", -1L)
        if (responsePath != destination || responseSource != source || responseDestination != destination ||
            !payload.optBoolean("moved", false) || bytes < 0L ||
            bytes > ShizukuWorkspaceFileStore.MAX_FILE_BYTES ||
            (type == WorkspaceEntryType.DIRECTORY && bytes != 0L)
        ) return null
        return WorkspaceMutation(destination, type, bytes)
    }

    private fun <T> dispatchJson(
        operation: String,
        dispatch: ShizukuDispatchResult,
        decode: (JSONObject) -> T?,
    ): WorkspaceResult<T> {
        return when (dispatch) {
            is ShizukuDispatchResult.Denied -> failure(ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE)
            is ShizukuDispatchResult.Failed -> if (dispatch.unknownOutcome) {
                failure(ToolErrorCode.UNKNOWN_OUTCOME)
            } else {
                failure(dispatch.errorCode?.let { mapWorkspaceError(it, operation) } ?: ToolErrorCode.IO_ERROR)
            }
            is ShizukuDispatchResult.Success -> {
                val payload = runCatching { JSONObject(dispatch.payload) }.getOrNull()
                    ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
                if (payload.optString("operation", "") != operation) {
                    return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
                }
                if (!payload.optBoolean("ok", false)) {
                    return failure(mapWorkspaceError(payload.optString("code", ""), operation))
                }
                val value = runCatching { decode(payload) }.getOrNull()
                    ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
                WorkspaceResult.Success(value)
            }
        }
    }

    private fun mapWorkspaceError(code: String, operation: String): ToolErrorCode = when (code) {
        ShizukuWorkspaceFileStore.INVALID_PATH,
        ShizukuWorkspaceFileStore.OUTSIDE_ROOT,
            -> ToolErrorCode.PATH_OUT_OF_SCOPE
        ShizukuWorkspaceFileStore.SYMLINK_REJECTED -> ToolErrorCode.SYMLINK_FORBIDDEN
        ShizukuWorkspaceFileStore.NOT_FOUND -> ToolErrorCode.WORKSPACE_NOT_FOUND
        ShizukuWorkspaceFileStore.PERMISSION_DENIED -> ToolErrorCode.SHIZUKU_PERMISSION_DENIED
        ShizukuWorkspaceFileStore.FILE_TOO_LARGE -> ToolErrorCode.FILE_TOO_LARGE
        ShizukuWorkspaceFileStore.LIMIT,
        ShizukuWorkspaceFileStore.OUTPUT_LIMIT,
            -> if (operation == "read" || operation == "stat") ToolErrorCode.FILE_TOO_LARGE else ToolErrorCode.QUOTA_EXCEEDED
        ShizukuWorkspaceFileStore.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
        ShizukuWorkspaceFileStore.TARGET_EXISTS,
        ShizukuWorkspaceFileStore.NON_EMPTY_DIRECTORY,
        ShizukuWorkspaceFileStore.MOVE_INTO_SELF,
        ShizukuWorkspaceFileStore.UNSUPPORTED_ENTRY,
        ShizukuWorkspaceFileStore.INVALID_CONTENT,
            -> ToolErrorCode.INVALID_REQUEST
        ShizukuWorkspaceFileStore.ATOMIC_REPLACE_UNAVAILABLE,
        ShizukuWorkspaceFileStore.OPERATION_UNAVAILABLE,
        ShizukuWorkspaceFileStore.WRITE_UNVERIFIED,
            -> ToolErrorCode.IO_ERROR
        else -> ToolErrorCode.IO_ERROR
    }

    private fun failure(code: ToolErrorCode): WorkspaceResult.Failure =
        WorkspaceResult.Failure(ToolError(code))

    private fun safeDispatch(block: () -> ShizukuDispatchResult): ShizukuDispatchResult = try {
        block()
    } catch (_: RuntimeException) {
        // A transport exception after a typed mutation may have reached the
        // service.  Preserve the no-replay boundary without exposing details.
        ShizukuDispatchResult.Failed("Shizuku dispatch failed", unknownOutcome = true)
    }

    private fun normalizePath(raw: String?, allowRoot: Boolean): String? = runCatching {
        ShizukuWorkspacePathPolicy.parse(raw, allowRoot).joinToString("/")
    }.getOrNull()

    private fun isChildOf(path: String, parent: String): Boolean =
        parent.isEmpty() || path.startsWith("$parent/")

    private fun strictUtf8(value: String): ByteArray? = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

    companion object {
        const val DEFAULT_WORKSPACE_ID = "shizuku"
        const val DEFAULT_DISPLAY_NAME = "Shizuku workspace"
        const val ROOT_PATH = "."
        private const val MAX_DISPLAY_NAME_LENGTH = 256
        private val SAFE_WORKSPACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")

        private fun isSafeWorkspaceId(value: String): Boolean =
            value.toByteArray(StandardCharsets.UTF_8).size <= 128 && SAFE_WORKSPACE_ID.matches(value)
    }
}

/**
 * Narrow construction seam for AppContainer.  Returned values are shared
 * backend interfaces; Binder/session/token details stay inside this package.
 */
object ShizukuBackendFactory {
    @JvmStatic
    fun createShellExecutor(bridge: ShizukuAuthorityBridge): ShellExecutor =
        ShizukuShellExecutor(bridge)

    @JvmStatic
    fun createWorkspaceBackend(
        bridge: ShizukuAuthorityBridge,
        workspaceId: String = ShizukuWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID,
        displayName: String = ShizukuWorkspaceBackendAdapter.DEFAULT_DISPLAY_NAME,
    ): WorkspaceBackend = ShizukuWorkspaceBackendAdapter(bridge, workspaceId, displayName)
}
