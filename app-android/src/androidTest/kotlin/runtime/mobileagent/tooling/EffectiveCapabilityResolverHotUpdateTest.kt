// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId

/**
 * The run boundary is intentionally asymmetric: a new run reads current
 * Agent-level grants, while an existing run can only lose authorization.
 */
class EffectiveCapabilityResolverHotUpdateTest {
    private val list = CapabilityId(CapabilityId.FILE_LIST)
    private val read = CapabilityId(CapabilityId.FILE_READ_TEXT)
    private val snapshot = AgentSnapshot(
        id = "snapshot-1",
        schemaVersion = 1,
        agentId = "agent-1",
        promptRevisionId = "prompt-1",
        chatModelId = "model-1",
        providerRevision = 1,
        knowledgeBaseIds = emptyList(),
        skillIds = emptyList(),
        createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun newRunReadsUnboundPersistentAgentGrantButHistoricalResolveStaysStrict() {
        val grant = grant("grant-1", list)
        val resolver = EffectiveCapabilityResolver(
            policyCapabilities = setOf(list),
            policyVersion = 1L,
        )

        assertTrue(
            resolver.resolveForRun(
                snapshot = snapshot,
                grants = listOf(grant),
                snapshotBindings = emptyList(),
                currentPolicyVersion = 1L,
            ).allows(list),
        )
        assertFalse(
            resolver.resolve(
                snapshot = snapshot,
                grants = listOf(grant),
                snapshotBindings = emptyList(),
                currentPolicyVersion = 1L,
            ).allows(list),
        )
    }

    @Test
    fun runningContextDoesNotExpandButRevocationAndRevisionChangeFailClosed() {
        val initial = grant("grant-1", list)
        var live = listOf(initial)
        val resolver = EffectiveCapabilityResolver(
            policyCapabilities = setOf(list, read),
            policyVersion = 1L,
            grants = CapabilityGrantReader { _, _ -> live },
            bindings = SnapshotGrantBindingReader { emptyList() },
        )
        val context = ToolExecutionContext(
            agentId = snapshot.agentId,
            snapshotId = snapshot.id,
            modelCallId = "run-1",
            sessionIdentity = "session-1",
            configSnapshotHash = "config-1",
            policyVersion = 1L,
            effectiveCapabilities = setOf(list),
            canonicalGrants = listOf(initial),
        )

        assertTrue(resolver.revalidate(context, list))

        // A newly created grant is visible to the next run, never to this one.
        live = listOf(initial, grant("grant-2", read))
        assertFalse(resolver.revalidate(context, read))

        // A changed row with the same id is not an in-flight authorization.
        live = listOf(initial.copy(revision = 2L))
        assertFalse(resolver.revalidate(context, list))

        // Revocation removes the live proof immediately.
        live = emptyList()
        assertFalse(resolver.revalidate(context, list))
    }

    private fun grant(id: String, capability: CapabilityId): CapabilityGrant = CapabilityGrant(
        grantId = id,
        agentId = snapshot.agentId,
        capability = capability,
        lifetime = runtime.mobileagent.domain.GrantLifetime.PERSISTENT,
        policyVersion = 1L,
    )
}
