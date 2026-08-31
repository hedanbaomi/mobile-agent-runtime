// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
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
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
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
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.wired.WiredAdbEntryType
import runtime.mobileagent.wired.WiredAdbErrorCode
import runtime.mobileagent.wired.WiredAdbFileEntry
import runtime.mobileagent.wired.WiredAdbFileOperation
import runtime.mobileagent.wired.WiredAdbFileResult
import runtime.mobileagent.wired.WiredAdbRequestId
import runtime.mobileagent.wired.WiredAdbResult
import runtime.mobileagent.wired.WiredAdbShellResult

/**
 * Shared ShellExecutor adapter for the public wired authority port.
 *
 * The low-level request id is created by the wired bridge and is kept in a
 * process-local map solely for cancellation. It never appears in a tool
 * result or diagnostic record.
 */
internal class WiredShellExecutor(
    private val authority: WiredAdbAuthorityPort,
) : ShellExecutor {
    private val inFlight = ConcurrentHashMap<String, WiredAdbRequestId>()

    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
        if (request.selectedAuthority != Authority.WIRED_ADB) {
            return failure(request, ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED)
        }
        val limits = request.limits.clamped(
            maxTimeoutMs = ShellLimits.MAX_TIMEOUT_MS,
            maxOutputBytes = ShellLimits.MAX_OUTPUT_BYTES,
        )
        val lowLevel = runCatching {
            authority.newShellRequest(
                command = request.command,
                cwd = request.cwd,
                timeoutMs = limits.timeoutMs,
                maxOutputBytes = limits.maxOutputBytes,
            )
        }.getOrElse { return ShellExecResult.unknownOutcome(request) }
        inFlight[request.requestId] = lowLevel.requestId
        val result = try {
            authority.shell.executeShell(lowLevel)
        } catch (_: Throwable) {
            return ShellExecResult.unknownOutcome(request)
        } finally {
            inFlight.remove(request.requestId)
        }
        return result.toShared(request)
    }

    override suspend fun cancel(requestId: String): Boolean {
        val lowLevel = inFlight[requestId] ?: return false
        return when (val result = runCatching { authority.shell.cancel(lowLevel) }.getOrNull()) {
            is WiredAdbResult.Success -> true
            else -> false
        }
    }

    private fun WiredAdbResult<WiredAdbShellResult>.toShared(request: ShellExecRequest): ShellExecResult = when (this) {
        is WiredAdbResult.Failure -> when (code) {
            WiredAdbErrorCode.UNKNOWN_OUTCOME,
            WiredAdbErrorCode.BRIDGE_DISCONNECTED,
            WiredAdbErrorCode.BRIDGE_NOT_PAIRED,
            WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
                -> ShellExecResult.unknownOutcome(request)
            else -> failure(request, code.toToolError())
        }
        is WiredAdbResult.Success -> value.toShared(request)
    }

    private fun WiredAdbShellResult.toShared(request: ShellExecRequest): ShellExecResult {
        val stdout = decode(stdoutCopy())
        val stderr = decode(stderrCopy())
        if (timedOut) {
            return ShellExecResult(
                status = ShellExecutionStatus.TIMED_OUT,
                timedOut = true,
                authority = Authority.WIRED_ADB,
                durationMs = durationMs.coerceAtLeast(0L),
                requestId = request.requestId,
                error = ToolError(ToolErrorCode.SHELL_TIMED_OUT),
            )
        }
        if (cancelled) {
            return ShellExecResult(
                status = ShellExecutionStatus.CANCELLED,
                cancelled = true,
                authority = Authority.WIRED_ADB,
                durationMs = durationMs.coerceAtLeast(0L),
                requestId = request.requestId,
                error = ToolError(ToolErrorCode.SHELL_CANCELLED),
            )
        }
        val out = ShellOutputLimiter.limit(stdout, request.maxOutputBytes)
        val remaining = (request.maxOutputBytes - out.bytes).coerceAtLeast(0L)
        val err = if (remaining > 0L) ShellOutputLimiter.limit(stderr, remaining)
        else runtime.mobileagent.skills.tooling.LimitedShellOutput("", stderr.isNotEmpty(), 0L)
        val truncated = stdoutTruncated || stderrTruncated || out.truncated || err.truncated
        val failed = exitCode == null || exitCode != 0
        return ShellExecResult(
            status = if (failed || truncated) ShellExecutionStatus.FAILED else ShellExecutionStatus.SUCCEEDED,
            exitCode = exitCode,
            stdout = out.text,
            stderr = err.text,
            stdoutTruncated = stdoutTruncated || out.truncated,
            stderrTruncated = stderrTruncated || err.truncated,
            authority = Authority.WIRED_ADB,
            durationMs = durationMs.coerceAtLeast(0L),
            requestId = request.requestId,
            error = when {
                truncated -> ToolError(ToolErrorCode.SHELL_OUTPUT_TRUNCATED)
                failed -> ToolError(ToolErrorCode.SHELL_EXECUTION_FAILED)
                else -> null
            },
        )
    }

    private fun failure(request: ShellExecRequest, code: ToolErrorCode): ShellExecResult =
        ShellExecResult(
            status = ShellExecutionStatus.FAILED,
            authority = Authority.WIRED_ADB,
            requestId = request.requestId,
            error = ToolError(code),
        )

    private fun failure(request: ShellExecRequest, code: WiredAdbErrorCode): ShellExecResult =
        failure(request, code.toToolError())

    private fun WiredAdbErrorCode.toToolError(): ToolErrorCode = when (this) {
        WiredAdbErrorCode.BRIDGE_NOT_PAIRED -> ToolErrorCode.BRIDGE_NOT_PAIRED
        WiredAdbErrorCode.BRIDGE_DISCONNECTED,
        WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
        WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH,
        WiredAdbErrorCode.PROTOCOL_FRAME_INVALID,
        WiredAdbErrorCode.PROTOCOL_AUTH_FAILED,
            -> ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH
        WiredAdbErrorCode.TIMEOUT -> ToolErrorCode.SHELL_TIMED_OUT
        WiredAdbErrorCode.REQUEST_CANCELLED -> ToolErrorCode.SHELL_CANCELLED
        WiredAdbErrorCode.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
        WiredAdbErrorCode.REQUEST_INVALID -> ToolErrorCode.INVALID_REQUEST
        else -> ToolErrorCode.SHELL_EXECUTION_FAILED
    }

    private fun decode(bytes: ByteArray): String = runCatching {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { "" }
}

