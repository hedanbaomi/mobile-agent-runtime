// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.data.RootRunAdmission
import runtime.mobileagent.data.RootRunTarget
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.tooling.ToolExecutionContext

internal object RootToolExecutorAdmission {
    const val PRODUCTION_TOOL_ADMISSION_ENABLED = true

    fun create(db: SqlConnection, coordinator: RootRuntimeCoordinator, context: ToolExecutionContext): RootToolExecutor? {
        val extraWorkspaceIds = buildSet {
            context.canonicalGrants.mapNotNullTo(this) { it.workspaceId }
            context.snapshotGrantBindings.mapNotNullTo(this) { it.workspaceId }
        }
        val extraGrantIds = buildSet {
            context.canonicalGrants.mapTo(this) { it.grantId }
            context.snapshotGrantBindings.mapTo(this) { it.grantId }
        }
        val admission = RootRunAdmission(db)
        val target = admission.resolveRunTarget(
            conversationId = context.sessionIdentity,
            snapshotId = context.snapshotId,
            extraWorkspaceIds = extraWorkspaceIds,
            extraGrantIds = extraGrantIds,
        )
        if (target == RootRunTarget.None) return null
        val selected = AuthorityPolicyRepository(db).getPolicy().selectedAuthority
        val execution = if (target is RootRunTarget.Bound && selected == target.authority) {
            coordinator.currentExecutionStamp(selected)?.takeIf { stamp ->
                context.taskIdentity.isNotBlank() && admission.matchesAdmittedRun(context.taskIdentity, stamp)
            }
        } else {
            null
        }
        val backend = RootRuntimeToolBackend(db, coordinator, context, execution)
        // Root-bound runs never fall back to ordinary workspace/shell executors when Root is unavailable.
        return RootToolExecutor(if (PRODUCTION_TOOL_ADMISSION_ENABLED) RootToolArguments.specs() else emptyList(), backend)
    }
}
