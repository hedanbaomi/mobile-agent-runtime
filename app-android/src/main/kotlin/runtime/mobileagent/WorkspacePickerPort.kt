// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Intent
import android.net.Uri
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.integration.WorkspaceAccessItem
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceResult

/**
 * The display-safe state of the one explicitly selected elevated authority.
 *
 * The picker must not infer a provider from a live Binder/socket.  The
 * RuntimeIntegration adapter supplies this snapshot from its canonical
 * authority manager; [ready] is only a dispatchability hint for the selected
 * authority and is never a reason to choose another authority.
 */
enum class WorkspacePickerAuthorityStatus {
    READY,
    CONNECTING,
    OFFLINE,
    NOT_SELECTED,
    UNSUPPORTED,
}

data class WorkspacePickerAuthoritySnapshot(
    val selectedAuthority: Authority = Authority.NONE,
    val status: WorkspacePickerAuthorityStatus = WorkspacePickerAuthorityStatus.NOT_SELECTED,
    val ready: Boolean = false,
) {
    init {
        require(selectedAuthority == Authority.NONE || selectedAuthority == Authority.SHIZUKU || selectedAuthority == Authority.WIRED_ADB)
        require(!ready || status == WorkspacePickerAuthorityStatus.READY)
    }
}

/**
 * A target is supplied by the foreground UI, never by a model tool call.
 *
 * Grant, Agent default and Thread binding are not fields on this type.
 * [RuntimeIntegration] derives a single [runtime.mobileagent.domain.WorkspaceIntent]
 * from the identities: a Thread id binds that Thread, an Agent id alone sets
 * the Agent default, and an empty target only adds the workspace to the library.
 */
data class WorkspacePickerTarget(
    val agentId: String? = null,
    val threadId: String? = null,
) {
    init {
        require(agentId == null || agentId.isNotBlank())
        require(threadId == null || threadId.isNotBlank())
        require(threadId == null || agentId != null) {
            "A thread workspace target requires an agent target"
        }
    }
}

/** Optional access information supplied by the provider adapter. */
data class WorkspacePickerDirectoryAccess(
    val readable: Boolean = true,
    val writable: Boolean = false,
)

/**
 * Narrow app-facing picker seam.  It deliberately reuses the existing
 * provider-neutral browse and attach DTOs; no path, URI, serial, locator, or
 * Binder object is exposed through picker state.
 *
 * RuntimeIntegration should provide the implementation.  Attach methods take
 * only a [WorkspacePickerTarget]: the grant, Agent default and Thread binding
 * semantics are derived from that target inside the canonical transaction, so
 * a picker can never assemble its own attach + grant + default combination.
 */
interface WorkspacePickerPort {
    fun authoritySnapshot(): WorkspacePickerAuthoritySnapshot

    fun recentWorkspaces(agentId: String? = null): List<WorkspaceAccessItem> = emptyList()

    suspend fun browsePrivilegedRoot(
        authority: Authority,
        maxEntries: Int = DEFAULT_PAGE_SIZE,
    ): WorkspaceResult<WorkspaceDirectoryPage>

    suspend fun browsePrivileged(
        authority: Authority,
        request: WorkspaceBrowseRequest,
    ): WorkspaceResult<WorkspaceDirectoryPage>

    /**
     * Implementations may override this to expose the provider's known access
     * bit for the current opaque handle.  A successful browse is readable by
     * default; an adapter can return false for a provider-reported inaccessible
     * directory so that the picker disables “使用此文件夹”.
     */
    fun directoryAccess(page: WorkspaceDirectoryPage): WorkspacePickerDirectoryAccess =
        WorkspacePickerDirectoryAccess(readable = true, writable = false)

    /** Attach a privileged directory; grant/default/binding follow the target. */
    suspend fun attachPrivilegedDirectory(
        authority: Authority,
        request: WorkspaceAttachRequest,
        target: WorkspacePickerTarget = WorkspacePickerTarget(),
    ): WorkspaceAccessResult

    /** SAF is an explicit fallback and accepts a transient Activity-result URI only. */
    suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int = DEFAULT_SAF_FLAGS,
        target: WorkspacePickerTarget = WorkspacePickerTarget(),
    ): WorkspaceAccessResult

    /** Opens a previously attached recent workspace without changing its identity. */
    suspend fun useRecentWorkspace(
        workspaceId: String,
        target: WorkspacePickerTarget = WorkspacePickerTarget(),
    ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
        runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNSUPPORTED,
    )

    /**
     * Revalidates and commits authorization for switching to a new Thread workspace.
     * Called only after the user explicitly confirms the new Thread creation.
     */
    suspend fun confirmNewThreadWorkspace(
        agentId: String,
        currentThreadId: String,
        currentWorkspaceId: String,
        requestedWorkspaceId: String,
    ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
        runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNSUPPORTED,
    )

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 128
        const val DEFAULT_SAF_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** A deterministic fail-closed implementation for unconnected UI previews/tests. */
object UnavailableWorkspacePickerPort : WorkspacePickerPort {
    override fun authoritySnapshot(): WorkspacePickerAuthoritySnapshot = WorkspacePickerAuthoritySnapshot()

    override suspend fun browsePrivilegedRoot(
        authority: Authority,
        maxEntries: Int,
    ): WorkspaceResult<WorkspaceDirectoryPage> = unavailableBrowse()

    override suspend fun browsePrivileged(
        authority: Authority,
        request: WorkspaceBrowseRequest,
    ): WorkspaceResult<WorkspaceDirectoryPage> = unavailableBrowse()

    override suspend fun attachPrivilegedDirectory(
        authority: Authority,
        request: WorkspaceAttachRequest,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
        runtime.mobileagent.integration.WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE,
    )

    override suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
        runtime.mobileagent.integration.WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED,
    )

    override suspend fun confirmNewThreadWorkspace(
        agentId: String,
        currentThreadId: String,
        currentWorkspaceId: String,
        requestedWorkspaceId: String,
    ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
        runtime.mobileagent.integration.WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE,
    )

    private fun unavailableBrowse(): WorkspaceResult<WorkspaceDirectoryPage> = WorkspaceResult.Failure(
        ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE),
    )
}

/**
 * A small helper for ports that need to keep an opaque directory handle while
 * the picker is open.  It intentionally has no serialisation or path API.
 */
internal data class WorkspacePickerHandleRef(
    val handle: WorkspaceDirectoryHandle,
)
