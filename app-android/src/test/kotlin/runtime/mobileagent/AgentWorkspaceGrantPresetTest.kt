// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun readOnlyPresetRetiresOnlyOrdinaryWholeDirectoryWritesAndDoesNotResurrectThem() {
        fun grant(id: String, capability: String, workspace: String = "workspace-one") = CapabilityGrant(
            grantId = id,
            agentId = "agent-one",
            capability = CapabilityId(capability),
            workspaceId = workspace,
            lifetime = GrantLifetime.PERSISTENT,
            policyVersion = 2,
            createdAt = "2026-09-25T00:00:00Z",
        )
        val rows = linkedMapOf(
            "read" to grant("read", CapabilityId.FILE_READ_TEXT),
            "write" to grant("write", CapabilityId.FILE_WRITE_TEXT),
            "delete" to grant("delete", CapabilityId.FILE_DELETE),
            "patch" to grant("patch", "file.apply_patch"),
            "scoped" to grant("scoped", CapabilityId.FILE_WRITE_TEXT).copy(pathScope = "notes"),
            "once" to grant("once", CapabilityId.FILE_DELETE).copy(lifetime = GrantLifetime.ONCE),
            "skill" to grant("skill", CapabilityId.FILE_WRITE_TEXT).copy(skillInstallId = "skill-one"),
            "sibling" to grant("sibling", CapabilityId.FILE_DELETE, "workspace-two"),
        )
        val port = object : AgentGrantPort {
            override val available = true
            override fun currentPolicyVersion() = 2L
            override fun listGrants(agentId: String, includeRevoked: Boolean) = rows.values
                .filter { includeRevoked || !it.revoked }
            override fun revokeGrant(grantId: String, expectedRevision: Long): CapabilityGrant {
                val old = requireNotNull(rows[grantId])
                require(old.revision == expectedRevision)
                return old.copy(revokedAt = "2026-09-26T00:00:00Z", revision = old.revision + 1)
                    .also { rows[grantId] = it }
            }
            override fun saveGrant(grant: CapabilityGrant): CapabilityGrant = grant.also { rows[it.grantId] = it }
        }
        val editor = AgentEditorUi(
            workspaces = listOf(
                AgentWorkspaceUi(
                    id = "workspace-one", displayName = "Workspace",
                    backendType = WorkspaceBackendType.INTERNAL,
                    readable = true, writable = true, quotaBytes = null,
                    maxFileBytes = 1024, enabled = true, revision = 1,
                ),
            ),
            workspaceGrantPreset = AgentWorkspaceGrantPresetUi("workspace-one", AgentWorkspaceAccessPreset.READ_ONLY),
        )

        saveAgentWorkspaceGrantPreset(editor, "agent-one", port)
        assertEquals(setOf("write", "delete", "patch"), rows.values.filter { it.revoked }.map { it.grantId }.toSet())
        assertEquals(setOf("read", "scoped", "once", "skill", "sibling"),
            rows.values.filter { it.grantId in setOf("read", "scoped", "once", "skill", "sibling") && !it.revoked }
                .map { it.grantId }.toSet())
        val countAfterFirst = rows.size
        assertTrue(saveAgentWorkspaceGrantPreset(editor, "agent-one", port).isEmpty())
        assertEquals(countAfterFirst, rows.size)
        assertTrue(rows.values.none {
            !it.revoked && it.workspaceId == "workspace-one" && it.pathScope == null &&
                it.skillInstallId == null && it.lifetime == GrantLifetime.PERSISTENT &&
                it.capability.value in setOf(CapabilityId.FILE_WRITE_TEXT, CapabilityId.FILE_DELETE, "file.apply_patch")
        })
    }

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
