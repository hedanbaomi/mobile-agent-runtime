// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.RootGrantRepository
import runtime.mobileagent.data.RootSnapshotGrantRepository
import runtime.mobileagent.data.RootWorkspaceRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootGrantBinding
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.domain.WorkspaceBackendType

/** A capability the user selected explicitly. This helper never infers or auto-grants one. */
data class RootGrantRequest(
    val capability: CapabilityId,
    val pathScope: String? = null,
    val lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
    val taskId: String? = null,
    val sessionId: String? = null,
    val expiresAt: String? = null,
)

/** Closed refusal set for Root grant preparation and sealed snapshot admission. */
enum class RootGrantRefusal {
    INVALID_AGENT,
    NO_CAPABILITIES,
    TOO_MANY_CAPABILITIES,
    UNSUPPORTED_CAPABILITY,
    DUPLICATE_CAPABILITY,
    INVALID_LIFETIME_OWNER,
    WORKSPACE_NOT_ROOT,
    ADB_WORKSPACE_NOT_PROMOTABLE,
    WORKSPACE_NOT_WRITABLE,
    GRANT_REJECTED,
    GRANT_READBACK_INVALID,
    INVALID_SNAPSHOT_ID,
    SNAPSHOT_EXISTS,
    GRANT_NOT_ROOT,
    GRANT_SET_INVALID,
    SNAPSHOT_CALLBACK_INVALID,
    SNAPSHOT_REJECTED,
    SEAL_READBACK_FAILED,
    WORKSPACE_GENERATION_DRIFT,
}

sealed interface RootGrantPreparation {
    data class Prepared(
        val agentId: String,
        val workspaceId: String,
        val workspaceGenerationId: String,
        val bindings: List<RootGrantBinding>,
    ) : RootGrantPreparation

    data class Refused(val reason: RootGrantRefusal, val detail: String? = null) : RootGrantPreparation
}

/** Sealed snapshot admission: the only state in which these Root grants authorize anything. */
data class RootSnapshotAdmission(
    val snapshotId: String,
    val agentId: String,
    val workspaceGenerationIds: List<String>,
    val grantIds: List<String>,
    val snapshot: AgentSnapshot,
) {
    override fun toString(): String =
        "RootSnapshotAdmission(snapshotId=$snapshotId, grantCount=${grantIds.size})"
}

sealed interface RootSnapshotOutcome {
    data class Sealed(val admission: RootSnapshotAdmission) : RootSnapshotOutcome
    data class Refused(val reason: RootGrantRefusal, val detail: String? = null) : RootSnapshotOutcome
}

/**
 * Explicit-capability Root grants plus sealed Agent snapshot admission.
 *
 * Grants are created only for the capabilities the caller passes in, with a fresh random grant id
 * bound to the workspace generation read back from the local Root origin. Snapshots are sealed only
 * through [RootSnapshotGrantRepository.createNewSnapshot] around a real
 * [AgentRepository.createSnapshot] callback: this class never writes a placeholder snapshot row
 * (no synthetic prompt/model/`expanded{}` fixtures) and never appends grants to an existing
 * snapshot. It also never creates a Run or dispatches anything.
 */
