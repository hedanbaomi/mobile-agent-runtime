// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime

class WorkspaceGrantRenewalTest {
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
            renewableWorkspaceCapabilities(grants, "agent-one", "workspace-one", Instant.parse("2026-09-25T02:00:00Z")),
        )
    }
}
