// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.os.Binder
import android.os.Process
import org.json.JSONObject
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

    /** Reserved transaction used by Shizuku to tear down a UserService. */
    override fun destroy() {
        // destroy() has no session-id argument in Shizuku's reserved Binder
        // transaction.  Bind it to the same per-service caller identity as
        // the session-bound methods; an unauthenticated Binder holder must not
        // be able to tear down the privileged UserService.  Do not return the
        // session token or any diagnostic data through this void transaction.
        if (checkCaller() != null) return
        runCatching { shellRunner.close() }
        System.exit(0)
    }

    private fun withSession(
        requestedSessionId: String?,
        operation: String,
        action: () -> String,
    ): String {
        val denial = checkSession(requestedSessionId)
        return denial?.let { denied(operation, it) } ?: action()
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
