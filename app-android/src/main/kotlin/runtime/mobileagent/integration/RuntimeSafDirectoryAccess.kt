// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.content.Intent
import android.net.Uri
import java.util.UUID
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AuthorityPolicyConflictException
import runtime.mobileagent.diagnostics.DiagnosticGrantScope
import runtime.mobileagent.diagnostics.WorkspaceGrantChangedRecord
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.SafWorkspaceGrant
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceIntentPlan
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.domain.plan
import runtime.mobileagent.workspace.WorkspaceUiKind
import runtime.mobileagent.workspace.persistedWorkspaceFolderLabel
import runtime.mobileagent.workspace.safTreeUiTitle

internal fun RuntimeDirectoryAccess.attachSafWorkspace(
    uri: Uri,
    resultFlags: Int,
    grant: WorkspaceAccessGrantTarget?,
    pickerTarget: WorkspacePickerTarget? = null,
    plan: WorkspaceIntentPlan,
): WorkspaceAccessResult {
    if (pickerTarget?.agentId != null && grant?.agentId != pickerTarget.agentId) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
    }
    if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
    }
    val requestedFlags = safRequestedFlags(resultFlags)
    try {
        // OpenDocumentTree may return a read-only grant. Preserve exactly
        // the provider's actual flags rather than assuming write access.
        appContext.contentResolver.takePersistableUriPermission(uri, requestedFlags)
    } catch (_: RuntimeException) {
        if (requestedFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
        }
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
        }
    }
    val persisted = appContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
    val actualFlags = safPersistableFlags(persisted.isReadPermission, persisted.isWritePermission)
    if (actualFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
    }

    val existingGrant = safWorkspaceGrantRepository.list(includeRevoked = true)
        .firstOrNull { it.uriReference == uri.toString() }
    val existingWorkspace = existingGrant?.let { workspaceRepository.get(it.workspaceId) }
    val id = existingGrant?.workspaceId ?: "saf-" + UUID.randomUUID().toString().replace("-", "")
    if (existingWorkspace != null && existingWorkspace.backendType != WorkspaceBackendType.SAF_TREE) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
    }
    val safTitle = safTreeUiTitle(appContext, uri)
    val workspace = Workspace(
        id = id,
        displayName = persistedWorkspaceFolderLabel(
            backendType = WorkspaceBackendType.SAF_TREE,
            requestedName = safTitle,
            ordinal = (workspaceRepository.list().count { it.backendType == WorkspaceBackendType.SAF_TREE } + 1),
            fullDevice = false,
        ),
        backendType = WorkspaceBackendType.SAF_TREE,
        rootReference = uri.toString(),
        readable = true,
        writable = actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
        quotaBytes = 4L * 1024L * 1024L,
        maxFileBytes = 256L * 1024L,
        enabled = true,
        revision = (existingWorkspace?.revision ?: 0L) + 1L,
        createdAt = existingWorkspace?.createdAt.orEmpty(),
        scope = WorkspaceScope.SELECTED_DIRECTORY,
    )
    val backend = try {
        runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, id)
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
    }
    val safGrant = SafWorkspaceGrant(
        workspaceId = id,
        uriReference = uri.toString(),
        readGranted = true,
        writeGranted = actualFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
        persistedFlags = actualFlags,
        status = SafGrantStatus.ACTIVE,
        createdAt = existingGrant?.createdAt ?: existingWorkspace?.createdAt.orEmpty(),
    )
    val selection = try {
        db.transaction {
            workspaceRepository.save(workspace)
            safWorkspaceGrantRepository.save(safGrant)
            grantStore.persistPickerSelection(workspace, pickerTarget, plan, grant) { backend }
        }
    } catch (failure: RuntimeWorkspaceAccessException) {
        return workspaceAccessFailure(failure.accessCode)
    } catch (_: AuthorityPolicyConflictException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }
    try {
        workspaceRegistry.registerOrReplace(workspace, backend)
    } catch (_: RuntimeException) {
        runCatching {
            workspaceRepository.save(
                workspace.copy(
                    enabled = false,
                    readable = false,
                    writable = false,
                    revision = workspace.revision + 1L,
                ),
            )
        }
        return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
    }
    presentation.remember(
        workspaceId = workspace.id,
        kind = WorkspaceUiKind.SAF,
        title = safTitle,
    )
    if (selection is RuntimePickerSelection.Committed) {
        diagnostics.recordWorkspaceGrantChanged(
            WorkspaceGrantChangedRecord(
                id,
                if (workspace.writable) DiagnosticGrantScope.READ_WRITE else DiagnosticGrantScope.READ,
                true,
            ),
        )
    }
    return finishPickerSelection(workspace, pickerTarget, selection, attachedName = workspace.displayName)
}
