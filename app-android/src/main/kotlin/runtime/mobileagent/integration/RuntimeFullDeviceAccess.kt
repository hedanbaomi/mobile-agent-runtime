// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import runtime.mobileagent.data.AuthorityPolicyConflictException
import runtime.mobileagent.data.FullDeviceFilesGrantRepository
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.resident.ResidentAdbAuthorityBridge
import runtime.mobileagent.security.PrivilegedWorkspaceBindingAad
import runtime.mobileagent.security.PrivilegedWorkspaceBindingSealResult
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrant
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.tooling.WorkspaceRegistry

internal class FullDeviceAccessPorts(
    val db: SqlConnection,
    val workspaceRepository: WorkspaceRepository,
    val privilegedWorkspaceBindingRepository: PrivilegedWorkspaceBindingRepository,
    val fullDeviceFilesGrantRepository: FullDeviceFilesGrantRepository,
    val workspaceRegistry: WorkspaceRegistry,
    val grantStore: RuntimeWorkspaceGrantStore,
    val appInstanceId: String,
    val permitsDangerousMode: Boolean,
    val requiresRecoveryLocator: Boolean,
    val authorityProviderFor: (Authority) -> PrivilegedWorkspaceProvider?,
    val privilegedFailure: (Authority) -> ToolErrorCode,
    val sealLocator: (ByteArray, PrivilegedWorkspaceBindingAad) -> PrivilegedWorkspaceBindingSealResult,
    val present: (Workspace, String?) -> WorkspaceAccessItem,
)

internal suspend fun RuntimeDirectoryAccess.openFullDeviceFilesInternal(
    authority: Authority,
    request: FullDeviceFilesRequest,
    grant: WorkspaceAccessGrantTarget?,
): WorkspaceAccessResult = openFullDeviceFiles(
    ports = FullDeviceAccessPorts(
        db = db,
        workspaceRepository = workspaceRepository,
        privilegedWorkspaceBindingRepository = privilegedWorkspaceBindingRepository,
        fullDeviceFilesGrantRepository = fullDeviceFilesGrantRepository,
        workspaceRegistry = workspaceRegistry,
        grantStore = grantStore,
        appInstanceId = appInstanceId,
        permitsDangerousMode = buildPolicy.permitsDangerousMode() &&
            dangerousModeManager.policy() != DangerousMode.DISABLED,
        requiresRecoveryLocator = authority != Authority.WIRED_ADB || wiredAuthority is ResidentAdbAuthorityBridge,
        authorityProviderFor = authorityProviderFor,
        privilegedFailure = privilegedFailure,
        sealLocator = bindingCipher::seal,
        present = { workspace, agentId -> presentation.item(workspace, agentId = agentId) },
    ),
    authority = authority,
    request = request,
    grant = grant,
)

