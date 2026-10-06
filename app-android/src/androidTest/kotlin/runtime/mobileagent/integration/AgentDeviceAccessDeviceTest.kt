// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.*
import runtime.mobileagent.data.AuditRepository
import runtime.mobileagent.data.AuthorityPolicyRepository
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.*
import runtime.mobileagent.tooling.*
import runtime.mobileagent.workspace.CanonicalWorkspaceSink

/** Real VM/SQLite and production factory boundaries; synthetic transports never run a command. */
@RunWith(AndroidJUnit4::class)
class AgentDeviceAccessDeviceTest {
    @Test fun firstAgentFullDeviceConfirmationPreservesDirectoryAndCommitsOnlyAfterSave() {
        val f = fixture()
        val vm = f.editor()
        var targetId: String? = null
        replace(vm, "canonicalWorkspaceSink", object : CanonicalWorkspaceSink by f.app.container.runtimeIntegration {
            override suspend fun openFullDeviceFiles(authority: Authority, request: FullDeviceFilesRequest,
                plan: WorkspaceIntentPlan, target: WorkspaceTarget): WorkspaceAccessResult {
                assertEquals(Authority.SHIZUKU, authority)
                assertNotNull(f.app.container.agents.get(requireNotNull(target.agentId)))
                assertFalse(plan.setAgentDefault)
                assertFalse(plan.bindThread)
                assertTrue(plan.grantRequired)
                assertTrue(request.confirmedByUser)
                targetId = target.agentId
                return success(request.workspaceId)
            }
        })
        vm.stageWorkspaceDraft(WorkspaceDraft(RuntimeIntegration.INTERNAL_WORKSPACE_ID, "Internal", true))
        assertTrue(vm.stageFullDeviceFiles())
        assertTrue(vm.hasPendingFullDeviceFiles())
        assertNull(targetId)
        assertEquals(RuntimeIntegration.INTERNAL_WORKSPACE_ID, vm.state.value.editor?.defaultWorkspaceId)
        val saved = vm.save()
        assertTrue(vm.state.value.error, saved)
        assertEquals(vm.state.value.selectedAgentId, targetId)
        assertFalse(vm.hasPendingFullDeviceFiles())
        assertEquals(RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            f.app.container.threadWorkspacePort.agentWorkspaceDefault(requireNotNull(targetId))?.workspaceId)
    }

    @Test fun cancellationStaleEditorAndChangedConsentCannotCreateAccess() {
        val f = fixture()
        val vm = f.editor()
        val before = f.app.container.agents.list().map { it.id }.toSet()
        val old = vm.editorSessionToken()
        assertTrue(vm.stageFullDeviceFiles())
        vm.closeEditor()
        assertFalse(vm.hasPendingFullDeviceFiles())
        vm.openEditor(null)
        assertFalse(vm.stageFullDeviceFiles(old))
        vm.edit(requireNotNull(vm.state.value.editor).copy(name = "Consent fixture", chatModelId = f.modelId))
        assertTrue(vm.stageFullDeviceFiles())
        f.consent = f.consent.copy(revision = f.consent.revision + 1)
        assertFalse(vm.save())
        assertTrue(vm.state.value.error.orEmpty().contains("重新确认"))
        assertEquals(before, f.app.container.agents.list().map { it.id }.toSet())
    }

    @Test fun failedFullDeviceCommitRollsBackAgentAndRetainsBothDraftsForExplicitRetry() {
        val f = fixture()
        val vm = f.editor()
        val before = f.app.container.agents.list().map { it.id }.toSet()
        var fail = true
        replace(vm, "canonicalWorkspaceSink", object : CanonicalWorkspaceSink by f.app.container.runtimeIntegration {
            override suspend fun openFullDeviceFiles(authority: Authority, request: FullDeviceFilesRequest,
                plan: WorkspaceIntentPlan, target: WorkspaceTarget): WorkspaceAccessResult =
                if (fail) WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
                else success(request.workspaceId)
        })
        vm.stageWorkspaceDraft(WorkspaceDraft(RuntimeIntegration.INTERNAL_WORKSPACE_ID, "Internal", true))
        assertTrue(vm.stageFullDeviceFiles())
        assertFalse(vm.save())
        assertEquals(before, f.app.container.agents.list().map { it.id }.toSet())
        assertTrue(vm.hasPendingFullDeviceFiles())
        assertNotNull(vm.pendingWorkspaceDraft())
        assertTrue(vm.state.value.error, vm.state.value.error.orEmpty().contains("完整设备文件"))
        fail = false
        val saved = vm.save()
        assertTrue(vm.state.value.error, saved)
        assertFalse(vm.hasPendingFullDeviceFiles())
        assertNull(vm.pendingWorkspaceDraft())
    }

