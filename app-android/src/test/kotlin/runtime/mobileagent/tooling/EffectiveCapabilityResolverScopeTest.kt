// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotGrantBinding

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

    /**
     * A consumed ONCE grant authorizes exactly its own in-flight request: the
     * same workspace, and a search path the grant's own scope covers.  A
     * directory-range grant must never be widened into its parent, and the
     * unscoped legacy form must not admit a scoped row at all.
     */
    @Test fun consumedOnceInFlightRequiresItsExactWorkspaceAndPathScope() {
        val capability = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val frozen = CapabilityGrant(
            grantId = "once-scoped",
            agentId = "agent",
            capability = capability,
            workspaceId = "workspace",
            pathScope = "src",
            lifetime = GrantLifetime.ONCE,
            policyVersion = 1,
        )
        val consumed = frozen.copy(revision = 2, consumedAt = CONSUMED_AT)
        val binding = SnapshotGrantBinding(
            "snapshot", "once-scoped", capability,
            workspaceId = "workspace", pathScope = "src", policyVersion = 1,
        )
        val context = ToolExecutionContext(
            "agent", "snapshot", "model", "session",
            configSnapshotHash = "config",
            policyVersion = 1,
            canonicalGrants = listOf(frozen),
            snapshotGrantBindings = listOf(binding),
        )
        val resolver = EffectiveCapabilityResolver(
            grants = CapabilityGrantReader { _, _ -> listOf(consumed) },
            bindings = SnapshotGrantBindingReader { listOf(binding) },
        )

        // A consumed row is no longer a live authorization ...
        assertFalse(resolver.revalidate(context, capability, "workspace", "src"))
        // ... but the exact in-flight request still is, for the whole subtree.
        assertTrue(resolver.revalidateInFlight(context, capability, frozen, GrantScope("workspace", "src")))
        assertTrue(resolver.revalidateInFlight(context, capability, frozen, GrantScope("workspace", "src/nested/file.txt")))
        // The workspace root is outside a directory-range grant.
        assertFalse(resolver.revalidateInFlight(context, capability, frozen, GrantScope("workspace", "")))
        // Another workspace, a sibling name and an unrelated path are all denied.
        assertFalse(resolver.revalidateInFlight(context, capability, frozen, GrantScope("other", "src")))
        assertFalse(resolver.revalidateInFlight(context, capability, frozen, GrantScope("workspace", "src-sibling.txt")))
        assertFalse(resolver.revalidateInFlight(context, capability, frozen, GrantScope("workspace", "docs")))
        assertFalse(
            resolver.revalidateInFlight(context, capability, frozen),
            "the unscoped legacy form must not admit a scoped row",
        )
        assertFalse(
            resolver.revalidateInFlight(context, capability),
            "the unscoped legacy form must not admit a scoped row",
        )
    }

    /**
     * The pre-consumption row is preserved only while the persisted row is
     * exactly that row advanced by its consumption CAS.  Revocation, expiry, a
     * scope or capability edit, a removed binding and a policy change all fail
     * closed even though the request did consume the grant a moment ago.
     */
    @Test fun consumedOnceInFlightFailsClosedOnEveryPostConsumptionChange() {
        val capability = CapabilityId(CapabilityId.FILE_READ_TEXT)
        val frozen = CapabilityGrant(
            grantId = "once-scoped",
            agentId = "agent",
            capability = capability,
            workspaceId = "workspace",
            pathScope = "src",
            lifetime = GrantLifetime.ONCE,
            policyVersion = 1,
        )
        val consumed = frozen.copy(revision = 2, consumedAt = CONSUMED_AT)
        val binding = SnapshotGrantBinding(
            "snapshot", "once-scoped", capability,
            workspaceId = "workspace", pathScope = "src", policyVersion = 1,
        )
        val context = ToolExecutionContext(
            "agent", "snapshot", "model", "session",
            configSnapshotHash = "config",
            policyVersion = 1,
            canonicalGrants = listOf(frozen),
            snapshotGrantBindings = listOf(binding),
        )

        fun resolverWith(
            live: CapabilityGrant,
            bindings: List<SnapshotGrantBinding> = listOf(binding),
            policy: Long = 1,
        ): EffectiveCapabilityResolver = EffectiveCapabilityResolver(
            grants = CapabilityGrantReader { _, _ -> listOf(live) },
            bindings = SnapshotGrantBindingReader { bindings },
            currentPolicyVersionReader = { policy },
        )

        fun inFlight(resolver: EffectiveCapabilityResolver): Boolean =
            resolver.revalidateInFlight(context, capability, frozen, GrantScope("workspace", "src"))

        assertTrue(inFlight(resolverWith(consumed)), "the exact consumption is still admitted")
        assertFalse(
            inFlight(resolverWith(consumed.copy(revision = 3, revokedAt = "2026-10-10T00:00:02Z"))),
            "revocation after consumption must block",
        )
        assertFalse(
            inFlight(resolverWith(consumed.copy(expiresAt = "2000-01-01T00:00:00Z"))),
            "an expiry that has passed must block",
        )
        assertFalse(
            inFlight(resolverWith(consumed.copy(pathScope = "docs"))),
            "a post-consumption scope edit must block",
        )
        assertFalse(
            inFlight(resolverWith(consumed.copy(capability = CapabilityId(CapabilityId.FILE_WRITE_TEXT)))),
            "a post-consumption capability edit must block",
        )
        assertFalse(
            inFlight(resolverWith(consumed.copy(workspaceId = "other-workspace"))),
            "a post-consumption workspace edit must block",
        )
        assertFalse(
            inFlight(resolverWith(frozen.copy(revision = 2))),
            "an advanced revision without the recorded consumption is not this request",
        )
        assertFalse(
            inFlight(resolverWith(consumed, bindings = emptyList())),
            "a removed snapshot binding must block",
        )
        assertFalse(
            inFlight(resolverWith(consumed, policy = 2)),
            "a policy-version change must block",
        )
    }

    private companion object {
        const val CONSUMED_AT = "2026-10-10T00:00:00Z"
    }
}