internal suspend fun openFullDeviceFiles(
    ports: FullDeviceAccessPorts,
    authority: Authority,
    request: FullDeviceFilesRequest,
    grant: WorkspaceAccessGrantTarget?,
): WorkspaceAccessResult {
    if (!ports.permitsDangerousMode || !request.confirmedByUser) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
    }
    val provider = ports.authorityProviderFor(authority)
        ?: return workspaceAccessFailure(ports.privilegedFailure(authority).toWorkspaceAccessCode())
    if (!provider.supportsFullDeviceFiles) return workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)
    val expectedRootReference = "authority:${authority.name}"
    try {
        Workspace(
            id = request.workspaceId,
            displayName = "设备文件区",
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = expectedRootReference,
            scope = WorkspaceScope.FULL_DEVICE_FILES,
        )
    } catch (_: IllegalArgumentException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
    }
    val preExisting = try {
        ports.workspaceRepository.get(request.workspaceId)
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }
    if (preExisting != null && (
        preExisting.backendType != WorkspaceBackendType.PRIVILEGED ||
            preExisting.scope != WorkspaceScope.FULL_DEVICE_FILES ||
            preExisting.rootReference != expectedRootReference
        )
    ) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
    }

    // The provider must observe a durable, monotonic confirmation before
    // it opens the device-root handle. A new confirmation starts at one;
    // a revoked tombstone can only be re-enabled with its next revision.
    // This keeps a stale toggle or replayed request from reactivating an
    // older full-device grant.
    val existingFullDeviceGrant = try {
        ports.fullDeviceFilesGrantRepository.load(request.workspaceId)
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }
    val currentFullDeviceRevision = try {
        ports.fullDeviceFilesGrantRepository.currentRevision(request.workspaceId)
    } catch (_: RuntimeException) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }
    val expectedFullDeviceRevision = when {
        existingFullDeviceGrant != null -> existingFullDeviceGrant.revision
        currentFullDeviceRevision != null -> currentFullDeviceRevision + 1L
        else -> 1L
    }
    if (request.grantRevision != expectedFullDeviceRevision) {
        return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
    }
    val fullDeviceGrant = FullDeviceFilesGrant(
        workspaceId = request.workspaceId,
        revision = request.grantRevision,
        confirmedAtEpochMs = System.currentTimeMillis(),
    )
    when (val saved = ports.fullDeviceFilesGrantRepository.save(fullDeviceGrant)) {
        is WorkspaceResult.Failure -> return workspaceAccessFailure(saved.error.code.toWorkspaceAccessCode())
        is WorkspaceResult.Success -> Unit
    }
    val newlyCreatedFullDeviceGrant = existingFullDeviceGrant == null

    fun rollbackNewFullDeviceGrant(): WorkspaceAccessResult.Failure? {
        if (!newlyCreatedFullDeviceGrant) return null
        return when (val rollback = ports.fullDeviceFilesGrantRepository.revoke(request.workspaceId, request.grantRevision)) {
            is WorkspaceResult.Success -> null
            is WorkspaceResult.Failure -> WorkspaceAccessResult.Failure(
                rollback.error.code.toWorkspaceAccessCode().takeIf { it != WorkspaceAccessErrorCode.UNSUPPORTED }
                    ?: WorkspaceAccessErrorCode.UNKNOWN_OUTCOME,
            )
        }
    }

    val attachment = try {
        provider.openFullDeviceFiles(request)
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { runCatching { rollbackNewFullDeviceGrant() } }
        throw cancelled
    } catch (_: RuntimeException) {
        rollbackNewFullDeviceGrant()?.let { return it }
        return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
    }
    val value = when (attachment) {
        is WorkspaceResult.Failure -> {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(attachment.error.code.toWorkspaceAccessCode())
        }
        is WorkspaceResult.Success -> attachment.value
    }

    val locatorVersion = RuntimeWorkspaceBindingFormat.LOCATOR_VERSION
    val (workspace, locatorEnvelope) = try {
        if (value.descriptor.id != request.workspaceId) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val existing = try {
            ports.workspaceRepository.get(request.workspaceId)
        } catch (_: RuntimeException) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        if (existing != null && (
            existing.backendType != WorkspaceBackendType.PRIVILEGED ||
                existing.scope != WorkspaceScope.FULL_DEVICE_FILES ||
                existing.rootReference != expectedRootReference
            )
        ) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        }
        val candidate = try {
            Workspace(
                id = value.descriptor.id,
                displayName = "设备文件区",
                backendType = WorkspaceBackendType.PRIVILEGED,
                rootReference = expectedRootReference,
                readable = value.descriptor.readable,
                writable = value.descriptor.writable,
                quotaBytes = value.descriptor.quotaBytes,
                maxFileBytes = value.descriptor.maxFileBytes,
                enabled = true,
                revision = (existing?.revision ?: 0L) + 1L,
                createdAt = existing?.createdAt.orEmpty(),
                scope = WorkspaceScope.FULL_DEVICE_FILES,
            )
        } catch (_: IllegalArgumentException) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        // Persist the sealed recovery locator exactly like a selected-directory
        // attachment — but only when the provider supplies one. Shizuku and
        // resident attachments must carry it: the remote file-service handle dies
        // with its process and the sealed locator is the only material a later
        // reattach can re-open. Legacy desktop Wired-ADB full-device attachments
        // carry no locator by contract.
        val recoveryLocator = value.recoveryLocator
        if (recoveryLocator == null && ports.requiresRecoveryLocator) {
            rollbackNewFullDeviceGrant()?.let { return it }
            return workspaceAccessFailure(WorkspaceAccessErrorCode.UNSUPPORTED)
        }
        val sealedEnvelope = recoveryLocator?.let { locator ->
            val bindingAad = try {
                PrivilegedWorkspaceBindingAad(
                    appInstanceId = ports.appInstanceId,
                    workspaceId = candidate.id,
                    authority = authority,
                    locatorVersion = locatorVersion,
                )
            } catch (_: IllegalArgumentException) {
                rollbackNewFullDeviceGrant()?.let { return it }
                return workspaceAccessFailure(WorkspaceAccessErrorCode.INVALID_REQUEST)
            }
            val locatorBytes = runCatching { locator.copyBytes() }.getOrNull()
            if (locatorBytes == null) {
                rollbackNewFullDeviceGrant()?.let { return it }
                return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
            }
            try {
                val sealed = try {
                    ports.sealLocator(locatorBytes, bindingAad)
                } catch (_: RuntimeException) {
                    rollbackNewFullDeviceGrant()?.let { return it }
                    return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
                }
                when (sealed) {
                    is PrivilegedWorkspaceBindingSealResult.Success -> sealed.envelope
                    is PrivilegedWorkspaceBindingSealResult.Failure -> {
                        rollbackNewFullDeviceGrant()?.let { return it }
                        return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
                    }
                }
            } finally {
                locatorBytes.fill(0)
            }
        }
        candidate to sealedEnvelope
    } finally {
        value.recoveryLocator?.clear()
    }

    val grants = try {
        ports.db.transaction {
            val previousBinding = ports.privilegedWorkspaceBindingRepository.get(workspace.id)
            if (previousBinding != null && (
                previousBinding.authority != authority ||
                    previousBinding.scope != WorkspaceScope.FULL_DEVICE_FILES ||
                    (locatorEnvelope == null && previousBinding.status != PrivilegedWorkspaceBindingStatus.REVOKED)
                )
            ) {
                throw AuthorityPolicyConflictException("Full-device workspace binding authority or locator contract conflicts")
            }
            val binding = locatorEnvelope?.let { envelope ->
                PrivilegedWorkspaceBinding(
                    workspaceId = workspace.id,
                    authority = authority,
                    encryptedLocator = envelope.encryptedLocator,
                    locatorNonce = envelope.locatorNonce,
                    locatorVersion = locatorVersion,
                    keyVersion = RuntimeWorkspaceBindingFormat.KEY_VERSION,
                    aadAppInstanceId = ports.appInstanceId,
                    scope = WorkspaceScope.FULL_DEVICE_FILES,
                    status = PrivilegedWorkspaceBindingStatus.ACTIVE,
                    revision = (previousBinding?.revision ?: 0L) + 1L,
                    createdAt = previousBinding?.createdAt.orEmpty(),
                )
            }
            ports.workspaceRepository.save(workspace)
            binding?.let { ports.privilegedWorkspaceBindingRepository.save(it) }
            grant?.let { ports.grantStore.persistWorkspaceGrantBundle(workspace, value.backend, it) }.orEmpty()
        }
    } catch (failure: RuntimeWorkspaceAccessException) {
        rollbackNewFullDeviceGrant()?.let { return it }
        return workspaceAccessFailure(failure.accessCode)
    } catch (_: AuthorityPolicyConflictException) {
        rollbackNewFullDeviceGrant()?.let { return it }
        return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
    } catch (_: RuntimeException) {
        rollbackNewFullDeviceGrant()?.let { return it }
        return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }
    try {
        ports.workspaceRegistry.registerOrReplace(workspace, value.backend)
    } catch (_: RuntimeException) {
        rollbackNewFullDeviceGrant()?.let { return it }
        runCatching {
            ports.workspaceRepository.save(
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
    return WorkspaceAccessResult.Success(
        ports.present(workspace, grant?.agentId),
        grants.map { it.toWorkspaceAccessSummary() },
    )
}
