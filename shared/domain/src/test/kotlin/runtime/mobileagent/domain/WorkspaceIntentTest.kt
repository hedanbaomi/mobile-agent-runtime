// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceIntentTest {

    private val agent = WorkspaceTarget(agentId = "agent-1")
    private val thread = WorkspaceTarget(agentId = "agent-1", threadId = "thread-1")
    private val draft = WorkspaceTarget()

    @Test
    fun addToLibraryNeverTouchesDefaultOrThread() {
        val plan = WorkspaceIntent.ADD_TO_LIBRARY.plan(agent)
        assertTrue(plan.grantRequired)
        assertFalse(plan.setAgentDefault)
        assertFalse(plan.bindThread)
        assertFalse(plan.deferred)
    }

    @Test
    fun setAgentDefaultGrantsAndSetsDefault() {
        val plan = WorkspaceIntent.SET_AGENT_DEFAULT.plan(agent)
        assertTrue(plan.grantRequired)
        assertTrue(plan.setAgentDefault)
        assertFalse(plan.bindThread)
        assertFalse(plan.deferred)
    }

    @Test
    fun setAgentDefaultWithoutAgentBecomesDeferredDraft() {
        val plan = WorkspaceIntent.SET_AGENT_DEFAULT.plan(draft)
        assertTrue(plan.deferred)
        assertFalse(plan.grantRequired)
        assertTrue(plan.setAgentDefault)
    }

    @Test
    fun bindThreadNeverChangesAgentDefault() {
        val plan = WorkspaceIntent.BIND_THREAD.plan(thread)
        assertTrue(plan.grantRequired)
        assertTrue(plan.bindThread)
        assertFalse(plan.setAgentDefault)
        assertFalse(plan.deferred)
    }

    @Test
    fun bindThreadRequiresThreadId() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceIntent.BIND_THREAD.plan(agent) }
    }

    @Test
    fun threadTargetRequiresAgent() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceTarget(threadId = "thread-1") }
    }

    @Test
    fun addToLibraryWithoutAgentAttachesOnly() {
        val plan = WorkspaceIntent.ADD_TO_LIBRARY.plan(draft)
        assertFalse(plan.grantRequired)
        assertFalse(plan.setAgentDefault)
        assertFalse(plan.deferred)
    }

    @Test
    fun draftRejectsBlankWorkspaceId() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceDraft(workspaceId = "", displayName = "x") }
    }

    @Test
    fun planRejectsCombinedDefaultAndThread() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceIntentPlan(
                intent = WorkspaceIntent.BIND_THREAD,
                grantRequired = true,
                setAgentDefault = true,
                bindThread = true,
                deferred = false,
            )
        }
    }

    @Test
    fun planRejectsDeferredGrant() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceIntentPlan(
                intent = WorkspaceIntent.SET_AGENT_DEFAULT,
                grantRequired = true,
                setAgentDefault = true,
                bindThread = false,
                deferred = true,
            )
        }
    }

    @Test
    fun everyIntentIsCovered() {
        assertEquals(3, WorkspaceIntent.entries.size)
    }
}