    @Test fun productionFactoryUsesAgentIdentityAndDeniesCachedDisclosureAfterPolicyOrAuthorityChange() = runBlocking {
        val f = fixture()
        f.withShell { runtime, authority, danger, requests ->
            val factory = f.factory(runtime)
            assertTrue(factory.executor.specs.any { it.name == "shell_exec" })
            assertTrue(factory.executor.specs.none { it.name.startsWith("memory_") })
            val call = call("cached-${f.id}")
            assertTrue(factory.invoke(call) is ToolResult.Value)
            assertEquals(1, requests.size)
            assertNull(requests.single().skillId)
            val details = AuditRepository(f.app.container.db).listDetails().filter { it.agentId == f.agentId }
            assertTrue(details.isNotEmpty())
            assertTrue(details.all { it.agentId == f.agentId && it.skillId == null })
            assertTrue(factory.authorizeReplay(call))
            val repo = AuthorityPolicyRepository(f.app.container.db)
            val policy = repo.getPolicy()
            try {
                repo.selectAuthority(policy.policyVersion, Authority.WIRED_ADB)
                assertFalse(factory.authorizeReplay(call))
                assertTrue(factory.invoke(call) is ToolResult.Denied)
                assertTrue(factory.invoke(call("new-${f.id}")) is ToolResult.Denied)
                assertEquals(1, requests.size)
            } finally {
                f.app.container.db.execute("UPDATE authority_policy SET selected_authority=?, dangerous_mode=?, policy_version=?, updated_at=? WHERE id=1",
                    listOf(policy.selectedAuthority.name, policy.dangerousMode.name, policy.policyVersion, policy.updatedAt))
            }
            assertTrue(factory.invoke(call) is ToolResult.Denied)
            val fresh = f.factory(runtime)
            assertTrue(fresh.invoke(call("authority-${f.id}")) is ToolResult.Value)
            authority.selectAuthority(Authority.WIRED_ADB)
            assertFalse(fresh.authorizeReplay(call("authority-${f.id}")))
            assertTrue(fresh.invoke(call("authority-${f.id}")) is ToolResult.Denied)
            assertEquals(2, requests.size)
            danger.setPolicy(DangerousMode.DISABLED)
        }
    }

