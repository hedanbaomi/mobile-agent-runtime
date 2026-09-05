// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shared authorization decision vectors.  The Python broker, built-in tools,
 * and cached-result disclosure checks must all agree on these outcomes at
 * every checkpoint (DISCOVERY, BEFORE_DISPATCH, AFTER_APPROVAL,
 * BEFORE_DISCLOSURE): one revocation cannot allow a replay on one route
 * while denying it on another.
 */
class AuthorizationEvaluatorTest {
    private fun grant(
        revoked: Boolean = false,
        scopesJson: String = "{}",
        revision: Int = 3,
        packageHash: String = "hash-1",
        capabilities: Set<String> = setOf("knowledge.search"),
        knowledgeBaseIds: Set<String> = setOf("kb-a"),
    ) = GrantView(
        grantId = "g-1",
        installId = "skill-1",
        packageHash = packageHash,
        revision = revision,
        revoked = revoked,
        scopesJson = scopesJson,
        capabilities = capabilities,
        knowledgeBaseIds = knowledgeBaseIds,
    )

    private fun owner(
        agentSkills: Set<String> = setOf("skill-1"),
        snapshotSkills: Set<String> = setOf("skill-1"),
        enabled: Boolean = true,
        installedHash: String = "hash-1",
    ) = OwnerView(agentSkills, snapshotSkills, enabled, installedHash)

    private fun evaluateAtAllCheckpoints(
        grant: GrantView?,
        owner: OwnerView? = null,
        resourceSkillId: String? = null,
        capability: String? = null,
        grantedCapabilities: Set<String> = emptySet(),
        resourceKnowledgeIds: Set<String> = emptySet(),
        grantedKnowledgeIds: Set<String> = emptySet(),
        policy: PolicyView = PolicyView(),
    ): Set<AuthorizationDecision> = AuthorizationCheckpoint.values().map { checkpoint ->
        AuthorizationEvaluator.evaluate(
            grant = grant,
            owner = owner,
            resourceSkillId = resourceSkillId,
            capability = capability,
            grantedCapabilities = grantedCapabilities,
            resourceKnowledgeIds = resourceKnowledgeIds,
            grantedKnowledgeIds = grantedKnowledgeIds,
            policy = policy,
            nowMillis = 1_700_000_000_000L,
            checkpoint = checkpoint,
        )
    }.toSet()

    @Test
    fun healthyGrantIsGrantedAtEveryCheckpoint() {
        val decisions = evaluateAtAllCheckpoints(
            grant = grant(),
            owner = owner(),
            resourceSkillId = "skill-1",
            capability = "knowledge.search",
            grantedCapabilities = setOf("knowledge.search"),
            resourceKnowledgeIds = setOf("kb-a"),
            grantedKnowledgeIds = setOf("kb-a", "kb-b"),
        )
        assertEquals(setOf(AuthorizationDecision.GRANTED), decisions)
    }

    @Test
    fun missingGrantIsUnavailableEverywhere() {
        assertEquals(
            setOf(AuthorizationDecision.AUTHORITY_UNAVAILABLE),
            evaluateAtAllCheckpoints(grant = null),
        )
    }

    @Test
    fun revokedGrantIsRevokedEverywhere() {
        assertEquals(
            setOf(AuthorizationDecision.REVOKED),
            evaluateAtAllCheckpoints(grant = grant(revoked = true)),
        )
    }

    @Test
    fun pastExpiryIsExpiredEverywhere() {
        assertEquals(
            setOf(AuthorizationDecision.EXPIRED),
            evaluateAtAllCheckpoints(grant = grant(scopesJson = """{"expiresAt":"2020-01-01T00:00:00Z"}""")),
        )
    }

    @Test
    fun malformedExpiryFailsClosedAsExpired() {
        assertEquals(
            setOf(AuthorizationDecision.EXPIRED),
            evaluateAtAllCheckpoints(grant = grant(scopesJson = """{"expiresAt":"not-a-time"}""")),
        )
    }

    @Test
    fun unreadableScopeDocumentFailsClosedAsExpired() {
        // b07 follow-up finding E: an unreadable scope document must never
        // read as "not expired".  The whole JSON may be malformed, or the
        // expiresAt value missing/unparseable.
        listOf(
            "not-json{{{",
            "[1,2]",
            "null",
            """{"expiresAt":null}""",
            """{"expiresAt":123}""",
            """{"expiresAt":""}""",
        ).forEach { scopes ->
            assertEquals(
                setOf(AuthorizationDecision.EXPIRED),
                evaluateAtAllCheckpoints(grant = grant(scopesJson = scopes)),
                "scopes=$scopes must fail closed",
            )
        }
    }

