// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.AuthoritySelection
import runtime.mobileagent.skills.tooling.AuthorityState
import runtime.mobileagent.skills.tooling.ToolErrorCode
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
 * EMU-027 regression: a SAF tree whose persisted URI permission survives a
 * target rename keeps its frozen registered descriptor "enabled". Operations
 * must surface AUTHORITY_TEMPORARILY_UNAVAILABLE and enumeration must drop the
 * unreachable workspace instead of advertising it as usable.
 */
class SafDeadTreeExposureTest {

    private class FakeSafBackend(
        frozen: WorkspaceDescriptor,
    ) : WorkspaceBackend {
        var live: WorkspaceDescriptor = frozen
        var listCalls = 0
        override val descriptor: WorkspaceDescriptor get() = live
        override val capabilities = setOf(
            CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
            CapabilityId(CapabilityId.FILE_LIST),
        )

        override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
            listCalls += 1
            return WorkspaceResult.Success(WorkspaceListing(relativePath = "/", entries = emptyList()))
        }

        override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> =
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.CAPABILITY_DENIED))

        override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.CAPABILITY_DENIED))

        override suspend fun applyPatch(request: WorkspaceApplyPatchRequest): WorkspaceResult<WorkspaceMutation> =
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.CAPABILITY_DENIED))

        override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> =
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.CAPABILITY_DENIED))

        override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> =
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.CAPABILITY_DENIED))

        override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> =
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.CAPABILITY_DENIED))

        override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> =
            WorkspaceResult.Failure(runtime.mobileagent.skills.tooling.ToolError(ToolErrorCode.CAPABILITY_DENIED))
    }

    private fun executorFor(
        registry: WorkspaceRegistry,
        grants: List<CapabilityGrant>,
    ): Pair<UnifiedWorkspaceToolExecutor, ToolExecutionContext> {
        val context = ToolExecutionContext(
            agentId = "agent-one",
            snapshotId = "snapshot-one",
            modelCallId = "model-one",
            sessionIdentity = "session-one",
            configSnapshotHash = "config-one",
            policyVersion = 1,
            effectiveCapabilities = grants.map { it.capability }.toSet(),
            canonicalGrants = grants,
            snapshotGrantBindings = grants.map { grant ->
                SnapshotGrantBinding(
                    snapshotId = "snapshot-one",
                    grantId = grant.grantId,
                    capability = grant.capability,
                    workspaceId = grant.workspaceId,
                    policyVersion = 1,
                )
            },
            authoritySelection = AuthoritySelection(
                selected = Authority.SHIZUKU,
                states = mapOf(Authority.SHIZUKU to AuthorityState.configured(Authority.SHIZUKU)),
            ),
        )
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            dangerousModeProvider = { DangerousMode.DISABLED },
            auditSink = object : WorkspaceAuditSink {
                override suspend fun record(event: WorkspaceAuditEvent): Boolean = true
            },
        )
        return executor to context
    }

    private fun grant(id: String, capability: String, workspaceId: String) = CapabilityGrant(
        grantId = id,
        agentId = "agent-one",
        capability = CapabilityId(capability),
        workspaceId = workspaceId,
        policyVersion = 1,
    )

    @Test
    fun deadSafTreeIsNotEnumeratedAndReportsTemporaryUnavailable(): Unit = runBlocking {
        val deadDescriptor = WorkspaceDescriptor(
            id = "saf-dead",
            displayName = "Renamed tree",
            backendType = WorkspaceBackendType.SAF_TREE,
            rootReference = "content://com.android.externalstorage.documents/tree/dead",
            scope = WorkspaceScope.SELECTED_DIRECTORY,
            readable = true,
            writable = true,
            enabled = true,
        )
        val liveDescriptor = deadDescriptor.copy(id = "saf-live", displayName = "Healthy tree")
        val deadBackend = FakeSafBackend(deadDescriptor)
        val liveBackend = FakeSafBackend(liveDescriptor)
        // The tree was renamed underneath the persisted grant: the live probe
        // reports disabled while the frozen registered descriptor stays enabled.
        deadBackend.live = deadDescriptor.copy(enabled = false)
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(deadDescriptor, deadBackend))
        assertTrue(registry.register(liveDescriptor, liveBackend))
        val grants = listOf(
            grant("g-enum-dead", CapabilityId.WORKSPACE_ENUMERATE, "saf-dead"),
            grant("g-enum-live", CapabilityId.WORKSPACE_ENUMERATE, "saf-live"),
            grant("g-list-dead", CapabilityId.FILE_LIST, "saf-dead"),
        )
        val (executor, context) = executorFor(registry, grants)

        val listed = executor.invoke(
            ToolCall("call-enum", UnifiedWorkspaceToolExecutor.WORKSPACE_LIST, "{}"), context,
        )
        assertTrue(listed is ToolResult.Value)
        val json = (listed as ToolResult.Value).json
        assertTrue(json.contains("saf-live"))
        assertFalse(json.contains("saf-dead"))

        val fileList = executor.invoke(
            ToolCall(
                "call-list",
                UnifiedWorkspaceToolExecutor.FILE_LIST,
                """{"workspace_id":"saf-dead","relative_path":""}""",
            ),
            context,
        )
        assertTrue(fileList is ToolResult.Failure)
        assertEquals(
            ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            (fileList as ToolResult.Failure).error.code,
        )
        assertEquals(0, deadBackend.listCalls)
    }
}
