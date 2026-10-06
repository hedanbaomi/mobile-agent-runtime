// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.shizuku.*
import runtime.mobileagent.skills.tooling.*
import runtime.mobileagent.wired.*
import runtime.mobileagent.workspace.WorkspaceVersionProjection

/** Own shell-UID resident service. USB performs activation; Binder performs later work. */
class ResidentAdbAuthorityBridge private constructor(
    context: Context,
    private val shellPermission: () -> Boolean,
    private val diagnostics: WiredAdbDiagnosticSink,
) : WiredAdbAuthorityPort, WiredAdbWorkspacePort, WiredAdbShellPort {
    private val registry = ResidentAdbRegistry.get(context)
    private val client = ShizukuAuthorityBridge(context, registry)
    @Volatile private var closed = false
    private val _status = MutableStateFlow(readStatus())
    override val status: StateFlow<WiredAdbStatus> = _status
    override val workspace: WiredAdbWorkspacePort get() = this
    override val shell: WiredAdbShellPort get() = this
    private val listener: () -> Unit = { refresh() }

    init {
        registry.addListener(listener)
    }

    private fun readStatus(): WiredAdbStatus {
        val configured = registry.configured()
        val enabled = registry.enabled()
        val ready = !closed && registry.binder() != null && client.refresh().ready
        return WiredAdbStatus(
            state = when {
                registry.persistenceFailed() -> WiredAdbLifecycleState.REAUTH_REQUIRED
                ready -> WiredAdbLifecycleState.READY
                configured -> WiredAdbLifecycleState.DISCONNECTED
                else -> WiredAdbLifecycleState.UNPAIRED
            },
            userIntent = if (enabled) WiredAdbUserIntent.ENABLED else WiredAdbUserIntent.DISABLED,
            platformGrant = if (configured) WiredAdbPlatformGrant.GRANTED else WiredAdbPlatformGrant.UNKNOWN,
            availability = if (ready) WiredAdbAvailability.READY else WiredAdbAvailability.TEMPORARILY_UNAVAILABLE,
            connection = if (ready) WiredAdbConnectionState.CONNECTED else WiredAdbConnectionState.DISCONNECTED,
            trusted = configured,
            serviceSessionId = registry.generation().takeIf { ready },
            protocolVersion = BridgeProtocol.VERSION,
            lastError = if (registry.persistenceFailed()) WiredAdbErrorCode.BRIDGE_SECRET_UNAVAILABLE else null,
        )
    }

    fun refresh(): WiredAdbStatus = readStatus().also { next ->
        val previous = _status.value
        _status.value = next
        if (previous.state != next.state) runCatching {
            diagnostics.record(WiredAdbDiagnosticEvent(state = next.state, operation = "lifecycle", outcome = "state", error = next.lastError))
        }
    }

    override fun setUserIntent(enabled: Boolean) { check(!closed); registry.setEnabled(enabled); refresh() }

    override fun requestPairingFromForeground(replaceExistingTrust: Boolean): WiredAdbResult<WiredAdbPairingPrompt> {
        if (closed) return WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_DISCONNECTED)
        val activation = registry.activationToken(replaceExistingTrust)
            ?: return WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_ALREADY_CONNECTED)
        val hex = try { activation.first.joinToString("") { "%02x".format(it.toInt() and 255) } }
            finally { activation.first.fill(0) }
        _status.value = readStatus().copy(state = WiredAdbLifecycleState.PAIRING)
        return WiredAdbResult.Success(WiredAdbPairingPrompt(hex, activation.second, ResidentAdbProtocol.MAX_ATTEMPTS))
    }

    override fun cancelPairing() { registry.cancelActivation(); refresh() }

    override suspend fun pair(): WiredAdbResult<WiredAdbTrustRecord> {
        if (connect() !is WiredAdbResult.Success) return WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, true)
        val generation = registry.generation() ?: return WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_SECRET_UNAVAILABLE)
        // Compatibility return value only. Resident identity is never saved into legacy desktop trust.
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(generation.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return WiredAdbResult.Success(WiredAdbTrustRecord("resident-$generation", generation, fingerprint,
            BridgeProtocol.VERSION, "resident-keystore-v1", fingerprint))
    }

    override suspend fun connect(): WiredAdbResult<Unit> = withContext(Dispatchers.IO) {
        if (closed) return@withContext WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_DISCONNECTED)
        if (!registry.enabled() && !registry.activationPending()) return@withContext WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_USER_DISABLED)
        repeat(100) {
            if (registry.binder() != null && registry.verify() && refresh().state == WiredAdbLifecycleState.READY) {
                return@withContext WiredAdbResult.Success(Unit)
            }
            delay(100)
        }
        refresh()
        WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, true)
    }

    /** There is no USB-bound session to tear down after resident activation. */
    override fun disconnect() { refresh() }
    override suspend fun forget() = withContext(Dispatchers.IO) {
        // After app restart the daemon is alive while this process has no cached Binder.
        // Ask it to republish before revocation; never erase the sole recovery credential first.
        val publication = if (registry.serviceAlive()) ResidentAdbRegistry.Publication.CONNECTED
            else registry.requestPublicationState()
        var shutdownConfirmed = false
        if ((publication == ResidentAdbRegistry.Publication.CONNECTED || publication == ResidentAdbRegistry.Publication.UNKNOWN) && !registry.serviceAlive()) {
            repeat(100) { if (!registry.serviceAlive()) delay(50) }
            check(registry.serviceAlive() && !registry.persistenceFailed()) { "Resident ADB revocation requires authenticated reconnection" }
        }
        if (registry.serviceAlive()) {
            check(registry.requestShutdown()) { "Resident ADB revocation authentication failed" }
            repeat(100) { if (registry.serviceAlive()) delay(20) }
            check(!registry.serviceAlive()) { "Resident ADB shutdown has not completed" }
            shutdownConfirmed = true
        }
        check(registry.forget(daemonKnownAbsent = shutdownConfirmed || publication == ResidentAdbRegistry.Publication.ABSENT ||
            publication == ResidentAdbRegistry.Publication.NO_CREDENTIAL)) { "Resident ADB revocation has not completed" }
        refresh()
        Unit
    }

    fun createWorkspaceBackend(workspaceId: String = "wired-adb", displayName: String = "ADB workspace"): WorkspaceBackend =
        ShizukuWorkspaceBackendAdapter(client, workspaceId, displayName)

    fun createWorkspaceProvider(
        workspaceId: String = "wired-adb",
        displayName: String = "ADB workspace",
        fullDeviceGrantStore: FullDeviceFilesGrantStore? = null,
    ): PrivilegedWorkspaceProvider = ShizukuDeviceWorkspaceProvider(client, workspaceId, displayName, fullDeviceGrantStore, Authority.WIRED_ADB)

    fun createShellExecutor(): ShellExecutor {
        val executor = ShizukuShellExecutor(client, Authority.WIRED_ADB)
        return object : ShellExecutor {
            override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                if (closed || !shellPermission() || registry.binder() == null) return ShellExecResult(
                    status = ShellExecutionStatus.FAILED, authority = Authority.WIRED_ADB,
                    requestId = request.requestId, error = ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE),
                )
                return executor.execute(request)
            }
            override suspend fun cancel(requestId: String): Boolean = executor.cancel(requestId)
        }
    }

    override fun newFileRequest(operation: WiredAdbFileOperation, relativePath: String?, destinationRelativePath: String?, contentUtf8: ByteArray?, replaceExisting: Boolean,
        maxBytes: Int, cursor: String?, maxEntries: Int, offsetBytes: Long, patchUtf8: ByteArray?, expectedVersion: Long?, patchFormat: WiredAdbPatchFormat): WiredAdbFileRequest =
        WiredAdbFileRequest(newWiredAdbRequestId(), operation, relativePath, destinationRelativePath, contentUtf8?.copyOf(), replaceExisting,
            maxBytes, cursor, maxEntries, offsetBytes, patchUtf8?.copyOf(), expectedVersion, patchFormat)

    override fun newShellRequest(command: String, cwd: String?, timeoutMs: Long, maxOutputBytes: Long): WiredAdbShellRequest =
        WiredAdbShellRequest(newWiredAdbRequestId(), command, cwd, timeoutMs, maxOutputBytes)

    override suspend fun executeShell(request: WiredAdbShellRequest): WiredAdbResult<WiredAdbShellResult> {
        if (closed || !shellPermission()) return WiredAdbResult.Failure(WiredAdbErrorCode.SHELL_CAPABILITY_DENIED)
        val output = client.execute(ShizukuShellRequest(request.requestId.value, request.command, request.cwd, request.timeoutMs,
            request.maxOutputBytes.coerceAtMost(ShizukuShellLimits.MAX_SERIALIZED_OUTPUT_BYTES.toLong()).toInt(),
            request.maxOutputBytes.coerceAtMost(ShizukuShellLimits.MAX_SERIALIZED_OUTPUT_BYTES.toLong()).toInt()))
        if (output.unknownOutcome) return WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME)
        if (output.state == ShizukuShellResult.State.DENIED) return WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        return WiredAdbResult.Success(WiredAdbShellResult(output.exitCode, output.stdout, output.stderr, output.timedOut, output.cancelled,
            output.stdoutTruncated, output.stderrTruncated, output.durationMs ?: 0))
    }

    override suspend fun cancel(requestId: WiredAdbRequestId): WiredAdbResult<Unit> =
        if (client.cancel(requestId.value)) WiredAdbResult.Success(Unit) else WiredAdbResult.Failure(WiredAdbErrorCode.BRIDGE_DISCONNECTED)

    override suspend fun executeFile(request: WiredAdbFileRequest): WiredAdbResult<WiredAdbFileResult> = withContext(Dispatchers.IO) {
        if (closed || !registry.granted()) return@withContext WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val path = request.relativePath ?: ""
        val result = when (request.operation) {
            WiredAdbFileOperation.LIST -> client.dispatchListPaged(path, request.maxEntries, request.cursor)
            WiredAdbFileOperation.STAT -> client.dispatchStat(path)
            WiredAdbFileOperation.READ_TEXT -> client.dispatchRead(path, request.maxBytes)
            WiredAdbFileOperation.WRITE_TEXT -> client.dispatchWrite(path, request.contentUtf8 ?: byteArrayOf(), request.replaceExisting)
            WiredAdbFileOperation.CREATE_DIRECTORY -> client.dispatchMkdir(path)
            WiredAdbFileOperation.MOVE -> client.dispatchMove(path, request.destinationRelativePath ?: "", request.replaceExisting)
            WiredAdbFileOperation.DELETE -> client.dispatchDelete(path)
            WiredAdbFileOperation.APPLY_PATCH -> {
                val stat = client.dispatchStat(path) as? ShizukuDispatchResult.Success
                    ?: return@withContext WiredAdbResult.Failure(WiredAdbErrorCode.CONFLICT)
                val version = JSONObject(stat.payload).optString("version", "")
                if (runCatching { WorkspaceVersionProjection.toPublic(version) }.getOrNull() != request.expectedVersion) {
                    return@withContext WiredAdbResult.Failure(WiredAdbErrorCode.CONFLICT)
                }
                client.dispatchApplyPatch(path, String(request.patchUtf8 ?: byteArrayOf(), Charsets.UTF_8), version,
                    request.patchFormat.name)
            }
        }
        when (result) {
            is ShizukuDispatchResult.Denied -> WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
            is ShizukuDispatchResult.Failed -> WiredAdbResult.Failure(if (result.unknownOutcome) WiredAdbErrorCode.UNKNOWN_OUTCOME else WiredAdbErrorCode.OPERATION_UNAVAILABLE)
            is ShizukuDispatchResult.Success -> try {
                val value = JSONObject(result.payload)
                if (!value.optBoolean("ok", false)) return@withContext WiredAdbResult.Failure(WiredAdbErrorCode.OPERATION_UNAVAILABLE)
                val entries = value.optJSONArray("entries")
                WiredAdbResult.Success(WiredAdbFileResult(request.operation, request.relativePath,
                    entries = (0 until (entries?.length() ?: 0)).map { index ->
                        val item = entries!!.getJSONObject(index)
                        WiredAdbFileEntry(item.getString("path"), if (item.getString("type") == "directory") WiredAdbEntryType.DIRECTORY else WiredAdbEntryType.FILE,
                            if (item.has("bytes")) item.optLong("bytes") else null)
                    }, text = value.takeIf { it.has("text") && !it.isNull("text") }?.getString("text"),
                    bytes = if (value.has("bytes")) value.optLong("bytes") else null,
                    truncated = value.optBoolean("truncated", false),
                    nextCursor = value.takeIf { it.has("nextCursor") && !it.isNull("nextCursor") }?.getString("nextCursor")))
            } catch (_: Exception) { WiredAdbResult.Failure(WiredAdbErrorCode.UNKNOWN_OUTCOME) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        registry.removeListener(listener)
        client.close()
        _status.value = readStatus()
    }

    companion object {
        @JvmStatic fun create(context: Context, shellPermission: () -> Boolean = { false }, diagnostics: WiredAdbDiagnosticSink = NOOP_WIRED_DIAGNOSTICS): ResidentAdbAuthorityBridge =
            ResidentAdbAuthorityBridge(context.applicationContext, shellPermission, diagnostics)
    }
}