/** Shared workspace adapter for the public wired authority port. */
internal class WiredWorkspaceBackend(
    private val authority: WiredAdbAuthorityPort,
    workspaceId: String = DEFAULT_WORKSPACE_ID,
    displayName: String = DEFAULT_DISPLAY_NAME,
) : WorkspaceBackend {
    override val descriptor = WorkspaceDescriptor(
        id = workspaceId,
        displayName = displayName,
        backendType = WorkspaceBackendType.PRIVILEGED,
        rootReference = "",
        readable = true,
        writable = true,
        quotaBytes = 4L * 1024L * 1024L,
        maxFileBytes = 256L * 1024L,
        maxFiles = 128,
        maxDirectoryEntries = 256,
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
        val requestedPath = request.relativePath.orEmpty()
        return execute(request.workspaceId, WiredAdbFileOperation.LIST, requestedPath, request.maxEntries)
            .map { value ->
                require(value.relativePath.orEmpty() == requestedPath) { "list response path mismatch" }
                WorkspaceListing(
                    relativePath = requestedPath.ifEmpty { "." },
                    entries = value.entries.take(request.maxEntries).map(::entry),
                    truncated = value.entries.size > request.maxEntries,
                )
            }
    }

    override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> =
        execute(request.workspaceId, WiredAdbFileOperation.STAT, request.relativePath, 1).map { value ->
            val item = value.entries.singleOrNull() ?: throw IllegalArgumentException("stat response missing")
            WorkspaceFileStat(
                relativePath = item.relativePath,
                type = item.type.toShared(),
                sizeBytes = item.bytes ?: 0L,
            )
        }

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
        execute(request.workspaceId, WiredAdbFileOperation.READ_TEXT, request.relativePath, 1, maxBytes = request.maxBytes.toInt())
            .map { value ->
                val text = value.text ?: throw IllegalArgumentException("read response missing")
                WorkspaceText(value.relativePath ?: request.relativePath, text, value.bytes ?: text.toByteArray().size.toLong())
            }

    override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> =
        execute(
            request.workspaceId,
            WiredAdbFileOperation.WRITE_TEXT,
            request.relativePath,
            1,
            content = request.text.toByteArray(StandardCharsets.UTF_8),
            replace = request.replace,
        ).map { value -> WorkspaceMutation(value.relativePath ?: request.relativePath, WorkspaceEntryType.FILE, value.bytes ?: 0L) }

    override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> =
        execute(request.workspaceId, WiredAdbFileOperation.CREATE_DIRECTORY, request.relativePath, 1)
            .map { value -> WorkspaceMutation(value.relativePath ?: request.relativePath, WorkspaceEntryType.DIRECTORY) }

    override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> =
        execute(
            request.workspaceId,
            WiredAdbFileOperation.MOVE,
            request.sourcePath,
            1,
            destination = request.destinationPath,
        ).map { value -> WorkspaceMutation(value.relativePath ?: request.destinationPath, WorkspaceEntryType.FILE, value.bytes ?: 0L) }

    override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> =
        execute(request.workspaceId, WiredAdbFileOperation.DELETE, request.relativePath, 1)
            .map { value -> WorkspaceMutation(value.relativePath ?: request.relativePath, WorkspaceEntryType.FILE) }

    private suspend fun execute(
        workspaceId: String,
        operation: WiredAdbFileOperation,
        path: String,
        maxEntries: Int,
        destination: String? = null,
        content: ByteArray? = null,
        replace: Boolean = false,
        maxBytes: Int = 24 * 1024,
    ): WorkspaceResult<WiredAdbFileResult> {
        if (workspaceId != descriptor.id) return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        val request = runCatching {
            authority.newFileRequest(operation, path, destination, content, replace, maxBytes)
        }.getOrElse { return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST)) }
        return when (val result = runCatching { authority.workspace.executeFile(request) }.getOrNull()) {
            is WiredAdbResult.Success -> WorkspaceResult.Success(result.value)
            is WiredAdbResult.Failure -> WorkspaceResult.Failure(ToolError(result.code.toToolError()))
            null -> WorkspaceResult.Failure(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
        }
    }

    private fun WiredAdbEntryType.toShared() = when (this) {
        WiredAdbEntryType.FILE -> WorkspaceEntryType.FILE
        WiredAdbEntryType.DIRECTORY -> WorkspaceEntryType.DIRECTORY
    }

    private fun entry(value: WiredAdbFileEntry) = WorkspaceEntry(
        relativePath = value.relativePath,
        type = value.type.toShared(),
        sizeBytes = value.bytes ?: 0L,
    )

    private fun WiredAdbErrorCode.toToolError(): ToolErrorCode = when (this) {
        WiredAdbErrorCode.BRIDGE_NOT_PAIRED -> ToolErrorCode.BRIDGE_NOT_PAIRED
        WiredAdbErrorCode.BRIDGE_DISCONNECTED,
        WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
        WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH,
        WiredAdbErrorCode.PROTOCOL_FRAME_INVALID,
        WiredAdbErrorCode.PROTOCOL_AUTH_FAILED,
            -> ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH
        WiredAdbErrorCode.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
        WiredAdbErrorCode.REQUEST_INVALID -> ToolErrorCode.INVALID_REQUEST
        WiredAdbErrorCode.REQUEST_CANCELLED -> ToolErrorCode.SHELL_CANCELLED
        WiredAdbErrorCode.TIMEOUT -> ToolErrorCode.TIMEOUT
        else -> ToolErrorCode.IO_ERROR
    }

    companion object {
        const val DEFAULT_WORKSPACE_ID = "wired-adb"
        const val DEFAULT_DISPLAY_NAME = "Wired ADB workspace"
    }
}

private fun <T, R> WorkspaceResult<T>.map(transform: (T) -> R): WorkspaceResult<R> = when (this) {
    is WorkspaceResult.Success -> runCatching { WorkspaceResult.Success(transform(value)) }
        .getOrElse { WorkspaceResult.Failure(ToolError(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)) }
    is WorkspaceResult.Failure -> this
}
