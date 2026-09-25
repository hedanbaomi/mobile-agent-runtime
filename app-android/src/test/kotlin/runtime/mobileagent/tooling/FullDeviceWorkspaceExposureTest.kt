// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
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
import runtime.mobileagent.skills.tooling.ToolErrorCode

class FullDeviceWorkspaceExposureTest {
    @Test
    fun dangerousModeGatesFullDeviceSchemaAndDispatchEvenWithFrozenGrants(): Unit = runBlocking {
        val deviceDescriptor = WorkspaceDescriptor(
            id = "device-files",
            displayName = "Device files",
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "authority:SHIZUKU",
            scope = WorkspaceScope.FULL_DEVICE_FILES,
        )
        var touched = false
        fun <T> noDispatch(): WorkspaceResult<T> {
            touched = true
            throw AssertionError("Full-device backend must not run after Dangerous Mode is disabled")
        }
        val backend = object : WorkspaceBackend {
            override val descriptor: WorkspaceDescriptor = deviceDescriptor
            override val capabilities = setOf(CapabilityId(CapabilityId.WORKSPACE_ENUMERATE))
            override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> = noDispatch()
            override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> = noDispatch()
            override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> = noDispatch()
            override suspend fun applyPatch(request: WorkspaceApplyPatchRequest): WorkspaceResult<WorkspaceMutation> = noDispatch()
            override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> = noDispatch()
            override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> = noDispatch()
            override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> = noDispatch()
            override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> = noDispatch()
        }
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(deviceDescriptor, backend))
        val grant = CapabilityGrant(
            grantId = "grant-device-files",
            agentId = "agent-one",
            capability = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
            workspaceId = deviceDescriptor.id,
            policyVersion = 1,
        )
        val context = ToolExecutionContext(
            agentId = grant.agentId,
            snapshotId = "snapshot-one",
            modelCallId = "model-one",
            sessionIdentity = "session-one",
            configSnapshotHash = "config-one",
            policyVersion = 1,
            effectiveCapabilities = setOf(grant.capability),
            canonicalGrants = listOf(grant),
            snapshotGrantBindings = listOf(SnapshotGrantBinding(
                snapshotId = "snapshot-one",
                grantId = grant.grantId,
                capability = grant.capability,
                workspaceId = grant.workspaceId,
                policyVersion = 1,
            )),
            authoritySelection = AuthoritySelection(
                selected = Authority.SHIZUKU,
                states = mapOf(Authority.SHIZUKU to AuthorityState.configured(Authority.SHIZUKU)),
            ),
        )
        var mode = DangerousMode.DISABLED
        fun executor() = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            dangerousModeProvider = { mode },
            auditSink = object : WorkspaceAuditSink {
                override suspend fun record(event: WorkspaceAuditEvent): Boolean = true
            },
        )
        assertFalse(executor().toolingSpecs.any { it.name == UnifiedWorkspaceToolExecutor.WORKSPACE_LIST })
        mode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK
        val enabledExecutor = executor()
        assertTrue(enabledExecutor.toolingSpecs.any { it.name == UnifiedWorkspaceToolExecutor.WORKSPACE_LIST })
        mode = DangerousMode.DISABLED
        val result = enabledExecutor.invoke(ToolCall("device-call", UnifiedWorkspaceToolExecutor.WORKSPACE_LIST, "{}"), context)
        assertTrue(result is ToolResult.Failure)
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, (result as ToolResult.Failure).error.code)
        assertFalse(touched)
    }
}
