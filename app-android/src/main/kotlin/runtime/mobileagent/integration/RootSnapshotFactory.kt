// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.time.Instant
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.privileged.RootRuntimeCoordinator

/** Seals only current explicit Root grants around the canonical snapshot creation transaction. */
internal class RootSnapshotFactory(private val db: SqlConnection, private val coordinator: RootRuntimeCoordinator) {
    fun withWorkspace(agentId: String, workspaceId: String?, snapshotId: String, at: String): AgentSnapshot = db.transaction {
        val workspace = workspaceId?.let { id ->
            WorkspaceRepository(db).get(id)?.takeIf { it.enabled && it.readable } ?: error("Workspace is unavailable")
        }
        if (workspace == null) {
            return@transaction AgentRepository(db).createSnapshot(agentId, snapshotId, at)
        }
        val isRootWorkspace = RootWorkspaceRepository(db).getOrigin(workspace.id) != null ||
            workspace.rootReference.startsWith("root:")
        val snapshot = create(agentId, snapshotId, at, workspace.id, requireSealedRoot = isRootWorkspace)
        if (!isRootWorkspace) {
            val grants = CapabilityGrantRepository(db)
            val policyVersion = AuthorityPolicyRepository(db).getPolicy().policyVersion
            val now = Instant.now()
            grants.forAgent(agentId, includeRevoked = false).filter { grant ->
                grant.workspaceId == workspace.id && grant.policyVersion == policyVersion &&
                    RootGrantRepository(db).get(grant.grantId) == null && grant.isActiveFor(now, null, null)
            }.forEach { grant ->
                grants.bindSnapshot(SnapshotGrantBinding(snapshot.id, grant.grantId, grant.capability,
                    grant.workspaceId, grant.pathScope, grant.policyVersion, boundAt = at))
            }
        }
        snapshot
    }

    fun withCurrentGrants(agentId: String, snapshotId: String, at: String): AgentSnapshot = db.transaction {
        require(agentId.isNotBlank() && snapshotId.isNotBlank() && at.isNotBlank())
        val snapshot = create(agentId, snapshotId, at, null)
        val grants = CapabilityGrantRepository(db)
        val policyVersion = AuthorityPolicyRepository(db).getPolicy().policyVersion
        val now = Utc.nowIso()
        val expected = grants.forAgent(agentId, includeRevoked = false).filter {
            it.policyVersion == policyVersion && RootGrantRepository(db).get(it.grantId) == null &&
                it.isActiveFor(Instant.parse(now), it.taskId, it.sessionId)
        }.map { SnapshotGrantBinding(snapshot.id, it.grantId, it.capability, it.workspaceId, it.pathScope, it.policyVersion, boundAt = now) }
        expected.forEach { require(grants.bindSnapshot(it) == it) }
        val ordinary = grants.listSnapshotBindings(snapshot.id).filter { RootGrantRepository(db).get(it.grantId) == null }
        require(ordinary.size == expected.size && ordinary.toSet() == expected.toSet()) { "Snapshot grant binding verification failed" }
        snapshot
    }

    fun create(
        agentId: String,
        snapshotId: String,
        at: String,
        workspaceId: String?,
        requireSealedRoot: Boolean = false,
    ): AgentSnapshot {
        val agents = AgentRepository(db)
        val selected = AuthorityPolicyRepository(db).getPolicy().selectedAuthority
        val execution = if (selected.isRootAuthority) coordinator.currentExecutionStamp(selected) else null
        val roots = RootGrantRepository(db)
        val now = Instant.parse(at)
        val rootIds = CapabilityGrantRepository(db).forAgent(agentId, includeRevoked = false).filter { grant ->
            (workspaceId == null || grant.workspaceId == workspaceId) &&
                execution != null && roots.get(grant.grantId)?.matches(grant, execution) == true &&
                grant.isActiveFor(now, grant.taskId, grant.sessionId) &&
                (grant.capability.value != CapabilityId.SHELL_EXECUTE ||
                    coordinator.currentExecutionStamp(selected, requireShellConsent = true) == execution)
        }.mapTo(linkedSetOf()) { it.grantId }
        if (rootIds.isEmpty()) {
            require(!requireSealedRoot) { "Root 工作区快照需要当前已验证的 Root 会话与有效授权。" }
            return agents.createSnapshot(agentId, snapshotId, at)
        }
        return coordinator.sealNewSnapshot(selected, snapshotId, agentId, rootIds) {
            agents.createSnapshot(agentId, snapshotId, at)
        }
    }
}
