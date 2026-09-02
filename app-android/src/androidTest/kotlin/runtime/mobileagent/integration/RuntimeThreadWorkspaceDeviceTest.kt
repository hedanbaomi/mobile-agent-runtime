// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import runtime.mobileagent.domain.WorkspaceScope
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
                setAsAgentDefault = true,
                grantCapabilities = setOf(read),
                lifetime = GrantLifetime.PERSISTENT,
            ),
        )
        val committed = pickerResult as? WorkspaceAccessResult.Success
            ?: error("picker did not return a committed success: $pickerResult")

        assertEquals(RuntimeIntegration.INTERNAL_WORKSPACE_ID, committed.workspace.workspaceId)
        assertEquals(setOf(read), committed.workspace.grantedCapabilities)
        assertTrue(committed.grants.any { it.capability == read })
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
            threadPort.conversationWorkspaceBinding(conversation.id)?.workspaceId,
        )
        assertEquals(
            RuntimeIntegration.INTERNAL_WORKSPACE_ID,
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
