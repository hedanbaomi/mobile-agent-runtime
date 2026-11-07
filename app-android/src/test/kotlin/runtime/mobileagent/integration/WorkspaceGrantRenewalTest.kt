// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.workspace.WorkspaceGrantSet

class WorkspaceGrantRenewalTest {
    @Test
    fun canonicalDefaultGrantPlanTracksReadOnlyBackendAndNeverIncludesShell() {
        val readOnly = setOf(
            CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
            CapabilityId(CapabilityId.FILE_LIST),
            CapabilityId(CapabilityId.FILE_STAT),
            CapabilityId(CapabilityId.FILE_READ_TEXT),
        )
        assertEquals(readOnly, canonicalDefaultWorkspaceGrantCapabilities(readOnly))
        val writable = readOnly + setOf(
            CapabilityId(CapabilityId.FILE_WRITE_TEXT),
            CapabilityId(CapabilityId.FILE_DELETE),
            CapabilityId("file.apply_patch"),
        )
        assertEquals(readOnly, canonicalDefaultWorkspaceGrantCapabilities(writable))
        assertEquals(writable, canonicalFullDeviceWorkspaceGrantCapabilities(writable))
        assertEquals(
            readOnly,
            canonicalDefaultWorkspaceGrantCapabilities(
                readOnly + setOf(
                    CapabilityId(CapabilityId.SHELL_EXECUTE),
                    CapabilityId("file.future_mutation"),
                ),
            ),
        )
    }

    @Test
    fun renewalKeepsOnlyExistingUnrevokedWorkspaceCapabilities() {
        fun grant(id: String, capability: String) = CapabilityGrant(
            grantId = id,
            agentId = "agent-one",
            workspaceId = "workspace-one",
            capability = CapabilityId(capability),
            policyVersion = 1,
            createdAt = "2026-09-25T00:00:00Z",
        )
        val grants = listOf(
            grant("read", CapabilityId.FILE_READ_TEXT),
            grant("write", CapabilityId.FILE_WRITE_TEXT).copy(revokedAt = "2026-09-25T01:00:00Z"),
            grant("legacy-write", CapabilityId.FILE_WRITE_TEXT).copy(policyVersion = 0),
            grant("other-workspace", CapabilityId.FILE_DELETE).copy(workspaceId = "workspace-two"),
            grant("other-agent", CapabilityId.FILE_LIST).copy(agentId = "agent-two"),
            grant("scoped", CapabilityId.FILE_STAT).copy(pathScope = "books"),
            grant("skill", CapabilityId.FILE_CREATE_DIRECTORY).copy(skillInstallId = "skill-one"),
            grant("expired", CapabilityId.WORKSPACE_ENUMERATE).copy(expiresAt = "2026-09-25T01:00:00Z"),
            grant("once", CapabilityId.FILE_DELETE).copy(lifetime = GrantLifetime.ONCE),
        )
        assertEquals(
            setOf(CapabilityId(CapabilityId.FILE_READ_TEXT)),
            WorkspaceGrantSet("agent-one", "workspace-one", grants)
                .renewableGrants(2, Instant.parse("2026-09-25T02:00:00Z")).map { it.capability }.toSet(),
        )
    }

    @Test
    fun inactiveLatestGenerationNeverRestoresOlderWrites() {
        val now = Instant.parse("2026-09-25T02:00:00Z")
        val old = CapabilityGrant(
            grantId = "old-write", agentId = "agent-one", workspaceId = "workspace-one",
            capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT), policyVersion = 1,
        )
        val latest = old.copy(grantId = "last-write", policyVersion = 2)
        val inactive = listOf(
            latest.copy(revokedAt = now.toString()), latest.copy(expiresAt = now.toString()),
            latest.copy(expiresAt = "invalid-expiry"),
        )
        for (row in inactive) {
            val bundle = WorkspaceGrantSet("agent-one", "workspace-one", listOf(old, row))
            assertEquals(emptyList<CapabilityGrant>(), bundle.renewableGrants(3, now))
        }
    }

    @Test
    fun renewalUsesOnlyMatchingScopeAndPreservesTheEffectiveExpiry() {
        val now = Instant.parse("2026-09-25T02:00:00Z")
        val first = CapabilityGrant(
            grantId = "read", agentId = "a", workspaceId = "w", capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
            expiresAt = "2026-09-25T03:00:00Z", policyVersion = 1,
        )
        val longer = first.copy(grantId = "longer", expiresAt = "2026-09-24T23:00:00-06:00")
        val foreign = first.copy(policyVersion = 9)
        val excluded = listOf(
            foreign.copy(pathScope = "books"), foreign.copy(agentId = "b"), foreign.copy(workspaceId = "x"),
            foreign.copy(skillInstallId = "skill"), foreign.copy(packageHash = "a".repeat(64)),
            foreign.copy(lifetime = GrantLifetime.ONCE), foreign.copy(lifetime = GrantLifetime.ONCE, consumedAt = now.toString()),
            foreign.copy(lifetime = GrantLifetime.TASK, taskId = "task"), foreign.copy(lifetime = GrantLifetime.SESSION, sessionId = "session"),
        )
        fun renew(rows: List<CapabilityGrant>) = WorkspaceGrantSet("a", "w", rows).renewableGrants(2, now)
        val rows = excluded + listOf(first, longer)
        assertEquals(listOf(longer), renew(rows))
        val unlimited = first.copy(grantId = "unlimited", expiresAt = null)
        assertEquals(listOf(unlimited), renew(rows + unlimited))
        assertEquals(emptyList<CapabilityGrant>(), renew(rows + foreign))
    }
}
