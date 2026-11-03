// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.json.*
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*

data class RootForegroundWorkspaceGrant(val workspace: Workspace, val grants: List<CapabilityGrant>)

/** Invoked only by the foreground confirmation callback while the coordinator owns the session lock. */
internal class RootForegroundAdmissionController(
    private val db: SqlConnection,
    private val cipher: RootLocatorCipher,
    private val rememberConfirmed: (RootExecutionStamp, RootWorkspaceOrigin, String) -> Unit,
) {
    fun admit(
        execution: RootExecutionStamp,
        transport: RootChannelTransport,
        path: String,
        writable: Boolean,
        metadata: Boolean,
        shell: Boolean,
        agentId: String?,
    ): RootForegroundWorkspaceGrant? {
        val admission = RootWorkspaceAdmission(db, RootWorkspaceAdmissionTransport {
            transport.bindWorkspace(it) ?: RootServiceBindWorkspaceResponse(false)
        }, cipher)
        val selected = RootWorkspaceSelection("Root workspace", path, writable = writable || shell)
        val outcome = admission.admit(selected, execution) as? RootWorkspaceAdmissionOutcome.Admitted ?: return null
        val admitted = outcome.workspace
        val workspace = WorkspaceRepository(db).get(admitted.workspaceId) ?: return null
        val grants = if (agentId == null) emptyList() else {
            val capabilities = explicitCapabilities(writable, metadata, shell)
            val prepared = RootGrantSnapshotAdmission(db).prepareGrants(agentId, workspace.id,
                capabilities.map { RootGrantRequest(it) }, execution)
            if (prepared !is RootGrantPreparation.Prepared) {
                releaseAndDisable(execution, transport, admitted, workspace)
                return null
            }
            prepared.bindings.map { requireNotNull(CapabilityGrantRepository(db).get(it.grantId)) }
        }
        rememberConfirmed(execution, admitted.origin, admitted.workspaceSessionHandle)
        return RootForegroundWorkspaceGrant(workspace, grants)
    }

    fun admitDraftGrants(
        execution: RootExecutionStamp,
        workspaceId: String,
        agentId: String,
        capabilities: Set<CapabilityId>,
    ): List<CapabilityGrant>? {
        if (capabilities.isEmpty()) return null
        val workspace = WorkspaceRepository(db).get(workspaceId) ?: return null
        if (RootWorkspaceLocatorRepository(db).getCurrent(workspaceId, execution) == null) return null
        val prepared = RootGrantSnapshotAdmission(db).prepareGrants(
            agentId,
            workspace.id,
            capabilities.map { RootGrantRequest(it) },
            execution,
        )
        if (prepared !is RootGrantPreparation.Prepared) return null
        return prepared.bindings.map { requireNotNull(CapabilityGrantRepository(db).get(it.grantId)) }
    }

    private fun releaseAndDisable(
        execution: RootExecutionStamp, transport: RootChannelTransport,
        admitted: RootAdmittedWorkspace, workspace: Workspace,
    ) {
        val id = execution.identity
        runCatching { transport.bindWorkspace(buildJsonObject {
            put("phase", "RELEASE"); put("targetServiceInstanceId", id.serviceInstanceId)
            put("targetSessionId", id.sessionId); put("targetIdentityEpoch", id.identityEpoch)
            put("workspaceSessionHandle", admitted.workspaceSessionHandle)
        }.toString()) }
        WorkspaceRepository(db).save(workspace.copy(enabled = false, readable = false, writable = false,
            revision = workspace.revision + 1))
    }

    companion object {
        internal fun explicitCapabilities(writable: Boolean, metadata: Boolean, shell: Boolean): Set<CapabilityId> = buildSet {
            add(CapabilityId(CapabilityId.FILE_LIST)); add(CapabilityId(CapabilityId.FILE_STAT))
            add(CapabilityId(CapabilityId.FILE_READ_TEXT))
            if (writable) {
                add(CapabilityId(CapabilityId.FILE_WRITE_TEXT))
                add(CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY)); add(CapabilityId(CapabilityId.FILE_MOVE))
                add(CapabilityId(CapabilityId.FILE_DELETE))
            }
            if (metadata) {
                add(CapabilityId(CapabilityId.FILE_METADATA_READ))
                if (writable) add(CapabilityId(CapabilityId.FILE_METADATA_WRITE))
            }
            if (shell) add(CapabilityId(CapabilityId.SHELL_EXECUTE))
        }
    }
}
