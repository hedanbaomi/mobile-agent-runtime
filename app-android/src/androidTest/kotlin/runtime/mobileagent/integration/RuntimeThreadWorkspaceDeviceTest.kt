// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.AgentsViewModel
import runtime.mobileagent.ChatViewModel
import runtime.mobileagent.MobileAgentApp
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentWorkspaceDefault
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceDraft
import runtime.mobileagent.domain.WorkspaceIntent
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.domain.WorkspaceTarget
import runtime.mobileagent.domain.plan
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceResult

/**
 * AppContainer integration coverage for the durable Thread/Agent workspace boundary.
 *
 * The fixture uses unique identities and the already-created internal workspace, so it does not
 * need Shizuku, a SAF provider, or a network connection.  Privileged transport tests remain
 * separately gated by the live-device suites.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeThreadWorkspaceDeviceTest {
    @Test
    fun pickerCommitBindsThreadAndDefaultAtomicallyAndSiblingGrantsSurvive() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val workspaceRepository = WorkspaceRepository(container.db)
        val sibling = saveWorkspace(workspaceRepository, "workspace-b-${fixture.suffix}")

        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = null,
            snapshotId = "snapshot-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "bound thread",
            conversationId = "conversation-${fixture.suffix}",
            at = fixture.now,
        )

        val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val write = CapabilityId(CapabilityId.FILE_WRITE_TEXT)
        val policyVersion = container.agentGrantPort.currentPolicyVersion()
        val siblingGrant = container.agentGrantPort.saveGrant(
            CapabilityGrant(
                grantId = "grant-sibling-${fixture.suffix}",
                agentId = fixture.agentId,
                capability = write,
                workspaceId = sibling.id,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = policyVersion,
                createdAt = fixture.now,
            ),
        )

        val pickerResult = runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(
                agentId = fixture.agentId,
                threadId = conversation.id,
            ),
        )
        val committed = pickerResult as? WorkspaceAccessResult.Success
            ?: error("picker did not return a committed success: $pickerResult")

        assertEquals(RuntimeIntegration.INTERNAL_WORKSPACE_ID, committed.workspace.workspaceId)
        assertTrue(committed.grants.isNotEmpty())
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversation.id)?.workspaceId,
        )
        assertEquals(
            null,
            threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId,
        )

        // The picker transaction for the selected workspace must not retire a sibling workspace.
        val grantsAfterPicker = container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = false)
        assertTrue(grantsAfterPicker.any { it.grantId == siblingGrant.grantId && !it.revoked })
        assertTrue(grantsAfterPicker.any { it.workspaceId == RuntimeIntegration.INTERNAL_WORKSPACE_ID })
    }

    @Test
    fun agentDefaultOnlySeedsNewThreadAndHotGrantUpdateCannotChangeFrozenRun() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val workspaceRepository = WorkspaceRepository(container.db)
        val workspaceB = saveWorkspace(workspaceRepository, "workspace-default-b-${fixture.suffix}")
        val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val write = CapabilityId(CapabilityId.FILE_WRITE_TEXT)
        val policyVersion = container.agentGrantPort.currentPolicyVersion()

        container.agentGrantPort.saveGrant(
            CapabilityGrant(
                grantId = "grant-default-a-${fixture.suffix}",
                agentId = fixture.agentId,
                capability = read,
                workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = policyVersion,
                createdAt = fixture.now,
            ),
        )
        container.agentGrantPort.saveGrant(
            CapabilityGrant(
                grantId = "grant-default-b-${fixture.suffix}",
                agentId = fixture.agentId,
                capability = write,
                workspaceId = workspaceB.id,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = policyVersion,
                createdAt = fixture.now,
            ),
        )

        val oldSnapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = null,
            snapshotId = "snapshot-old-${fixture.suffix}",
            at = fixture.now,
        )
        val oldConversation = container.conversations.create(
            snapshotId = oldSnapshot.id,
            title = "old thread",
            conversationId = "conversation-old-${fixture.suffix}",
            at = fixture.now,
        )
        val oldBinding = threadPort.bindConversationWorkspace(
            ConversationWorkspaceBinding(
                sessionId = oldConversation.id,
                workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
                boundAt = fixture.now,
            ),
        )
        val defaultA = threadPort.saveAgentWorkspaceDefault(
            AgentWorkspaceDefault(
                agentId = fixture.agentId,
                workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
                updatedAt = fixture.now,
            ),
        )

        val frozenBeforeUpdate = runtime.createToolExecutionContextForWorkspace(
            snapshot = oldSnapshot,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            modelCallId = "model-call-old-${fixture.suffix}",
            sessionIdentity = oldConversation.id,
            taskIdentity = "task-old-${fixture.suffix}",
            configSnapshotHash = "config-old-${fixture.suffix}",
        )
        assertEquals(setOf(read), frozenBeforeUpdate.canonicalGrants.map { it.capability }.toSet())

        val defaultB = threadPort.saveAgentWorkspaceDefault(
            defaultA.copy(
                workspaceId = workspaceB.id,
                revision = defaultA.revision + 1L,
                updatedAt = "${fixture.now}-changed",
            ),
        )
        assertEquals(workspaceB.id, defaultB.workspaceId)
        assertEquals(oldBinding, threadPort.conversationWorkspaceBinding(oldConversation.id))

        // Add a capability after the old context was frozen.  A new run can see it; the old one
        // must retain exactly the capability set it captured at its own preflight boundary.
        val lateGrant = container.agentGrantPort.saveGrant(
            CapabilityGrant(
                grantId = "grant-late-${fixture.suffix}",
                agentId = fixture.agentId,
                capability = write,
                workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = policyVersion,
                createdAt = fixture.now,
            ),
        )
        val freshContext = runtime.createToolExecutionContextForWorkspace(
            snapshot = oldSnapshot,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            modelCallId = "model-call-fresh-${fixture.suffix}",
            sessionIdentity = oldConversation.id,
            taskIdentity = "task-fresh-${fixture.suffix}",
            configSnapshotHash = "config-fresh-${fixture.suffix}",
        )
        assertEquals(setOf(read, write), freshContext.canonicalGrants.map { it.capability }.toSet())
        assertTrue(freshContext.canonicalGrants.any { it.grantId == lateGrant.grantId })
        assertEquals(setOf(read), frozenBeforeUpdate.canonicalGrants.map { it.capability }.toSet())

        // A new thread may resolve the changed default, while an existing thread stays bound to A.
        assertEquals(workspaceB.id, threadPort.resolveNewThreadWorkspace(fixture.agentId))
        assertEquals(RuntimeIntegration.INTERNAL_WORKSPACE_ID, threadPort.conversationWorkspaceBinding(oldConversation.id)?.workspaceId)
        assertThrows(IllegalArgumentException::class.java) {
            runtime.createToolExecutionContextForWorkspace(
                snapshot = oldSnapshot,
                workspaceId = workspaceB.id,
                modelCallId = "model-call-wrong-workspace-${fixture.suffix}",
                sessionIdentity = oldConversation.id,
                taskIdentity = "task-wrong-${fixture.suffix}",
                configSnapshotHash = "config-wrong-${fixture.suffix}",
            )
        }

        val newSnapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = workspaceB.id,
            snapshotId = "snapshot-new-${fixture.suffix}",
            at = fixture.now,
        )
        val newBindings = container.agentGrantPort.listSnapshotBindings(newSnapshot.id)
        assertTrue(newBindings.isNotEmpty())
        assertTrue(newBindings.all { it.workspaceId == workspaceB.id })
    }

    @Test
    fun unboundThreadDoesNotInheritDefaultOrExposeWorkspaceGrant() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val workspaceRepository = WorkspaceRepository(container.db)
        val workspace = saveWorkspace(workspaceRepository, "workspace-unbound-${fixture.suffix}")
        val policyVersion = container.agentGrantPort.currentPolicyVersion()
        container.agentGrantPort.saveGrant(
            CapabilityGrant(
                grantId = "grant-unbound-${fixture.suffix}",
                agentId = fixture.agentId,
                capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                workspaceId = workspace.id,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = policyVersion,
                createdAt = fixture.now,
            ),
        )
        threadPort.saveAgentWorkspaceDefault(
            AgentWorkspaceDefault(
                agentId = fixture.agentId,
                workspaceId = workspace.id,
                updatedAt = fixture.now,
            ),
        )

        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = null,
            snapshotId = "snapshot-unbound-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "unbound thread",
            conversationId = "conversation-unbound-${fixture.suffix}",
            at = fixture.now,
        )
        assertEquals(null, threadPort.conversationWorkspaceBinding(conversation.id))

        val context = runtime.createToolExecutionContextForWorkspace(
            snapshot = snapshot,
            workspaceId = null,
            modelCallId = "model-call-unbound-${fixture.suffix}",
            sessionIdentity = conversation.id,
            taskIdentity = "task-unbound-${fixture.suffix}",
            configSnapshotHash = "config-unbound-${fixture.suffix}",
        )
        assertTrue(context.canonicalGrants.none { it.workspaceId != null })
        assertTrue(context.snapshotGrantBindings.none { it.workspaceId != null })
        assertTrue(context.effectiveCapabilities.none { it.value.startsWith("file.") })
        val exposure = runtime.toolExposureDiagnostics(context)
        assertEquals(0, exposure.grantedWorkspaceCount)
        assertEquals(0, exposure.boundWorkspaceCount)
        assertEquals(0, exposure.effectiveAgentWorkspaceCapabilityCount)
        val factory = runtime.createToolExecutorFactory(context)
        assertEquals(0, factory.exposureSummary.ownerToolCounts["workspace"] ?: 0)
    }

    @Test
    fun deferredAgentDefaultAttachesWithoutGrantUntilDraftCommit() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val beforeGrants = container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = true)
        val deferred = WorkspaceIntent.SET_AGENT_DEFAULT.plan(WorkspaceTarget())
        val attached = runtime.useRecent(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            plan = deferred,
            target = WorkspaceTarget(),
        )
        val success = attached as? WorkspaceAccessResult.Success
            ?: error("deferred SET_AGENT_DEFAULT must attach the workspace without requiring an Agent: $attached")
        assertEquals(RuntimeIntegration.INTERNAL_WORKSPACE_ID, success.workspace.workspaceId)
        assertTrue(success.grants.isEmpty())
        assertEquals(beforeGrants, container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = true))
        assertEquals(null, threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId)

        val committed = runtime.commitDraft(
            WorkspaceDraft(
                workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
                displayName = "应用私有工作区",
                setAsAgentDefault = true,
            ),
            fixture.agentId,
        ) as? WorkspaceAccessResult.Success
            ?: error("draft commit did not succeed")
        assertTrue(committed.grants.isNotEmpty())
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId,
        )
    }

    @Test
    fun setAgentDefaultThenNewThreadFreezesBindingAndExposesWorkspaceTools() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val committed = runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(agentId = fixture.agentId),
        ) as? WorkspaceAccessResult.Success
            ?: error("SET_AGENT_DEFAULT did not commit")
        assertEquals(RuntimeIntegration.INTERNAL_WORKSPACE_ID, committed.workspace.workspaceId)
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId,
        )
        val chat = ChatViewModel(fixture.app, SavedStateHandle())
        chat.selectAgent(fixture.agentId)
        val conversationId = requireNotNull(chat.newSession()) { "newSession must inherit the Agent default" }
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversationId)?.workspaceId,
        )
        val conversation = requireNotNull(container.conversations.get(conversationId))
        val snapshot = requireNotNull(container.agents.getSnapshot(conversation.snapshotId))
        assertBoundWorkspaceTools(
            runtime = runtime,
            snapshot = snapshot,
            conversationId = conversationId,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            suffix = fixture.suffix,
        )
    }

    @Test
    fun newAgentEditorDraftSaveThenNewSessionExposesWorkspaceTools() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val container = app.container
        val providerId = "provider-draft-$suffix"
        val modelId = "model-draft-$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Draft workspace fixture",
                apiFormat = runtime.mobileagent.domain.ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-draft-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-draft-model",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        val agentsBefore = container.agents.list().map { it.id }.toSet()
        val vm = AgentsViewModel(app, SavedStateHandle())
        vm.openEditor(null)
        val editor = requireNotNull(vm.state.value.editor)
        vm.edit(editor.copy(name = "Draft workspace agent", chatModelId = modelId, prompt = "Use the selected workspace."))
        vm.stageWorkspaceDraft(
            WorkspaceDraft(
                workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
                displayName = "应用私有工作区",
                setAsAgentDefault = true,
            ),
        )
        assertNotNull(vm.pendingWorkspaceDraft())
        assertTrue(vm.save())
        val agentId = requireNotNull(vm.state.value.selectedAgentId)
        assertFalse(agentId in agentsBefore)
        assertNull(vm.pendingWorkspaceDraft())
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            container.threadWorkspacePort.agentWorkspaceDefault(agentId)?.workspaceId,
        )
        assertTrue(
            container.agentGrantPort.listGrants(agentId, includeRevoked = false)
                .any { it.workspaceId == RuntimeIntegration.INTERNAL_WORKSPACE_ID },
        )

        val chat = ChatViewModel(app, SavedStateHandle())
        chat.selectAgent(agentId)
        val conversationId = requireNotNull(chat.newSession())
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            container.threadWorkspacePort.conversationWorkspaceBinding(conversationId)?.workspaceId,
        )
        val conversation = requireNotNull(container.conversations.get(conversationId))
        val snapshot = requireNotNull(container.agents.getSnapshot(conversation.snapshotId))
        assertBoundWorkspaceTools(
            runtime = container.runtimeIntegration,
            snapshot = snapshot,
            conversationId = conversationId,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            suffix = suffix,
        )
    }

    @Test
    fun cancelEditorDropsDraftWithoutCreatingAgentOrGrant() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val agentsBefore = app.container.agents.list().map { it.id }.toSet()
        val vm = AgentsViewModel(app, SavedStateHandle())
        vm.openEditor(null)
        vm.stageWorkspaceDraft(
            WorkspaceDraft(
                workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
                displayName = "应用私有工作区",
                setAsAgentDefault = true,
            ),
        )
        vm.closeEditor()
        assertNull(vm.pendingWorkspaceDraft())
        assertEquals(agentsBefore, app.container.agents.list().map { it.id }.toSet())
    }

    @Test
    fun failedDraftCommitRollsBackNewAgent() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val container = app.container
        val providerId = "provider-rollback-$suffix"
        val modelId = "model-rollback-$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Rollback workspace fixture",
                apiFormat = runtime.mobileagent.domain.ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-rollback-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-rollback-model",
                capabilities = setOf("stream"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        val agentsBefore = container.agents.list().map { it.id }.toSet()
        val vm = AgentsViewModel(app, SavedStateHandle())
        vm.openEditor(null)
        val editor = requireNotNull(vm.state.value.editor)
        vm.edit(editor.copy(name = "Rollback workspace agent", chatModelId = modelId, prompt = "Use the selected workspace."))
        vm.stageWorkspaceDraft(
            WorkspaceDraft(
                workspaceId = "ws-missing-$suffix",
                displayName = "Download",
                setAsAgentDefault = true,
            ),
        )
        assertFalse(vm.save())
        assertEquals(agentsBefore, container.agents.list().map { it.id }.toSet())
        assertNotNull(vm.pendingWorkspaceDraft())
    }

    @Test
    fun threadWorkspaceChangeDoesNotRewriteAgentDefault() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val other = runtime.registerAppPrivateWorkspace(
            workspaceId = "workspace-thread-switch-${fixture.suffix}",
            root = File(fixture.app.filesDir, "agent-workspace-switch-${fixture.suffix}").toPath(),
        )
        runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(agentId = fixture.agentId),
        )
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId,
        )
        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            snapshotId = "snapshot-thread-switch-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "switch thread",
            conversationId = "conversation-thread-switch-${fixture.suffix}",
            at = fixture.now,
        )
        val firstBind = runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(
                agentId = fixture.agentId,
                threadId = conversation.id,
            ),
        )
        assertTrue("unbound thread may bind once: $firstBind", firstBind is WorkspaceAccessResult.Success)
        val switched = runtime.useRecentWorkspace(
            workspaceId = other.id,
            target = WorkspacePickerTarget(
                agentId = fixture.agentId,
                threadId = conversation.id,
            ),
        )
        val required = switched as? WorkspaceAccessResult.NewThreadRequired
            ?: error("bound thread must not be rewritten: $switched")
        assertEquals(conversation.id, required.currentThreadId)
        assertEquals(other.id, required.requestedWorkspaceId)
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversation.id)?.workspaceId,
        )
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId,
        )
        // Zero-side-effect: before confirmation, Agent has 0 grants for other.id
        assertEquals(0, container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = false).count { it.workspaceId == other.id })
        assertTrue(required.requiresGrantCommit)

        val confirmed = runtime.confirmNewThreadWorkspace(required)
        assertTrue("confirmation succeeds: $confirmed", confirmed is WorkspaceAccessResult.Success)
        assertTrue(container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = false).any { it.workspaceId == other.id })

        val chat = ChatViewModel(fixture.app, SavedStateHandle())
        chat.selectAgent(fixture.agentId)
        val newConversationId = requireNotNull(chat.newSession(other.id))
        assertEquals(other.id, threadPort.conversationWorkspaceBinding(newConversationId)?.workspaceId)
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversation.id)?.workspaceId,
        )
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId,
        )
        val newConversation = requireNotNull(container.conversations.get(newConversationId))
        val newSnapshot = requireNotNull(container.agents.getSnapshot(newConversation.snapshotId))
        assertTrue(container.agentGrantPort.listSnapshotBindings(newSnapshot.id).all { it.workspaceId == other.id })
        assertBoundWorkspaceTools(
            runtime = runtime,
            snapshot = snapshot,
            conversationId = conversation.id,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            suffix = "${fixture.suffix}-old",
        )
        assertBoundWorkspaceTools(
            runtime = runtime,
            snapshot = newSnapshot,
            conversationId = newConversationId,
            workspaceId = other.id,
            suffix = "${fixture.suffix}-new",
        )
    }

    @Test
    fun cancelDoesNotPersistDurableGrant() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val other = runtime.registerAppPrivateWorkspace(
            workspaceId = "workspace-cancel-${fixture.suffix}",
            root = File(fixture.app.filesDir, "agent-workspace-cancel-${fixture.suffix}").toPath(),
        )
        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            snapshotId = "snapshot-cancel-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "cancel thread",
            conversationId = "conversation-cancel-${fixture.suffix}",
            at = fixture.now,
        )
        runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )
        val initialSnapshots = container.agents.listSnapshots(fixture.agentId).size
        val initialConversations = container.conversations.list().size
        val initialDefault = threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId

        // Select W2 -> NewThreadRequired
        val switched = runtime.useRecentWorkspace(
            workspaceId = other.id,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )
        val required = switched as? WorkspaceAccessResult.NewThreadRequired
            ?: error("expected NewThreadRequired: $switched")
        assertTrue(required.requiresGrantCommit)

        // User cancels: no confirm is called
        // Assert: Thread remains W1
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversation.id)?.workspaceId,
        )
        // Agent default unchanged
        assertEquals(
            initialDefault,
            threadPort.agentWorkspaceDefault(fixture.agentId)?.workspaceId,
        )
        // Agent W2 grant count remains 0
        assertEquals(
            0,
            container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = false).count { it.workspaceId == other.id },
        )
        // No snapshot or conversation created for W2
        assertEquals(initialSnapshots, container.agents.listSnapshots(fixture.agentId).size)
        assertEquals(initialConversations, container.conversations.list().size)
    }

    @Test
    fun confirmReusesExistingGrantsWithoutDuplicates() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val other = runtime.registerAppPrivateWorkspace(
            workspaceId = "workspace-reuse-${fixture.suffix}",
            root = File(fixture.app.filesDir, "agent-workspace-reuse-${fixture.suffix}").toPath(),
        )

        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            snapshotId = "snapshot-reuse-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "reuse thread",
            conversationId = "conversation-reuse-${fixture.suffix}",
            at = fixture.now,
        )
        runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )

        // First attempt: not yet granted -> REQUIRES_CONFIRMATION_COMMIT
        val switched1 = runtime.useRecentWorkspace(
            workspaceId = other.id,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )
        val required1 = switched1 as? WorkspaceAccessResult.NewThreadRequired
            ?: error("expected NewThreadRequired: $switched1")
        assertEquals(NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT, required1.authorizationState)
        assertTrue(required1.requiresGrantCommit)

        // First confirmation commits the capability grants
        val confirmed1 = runtime.confirmNewThreadWorkspace(required1)
        assertTrue("first confirmation succeeds: $confirmed1", confirmed1 is WorkspaceAccessResult.Success)
        val initialGrants = container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = false)
            .filter { it.workspaceId == other.id }
        assertTrue(initialGrants.isNotEmpty())

        // Second attempt: already granted -> ALREADY_GRANTED
        val switched2 = runtime.useRecentWorkspace(
            workspaceId = other.id,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )
        val required2 = switched2 as? WorkspaceAccessResult.NewThreadRequired
            ?: error("expected NewThreadRequired: $switched2")
        assertEquals(NewThreadAuthorizationState.ALREADY_GRANTED, required2.authorizationState)
        assertFalse(required2.requiresGrantCommit)

        // Second confirmation reuses existing active grants without duplicating
        val confirmed2 = runtime.confirmNewThreadWorkspace(required2)
        assertTrue("second confirmation succeeds: $confirmed2", confirmed2 is WorkspaceAccessResult.Success)

        val afterGrants = container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = false)
            .filter { it.workspaceId == other.id }
        assertEquals("grant count must not duplicate", initialGrants.size, afterGrants.size)
    }

    @Test
    fun staleConfirmationFailsClosed() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val other = runtime.registerAppPrivateWorkspace(
            workspaceId = "workspace-stale-${fixture.suffix}",
            root = File(fixture.app.filesDir, "agent-workspace-stale-${fixture.suffix}").toPath(),
        )
        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            snapshotId = "snapshot-stale-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "stale thread",
            conversationId = "conversation-stale-${fixture.suffix}",
            at = fixture.now,
        )
        runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )

        val switched = runtime.useRecentWorkspace(
            workspaceId = other.id,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )
        val required = switched as? WorkspaceAccessResult.NewThreadRequired
            ?: error("expected NewThreadRequired: $switched")

        // Case A: current thread binding changes (e.g. wrong currentWorkspaceId)
        val staleWrongWorkspace = runtime.confirmNewThreadWorkspace(
            agentId = required.agentId,
            currentThreadId = required.currentThreadId,
            currentWorkspaceId = "wrong-workspace-id",
            requestedWorkspaceId = required.requestedWorkspaceId,
        )
        assertTrue(
            "stale workspace fails closed: $staleWrongWorkspace",
            staleWrongWorkspace is WorkspaceAccessResult.Failure &&
                staleWrongWorkspace.code == WorkspaceAccessErrorCode.CONFLICT,
        )

        // Case B: non-existent agent
        val staleWrongAgent = runtime.confirmNewThreadWorkspace(
            agentId = "non-existent-agent-id",
            currentThreadId = required.currentThreadId,
            currentWorkspaceId = required.currentWorkspaceId,
            requestedWorkspaceId = required.requestedWorkspaceId,
        )
        assertTrue(
            "stale agent fails closed: $staleWrongAgent",
            staleWrongAgent is WorkspaceAccessResult.Failure &&
                staleWrongAgent.code == WorkspaceAccessErrorCode.CONFLICT,
        )
    }

    @Test
    fun authorityUnavailableOnConfirmationFailsClosed() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            snapshotId = "snapshot-auth-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "auth thread",
            conversationId = "conversation-auth-${fixture.suffix}",
            at = fixture.now,
        )
        runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )

        // Create a privileged workspace in repository
        val privilegedWs = WorkspaceRepository(container.db).save(
            Workspace(
                id = "workspace-shizuku-${fixture.suffix}",
                displayName = "Shizuku Privileged",
                backendType = WorkspaceBackendType.PRIVILEGED,
                rootReference = "authority:SHIZUKU",
                readable = true,
                writable = true,
                quotaBytes = 4L * 1024L * 1024L,
                maxFileBytes = 256L * 1024L,
                enabled = true,
                scope = WorkspaceScope.SELECTED_DIRECTORY,
            ),
        )
        // Select authority NONE
        runtime.selectAuthority(Authority.NONE)
        val confirmResult = runtime.confirmNewThreadWorkspace(
            agentId = fixture.agentId,
            currentThreadId = conversation.id,
            currentWorkspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            requestedWorkspaceId = privilegedWs.id,
        )
        assertTrue(
            "authority unavailable on confirm fails closed: $confirmResult",
            confirmResult is WorkspaceAccessResult.Failure &&
                (confirmResult.code == WorkspaceAccessErrorCode.AUTHORITY_NOT_SELECTED ||
                    confirmResult.code == WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE),
        )
    }

    @Test
    fun unboundThreadCanBindOnceAndThenStaysImmutable() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val threadPort = container.threadWorkspacePort
        val snapshot = runtime.createSnapshotWithWorkspace(
            agentId = fixture.agentId,
            workspaceId = null,
            snapshotId = "snapshot-unbound-first-${fixture.suffix}",
            at = fixture.now,
        )
        val conversation = container.conversations.create(
            snapshotId = snapshot.id,
            title = "unbound first bind",
            conversationId = "conversation-unbound-first-${fixture.suffix}",
            at = fixture.now,
        )
        assertEquals(null, threadPort.conversationWorkspaceBinding(conversation.id))
        val bound = runtime.useRecentWorkspace(
            workspaceId = RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )
        assertTrue(bound is WorkspaceAccessResult.Success)
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversation.id)?.workspaceId,
        )
        val other = saveWorkspace(WorkspaceRepository(container.db), "workspace-after-first-${fixture.suffix}")
        val second = runtime.useRecentWorkspace(
            workspaceId = other.id,
            target = WorkspacePickerTarget(agentId = fixture.agentId, threadId = conversation.id),
        )
        assertTrue(second is WorkspaceAccessResult.NewThreadRequired)
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversation.id)?.workspaceId,
        )
    }

    @Test
    fun privilegedBrowseWithWrongSelectionNeverFallsBackOrRevokesGrants() = runBlocking {
        val fixture = fixture()
        val container = fixture.app.container
        val runtime = container.runtimeIntegration
        val before = container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = true)
        val previous = runtime.snapshot().selectedAuthority
        try {
            runtime.selectAuthority(Authority.NONE)
            val result = runtime.browsePrivilegedRoot(Authority.SHIZUKU)
            assertTrue(result is WorkspaceResult.Failure)
            assertEquals(
                ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED,
                (result as WorkspaceResult.Failure).error.code,
            )
            assertEquals(before, container.agentGrantPort.listGrants(fixture.agentId, includeRevoked = true))
        } finally {
            if (previous != Authority.NONE) runCatching { runtime.selectAuthority(previous) }
        }
    }

    private data class Fixture(
        val app: MobileAgentApp,
        val agentId: String,
        val suffix: String,
        val now: String,
    )

    private fun fixture(): Fixture {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val container = app.container
        val providerId = "provider-thread-workspace-$suffix"
        val modelId = "model-thread-workspace-$suffix"
        val agentId = "agent-thread-workspace-$suffix"
        container.profiles.createProvider(
            ProviderProfile(
                id = providerId,
                name = "Thread workspace fixture",
                apiFormat = runtime.mobileagent.domain.ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-thread-workspace-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = modelId,
                providerId = providerId,
                role = ModelRole.CHAT,
                modelId = "fixture-thread-workspace-model",
                capabilities = setOf("stream"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Thread workspace fixture",
                promptRevisionId = "pending",
                chatProfileId = modelId,
                revision = 0,
            ),
            "Use the selected workspace.",
        )
        return Fixture(app, agentId, suffix, Utc.nowIso())
    }

    private fun assertBoundWorkspaceTools(
        runtime: RuntimeIntegration,
        snapshot: runtime.mobileagent.domain.AgentSnapshot,
        conversationId: String,
        workspaceId: String,
        suffix: String,
    ) {
        val context = runtime.createToolExecutionContextForWorkspace(
            snapshot = snapshot,
            workspaceId = workspaceId,
            modelCallId = "model-call-bound-$suffix",
            sessionIdentity = conversationId,
            taskIdentity = "task-bound-$suffix",
            configSnapshotHash = "config-bound-$suffix",
        )
        assertTrue(context.canonicalGrants.any { it.workspaceId == workspaceId })
        assertTrue(context.snapshotGrantBindings.all { it.workspaceId == workspaceId })
        val exposure = runtime.toolExposureDiagnostics(context)
        assertTrue("binding persisted but grantedWorkspaceCount=0", exposure.grantedWorkspaceCount >= 1)
        assertTrue("binding persisted but boundWorkspaceCount=0", exposure.boundWorkspaceCount >= 1)
        assertTrue(exposure.effectiveAgentWorkspaceCapabilityCount > 0)
        val factory = runtime.createToolExecutorFactory(context)
        val workspaceToolCount = factory.exposureSummary.ownerToolCounts["workspace"] ?: 0
        assertTrue("binding persisted but workspaceToolCount=0", workspaceToolCount > 0)
        assertTrue(factory.toolingSpecs.any { it.name == "workspace_list" || it.name.startsWith("file_") })
    }

    private fun saveWorkspace(repository: WorkspaceRepository, id: String): Workspace = repository.save(
        Workspace(
            id = id,
            displayName = "Fixture workspace $id",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = "fixture-root-$id",
            readable = true,
            writable = true,
            quotaBytes = 4L * 1024L * 1024L,
            maxFileBytes = 256L * 1024L,
            enabled = true,
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        ),
    )
}
