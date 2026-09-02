// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID

/**
 * Shizuku UserService implementation.
 *
 * Shizuku constructs this class in its own process with the server's UID.  The
 * no-argument constructor is intentional: the service does not use a Context,
 * content providers, or any ambient application capability.
 */
class ShizukuUserService private constructor(
    private val shellRunner: ShizukuShellRunner,
) : IShizukuCommandService.Stub() {
    /** Public no-arg entrypoint required by Shizuku's UserService loader. */
    constructor() : this(ProcessShizukuShellRunner())

    /**
     * Test-only injection seam.  The marker prevents this constructor from
     * being confused with Shizuku's required no-argument loader entrypoint.
     */
    internal constructor(shellRunner: ShizukuShellRunner, testOnly: Unit) : this(shellRunner)

    private val files = ShizukuWorkspaceFileStore()
    /**
     * Device-root directory handles and attached workspace handles are kept
     * only for this UserService instance.  They are opaque to the app and are
     * invalid as soon as the authenticated service/session goes away.
     */
    private val privilegedDirectories = ShizukuDirectoryHandleStore()
    /** At most two bounded chunk writers may be in flight; bytes never cross Binder inline. */
    private val workspaceReadPool: ExecutorService = Executors.newFixedThreadPool(2)
    /** Android UID is stable for this process, but read it at each boundary. */
    private val serviceUid: Int
        get() = Process.myUid()
    private val protocolVersion = ShizukuBridgePolicy.USER_SERVICE_PROTOCOL_VERSION
    private val sessionId = UUID.randomUUID().toString()
    private val callerLock = Any()
    private var handshakeCallerUid: Int? = null

    override fun getStatus(): String {
        val callerUid = Binder.getCallingUid()
        val acceptedCaller = synchronized(callerLock) {
            when {
                handshakeCallerUid == null -> {
                    handshakeCallerUid = callerUid
                    true
                }
                handshakeCallerUid == callerUid -> true
                else -> false
            }
        }
        val base = runCatching { JSONObject(files.status()) }
            .getOrElse { JSONObject().put("ok", false).put("operation", "status") }
        if (!acceptedCaller) {
            return JSONObject()
                .put("ok", false)
                .put("operation", "status")
                .put("code", ShizukuShellLimits.CALLER_UNTRUSTED)
                .put("serviceUid", serviceUid)
                .put("protocolVersion", protocolVersion)
                .put("sessionId", sessionId)
                .put("callerUid", callerUid)
                .toString()
        }
        if (serviceUid != ShizukuBridgePolicy.SHELL_UID) {
            // The status still reports the observed Process.myUid so the app
            // can audit the mismatch, but it is never a usable handshake.
            base.put("ok", false).put("code", ShizukuShellLimits.UID_UNTRUSTED)
        }
        return base
            .put("protocolVersion", protocolVersion)
            .put("sessionId", sessionId)
            .put("callerUid", callerUid)
            .toString()
    }

    /** Legacy transactions intentionally require the authenticated session. */
    override fun list(relativePath: String?): String = denied("list", "SESSION_REQUIRED")

    override fun read(relativePath: String?, maxBytes: Int): String = denied("read", "SESSION_REQUIRED")

    override fun write(relativePath: String?, utf8Content: ByteArray?, replaceExisting: Boolean): String =
        denied("write", "SESSION_REQUIRED")

    override fun mkdir(relativePath: String?): String = denied("mkdir", "SESSION_REQUIRED")

    override fun delete(relativePath: String?): String = denied("delete", "SESSION_REQUIRED")

    override fun listSession(sessionId: String?, relativePath: String?): String =
        withSession(sessionId, "list") { files.list(relativePath) }

    override fun readSession(sessionId: String?, relativePath: String?, maxBytes: Int): String =
        withSession(sessionId, "read") { files.read(relativePath, maxBytes) }

    override fun listPagedSession(
        sessionId: String?,
        relativePath: String?,
        maxEntries: Int,
        cursor: String?,
    ): String = withSession(sessionId, "list") {
        files.list(relativePath, maxEntries, cursor)
    }

    override fun readChunkSession(
        sessionId: String?,
        relativePath: String?,
        offsetBytes: Long,
        maxBytes: Int,
    ): ShizukuWorkspaceReadResponse {
        val denial = checkSession(sessionId)
        if (denial != null) return ShizukuWorkspaceReadResponse.rejected(denial)
        return readResponse(files.readChunk(relativePath, maxBytes, offsetBytes))
    }

    override fun applyPatchSession(
        sessionId: String?,
        relativePath: String?,
        patch: String?,
        expectedVersion: String?,
        format: String?,
    ): String = withSession(sessionId, "apply_patch") {
        files.applyPatch(relativePath, patch, expectedVersion, format)
    }

    override fun writeSession(
        sessionId: String?,
        relativePath: String?,
        utf8Content: ByteArray?,
        replaceExisting: Boolean,
    ): String = withSession(sessionId, "write") {
        files.write(relativePath, utf8Content, replaceExisting)
    }

    override fun mkdirSession(sessionId: String?, relativePath: String?): String =
        withSession(sessionId, "mkdir") { files.mkdir(relativePath) }

    override fun deleteSession(sessionId: String?, relativePath: String?): String =
        withSession(sessionId, "delete") { files.delete(relativePath) }

    override fun statSession(sessionId: String?, relativePath: String?): String =
        withSession(sessionId, "stat") { files.stat(relativePath) }

    override fun moveSession(
        sessionId: String?,
        sourcePath: String?,
        destinationPath: String?,
        replaceExisting: Boolean,
    ): String = withSession(sessionId, "move") {
        files.move(sourcePath, destinationPath, replaceExisting)
    }

    override fun startShell(
        sessionId: String?,
        callId: String?,
        command: String?,
        cwd: String?,
        timeoutMs: Long,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
    ): ShizukuShellResponse {
        val authority = checkSession(sessionId)
        if (authority != null) return ShizukuShellResponse.rejected(authority)
        if (callId == null || command == null) {
            return ShizukuShellResponse.rejected(ShizukuShellLimits.INVALID_REQUEST)
        }
        return shellRunner.start(
            ShizukuShellRunnerRequest(
                callId = callId,
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                maxStdoutBytes = maxStdoutBytes,
                maxStderrBytes = maxStderrBytes,
            ),
        )
    }

    override fun cancelShell(sessionId: String?, callId: String?): Boolean {
        if (checkSession(sessionId) != null || callId.isNullOrBlank()) return false
        return shellRunner.cancel(callId)
    }

    override fun openDirectoryRootSession(sessionId: String?, maxEntries: Int): String =
        withSession(sessionId, "open_directory_root") {
            if (maxEntries !in 1..ShizukuDirectoryHandleStore.MAX_DIRECTORY_ENTRIES) {
                return@withSession denied("open_directory_root", ShizukuWorkspaceFileStore.LIMIT)
            }
            privilegedDirectories.openRoot(maxEntries)
        }

    override fun browseDirectorySession(
        sessionId: String?,
        directoryHandle: String?,
        maxEntries: Int,
    ): String = withSession(sessionId, "browse_directory") {
        if (maxEntries !in 1..ShizukuDirectoryHandleStore.MAX_DIRECTORY_ENTRIES) {
            return@withSession denied("browse_directory", ShizukuWorkspaceFileStore.LIMIT)
        }
        privilegedDirectories.browse(directoryHandle, maxEntries)
    }

    override fun attachDirectorySession(sessionId: String?, directoryHandle: String?): String =
        withSession(sessionId, "attach_directory") {
            privilegedDirectories.attach(directoryHandle)
        }

    override fun reattachDirectorySession(sessionId: String?, recoveryLocator: ByteArray?): String = try {
        withSession(sessionId, "reattach_directory") {
            privilegedDirectories.reattach(recoveryLocator)
        }
    } finally {
        recoveryLocator?.fill(0)
    }

    override fun listWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
        maxEntries: Int,
    ): String = withWorkspaceSession(sessionId, "list", workspaceHandle) { workspace ->
        if (maxEntries !in 1..ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES) {
            return@withWorkspaceSession denied("list", ShizukuWorkspaceFileStore.LIMIT)
        }
        workspace.store.list(relativePath)
    }

    override fun readWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
        maxBytes: Int,
    ): String = withWorkspaceSession(sessionId, "read", workspaceHandle) { workspace ->
        workspace.store.read(relativePath, maxBytes)
    }

    override fun listWorkspacePagedSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
        maxEntries: Int,
        cursor: String?,
    ): String = withWorkspaceSession(sessionId, "list", workspaceHandle) { workspace ->
        if (maxEntries !in 1..ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES) {
            return@withWorkspaceSession denied("list", ShizukuWorkspaceFileStore.LIMIT)
        }
        workspace.store.list(relativePath, maxEntries, cursor)
    }

    override fun readChunkWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
        offsetBytes: Long,
        maxBytes: Int,
    ): ShizukuWorkspaceReadResponse {
        val denial = checkSession(sessionId)
        if (denial != null) return ShizukuWorkspaceReadResponse.rejected(denial)
        val workspace = privilegedDirectories.workspace(workspaceHandle)
            ?: return ShizukuWorkspaceReadResponse.rejected(ShizukuDirectoryHandleStore.INVALID_HANDLE)
        val result = runCatching { workspace.store.readChunk(relativePath, maxBytes, offsetBytes) }
            .getOrElse { ShizukuWorkspaceFileStore.ReadChunkResult.Failure(ShizukuWorkspaceFileStore.OPERATION_UNAVAILABLE) }
        return readResponse(result)
    }

    override fun applyPatchWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
        patch: String?,
        expectedVersion: String?,
        format: String?,
    ): String = withWorkspaceSession(sessionId, "apply_patch", workspaceHandle) { workspace ->
        workspace.store.applyPatch(relativePath, patch, expectedVersion, format)
    }

    override fun writeWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
        utf8Content: ByteArray?,
        replaceExisting: Boolean,
    ): String = withWorkspaceSession(sessionId, "write", workspaceHandle) { handle ->
        handle.store.write(relativePath, utf8Content, replaceExisting)
    }

    override fun mkdirWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
    ): String = withWorkspaceSession(sessionId, "mkdir", workspaceHandle) { workspace ->
        workspace.store.mkdir(relativePath)
    }

    override fun deleteWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
    ): String = withWorkspaceSession(sessionId, "delete", workspaceHandle) { workspace ->
        workspace.store.delete(relativePath)
    }

    override fun statWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        relativePath: String?,
    ): String = withWorkspaceSession(sessionId, "stat", workspaceHandle) { workspace ->
        workspace.store.stat(relativePath)
    }

    override fun moveWorkspaceSession(
        sessionId: String?,
        workspaceHandle: String?,
        sourcePath: String?,
        destinationPath: String?,
        replaceExisting: Boolean,
    ): String = withWorkspaceSession(sessionId, "move", workspaceHandle) { workspace ->
        workspace.store.move(sourcePath, destinationPath, replaceExisting)
    }

    /** Reserved transaction used by Shizuku to tear down a UserService. */
    override fun destroy() {
        // destroy() has no session-id argument in Shizuku's reserved Binder
        // transaction.  Bind it to the same per-service caller identity as
        // the session-bound methods; an unauthenticated Binder holder must not
        // be able to tear down the privileged UserService.  Do not return the
        // session token or any diagnostic data through this void transaction.
        if (checkCaller() != null) return
        runCatching { shellRunner.close() }
        workspaceReadPool.shutdownNow()
        System.exit(0)
    }

    private fun readResponse(
        result: ShizukuWorkspaceFileStore.ReadChunkResult,
    ): ShizukuWorkspaceReadResponse {
        return when (result) {
        is ShizukuWorkspaceFileStore.ReadChunkResult.Failure ->
            ShizukuWorkspaceReadResponse.rejected(result.code)
        is ShizukuWorkspaceFileStore.ReadChunkResult.Success -> {
            val metadata = JSONObject()
                .put("ok", true)
                .put("operation", "read")
                .put("path", result.path)
                .put("bytes", result.bytes.size)
                .put("version", result.version)
                .put("offsetBytes", result.offsetBytes)
                .put("totalBytes", result.totalBytes)
                .put("eof", result.eof)
                .toString()
            val pipe = try {
                ParcelFileDescriptor.createPipe()
            } catch (_: IOException) {
                return ShizukuWorkspaceReadResponse.rejected(ShizukuWorkspaceFileStore.OPERATION_UNAVAILABLE)
            }
            try {
                workspaceReadPool.execute {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                            output.write(result.bytes)
                            output.flush()
                        }
                    } catch (_: IOException) {
                        runCatching { pipe[1].close() }
                    }
                }
            } catch (_: RuntimeException) {
                runCatching { pipe[0].close() }
                runCatching { pipe[1].close() }
                return ShizukuWorkspaceReadResponse.rejected(ShizukuWorkspaceFileStore.OPERATION_UNAVAILABLE)
            }
            ShizukuWorkspaceReadResponse.accepted(metadata, pipe[0])
        }
    }
    }

    private fun withSession(
        requestedSessionId: String?,
        operation: String,
        action: () -> String,
    ): String {
        val denial = checkSession(requestedSessionId)
        return denial?.let { denied(operation, it) } ?: action()
    }

    private fun withWorkspaceSession(
        requestedSessionId: String?,
        operation: String,
        workspaceHandle: String?,
        action: (ShizukuDirectoryHandleStore.WorkspaceHandle) -> String,
    ): String {
        val denial = checkSession(requestedSessionId)
        if (denial != null) return denied(operation, denial)
        val workspace = privilegedDirectories.workspace(workspaceHandle)
            ?: return denied(operation, ShizukuDirectoryHandleStore.INVALID_HANDLE)
        return runCatching { action(workspace) }
            .getOrElse { denied(operation, ShizukuWorkspaceFileStore.OPERATION_UNAVAILABLE) }
    }

    /**
     * Check both the one-time handshake caller identity and the per-service
     * protocol session.  The service's own UID is checked on every call so a
     * rooted/unknown UserService can never execute typed or shell work even if
     * a stale Binder handle reaches it.
     */
    private fun checkSession(requestedSessionId: String?): String? {
        checkCaller()?.let { return it }
        if (requestedSessionId.isNullOrBlank() || requestedSessionId != sessionId) {
            return ShizukuShellLimits.SESSION_INVALID
        }
        return null
    }

    /**
     * Validate the UserService process UID and the caller captured by the
     * one-time status/session handshake.  This is intentionally separate from
     * the token check because Shizuku's reserved destroy transaction cannot
     * carry a session-id argument.
     */
    private fun checkCaller(): String? {
        if (serviceUid != ShizukuBridgePolicy.SHELL_UID) return ShizukuShellLimits.UID_UNTRUSTED
        val callerUid = Binder.getCallingUid()
        val handshakeUid = synchronized(callerLock) { handshakeCallerUid }
            ?: return ShizukuShellLimits.SESSION_INVALID
        if (callerUid != handshakeUid) return ShizukuShellLimits.CALLER_UNTRUSTED
        return null
    }

    private fun denied(operation: String, code: String): String = JSONObject()
        .put("ok", false)
        .put("operation", operation)
        .put("code", code)
        .toString()
}