    @Test fun productionFactoryPendingApprovalCannotSurvivePolicyOrConsentEpochChange() = runBlocking {
        val f = fixture()
        f.withShell { runtime, _, danger, requests ->
            danger.setPolicy(DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            val pending = f.factory(runtime)
            assertEquals(ToolResult.NeedsApproval, pending.invoke(call("pending-${f.id}", "rm -rf /data/local/tmp/synthetic-fixture")))
            danger.setPolicy(DangerousMode.DISABLED)
            danger.setPolicy(DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            assertTrue(pending.approve("pending-${f.id}") is ToolResult.Denied)
            assertTrue(requests.isEmpty())
            val next = f.factory(runtime)
            val call = call("policy-${f.id}", "rm -rf /data/local/tmp/synthetic-fixture")
            assertEquals(ToolResult.NeedsApproval, next.invoke(call))
            val repo = AuthorityPolicyRepository(f.app.container.db)
            val policy = repo.getPolicy()
            try {
                repo.selectAuthority(policy.policyVersion, Authority.WIRED_ADB)
                assertTrue(next.approve(call.callId) is ToolResult.Denied)
                assertTrue(requests.isEmpty())
            } finally {
                f.app.container.db.execute("UPDATE authority_policy SET selected_authority=?, dangerous_mode=?, policy_version=?, updated_at=? WHERE id=1",
                    listOf(policy.selectedAuthority.name, policy.dangerousMode.name, policy.policyVersion, policy.updatedAt))
            }
        }
    }

    private class Fixture(val app: MobileAgentApp, val id: String, val modelId: String, val agentId: String) {
        var consent = SettingsAuthoritySnapshot(selectedAuthority = Authority.SHIZUKU,
            dangerousMode = DangerousMode.ENABLED_AUTONOMOUS, dangerousModeBuildAllowed = true, revision = 10)
        fun editor() = AgentsViewModel(app, SavedStateHandle()).also { vm ->
            replace(vm, "authorityPort", object : SettingsAuthorityPort by app.container.runtimeIntegration {
                override fun snapshot() = consent
            })
            vm.openEditor(null)
            vm.edit(requireNotNull(vm.state.value.editor).copy(name = "Access fixture $id", chatModelId = modelId, prompt = "Use the authorized device tools."))
        }
        fun factory(runtime: RuntimeIntegration): ToolExecutorFactory {
            val snapshot = app.container.agents.createSnapshot(agentId, "snapshot-${UUID.randomUUID()}", Utc.nowIso())
            return runtime.createToolExecutorFactory(runtime.createToolExecutionContext(snapshot,
                modelCallId = "model-$id", sessionIdentity = "session-$id", configSnapshotHash = "config-$id",
                skillId = "ungranted-skill-$id", skillRevision = 1, trustedSkillEnvelope = true))
        }
        suspend fun withShell(block: suspend (RuntimeIntegration, AuthorityManager, DangerousModeManager, MutableList<ShellExecRequest>) -> Unit) {
            val runtime = app.container.runtimeIntegration
            val authority = AuthorityManager().apply {
                selectAuthority(Authority.SHIZUKU); setUserIntent(Authority.SHIZUKU, true); setConfigured(Authority.SHIZUKU, true)
                updatePlatformGrant(Authority.SHIZUKU, PlatformGrant.GRANTED)
                updateAvailability(Authority.SHIZUKU, Availability.READY)
                updateConnection(Authority.SHIZUKU, Connection.CONNECTED, "synthetic")
            }
            val danger = DangerousModeManager(InMemoryDangerousModeStateStore(), DangerousBuildPolicy.testOnlyOverride()).apply {
                setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
            }
            val requests = mutableListOf<ShellExecRequest>()
            val backend = object : ShellExecutor {
                override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                    requests += request
                    return ShellExecResult.succeeded(request, 0, "synthetic", "", 1)
                }
                override suspend fun cancel(requestId: String) = true
            }
            val oldAuthority = replace(runtime, "authorityManager", authority)
            val oldDanger = replace(runtime, "dangerousModeManager", danger)
            val oldBackends = replace(runtime, "shellBackends", linkedMapOf(Authority.SHIZUKU to backend))
            try { block(runtime, authority, danger, requests) } finally {
                replace(runtime, "shellBackends", oldBackends)
                replace(runtime, "dangerousModeManager", oldDanger)
                replace(runtime, "authorityManager", oldAuthority)
            }
        }
    }

    private fun fixture(): Fixture {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val id = UUID.randomUUID().toString()
        val provider = "provider-$id"
        val model = "model-$id"
        val agent = "agent-$id"
        app.container.profiles.createProvider(ProviderProfile(id = provider, name = "Access fixture", apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1", secretRef = "fixture-$id", revision = 1))
        app.container.profiles.createModel(ModelProfile(model, provider, ModelRole.CHAT, "fixture", setOf("stream"),
            contextLimit = 4096, outputLimit = 512, revision = 1))
        app.container.agents.saveWithPrompt(AgentProfile(agent, "Factory fixture", "pending", model, revision = 0), "Fixture")
        return Fixture(app, id, model, agent)
    }

    companion object {
        private fun replace(target: Any, field: String, value: Any?): Any? = target.javaClass.getDeclaredField(field).let {
            it.isAccessible = true
            val old = it.get(target)
            it.set(target, value)
            old
        }
        private fun call(id: String, command: String = "pm list packages") = ToolCall(id, "shell_exec", "{\"command\":\"$command\"}")
        private fun success(id: String) = WorkspaceAccessResult.Success(WorkspaceAccessItem(id, "Full device files",
            WorkspaceBackendType.PRIVILEGED, WorkspaceScope.FULL_DEVICE_FILES, true, true, WorkspaceAccessStatus.ACTIVE))
    }
}