class RootGrantSnapshotAdmission(
    private val db: SqlConnection,
    private val grants: RootGrantRepository = RootGrantRepository(db),
    private val snapshots: RootSnapshotGrantRepository = RootSnapshotGrantRepository(db),
    private val agents: AgentRepository = AgentRepository(db),
    private val newGrantId: () -> String = { EntityId.random().value },
    private val workspaces: WorkspaceRepository = WorkspaceRepository(db),
    private val origins: RootWorkspaceRepository = RootWorkspaceRepository(db),
    private val adbBindings: PrivilegedWorkspaceBindingRepository = PrivilegedWorkspaceBindingRepository(db),
) {
    fun prepareGrants(
        agentId: String,
        workspaceId: String,
        requests: List<RootGrantRequest>,
        execution: RootExecutionStamp,
    ): RootGrantPreparation {
        requestsInvalid(agentId, requests)?.let { return it }
        val context = try {
            workspaceContext(workspaceId, execution)
        } catch (failure: GrantRejection) {
            return RootGrantPreparation.Refused(failure.reason, failure.detail)
        }
        val bindings = try {
            db.transaction { createGrants(agentId, workspaceId, requests, execution, context) }
        } catch (failure: GrantRejection) {
            return RootGrantPreparation.Refused(failure.reason, failure.detail)
        } catch (failure: Exception) {
            return RootGrantPreparation.Refused(RootGrantRefusal.GRANT_REJECTED, failure.javaClass.simpleName)
        }
        return RootGrantPreparation.Prepared(agentId, workspaceId, context.origin.generationId, bindings)
    }

    fun createSnapshot(
        agentId: String,
        snapshotId: String,
        grantIds: Set<String>,
        execution: RootExecutionStamp,
        canonicalSnapshotCallback: (String) -> AgentSnapshot,
    ): RootSnapshotOutcome {
        snapshotInvalid(agentId, snapshotId, grantIds)?.let { return it }
        val verified = try {
            verifyGrants(agentId, grantIds, execution)
        } catch (failure: GrantRejection) {
            return RootSnapshotOutcome.Refused(failure.reason, failure.detail)
        }
        val sealed = try {
            snapshots.createNewSnapshot(snapshotId, agentId, grantIds, execution) {
                val created = canonicalSnapshotCallback(snapshotId)
                if (created.id != snapshotId || created.agentId != agentId) {
                    throw GrantRejection(RootGrantRefusal.SNAPSHOT_CALLBACK_INVALID, "identity")
                }
                if (created.bindingManifestJson == PLACEHOLDER_MANIFEST) {
                    throw GrantRejection(RootGrantRefusal.SNAPSHOT_CALLBACK_INVALID, "placeholder")
                }
                created
            }
        } catch (failure: GrantRejection) {
            return RootSnapshotOutcome.Refused(failure.reason, failure.detail)
        } catch (failure: Exception) {
            return RootSnapshotOutcome.Refused(RootGrantRefusal.SNAPSHOT_REJECTED, failure.javaClass.simpleName)
        }
        if (snapshots.revalidate(snapshotId, grantIds.first(), execution) == null) {
            return RootSnapshotOutcome.Refused(RootGrantRefusal.SEAL_READBACK_FAILED)
        }
        val generations = generations(verified) ?: return RootSnapshotOutcome.Refused(
            RootGrantRefusal.WORKSPACE_GENERATION_DRIFT,
        )
        return RootSnapshotOutcome.Sealed(
            RootSnapshotAdmission(snapshotId, agentId, generations, grantIds.sorted(), sealed),
        )
    }

    fun wrapSnapshotCreation(
        agentId: String,
        grantIds: Set<String>,
        execution: RootExecutionStamp,
        createSnapshot: (String) -> AgentSnapshot = { id -> agents.createSnapshot(agentId, id) },
    ): RootSnapshotOutcome = createSnapshot(agentId, EntityId.random().value, grantIds, execution, createSnapshot)

    /** Prepare the explicit grants and seal one new snapshot in a single caller-visible step. */
    fun prepareAndSeal(
        agentId: String,
        workspaceId: String,
        requests: List<RootGrantRequest>,
        execution: RootExecutionStamp,
        createSnapshot: (String) -> AgentSnapshot,
    ): RootSnapshotOutcome {
        val prepared = prepareGrants(agentId, workspaceId, requests, execution)
        if (prepared is RootGrantPreparation.Refused) {
            return RootSnapshotOutcome.Refused(prepared.reason, prepared.detail)
        }
        val bindings = (prepared as RootGrantPreparation.Prepared).bindings
        return wrapSnapshotCreation(agentId, bindings.map { it.grantId }.toSet(), execution, createSnapshot)
    }

    private fun requestsInvalid(agentId: String, requests: List<RootGrantRequest>): RootGrantPreparation.Refused? =
        when {
            agentId.isBlank() -> RootGrantPreparation.Refused(RootGrantRefusal.INVALID_AGENT)
            requests.isEmpty() -> RootGrantPreparation.Refused(RootGrantRefusal.NO_CAPABILITIES)
            requests.size > MAX_GRANTS -> RootGrantPreparation.Refused(RootGrantRefusal.TOO_MANY_CAPABILITIES)
            requests.any { !RootApprovalTools.isSupportedCapability(it.capability) } ->
                RootGrantPreparation.Refused(RootGrantRefusal.UNSUPPORTED_CAPABILITY)
            requests.map { it.capability.value }.distinct().size != requests.size ->
                RootGrantPreparation.Refused(RootGrantRefusal.DUPLICATE_CAPABILITY)
            requests.any { !lifetimeOwnerValid(it) } ->
                RootGrantPreparation.Refused(RootGrantRefusal.INVALID_LIFETIME_OWNER)
            else -> null
        }

    private fun lifetimeOwnerValid(request: RootGrantRequest): Boolean = when (request.lifetime) {
        GrantLifetime.PERSISTENT -> request.taskId == null && request.sessionId == null
        GrantLifetime.TASK -> request.taskId != null && request.sessionId == null
        GrantLifetime.SESSION -> request.sessionId != null && request.taskId == null
        GrantLifetime.ONCE -> true
    }

    private fun workspaceContext(workspaceId: String, execution: RootExecutionStamp): RootWorkspaceContext {
        val origin = origins.getOrigin(workspaceId)
            ?: throw GrantRejection(RootGrantRefusal.WORKSPACE_NOT_ROOT, "origin")
        if (!origin.matches(execution.identity)) throw GrantRejection(RootGrantRefusal.WORKSPACE_NOT_ROOT, "identity")
        if (adbBindings.get(workspaceId) != null) throw GrantRejection(RootGrantRefusal.ADB_WORKSPACE_NOT_PROMOTABLE)
        val workspace = workspaces.get(workspaceId)
            ?: throw GrantRejection(RootGrantRefusal.WORKSPACE_NOT_ROOT, "workspace")
        val privileged = workspace.backendType == WorkspaceBackendType.PRIVILEGED &&
            workspace.enabled &&
            workspace.readable &&
            workspace.rootReference == "root:${origin.generationId}"
        if (!privileged) throw GrantRejection(RootGrantRefusal.WORKSPACE_NOT_ROOT, "workspace")
        return RootWorkspaceContext(origin, workspace.writable)
    }

    private fun createGrants(
        agentId: String,
        workspaceId: String,
        requests: List<RootGrantRequest>,
        execution: RootExecutionStamp,
        context: RootWorkspaceContext,
    ): List<RootGrantBinding> {
        val canonical = CapabilityGrantRepository(db)
        return requests.map { request ->
            if (RootApprovalTools.requiresWritableWorkspace(request.capability) && !context.writable) {
                throw GrantRejection(RootGrantRefusal.WORKSPACE_NOT_WRITABLE)
            }
            val binding = grants.create(
                CapabilityGrant(
                    grantId = newGrantId(),
                    agentId = agentId,
                    capability = request.capability,
                    workspaceId = workspaceId,
                    pathScope = request.pathScope,
                    lifetime = request.lifetime,
                    policyVersion = execution.policyVersion,
                    expiresAt = request.expiresAt,
                    taskId = request.taskId,
                    sessionId = request.sessionId,
                ),
                execution,
            )
            val stored = canonical.get(binding.grantId)
                ?: throw GrantRejection(RootGrantRefusal.GRANT_READBACK_INVALID, "canonical")
            val root = grants.get(binding.grantId)
                ?: throw GrantRejection(RootGrantRefusal.GRANT_READBACK_INVALID, "binding")
            val scoped = root == binding && binding.matches(stored, execution) &&
                binding.workspaceId == workspaceId &&
                binding.workspaceGenerationId == context.origin.generationId
            if (!scoped) throw GrantRejection(RootGrantRefusal.GRANT_READBACK_INVALID, "scope")
            binding
        }
    }

    private fun snapshotInvalid(
        agentId: String,
        snapshotId: String,
        grantIds: Set<String>,
    ): RootSnapshotOutcome.Refused? = when {
        agentId.isBlank() -> RootSnapshotOutcome.Refused(RootGrantRefusal.INVALID_AGENT)
        snapshotId.isBlank() -> RootSnapshotOutcome.Refused(RootGrantRefusal.INVALID_SNAPSHOT_ID)
        grantIds.isEmpty() || grantIds.size > MAX_GRANTS -> RootSnapshotOutcome.Refused(RootGrantRefusal.GRANT_SET_INVALID)
        grantIds.any { it.isBlank() } -> RootSnapshotOutcome.Refused(RootGrantRefusal.GRANT_SET_INVALID, "blank")
        agents.getSnapshot(snapshotId) != null -> RootSnapshotOutcome.Refused(RootGrantRefusal.SNAPSHOT_EXISTS)
        else -> null
    }

    private fun verifyGrants(
        agentId: String,
        grantIds: Set<String>,
        execution: RootExecutionStamp,
    ): List<RootGrantBinding> {
        val canonical = CapabilityGrantRepository(db)
        return grantIds.sorted().map { id ->
            val stored = canonical.get(id) ?: throw GrantRejection(RootGrantRefusal.GRANT_NOT_ROOT, "canonical")
            val root = grants.get(id) ?: throw GrantRejection(RootGrantRefusal.GRANT_NOT_ROOT, "root")
            if (stored.agentId != agentId || !root.matches(stored, execution)) {
                throw GrantRejection(RootGrantRefusal.GRANT_NOT_ROOT, "scope")
            }
            root
        }
    }

    /** Re-reads each grant's workspace origin so a drifted generation cannot stay sealed. */
    private fun generations(bindings: List<RootGrantBinding>): List<String>? {
        val seen = mutableListOf<String>()
        for (binding in bindings) {
            val origin = origins.getOrigin(binding.workspaceId)
            if (origin == null || origin.generationId != binding.workspaceGenerationId) return null
            if (seen.none { it == origin.generationId }) seen.add(origin.generationId)
        }
        return seen
    }

    private data class RootWorkspaceContext(val origin: RootWorkspaceOrigin, val writable: Boolean)

    private class GrantRejection(
        val reason: RootGrantRefusal,
        val detail: String? = null,
    ) : Exception(detail ?: reason.name)

    private companion object {
        const val MAX_GRANTS = 128
        const val PLACEHOLDER_MANIFEST = "{}"
    }
}
