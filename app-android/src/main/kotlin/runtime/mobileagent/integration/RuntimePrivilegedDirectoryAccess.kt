// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.diagnostics.PrivilegedWorkspaceBindingPersistedRecord
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceIntentPlan
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.domain.plan
import runtime.mobileagent.workspace.WorkspaceUiKind
import runtime.mobileagent.workspace.privilegedUiTitle
import runtime.mobileagent.security.PrivilegedWorkspaceBindingAad
import runtime.mobileagent.security.PrivilegedWorkspaceBindingSealResult
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult

internal suspend fun RuntimeDirectoryAccess.attachPrivilegedDirectoryInternal(
    authority: Authority,
    request: WorkspaceAttachRequest,
    grant: WorkspaceAccessGrantTarget?,
    pickerTarget: WorkspacePickerTarget? = null,
    plan: WorkspaceIntentPlan,
): WorkspaceAccessResult {
    if (pickerTarget?.agentId != null && grant?.agentId != pickerTarget.agentId) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
    }
    val provider = authorityProviderFor(authority)
        ?: return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
    val attachment = try {
        provider.attachDirectory(request)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
    }
    val value = when (attachment) {
        is WorkspaceResult.Failure -> return workspaceAccessFailure(attachment.error.code.toWorkspaceAccessCode())
        is WorkspaceResult.Success -> attachment.value
    }
    return persistPrivilegedAttachment(authority, request.workspaceId, request.displayName, value, grant, pickerTarget, plan)
}

internal suspend fun RuntimeDirectoryAccess.attachPrivilegedPathInternal(
    authority: Authority,
    workspaceId: String,
    displayName: String,
    absolutePath: String,
    grant: WorkspaceAccessGrantTarget?,
    pickerTarget: WorkspacePickerTarget? = null,
    plan: WorkspaceIntentPlan,
): WorkspaceAccessResult {
    if (authority != Authority.WIRED_ADB) return workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)
    val provider = authorityProviderFor(authority) as? runtime.mobileagent.wired.WiredAdbDeviceWorkspaceProvider
        ?: return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
    val attachment = try {
        provider.attachUserPath(workspaceId, displayName, absolutePath)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
    }
    val value = when (attachment) {
        is WorkspaceResult.Failure -> return workspaceAccessFailure(attachment.error.code.toWorkspaceAccessCode())
        is WorkspaceResult.Success -> attachment.value
    }
    return persistPrivilegedAttachment(authority, workspaceId, displayName, value, grant, pickerTarget, plan)
}

internal fun RuntimeDirectoryAccess.persistPrivilegedAttachment(
    authority: Authority,
    workspaceId: String,
    displayName: String,
    value: runtime.mobileagent.skills.tooling.WorkspaceAttachment,
    grant: WorkspaceAccessGrantTarget?,
    pickerTarget: WorkspacePickerTarget? = null,
    plan: WorkspaceIntentPlan,
): WorkspaceAccessResult = persistPrivilegedAttachment(value, workspaceId, workspaceRepository) { existing ->
    persistPrivilegedAttachmentInternal(authority, workspaceId, displayName, value, grant, pickerTarget, plan, existing)
}

