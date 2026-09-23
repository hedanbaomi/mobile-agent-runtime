// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceText
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest

/**
 * file_move is not a model tool on the Android workspace backends.
 *
 * A stale model schema that still names it must never see it in the tool
 * schema, and an explicit call must fail closed with the typed
 * [ToolErrorCode.OPERATION_UNAVAILABLE] result before any backend is touched.
 */
class FileMoveNeverExposedTest {
    @Test
    fun fileMoveIsNeverExposedAndStaleCallsFailClosedWithoutTouchingBackend(): Unit = runBlocking {
        val descriptor = WorkspaceDescriptor(
            id = "workspace-move-retired",
            displayName = "Move retired workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            writable = true,
        )
        var touched = 0
        fun <T> touchedBackend(): WorkspaceResult<T> {
            touched += 1
            throw AssertionError("a stale file_move call must never reach the workspace backend")
        }
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = descriptor

            // Over-advertise deliberately: even a move-capable backend must
            // not make file_move visible or reachable from the model.
            override val capabilities: Set<CapabilityId> = setOf(
                CapabilityId(CapabilityId.FILE_MOVE),
            )

            override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> = touchedBackend()
            override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> = touchedBackend()
            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> = touchedBackend()
            override suspend fun applyPatch(request: WorkspaceApplyPatchRequest): WorkspaceResult<WorkspaceMutation> = touchedBackend()
            override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> = touchedBackend()
            override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> = touchedBackend()
            override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> = touchedBackend()
            override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> = touchedBackend()
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(descriptor, backend))
        val context = workspaceContext()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
        )

        assertTrue(executor.toolingSpecs.none { it.name == UnifiedWorkspaceToolExecutor.FILE_MOVE })
        assertTrue(executor.specs.none { it.name == UnifiedWorkspaceToolExecutor.FILE_MOVE })

        val arguments =
            "{\"workspace_id\":\"$WORKSPACE_ID\",\"relative_path\":\"from.txt\",\"destination_relative_path\":\"to.txt\"}"
        val legacy = executor.invoke(
            ToolCall("move-stale-legacy", UnifiedWorkspaceToolExecutor.FILE_MOVE, arguments),
            context,
        )
        assertTrue(legacy is ToolResult.Failure)
        val legacyError = (legacy as ToolResult.Failure).error
        assertEquals(ToolErrorCode.OPERATION_UNAVAILABLE, legacyError.code)
        assertTrue(legacyError.message.contains("file_copy"))
        assertTrue(legacyError.message.contains("file_delete"))

        val typed = executor.invoke(
            ToolInvocation.fromRuntime(
                callId = "move-stale-typed",
                snapshotId = context.snapshotId,
                agentId = context.agentId,
                name = UnifiedWorkspaceToolExecutor.FILE_MOVE,
                argumentsJson = arguments,
            ),
            context,
        )
        assertTrue(typed is ToolExecution.Failed)
        assertEquals(ToolErrorCode.OPERATION_UNAVAILABLE, (typed as ToolExecution.Failed).error.code)

        assertEquals(0, touched)
    }

    private fun workspaceContext(): ToolExecutionContext {
        val grants = listOf(
            grant("grant-move-retired", CapabilityId(CapabilityId.FILE_MOVE), WORKSPACE_ID),
        )
        return ToolExecutionContext(
            agentId = AGENT_ID,
            snapshotId = SNAPSHOT_ID,
            modelCallId = "model-move-retired",
            sessionIdentity = "session-move-retired",
            configSnapshotHash = "config-move-retired",
            policyVersion = POLICY_VERSION,
            effectiveCapabilities = grants.map { it.capability }.toSet(),
            canonicalGrants = grants,
            snapshotGrantBindings = grants.map { grant ->
                SnapshotGrantBinding(
                    snapshotId = SNAPSHOT_ID,
                    grantId = grant.grantId,
                    capability = grant.capability,
                    workspaceId = grant.workspaceId,
                    pathScope = grant.pathScope,
                    policyVersion = grant.policyVersion,
                )
            },
        )
    }

    private fun grant(grantId: String, capability: CapabilityId, workspaceId: String): CapabilityGrant =
        CapabilityGrant(
            grantId = grantId,
            agentId = AGENT_ID,
            capability = capability,
            workspaceId = workspaceId,
            policyVersion = POLICY_VERSION,
            revision = 1,
        )

    private companion object {
        const val AGENT_ID = "agent-move-retired"
        const val SNAPSHOT_ID = "snapshot-move-retired"
        const val WORKSPACE_ID = "workspace-move-retired"
        const val POLICY_VERSION = 1L
    }
}
