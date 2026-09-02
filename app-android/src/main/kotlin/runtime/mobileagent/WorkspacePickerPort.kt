// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Intent
import android.net.Uri
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.integration.WorkspaceAccessGrantTarget
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

/** A target is supplied by the foreground UI, never by a model tool call. */
data class WorkspacePickerTarget(
    val agentId: String? = null,
    val threadId: String? = null,
    val setAsAgentDefault: Boolean = false,
    val grantCapabilities: Set<CapabilityId> = emptySet(),
    val lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
) {
    init {
        require(agentId == null || agentId.isNotBlank())
        require(threadId == null || threadId.isNotBlank())
        require(threadId == null || agentId != null) {
            "A thread workspace target requires an agent target"
        }
    }

    fun grantForWorkspace(): WorkspaceAccessGrantTarget? = agentId?.let {
        WorkspaceAccessGrantTarget(
            agentId = it,
            capabilities = grantCapabilities,
            lifetime = lifetime,
        )
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
 * RuntimeIntegration should provide the implementation.  The overloads with
 * [target] preserve the exact existing three-argument attach methods for
 * adapters that have not yet connected Thread/default binding.  A production
 * adapter should override the target overload and commit the workspace,
 * Agent grant, and Thread/default binding in its own canonical transaction.
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

    suspend fun attachPrivilegedDirectory(
        authority: Authority,
        request: WorkspaceAttachRequest,
        grant: WorkspaceAccessGrantTarget? = null,
    ): WorkspaceAccessResult

    suspend fun attachPrivilegedDirectory(
        authority: Authority,
        request: WorkspaceAttachRequest,
        grant: WorkspaceAccessGrantTarget?,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult = attachPrivilegedDirectory(authority, request, grant)

    /** SAF is an explicit fallback and accepts a transient Activity-result URI only. */
    suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int = DEFAULT_SAF_FLAGS,
        grant: WorkspaceAccessGrantTarget? = null,
    ): WorkspaceAccessResult

    suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int,
        grant: WorkspaceAccessGrantTarget?,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult = attachSaf(uri, resultFlags, grant)

    /** Opens a previously attached recent workspace without changing its identity. */
    suspend fun useRecentWorkspace(
        workspaceId: String,
        target: WorkspacePickerTarget = WorkspacePickerTarget(),
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
        grant: WorkspaceAccessGrantTarget?,
    ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
        runtime.mobileagent.integration.WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE,
    )

    override suspend fun attachSaf(
        uri: Uri,
        resultFlags: Int,
        grant: WorkspaceAccessGrantTarget?,
    ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
        runtime.mobileagent.integration.WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED,
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
