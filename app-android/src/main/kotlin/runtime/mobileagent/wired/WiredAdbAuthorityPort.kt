// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend-neutral file surface exposed to the Runtime container.
 *
 * The request objects are created by [WiredAdbAuthorityPort.newFileRequest],
 * so request identifiers remain Runtime-owned.  No transport, ADB path,
 * serial, endpoint, token, secret, or model JSON crosses this port.
 */
interface WiredAdbWorkspacePort {
    suspend fun executeFile(request: WiredAdbFileRequest): WiredAdbResult<WiredAdbFileResult>

    /**
     * Binds a user-selected absolute device directory to an opaque handle.
     * The path is accepted only from a foreground user action and is never
     * returned in the attachment or exposed to model-facing code.
     */
    suspend fun attachDirectory(
        workspaceId: String,
        displayName: String,
        absolutePath: String,
        scope: WiredAdbWorkspaceScope = WiredAdbWorkspaceScope.SELECTED_DIRECTORY,
        grantRevision: Long = 0L,
        confirmedByUser: Boolean = true,
    ): WiredAdbResult<WiredAdbWorkspaceAttachment> =
        WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)

    suspend fun browseDirectory(
        handle: WiredAdbWorkspaceHandle,
        relativePath: String? = null,
        maxEntries: Int = WIRED_MAX_DIRECTORY_ENTRIES,
    ): WiredAdbResult<WiredAdbWorkspacePage> =
        WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)

    suspend fun executeBoundFile(
        handle: WiredAdbWorkspaceHandle,
        request: WiredAdbFileRequest,
    ): WiredAdbResult<WiredAdbFileResult> =
        WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)

    suspend fun releaseDirectory(handle: WiredAdbWorkspaceHandle): WiredAdbResult<Unit> =
        WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)
}

enum class WiredAdbWorkspaceScope { SELECTED_DIRECTORY, FULL_DEVICE_FILES }

/** Opaque per-connection handle; path and bridge binding remain package-private. */
class WiredAdbWorkspaceHandle internal constructor(
    internal val owner: Any,
    internal val workspaceId: String,
    internal val binding: String,
    internal val epoch: Long,
) {
    override fun toString(): String = "WiredAdbWorkspaceHandle(workspaceId=$workspaceId)"
}

data class WiredAdbWorkspaceAttachment(
    val workspaceId: String,
    val scope: WiredAdbWorkspaceScope,
    val handle: WiredAdbWorkspaceHandle,
    val initialPage: WiredAdbWorkspacePage,
) {
    override fun toString(): String =
        "WiredAdbWorkspaceAttachment(workspaceId=$workspaceId, scope=$scope)"
}

data class WiredAdbWorkspacePage(
    val handle: WiredAdbWorkspaceHandle,
    val relativePath: String,
    val entries: List<WiredAdbFileEntry>,
    val truncated: Boolean,
)

/**
 * Backend-neutral dangerous-shell surface.  Policy/capability checks stay in
 * the caller and in the bridge; this port never executes a local shell.
 */
interface WiredAdbShellPort {
    suspend fun executeShell(request: WiredAdbShellRequest): WiredAdbResult<WiredAdbShellResult>

    suspend fun cancel(requestId: WiredAdbRequestId): WiredAdbResult<Unit>
}

/**
 * Narrow cross-package authority contract used by AppContainer.
 *
 * The concrete bridge and its shared-protocol/session factory remain hidden
 * behind [WiredAdbAuthorityBridgeFactory].  Pairing is intentionally a
 * no-argument operation here: the foreground-only token is held by the
 * bridge and is never supplied by model-facing code.
 */
interface WiredAdbAuthorityPort : AutoCloseable {
    val status: StateFlow<WiredAdbStatus>

    val workspace: WiredAdbWorkspacePort

    val shell: WiredAdbShellPort

    fun setUserIntent(enabled: Boolean)

    fun requestPairingFromForeground(replaceExistingTrust: Boolean = false): WiredAdbResult<WiredAdbPairingPrompt>

    suspend fun pair(): WiredAdbResult<WiredAdbTrustRecord>

    /** Clears only an in-progress foreground pairing; durable trust is kept. */
    fun cancelPairing() = Unit

    suspend fun connect(): WiredAdbResult<Unit>

    fun disconnect()

    suspend fun forget()

    fun newFileRequest(
        operation: WiredAdbFileOperation,
        relativePath: String?,
        destinationRelativePath: String? = null,
        contentUtf8: ByteArray? = null,
        replaceExisting: Boolean = false,
        maxBytes: Int = WIRED_DEFAULT_READ_BYTES,
    ): WiredAdbFileRequest

    fun newShellRequest(
        command: String,
        cwd: String? = null,
        timeoutMs: Long = 30_000L,
        maxOutputBytes: Long = WIRED_ADB_MAX_SHELL_OUTPUT_BYTES,
    ): WiredAdbShellRequest
}

/**
 * Public construction seam for AppContainer.  It fixes the only transport
 * (ADB reverse loopback) and the shared protocol/session implementation;
 * callers inject only Android persistence and policy ports.  First pairing
 * receives the desktop identity from the authenticated shared challenge; no
 * selected serial or desktop identity is accepted here.
 */
object WiredAdbAuthorityBridgeFactory {
    /**
     * Production app-scoped construction.  The dedicated wired app identity
     * is generated and persisted in the canonical wired metadata namespace;
     * it is not derived from announcement, telemetry, or Runtime identity.
     */
    @JvmStatic
    fun create(
        context: Context,
        diagnostics: WiredAdbDiagnosticSink = NOOP_WIRED_DIAGNOSTICS,
        shellPermission: () -> Boolean = { false },
    ): WiredAdbAuthorityPort {
        val metadata = AndroidWiredAdbMetadataStoreFactory.create(context)
        val appInstanceId = metadata.loadOrCreateAppInstanceId()
        return create(
            appInstanceId = appInstanceId,
            trustStore = metadata.trustStore,
            secretStore = AndroidKeystoreWiredAdbSecretStoreFactory.create(context),
            intentStore = metadata.intentStore,
            diagnostics = diagnostics,
            shellPermission = shellPermission,
        )
    }

    /**
     * Injectable construction seam for tests and a host that owns equivalent
     * durable stores.  The caller supplies only the app identity and stores;
     * first-pair desktop/serial identity is still challenge-delivered.
     */
    @JvmStatic
    fun create(
        appInstanceId: String,
        trustStore: WiredAdbTrustStore,
        secretStore: WiredAdbBoundSecretStore,
        intentStore: WiredAdbIntentStore,
        diagnostics: WiredAdbDiagnosticSink = NOOP_WIRED_DIAGNOSTICS,
        shellPermission: () -> Boolean = { false },
    ): WiredAdbAuthorityPort = WiredAdbAuthorityBridge(
        appInstanceId = appInstanceId,
        trustStore = trustStore,
        secretStore = secretStore,
        intentStore = intentStore,
        connector = FixedLoopbackConnector(),
        sessionFactory = DEFAULT_WIRED_SESSION_FACTORY,
        clock = DEFAULT_WIRED_CLOCK,
        random = DEFAULT_WIRED_RANDOM,
        diagnostics = diagnostics,
        shellPermission = shellPermission,
    )
}
