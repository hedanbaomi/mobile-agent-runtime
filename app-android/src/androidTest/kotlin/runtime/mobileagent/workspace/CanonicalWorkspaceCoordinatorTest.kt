// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceDraft
import runtime.mobileagent.domain.WorkspaceIntent
import runtime.mobileagent.domain.WorkspaceIntentPlan
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.domain.WorkspaceTarget
import runtime.mobileagent.integration.WorkspaceAccessErrorCode
import runtime.mobileagent.integration.WorkspaceAccessItem
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.integration.WorkspaceAccessStatus
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle

class CanonicalWorkspaceCoordinatorTest {
    @Test
    fun agentSelectionWithoutIdStagesDraftAndDoesNotGrant() = runBlocking {
        val sink = RecordingSink()
        val coordinator = CanonicalWorkspaceCoordinator(sink)
        val outcome = coordinator.selectPrivileged(
            intent = WorkspaceIntent.SET_AGENT_DEFAULT,
            authority = Authority.SHIZUKU,
            request = WorkspaceAttachRequest("ws-draft", "Download", FakeHandle()),
            target = WorkspaceTarget(),
        )
        val staged = outcome as WorkspaceSelectionOutcome.Staged
        assertEquals("ws-draft", staged.draft.workspaceId)
        assertTrue(staged.draft.setAsAgentDefault)
        assertTrue(sink.lastPlan?.deferred == true)
        assertTrue(sink.lastPlan?.grantRequired == false)
    }

    @Test
    fun agentSelectionWithIdSetsDefaultAndGrants() = runBlocking {
        val sink = RecordingSink()
        val coordinator = CanonicalWorkspaceCoordinator(sink)
        val outcome = coordinator.selectPrivileged(
            intent = WorkspaceIntent.SET_AGENT_DEFAULT,
            authority = Authority.SHIZUKU,
            request = WorkspaceAttachRequest("ws-default", "Download", FakeHandle()),
            target = WorkspaceTarget(agentId = "agent-1"),
        )
        assertTrue(outcome is WorkspaceSelectionOutcome.Committed)
        assertTrue(sink.lastPlan?.setAgentDefault == true)
        assertTrue(sink.lastPlan?.grantRequired == true)
        assertTrue(sink.lastPlan?.bindThread == false)
    }

    @Test
    fun threadSelectionNeverSetsAgentDefault() = runBlocking {
        val sink = RecordingSink()
        val coordinator = CanonicalWorkspaceCoordinator(sink)
        coordinator.selectRecent(
            intent = WorkspaceIntent.BIND_THREAD,
            workspaceId = "ws-thread",
            target = WorkspaceTarget(agentId = "agent-1", threadId = "thread-1"),
        )
        assertTrue(sink.lastPlan?.bindThread == true)
        assertTrue(sink.lastPlan?.setAgentDefault == false)
    }

    @Test
    fun commitDraftUsesStagedDefaultFlag() = runBlocking {
        val sink = RecordingSink()
        val coordinator = CanonicalWorkspaceCoordinator(sink)
        val result = coordinator.commitDraft(
            WorkspaceDraft(workspaceId = "ws-draft", displayName = "Download", setAsAgentDefault = true),
            agentId = "agent-new",
        )
        assertTrue(result is WorkspaceSelectionOutcome.Committed)
        assertEquals("agent-new", sink.committedAgentId)
    }

    private class FakeHandle : WorkspaceDirectoryHandle()

    private class RecordingSink : CanonicalWorkspaceSink {
        var lastPlan: WorkspaceIntentPlan? = null
        var committedAgentId: String? = null

        override suspend fun attachPrivileged(
            authority: Authority,
            request: WorkspaceAttachRequest,
            plan: WorkspaceIntentPlan,
            target: WorkspaceTarget,
        ): WorkspaceAccessResult {
            lastPlan = plan
            return success(request.workspaceId, request.displayName)
        }

        override suspend fun attachSaf(
            uri: Uri,
            resultFlags: Int,
            plan: WorkspaceIntentPlan,
            target: WorkspaceTarget,
        ): WorkspaceAccessResult {
            lastPlan = plan
            return success("saf-1", "folder")
        }

        override suspend fun attachPrivilegedPath(
            authority: Authority,
            workspaceId: String,
            displayName: String,
            absolutePath: String,
            plan: WorkspaceIntentPlan,
            target: WorkspaceTarget,
        ): WorkspaceAccessResult {
            lastPlan = plan
            return success(workspaceId, displayName)
        }

        override suspend fun openFullDeviceFiles(
            authority: Authority,
            request: FullDeviceFilesRequest,
            plan: WorkspaceIntentPlan,
            target: WorkspaceTarget,
        ): WorkspaceAccessResult {
            lastPlan = plan
            return WorkspaceAccessResult.Failure(WorkspaceAccessErrorCode.UNSUPPORTED)
        }

        override suspend fun useRecent(
            workspaceId: String,
            plan: WorkspaceIntentPlan,
            target: WorkspaceTarget,
        ): WorkspaceAccessResult {
            lastPlan = plan
            return success(workspaceId, workspaceId)
        }

        override suspend fun commitDraft(draft: WorkspaceDraft, agentId: String): WorkspaceAccessResult {
            committedAgentId = agentId
            return success(draft.workspaceId, draft.displayName)
        }

        private fun success(id: String, name: String) = WorkspaceAccessResult.Success(
            WorkspaceAccessItem(
                workspaceId = id,
                displayName = name,
                backendType = WorkspaceBackendType.INTERNAL,
                scope = WorkspaceScope.SELECTED_DIRECTORY,
                readable = true,
                writable = true,
                status = WorkspaceAccessStatus.ACTIVE,
                durablyAuthorized = true,
            ),
        )
    }
}
