// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.nio.file.Path
import java.time.Instant
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.AuthorityPolicyConflictException
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.ConversationWorkspaceBindingRepository
import runtime.mobileagent.data.AgentWorkspaceDefaultRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.workspace.WorkspaceGrantSet
import runtime.mobileagent.domain.WorkspaceIntentPlan
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.domain.plan
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.tooling.WorkspacePathPolicy

internal class RuntimeWorkspaceAccessException(
    val accessCode: WorkspaceAccessErrorCode,
) : IllegalStateException()

internal sealed interface RuntimePickerSelection {
    data class NewThread(val agentId: String, val binding: ConversationWorkspaceBinding) : RuntimePickerSelection
    data class Committed(
        val grants: List<CapabilityGrant>,
        val conversation: ConversationWorkspaceBinding? = null,
        val agentDefault: AgentWorkspaceDefault? = null,
        val previousAgentDefaultWorkspaceId: String? = null,
    ) : RuntimePickerSelection
}


/** Canonical grant reconciliation; callers retain their enclosing writer transaction. */
internal class RuntimeWorkspaceGrantStore(
    private val db: runtime.mobileagent.data.SqlConnection,
    private val workspaceRepository: WorkspaceRepository,
    private val capabilityGrantRepository: CapabilityGrantRepository,
    private val authorityPolicyRepository: AuthorityPolicyRepository,
    private val conversationWorkspaceBindingRepository: ConversationWorkspaceBindingRepository,
    private val agentWorkspaceDefaultRepository: AgentWorkspaceDefaultRepository,
) {
    /**
     * Decide under the same writer transaction that grants and binds. A competing selection
     * may already have bound this Thread. Its losing candidate can enter the workspace library,
     * but must wait for confirmation before granting anything or requiring a live backend.
     */
    fun persistPickerSelection(
        workspace: Workspace,
        target: WorkspacePickerTarget?,
        plan: WorkspaceIntentPlan,
        grant: WorkspaceAccessGrantTarget?,
        backend: () -> WorkspaceBackend,
    ): RuntimePickerSelection {
        val threadId = if (plan.bindThread) target?.threadId else null
        val binding = threadId?.let(conversationWorkspaceBindingRepository::get)
        if (binding != null && binding.workspaceId != workspace.id) {
            return RuntimePickerSelection.NewThread(requireNotNull(target?.agentId), binding)
        }
        val registered = backend()
        val grants = grant?.let {
            persistWorkspaceGrantBundle(workspace, registered, it, preserveActiveWholeDirectoryCapabilities = plan.bindThread)
        }.orEmpty()
        val setDefault = plan.setAgentDefault && !plan.deferred
        val agentId = target?.agentId
        if (agentId == null) {
            require(!plan.bindThread && !setDefault) { "A workspace binding target requires an Agent" }
            return RuntimePickerSelection.Committed(grants)
        }
        require(grants.any { it.agentId == agentId && it.workspaceId == workspace.id }) {
            "Workspace binding requires an active Agent grant"
        }
        val conversation = threadId?.let { conversationWorkspaceBindingRepository.bind(it, workspace.id) }
        require(!setDefault || workspace.scope == WorkspaceScope.SELECTED_DIRECTORY) {
            "Full-device workspace cannot be an Agent default"
        }
        val previousDefault = if (setDefault) agentWorkspaceDefaultRepository.get(agentId) else null
        val agentDefault = if (setDefault) {
            agentWorkspaceDefaultRepository.set(agentId, workspace.id, expectedRevision = previousDefault?.revision)
        } else null
        return RuntimePickerSelection.Committed(
            grants = grants,
            conversation = conversation,
            agentDefault = agentDefault,
            previousAgentDefaultWorkspaceId = previousDefault?.workspaceId,
        )
    }

    fun persistWorkspaceGrantBundle(
        workspace: Workspace,
        backend: runtime.mobileagent.skills.tooling.WorkspaceBackend,
        target: WorkspaceAccessGrantTarget,
        preserveActiveWholeDirectoryCapabilities: Boolean = false,
    ): List<CapabilityGrant> {
        var requestedCapabilities = target.capabilities.ifEmpty {
            if (workspace.scope == WorkspaceScope.FULL_DEVICE_FILES) {
                canonicalFullDeviceWorkspaceGrantCapabilities(backend.capabilities)
            } else {
                canonicalDefaultWorkspaceGrantCapabilities(backend.capabilities)
            }
        }
        if (requestedCapabilities.isEmpty()) throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        if (requestedCapabilities.any { it !in backend.capabilities }) {
            throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        if (target.lifetime != runtime.mobileagent.domain.GrantLifetime.PERSISTENT) {
            // Workspace access is an Agent-level setting shared by all of its
            // sessions. Scoped or one-shot overlays are intentionally not part
            // of this facade; they would create a second session truth.
            throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        val normalizedPath = try {
            WorkspacePathPolicy.normalize(target.pathScope, allowRoot = true)
                .takeIf { it.isNotEmpty() }
        } catch (_: RuntimeException) {
            throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.INVALID_REQUEST)
        }
        val policyVersion = authorityPolicyRepository.getPolicy().policyVersion
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val bundle = WorkspaceGrantSet(
            target.agentId, workspace.id, capabilityGrantRepository.list(target.agentId, workspace.id), normalizedPath,
        )
        val active = bundle.activeAt(policyVersion, now)
        // Picking/reattaching a directory is not a request to downgrade the
        // Agent's access preset. An empty target seeds read-only authority for
        // a new workspace and preserves existing consent for the same scope.
        // An explicit capability bundle still reconciles exactly as requested.
        val preserveExistingAuthorization = target.capabilities.isEmpty() &&
            (preserveActiveWholeDirectoryCapabilities || normalizedPath == null)
        if (preserveExistingAuthorization) {
            if (normalizedPath != null) throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.INVALID_REQUEST)
            requestedCapabilities = requestedCapabilities + active.map { it.capability }.intersect(backend.capabilities)
        }
        if (requestedCapabilities.any { it !in backend.capabilities }) {
            throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        }
        // Reconcile only this bundle's scope. Path-scoped, one-shot, task,
        // session and Skill grants are independent of an automatic default.
        bundle.rows.filter { grant ->
            // A temporarily unsupported operation stays consented until the user changes the preset.
            grant.policyVersion != policyVersion || (!preserveExistingAuthorization && grant.capability !in requestedCapabilities)
        }.forEach { grant -> capabilityGrantRepository.revoke(grant.grantId, grant.revision) }
        return requestedCapabilities.sortedBy { it.value }.map { capability ->
            active.firstOrNull { it.capability == capability }
                ?: capabilityGrantRepository.save(bundle.newGrant(capability, policyVersion))
        }
    }

    fun persistWorkspaceAndGrants(
        workspace: Workspace,
        backend: runtime.mobileagent.skills.tooling.WorkspaceBackend,
        target: WorkspaceAccessGrantTarget?,
    ): List<CapabilityGrant> = try {
        db.transaction {
            workspaceRepository.save(workspace)
            target?.let { persistWorkspaceGrantBundle(workspace, backend, it) }.orEmpty()
        }
    } catch (failure: RuntimeWorkspaceAccessException) {
        throw failure
    } catch (_: AuthorityPolicyConflictException) {
        throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.CONFLICT)
    } catch (_: RuntimeException) {
        throw RuntimeWorkspaceAccessException(WorkspaceAccessErrorCode.PERSISTENCE_FAILED)
    }

}
