// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.feature.agents.AgentEditorUi
import runtime.mobileagent.feature.agents.AgentGrantUi
import runtime.mobileagent.feature.agents.AgentWorkspaceAccessPreset
import runtime.mobileagent.feature.agents.AgentWorkspaceGrantPresetUi
import runtime.mobileagent.feature.agents.AgentWorkspaceUi

class AgentWorkspaceGrantPresetTest {
    @Test
    fun stalePolicyGrantsDoNotSuppressCurrentPolicyPreset() {
        val capabilities = listOf(
            CapabilityId.WORKSPACE_ENUMERATE,
            CapabilityId.FILE_LIST,
            CapabilityId.FILE_STAT,
            CapabilityId.FILE_READ_TEXT,
        )
        val old = capabilities.mapIndexed { index, capability ->
            AgentGrantUi(
                grant = CapabilityGrant(
                    grantId = "old-$index",
                    agentId = "agent-one",
                    capability = CapabilityId(capability),
                    workspaceId = "workspace-one",
                    lifetime = GrantLifetime.PERSISTENT,
                    policyVersion = 1,
                    createdAt = "2026-09-25T00:00:00Z",
                ),
                // Exercise the persistence guard even if a stale UI projection
                // incorrectly still calls this row enabled.
                enabled = true,
            )
        }
        val editor = AgentEditorUi(
            workspaces = listOf(
                AgentWorkspaceUi(
                    id = "workspace-one",
                    displayName = "Workspace",
                    backendType = WorkspaceBackendType.INTERNAL,
                    readable = true,
                    writable = true,
                    quotaBytes = null,
                    maxFileBytes = 1024,
                    enabled = true,
                    revision = 1,
                ),
            ),
            grants = old,
            workspaceGrantPreset = AgentWorkspaceGrantPresetUi(
                workspaceId = "workspace-one",
                access = AgentWorkspaceAccessPreset.READ_ONLY,
            ),
        )
        val port = object : AgentGrantPort {
            override val available = true
            override fun currentPolicyVersion() = 2L
            override fun saveGrant(grant: CapabilityGrant) = grant
        }

        val renewed = saveAgentWorkspaceGrantPreset(editor, "agent-one", port)
        assertEquals(capabilities.toSet(), renewed.map { it.capability.value }.toSet())
        assertEquals(setOf(2L), renewed.map { it.policyVersion }.toSet())
        assertEquals(setOf("workspace-one"), renewed.map { it.workspaceId }.toSet())
    }
}