    @Test
    fun isExpiredUnitSemantics() {
        val now = 1_700_000_000_000L
        assertTrue(AuthorizationEvaluator.isExpired("not-json{{{", now))
        assertTrue(AuthorizationEvaluator.isExpired("""{"expiresAt":null}""", now))
        assertTrue(AuthorizationEvaluator.isExpired("""{"expiresAt":"not-a-time"}""", now))
        assertTrue(AuthorizationEvaluator.isExpired("""{"expiresAt":"2020-01-01T00:00:00Z"}""", now))
        assertFalse(AuthorizationEvaluator.isExpired("""{"expiresAt":"2030-01-01T00:00:00Z"}""", now))
        assertFalse(AuthorizationEvaluator.isExpired("{}", now))
        assertFalse(AuthorizationEvaluator.isExpired("", now))
        assertFalse(AuthorizationEvaluator.isExpired(null, now))
    }

    @Test
    fun futureExpiryRemainsGranted() {
        val decisions = evaluateAtAllCheckpoints(grant = grant(scopesJson = """{"expiresAt":"2030-01-01T00:00:00Z"}"""))
        assertEquals(setOf(AuthorizationDecision.GRANTED), decisions)
    }

    @Test
    fun ownershipDriftIsOwnerChanged() {
        assertEquals(
            setOf(AuthorizationDecision.OWNER_CHANGED),
            evaluateAtAllCheckpoints(grant = grant(), owner = owner(agentSkills = emptySet()), resourceSkillId = "skill-1"),
        )
        assertEquals(
            setOf(AuthorizationDecision.OWNER_CHANGED),
            evaluateAtAllCheckpoints(grant = grant(), owner = owner(enabled = false), resourceSkillId = "skill-1"),
        )
        assertEquals(
            setOf(AuthorizationDecision.OWNER_CHANGED),
            evaluateAtAllCheckpoints(grant = grant(), owner = owner(installedHash = "hash-2"), resourceSkillId = "skill-1"),
        )
    }

    @Test
    fun frozenGrantDriftHasTypedDecisions() {
        val frozen = grant()
        assertEquals(
            setOf(AuthorizationDecision.POLICY_VERSION_MISMATCH),
            evaluateAtAllCheckpoints(
                grant = grant(revision = 4),
                policy = PolicyView(frozenGrant = frozen),
            ),
        )
        assertEquals(
            setOf(AuthorizationDecision.OWNER_CHANGED),
            evaluateAtAllCheckpoints(
                grant = grant(packageHash = "hash-2"),
                policy = PolicyView(frozenGrant = frozen),
            ),
        )
        assertEquals(
            setOf(AuthorizationDecision.RESOURCE_OUT_OF_SCOPE),
            evaluateAtAllCheckpoints(
                grant = grant(knowledgeBaseIds = setOf("kb-a", "kb-evil")),
                policy = PolicyView(frozenGrant = frozen),
            ),
        )
        assertEquals(
            setOf(AuthorizationDecision.REVOKED),
            evaluateAtAllCheckpoints(
                grant = grant(revoked = true),
                policy = PolicyView(frozenGrant = frozen),
            ),
        )
        assertEquals(
            setOf(AuthorizationDecision.GRANTED),
            evaluateAtAllCheckpoints(grant = grant(), policy = PolicyView(frozenGrant = frozen)),
        )
    }

    @Test
    fun capabilityAndKnowledgeScopeAreEnforced() {
        assertEquals(
            setOf(AuthorizationDecision.RESOURCE_OUT_OF_SCOPE),
            evaluateAtAllCheckpoints(
                grant = grant(),
                capability = "network.http",
                grantedCapabilities = setOf("knowledge.search"),
            ),
        )
        assertEquals(
            setOf(AuthorizationDecision.RESOURCE_OUT_OF_SCOPE),
            evaluateAtAllCheckpoints(
                grant = grant(),
                resourceKnowledgeIds = setOf("kb-a", "kb-gone"),
                grantedKnowledgeIds = setOf("kb-a"),
            ),
        )
    }

    @Test
    fun deniedReasonsAreTypedNeverInternal() {
        AuthorizationDecision.values()
            .filter { it != AuthorizationDecision.GRANTED }
            .forEach { decision ->
                val reason = with(AuthorizationEvaluator) { decision.toDeniedReason() }
                assertTrue(reason.isNotBlank())
                assertFalse("INTERNAL" in reason, "decision $decision must not escalate to INTERNAL")
            }
    }
}
