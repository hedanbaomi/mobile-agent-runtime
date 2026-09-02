// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.content.Intent
import android.net.Uri
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceResult

/** Stable error vocabulary for the provider-neutral workspace entry points. */
enum class WorkspaceAccessErrorCode {
    INVALID_REQUEST,
    WORKSPACE_NOT_FOUND,
    AUTHORITY_NOT_SELECTED,
    AUTHORITY_UNAVAILABLE,
    CAPABILITY_DENIED,
    CONFLICT,
    UNSUPPORTED,
    URI_PERMISSION_REQUIRED,
    PERSISTENCE_FAILED,
    UNKNOWN_OUTCOME,
}

enum class WorkspaceAccessStatus {
    ACTIVE,
    GRANT_LOST,
    REVOKED,
    DISABLED,
    UNAVAILABLE,
}

/**
 * Display-safe metadata for one workspace. It deliberately contains no URI,
 * root reference, absolute path, serial, provider document id, or secret.
 */
data class WorkspaceAccessItem(
    val workspaceId: String,
    val displayName: String,
    val backendType: WorkspaceBackendType,
    val scope: WorkspaceScope,
    val readable: Boolean,
    val writable: Boolean,
    val status: WorkspaceAccessStatus,
    val authority: Authority? = null,
    /**
     * True when this Agent still owns a durable, active capability grant for
     * the workspace. This is intentionally independent from [status]: an ADB
     * transport may be offline while the user's authorization remains valid.
     */
    val durablyAuthorized: Boolean = false,
    val grantedCapabilities: Set<CapabilityId> = emptySet(),
    val grantRevision: Long? = null,
)

/** Opaque, display-safe summary of a grant created by a shortcut operation. */
data class WorkspaceAccessGrantSummary(
    val grantId: String,
    val capability: CapabilityId,
    val lifetime: GrantLifetime,
    val revision: Long,
)

/**
 * Complete a successful workspace mutation from the exact values that were
 * committed in the transaction. A caller must not re-read repositories merely
 * to assemble the return DTO: that can turn a durable success into an
 * UNKNOWN_OUTCOME in the UI when a later projection read fails.
 */
internal fun committedWorkspaceAccessItem(
    workspace: Workspace,
    displayName: String,
    status: WorkspaceAccessStatus,
    authority: Authority?,
    activeGrants: List<CapabilityGrant>,
    fullDeviceConfirmationPresent: Boolean,
): WorkspaceAccessItem = WorkspaceAccessItem(
    workspaceId = workspace.id,
    displayName = displayName,
    backendType = workspace.backendType,
    scope = workspace.scope,
    readable = workspace.readable && status == WorkspaceAccessStatus.ACTIVE,
    writable = workspace.writable && status == WorkspaceAccessStatus.ACTIVE,
    status = status,
    authority = authority,
    durablyAuthorized = activeGrants.isNotEmpty() && fullDeviceConfirmationPresent,
    grantedCapabilities = activeGrants.map { it.capability }.toSet(),
    grantRevision = activeGrants.maxOfOrNull { it.revision },
)

/**
 * Target for a one-transaction workspace shortcut. Identity and path scope
 * are inputs owned by the caller; they are never returned by this port.
 */
data class WorkspaceAccessGrantTarget(
    val agentId: String,
    /** Empty selects the backend's currently available typed file capabilities. */
    val capabilities: Set<CapabilityId> = emptySet(),
    val lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
    val pathScope: String? = null,
)

sealed interface WorkspaceAccessResult {
    data class Success(
        val workspace: WorkspaceAccessItem,
        val grants: List<WorkspaceAccessGrantSummary> = emptyList(),
    ) : WorkspaceAccessResult

    data class Failure(val code: WorkspaceAccessErrorCode) : WorkspaceAccessResult
}

/**
 * Single app-facing facade for all workspace entry paths. SAF accepts a
 * transient activity-result URI as an input only; implementations persist and
 * consume it without exposing it through any output DTO. Privileged methods
 * require the explicitly selected authority and never fall back to another.
 */
interface WorkspaceAccessPort {
    fun listWorkspaces(
        agentId: String? = null,
    ): List<WorkspaceAccessItem>

    /** Attach/reuse one SAF tree and optionally grant it in the same operation. */
    fun attachSaf(
        uri: Uri,
        resultFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        grant: WorkspaceAccessGrantTarget? = null,
    ): WorkspaceAccessResult

    /** Grant an already attached workspace without changing its backend. */
    fun grantWorkspace(workspaceId: String, grant: WorkspaceAccessGrantTarget): WorkspaceAccessResult

    fun revokeGrant(grantId: String, expectedRevision: Long): WorkspaceAccessResult

    /** Revoke all grants for this workspace and disable its backend. */
    fun revokeWorkspace(workspaceId: String): WorkspaceAccessResult

    /**
     * Return the current durable full-device grant revision, including a
     * revoked tombstone. Callers use this value to form a CAS request; null
     * means that this workspace has never had a full-device confirmation.
     */
    fun fullDeviceFilesGrantRevision(workspaceId: String): Long?

    /** Browse only through the selected typed authority; handles are in-memory. */
    suspend fun browsePrivilegedRoot(
        authority: Authority,
        maxEntries: Int = 256,
    ): WorkspaceResult<WorkspaceDirectoryPage>

    suspend fun browsePrivileged(
        authority: Authority,
        request: WorkspaceBrowseRequest,
    ): WorkspaceResult<WorkspaceDirectoryPage>

    /** Attach a provider-owned directory handle as a new opaque workspace. */
    suspend fun attachPrivilegedDirectory(
        authority: Authority,
        request: WorkspaceAttachRequest,
        grant: WorkspaceAccessGrantTarget? = null,
    ): WorkspaceAccessResult

    /**
     * Attach a foreground-user-entered absolute path. Only the wired ADB
     * adapter supports this input; the path is consumed by the broker and is
     * never returned through UI state, diagnostics, tools, or model context.
     */
    suspend fun attachPrivilegedPath(
        authority: Authority,
        workspaceId: String,
        displayName: String,
        absolutePath: String,
        grant: WorkspaceAccessGrantTarget? = null,
    ): WorkspaceAccessResult

    /** Explicit high-risk scope; never inferred from ordinary directory access. */
    suspend fun openFullDeviceFiles(
        authority: Authority,
        request: FullDeviceFilesRequest,
        grant: WorkspaceAccessGrantTarget? = null,
    ): WorkspaceAccessResult

    suspend fun revokeFullDeviceFiles(
        authority: Authority,
        workspaceId: String,
        expectedRevision: Long,
    ): WorkspaceAccessResult
}