/** All consumers below run inside the attachment owner's cleanup scope. */
private fun RuntimeDirectoryAccess.persistPrivilegedAttachmentInternal(
    authority: Authority,
    workspaceId: String,
    displayName: String,
    value: runtime.mobileagent.skills.tooling.WorkspaceAttachment,
    grant: WorkspaceAccessGrantTarget?,
    pickerTarget: WorkspacePickerTarget?,
    plan: WorkspaceIntentPlan,
    existing: Workspace?,
): WorkspaceAccessResult {
    if (existing != null && (
        existing.backendType != WorkspaceBackendType.PRIVILEGED ||
            existing.scope == WorkspaceScope.FULL_DEVICE_FILES
        )
    ) {
        value.recoveryLocator?.clear()
        return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
    }
    val recoveryLocator = value.recoveryLocator
        ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)
    val locatorVersion = RuntimeWorkspaceBindingFormat.LOCATOR_VERSION
    val aad = try {
        PrivilegedWorkspaceBindingAad(
            appInstanceId = appInstanceId,
            workspaceId = workspaceId,
            authority = authority,
            locatorVersion = locatorVersion,
        )
    } catch (_: IllegalArgumentException) {
        recoveryLocator.clear()
        return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
    }
    val locatorBytes = runCatching { recoveryLocator.copyBytes() }.getOrNull()
        ?: run {
            recoveryLocator.clear()
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
    val envelope = try {
        when (val sealed = bindingCipher.seal(locatorBytes, aad)) {
            is PrivilegedWorkspaceBindingSealResult.Success -> sealed.envelope
            is PrivilegedWorkspaceBindingSealResult.Failure -> {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
            }
        }
    } finally {
        locatorBytes.fill(0)
        recoveryLocator.clear()
    }
    val workspace = try {
        Workspace(
            id = value.descriptor.id,
            displayName = presentation.displayName(
                Workspace(
                    id = value.descriptor.id,
                    displayName = displayName,
                    backendType = WorkspaceBackendType.PRIVILEGED,
                    rootReference = "authority:${authority.name}",
                    scope = WorkspaceScope.SELECTED_DIRECTORY,
                ),
                ordinal = workspaceRepository.list().count { it.backendType == WorkspaceBackendType.PRIVILEGED } + 1,
            ),
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:${authority.name}",
            readable = value.descriptor.readable,
            writable = value.descriptor.writable,
            quotaBytes = value.descriptor.quotaBytes,
            maxFileBytes = value.descriptor.maxFileBytes,
            enabled = true,
            revision = (existing?.revision ?: 0L) + 1L,
            createdAt = existing?.createdAt.orEmpty(),
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        )
    } catch (_: IllegalArgumentException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
    }
    val previousBinding = privilegedWorkspaceBindingRepository.get(workspaceId)
    val binding = PrivilegedWorkspaceBinding(
        workspaceId = workspaceId,
        authority = authority,
        encryptedLocator = envelope.encryptedLocator,
        locatorNonce = envelope.locatorNonce,
        locatorVersion = locatorVersion,
        keyVersion = RuntimeWorkspaceBindingFormat.KEY_VERSION,
        aadAppInstanceId = appInstanceId,
        scope = WorkspaceScope.SELECTED_DIRECTORY,
        status = PrivilegedWorkspaceBindingStatus.ACTIVE,
        revision = (previousBinding?.revision ?: 0L) + 1L,
        createdAt = previousBinding?.createdAt.orEmpty(),
    )
    val selection = try {
        db.transaction {
            workspaceRepository.save(workspace)
            privilegedWorkspaceBindingRepository.save(binding)
            grantStore.persistPickerSelection(workspace, pickerTarget, plan, grant) { value.backend }
        }
    } catch (failure: RuntimeWorkspaceAccessException) {
        return workspaceAccessFailure(failure.accessCode)
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }
    try {
        workspaceRegistry.registerOrReplace(workspace, value.backend)
    } catch (_: RuntimeException) {
        runCatching {
            privilegedWorkspaceBindingRepository.updateStatus(
                workspaceId,
                binding.revision,
                PrivilegedWorkspaceBindingStatus.UNAVAILABLE,
            )
        }
        return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
    }
    diagnostics.recordPrivilegedWorkspaceBindingPersisted(
        PrivilegedWorkspaceBindingPersistedRecord(
            workspaceId = workspaceId,
            authority = authority.toDiagnostic(),
            bindingRevision = binding.revision.toDiagnosticGeneration(),
            grantGeneration = (selection as? RuntimePickerSelection.Committed)?.grants
                ?.maxOfOrNull { it.revision }?.toDiagnosticGeneration() ?: 0,
        ),
    )
    val privilegedKind = if (authority == Authority.WIRED_ADB) {
        WorkspaceUiKind.PRIVILEGED_WIRED
    } else {
        WorkspaceUiKind.PRIVILEGED_SHIZUKU
    }
    presentation.remember(
        workspaceId = workspaceId,
        kind = privilegedKind,
        title = privilegedUiTitle(displayName) ?: displayName.substringAfterLast('/').ifBlank { displayName },
    )
    return finishPickerSelection(
        workspace, pickerTarget, selection, attachedName = workspace.displayName, attachedAuthority = authority,
    )
}


private fun Long.toDiagnosticGeneration(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

internal object RuntimeWorkspaceBindingFormat {
    const val LOCATOR_VERSION = 1
    const val KEY_VERSION = 1
}
