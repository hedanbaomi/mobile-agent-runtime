// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime

class EffectiveCapabilityResolverScopeTest {
    @Test fun consumedOnceBaselineOnlyAuthorizesItsAdmittedRequestUntilRevocation() {
        val capability = CapabilityId(CapabilityId.SHELL_EXECUTE)
        val frozen = CapabilityGrant("once", "agent", capability, lifetime = GrantLifetime.ONCE, policyVersion = 1)
        var live = frozen.copy(revision = 2, consumedAt = "2026-10-10T00:00:00Z")
        val binding = runtime.mobileagent.domain.SnapshotGrantBinding("snapshot", "once", capability, policyVersion = 1)
        val context = ToolExecutionContext("agent", "snapshot", "model", "session", configSnapshotHash = "config",
            policyVersion = 1, canonicalGrants = listOf(frozen), snapshotGrantBindings = listOf(binding))
        val resolver = EffectiveCapabilityResolver(grants = CapabilityGrantReader { _, _ -> listOf(live) },
            bindings = SnapshotGrantBindingReader { listOf(binding) })
        assertFalse(resolver.revalidate(context, capability))
        assertTrue(resolver.revalidateInFlight(context, capability, frozen))
        live = live.copy(revision = 3, revokedAt = "2026-10-10T00:00:01Z")
        assertFalse(resolver.revalidateInFlight(context, capability, frozen))
    }

    @Test fun scopedGrantsRequireTheSameScopeAtPreflightRevisionAndDispatch() {
        val capability = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val grant = CapabilityGrant("grant", "agent", capability, lifetime = GrantLifetime.PERSISTENT,
            workspaceId = "workspace", pathScope = "docs", policyVersion = 1, revision = 1)
        val context = ToolExecutionContext("agent", "snapshot", "model", "session",
            configSnapshotHash = "config", policyVersion = 1, canonicalGrants = listOf(grant))
        val resolver = EffectiveCapabilityResolver(grants = CapabilityGrantReader { _, _ -> listOf(grant) })
        for ((workspace, path) in listOf(null to null, "workspace" to null, null to "docs/file", "other" to "docs/file", "workspace" to "docs-sibling/file")) {
            assertFalse(resolver.revalidate(context, capability, workspace, path))
            assertNull(resolver.liveGrantRevision(context, capability, workspaceId = workspace, path = path))
            assertEquals(DispatchAuthorization.DENIED, resolver.authorizeForDispatch(context, capability,
                consumer = { error("persistent grant must not consume") }, workspaceId = workspace, path = path))
        }
        assertTrue(resolver.revalidate(context, capability, "workspace", "docs/file"))
        assertEquals(1L, resolver.liveGrantRevision(context, capability, workspaceId = "workspace", path = "docs/file"))
        assertEquals(DispatchAuthorization.ALLOWED_EXISTING_GRANT, resolver.authorizeForDispatch(context, capability,
            consumer = { error("persistent grant must not consume") }, workspaceId = "workspace", path = "docs/file"))
    }
}
