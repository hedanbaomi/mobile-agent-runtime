// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.content.Context
import android.net.Uri
import java.time.Instant
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AuthorityPolicyConflictException
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.FullDeviceFilesGrantRepository
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.SafWorkspaceGrantRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.DiagnosticGrantScope
import runtime.mobileagent.diagnostics.WorkspaceGrantChangedRecord
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.SafWorkspaceGrant
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.workspace.WorkspaceGrantSet
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.workspace.WorkspaceUiKind
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.DangerousBuildPolicy
import runtime.mobileagent.tooling.DangerousModeManager
import runtime.mobileagent.tooling.WorkspaceRegistry
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceResult

/** Presentation-only callbacks cannot grant, rebind, reopen or choose an Authority. */
internal interface RuntimeWorkspacePresentation {
    fun item(workspace: Workspace, agentId: String?): WorkspaceAccessItem
    fun displayName(workspace: Workspace, ordinal: Int): String
    fun authority(workspace: Workspace): Authority?
    fun recordBinding(binding: ConversationWorkspaceBinding, agentId: String)
    fun recordDefault(previous: String?, persisted: AgentWorkspaceDefault)
    fun remember(workspaceId: String, kind: WorkspaceUiKind, title: String)
}

/** Platform directory acquisition, durable authorization and revocation have one owner. */
internal class RuntimeDirectoryAccess(
    internal val appContext: Context,
    internal val db: runtime.mobileagent.data.SqlConnection,
    internal val workspaceRepository: WorkspaceRepository,
    internal val capabilityGrantRepository: CapabilityGrantRepository,
    internal val authorityPolicyRepository: AuthorityPolicyRepository,
    internal val privilegedWorkspaceBindingRepository: PrivilegedWorkspaceBindingRepository,
    internal val safWorkspaceGrantRepository: SafWorkspaceGrantRepository,
    internal val fullDeviceFilesGrantRepository: FullDeviceFilesGrantRepository,
    internal val workspaceRegistry: WorkspaceRegistry,
    internal val bindingCipher: runtime.mobileagent.security.PrivilegedWorkspaceBindingCipher,
    internal val appInstanceId: String,
    internal val wiredAuthority: WiredAdbAuthorityPort,
    internal val buildPolicy: DangerousBuildPolicy,
    internal val dangerousModeManager: DangerousModeManager,
    internal val diagnostics: AndroidDiagnosticLogger,
    internal val grantStore: RuntimeWorkspaceGrantStore,
    internal val authorityProviderFor: (Authority) -> PrivilegedWorkspaceProvider?,
    internal val privilegedFailure: (Authority) -> ToolErrorCode,
    internal val isRootWorkspace: (String) -> Boolean,
    internal val presentation: RuntimeWorkspacePresentation,
) {
    fun finishPickerSelection(
        workspace: Workspace,
        target: WorkspacePickerTarget?,
        selection: RuntimePickerSelection,
        attachedName: String? = null,
        attachedAuthority: Authority? = null,
    ): WorkspaceAccessResult {
        val grants = when (selection) {
            is RuntimePickerSelection.NewThread -> {
                val now = Instant.ofEpochMilli(System.currentTimeMillis())
                val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
                capabilityGrantRepository.forAgent(selection.agentId, includeRevoked = false).filter {
                    it.workspaceId == workspace.id && it.policyVersion == policyVersion && it.isActiveFor(now, null, null)
                }
            }
            is RuntimePickerSelection.Committed -> {
                selection.conversation?.let { binding ->
                    presentation.recordBinding(binding, target?.agentId.orEmpty())
                }
                selection.agentDefault?.let { persisted ->
                    presentation.recordDefault(selection.previousAgentDefaultWorkspaceId, persisted)
                }
                selection.grants
            }
        }
        val committed = selection is RuntimePickerSelection.Committed
        val item = committedWorkspaceAccessItem(
            workspace = workspace,
            displayName = attachedName?.takeIf { committed } ?: presentation.displayName(workspace, 1),
            status = WorkspaceAccessStatus.ACTIVE,
            authority = attachedAuthority?.takeIf { committed } ?: presentation.authority(workspace),
            activeGrants = grants,
            fullDeviceConfirmationPresent = true,
        )
        val summaries = grants.map { it.toWorkspaceAccessSummary() }
        return when (selection) {
            is RuntimePickerSelection.Committed -> WorkspaceAccessResult.Success(item, summaries)
            is RuntimePickerSelection.NewThread -> WorkspaceAccessResult.NewThreadRequired(
                agentId = selection.agentId,
                currentThreadId = selection.binding.sessionId,
                currentWorkspaceId = selection.binding.workspaceId,
                requestedWorkspaceId = workspace.id,
                workspace = item,
                grants = summaries,
                authorizationState = if (grants.isEmpty()) {
                    NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT
                } else {
                    NewThreadAuthorizationState.ALREADY_GRANTED
                },
            )
        }
    }

    fun grantWorkspaceInternal(
        workspaceId: String,
        target: WorkspaceAccessGrantTarget,
    ): WorkspaceAccessResult {
        if (isRootWorkspace(workspaceId)) return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        val workspace = workspaceRepository.get(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (!workspace.enabled) return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        val registered = workspaceRegistry.registered(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
            if (safWorkspaceGrantRepository.get(workspaceId)?.activePersistedUri(appContext) == null) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
            }
        } else if (workspace.scope == WorkspaceScope.FULL_DEVICE_FILES) {
            if (fullDeviceFilesGrantRepository.load(workspaceId) == null) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
            }
            val authority = workspace.rootReference.removePrefix("authority:")
                .let { runCatching { Authority.valueOf(it) }.getOrNull() }
                ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
            if (authorityProviderFor(authority) == null) {
                return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
            }
        }
        val grants = try {
            grantStore.persistWorkspaceAndGrants(workspace, registered.backend, target)
        } catch (failure: RuntimeWorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        }
        return WorkspaceAccessResult.Success(
            presentation.item(workspace, agentId = target.agentId),
            grants.map { it.toWorkspaceAccessSummary() },
        )
    }

    fun renewExistingWorkspaceGrantsInternal(agentId: String, workspaceId: String): WorkspaceAccessResult {
        if (isRootWorkspace(workspaceId)) return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        val workspace = workspaceRepository.get(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (workspace.scope != WorkspaceScope.SELECTED_DIRECTORY || !workspace.enabled) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        val registered = workspaceRegistry.registered(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        // System permission/provider checks can block. Complete them before the DB write lock.
        if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
            if (safWorkspaceGrantRepository.get(workspaceId)?.activePersistedUri(appContext) == null) {
                return workspaceAccessFailure(WorkspaceAccessErrorCode.URI_PERMISSION_REQUIRED)
            }
        } else if (workspace.backendType == WorkspaceBackendType.PRIVILEGED) {
            val authority = presentation.authority(workspace)
                ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
            if (authorityProviderFor(authority) == null) {
                return workspaceAccessFailure(privilegedFailure(authority).toWorkspaceAccessCode())
            }
        }
        val renewed = try {
            db.transaction {
                val current = workspaceRepository.get(workspaceId)
                    ?: throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
                if (current != workspace || workspaceRegistry.registered(workspaceId)?.backend !== registered.backend) {
                    throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.CONFLICT)
                }
                val policy = authorityPolicyRepository.getPolicy().policyVersion
                val bundle = WorkspaceGrantSet(
                    agentId, workspaceId, capabilityGrantRepository.list(agentId, workspaceId, includeRevoked = true),
                )
                val sources = bundle.renewableGrants(policy, Instant.now())
                if (sources.isEmpty() || sources.any { it.capability !in registered.backend.capabilities }) {
                    throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
                }
                sources.map { source ->
                    if (source.policyVersion == policy) return@map source
                    bundle.rows.filter { !it.revoked && it.capability == source.capability && it.policyVersion != policy }
                        .forEach { capabilityGrantRepository.revoke(it.grantId, it.revision) }
                    capabilityGrantRepository.save(
                        source.copy(grantId = EntityId.random().value, policyVersion = policy, createdAt = Utc.nowIso(), revision = 1L),
                    )
                }
            }
        } catch (failure: RuntimeWorkspaceAccessException) {
            return workspaceAccessFailure(failure.accessCode)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        return WorkspaceAccessResult.Success(
            presentation.item(workspace, agentId = agentId),
            renewed.map { it.toWorkspaceAccessSummary() },
        )
    }

    fun revokeGrantInternal(grantId: String, expectedRevision: Long): WorkspaceAccessResult {
        val current = capabilityGrantRepository.get(grantId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (current.revision != expectedRevision) return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        val workspace = current.workspaceId?.let(workspaceRepository::get)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        val revoked = try {
            capabilityGrantRepository.revoke(grantId, expectedRevision)
            capabilityGrantRepository.get(grantId)
        } catch (_: AuthorityPolicyConflictException) {
            null
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        } ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        return WorkspaceAccessResult.Success(
            presentation.item(workspace, agentId = revoked.agentId),
            listOf(revoked.toWorkspaceAccessSummary()),
        )
    }

    fun revokeWorkspaceInternal(workspaceId: String): WorkspaceAccessResult {
        val workspace = workspaceRepository.get(workspaceId)
            ?: return workspaceAccessFailure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        val activeGrants = capabilityGrantRepository.forWorkspace(workspaceId, includeRevoked = false)
        try {
            db.transaction {
                activeGrants.forEach { grant ->
                    capabilityGrantRepository.revoke(grant.grantId, grant.revision)
                }
                if (workspace.backendType == WorkspaceBackendType.SAF_TREE) {
                    safWorkspaceGrantRepository.markRevoked(workspaceId)
                }
                privilegedWorkspaceBindingRepository.get(workspaceId)?.let { binding ->
                    if (binding.status != PrivilegedWorkspaceBindingStatus.REVOKED) {
                        privilegedWorkspaceBindingRepository.updateStatus(
                            workspaceId,
                            binding.revision,
                            PrivilegedWorkspaceBindingStatus.REVOKED,
                        )
                    }
                }
                workspaceRepository.save(
                    workspace.copy(
                        enabled = false,
                        readable = false,
                        writable = false,
                        revision = workspace.revision + 1L,
                    ),
                )
            }
        } catch (_: AuthorityPolicyConflictException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.CONFLICT)
        } catch (_: RuntimeException) {
            return workspaceAccessFailure(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
        }
        if (workspace.scope == WorkspaceScope.FULL_DEVICE_FILES) {
            val fullDeviceGrant = fullDeviceFilesGrantRepository.load(workspaceId)
            if (fullDeviceGrant != null) {
                when (val revoked = fullDeviceFilesGrantRepository.revoke(workspaceId, fullDeviceGrant.revision)) {
                    is WorkspaceResult.Success -> Unit
                    is WorkspaceResult.Failure -> {
                        // The provider-side grant is the high-risk gate. If
                        // its durable revoke cannot be confirmed, remove the
                        // in-memory backend before returning so a stale
                        // registry entry cannot dispatch against the scope.
                        workspaceRegistry.unregister(workspaceId)
                        return workspaceAccessFailure(revoked.error.code.toWorkspaceAccessCode())
                    }
                }
            }
        }
        var platformReleaseFailed = false
        val saf = safWorkspaceGrantRepository.get(workspaceId)
        val safUri = saf?.uriReference
        val sharedSafUriStillHeld = safUri != null && safWorkspaceGrantRepository.list(includeRevoked = false).any { other ->
            other.workspaceId != workspaceId &&
                other.status == SafGrantStatus.ACTIVE &&
                other.uriReference == safUri
        }
        if (saf != null && !sharedSafUriStillHeld) {
            platformReleaseFailed = runCatching {
                val uri = Uri.parse(saf.uriReference)
                val actual = appContext.contentResolver.persistedUriPermissions
                    .firstOrNull { it.uri == uri }
                    ?.let { safPersistableFlags(it.isReadPermission, it.isWritePermission) }
                    ?: saf.persistedFlags
                if (actual != 0) appContext.contentResolver.releasePersistableUriPermission(uri, actual)
            }.isFailure
        }
        workspaceRegistry.unregister(workspaceId)
        diagnostics.recordWorkspaceGrantChanged(
            WorkspaceGrantChangedRecord(workspaceId, DiagnosticGrantScope.NONE, false),
        )
        if (platformReleaseFailed) return workspaceAccessFailure(WorkspaceAccessErrorCode.UNKNOWN_OUTCOME)
        val disabled = workspaceRepository.get(workspaceId) ?: workspace.copy(enabled = false)
        return WorkspaceAccessResult.Success(presentation.item(disabled, agentId = null), emptyList())
    }

}

internal fun ToolErrorCode.toWorkspaceAccessCode(): WorkspaceAccessErrorCode = when (this) {
    ToolErrorCode.WORKSPACE_NOT_FOUND -> WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND
    ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED -> WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED
    ToolErrorCode.AUTHORITY_NOT_GRANTED,
    ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
    ToolErrorCode.SHELL_CAPABILITY_DENIED,
        -> WorkspaceAccessErrorCode.CAPABILITY_DENIED
    ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
    ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE,
    ToolErrorCode.BRIDGE_NOT_PAIRED,
    ToolErrorCode.BRIDGE_DISCONNECTED,
    ToolErrorCode.ADB_DEVICE_UNAUTHORIZED,
    ToolErrorCode.ADB_DEVICE_OFFLINE,
    ToolErrorCode.ADB_DEVICE_DISCONNECTED,
        -> WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE
    ToolErrorCode.CONFLICT -> WorkspaceAccessErrorCode.CONFLICT
    ToolErrorCode.INVALID_REQUEST,
    ToolErrorCode.PATH_OUT_OF_SCOPE,
    ToolErrorCode.ROOT_OPERATION_FORBIDDEN,
        -> WorkspaceAccessErrorCode.INVALID_REQUEST
    ToolErrorCode.AUDIT_UNAVAILABLE,
    ToolErrorCode.AUDIT_FUSE_OPEN,
    ToolErrorCode.IO_ERROR,
        -> WorkspaceAccessErrorCode.PERSISTENCE_FAILED
    ToolErrorCode.UNKNOWN_OUTCOME,
    ToolErrorCode.TIMEOUT,
    ToolErrorCode.SHELL_TIMED_OUT,
    ToolErrorCode.SHELL_CANCELLED,
        -> WorkspaceAccessErrorCode.UNKNOWN_OUTCOME
    else -> WorkspaceAccessErrorCode.UNSUPPORTED
}

internal fun <T> workspaceFailure(code: ToolErrorCode): WorkspaceResult<T> =
    WorkspaceResult.Failure(ToolError(code))

internal fun workspaceAccessFailure(code: WorkspaceAccessErrorCode): WorkspaceAccessResult =
    WorkspaceAccessResult.Failure(code)

internal fun CapabilityGrant.toWorkspaceAccessSummary(): WorkspaceAccessGrantSummary =
    WorkspaceAccessGrantSummary(
        grantId = grantId,
        capability = capability,
        lifetime = lifetime,
        revision = revision,
    )

internal fun SafWorkspaceGrant.activePersistedUri(appContext: Context): Uri? {
    if (status != SafGrantStatus.ACTIVE || (!readGranted && !writeGranted)) return null
    val uri = runCatching { Uri.parse(uriReference) }.getOrNull() ?: return null
    val held = appContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return null
    return uri.takeIf { held.isReadPermission && (!writeGranted || held.isWritePermission) }
}
