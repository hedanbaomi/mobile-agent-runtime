// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

class WorkspaceAccessCompletionTest {
    @Test
    fun committedWorkspaceUsesThePersistedGrantBundleWithoutARepositoryProjection() {
        val workspace = Workspace(
            id = "saf-workspace-fixture",
            displayName = "用户工作区 2",
            backendType = WorkspaceBackendType.SAF_TREE,
            rootReference = "content://provider/opaque-document-id",
            readable = true,
            writable = true,
            revision = 3L,
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        )
        val grants = listOf(
            CapabilityGrant(
                grantId = "grant-read-fixture",
                agentId = "agent-fixture",
                capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
                workspaceId = workspace.id,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = 4L,
                revision = 2L,
            ),
            CapabilityGrant(
                grantId = "grant-write-fixture",
                agentId = "agent-fixture",
                capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT),
                workspaceId = workspace.id,
                lifetime = GrantLifetime.PERSISTENT,
                policyVersion = 4L,
                revision = 5L,
            ),
        )

        val item = committedWorkspaceAccessItem(
            workspace = workspace,
            displayName = workspace.displayName,
            status = WorkspaceAccessStatus.ACTIVE,
            authority = null,
            activeGrants = grants,
            fullDeviceConfirmationPresent = true,
        )

        assertEquals(workspace.id, item.workspaceId)
        assertEquals(WorkspaceAccessStatus.ACTIVE, item.status)
        assertTrue(item.readable)
        assertTrue(item.writable)
        assertTrue(item.durablyAuthorized)
        assertEquals(
            setOf(
                CapabilityId(CapabilityId.FILE_READ_TEXT),
                CapabilityId(CapabilityId.FILE_WRITE_TEXT),
            ),
            item.grantedCapabilities,
        )
        assertEquals(5L, item.grantRevision)
        assertFalse(item.displayName.contains("content://"))
        assertFalse(item.toString().contains("opaque-document-id"))
    }
}
