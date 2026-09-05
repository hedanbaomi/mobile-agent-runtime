// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import runtime.mobileagent.skills.PermissionGrant
import java.time.Instant

/**
 * Checkpoints at which one authorization must hold.  The rules are stateless,
 * so every checkpoint applies the same decision table; the checkpoint value
 * only records *when* the evaluation ran (discovery, dispatch, approval, or
 * disclosure of a cached result).
 */
enum class AuthorizationCheckpoint {
    DISCOVERY,
    BEFORE_DISPATCH,
    AFTER_APPROVAL,
    BEFORE_DISCLOSURE,
}

/** Typed authorization outcome.  Never a free string, never INTERNAL. */
enum class AuthorizationDecision {
    GRANTED,
    REVOKED,
    EXPIRED,
    OWNER_CHANGED,
    RESOURCE_OUT_OF_SCOPE,
    POLICY_VERSION_MISMATCH,
    AUTHORITY_UNAVAILABLE,
}

/** Grant facts the evaluator reads.  No secrets, no paths. */
data class GrantView(
    val grantId: String,
    val installId: String,
    val packageHash: String,
    val revision: Int,
    val revoked: Boolean,
    val scopesJson: String = "{}",
    val capabilities: Set<String> = emptySet(),
    val knowledgeBaseIds: Set<String> = emptySet(),
    val hosts: Set<String> = emptySet(),
    val methods: Set<String> = emptySet(),
)

fun PermissionGrant.toGrantView(): GrantView =
    GrantView(grantId, installId, packageHash, revision, revoked, scopesJson, capabilities, knowledgeBaseIds, hosts, methods)

/** Ownership facts: the skill must still belong to the agent and snapshot. */
data class OwnerView(
    val agentSkillIds: Set<String> = emptySet(),
    val snapshotSkillIds: Set<String> = emptySet(),
    val installEnabled: Boolean = true,
    val installedPackageHash: String = "",
)

/** Frozen run facts bound at completion time for replay comparison. */
data class PolicyView(
    val expectedGrantRevision: Int? = null,
    val expectedPackageHash: String? = null,
    val frozenGrant: GrantView? = null,
)

/**
 * Shared grant-validity evaluator for the Python broker, built-in/legacy
 * tools, and cached-result disclosure checks.
 *
 * Previously each path validated a different subset (the Python broker
 * checked `expiresAt`/package-hash/ownership while built-in tools did not),
 * so the same revocation could allow a replay on one route and deny it on
 * another.  All routes now share this decision table; the same test vectors
 * verify every consumer.
 *
 * Evaluation order (first match wins):
 * 1. no grant → AUTHORITY_UNAVAILABLE
 * 2. revoked → REVOKED
 * 3. `expiresAt` scope reached or malformed → EXPIRED (fail-closed)
 * 4. skill no longer owned/enabled, or code identity changed → OWNER_CHANGED
 * 5. frozen run revision/hash/scope no longer matches → POLICY_VERSION_MISMATCH
 *    (revision), OWNER_CHANGED (package hash), RESOURCE_OUT_OF_SCOPE (scope)
 * 6. capability or knowledge scope not covered → RESOURCE_OUT_OF_SCOPE
 * 7. otherwise GRANTED
 */
object AuthorizationEvaluator {
    fun evaluate(
        grant: GrantView?,
        owner: OwnerView? = null,
        resourceSkillId: String? = null,
        capability: String? = null,
        grantedCapabilities: Set<String> = emptySet(),
        resourceKnowledgeIds: Set<String> = emptySet(),
        grantedKnowledgeIds: Set<String> = emptySet(),
        policy: PolicyView = PolicyView(),
        nowMillis: Long = System.currentTimeMillis(),
        checkpoint: AuthorizationCheckpoint = AuthorizationCheckpoint.BEFORE_DISPATCH,
    ): AuthorizationDecision {
        if (grant == null) return AuthorizationDecision.AUTHORITY_UNAVAILABLE
        if (grant.revoked) return AuthorizationDecision.REVOKED
        if (isExpired(grant.scopesJson, nowMillis)) return AuthorizationDecision.EXPIRED
        if (owner != null && resourceSkillId != null) {
            if (resourceSkillId !in owner.agentSkillIds || resourceSkillId !in owner.snapshotSkillIds) {
                return AuthorizationDecision.OWNER_CHANGED
            }
            if (!owner.installEnabled) return AuthorizationDecision.OWNER_CHANGED
            if (owner.installedPackageHash.isNotBlank() && owner.installedPackageHash != grant.packageHash) {
                return AuthorizationDecision.OWNER_CHANGED
            }
        }
        policy.frozenGrant?.let { frozen ->
            if (grant.revoked && !frozen.revoked) return AuthorizationDecision.REVOKED
            if (frozen.packageHash != grant.packageHash) return AuthorizationDecision.OWNER_CHANGED
            if (frozen.revision != grant.revision) return AuthorizationDecision.POLICY_VERSION_MISMATCH
            if (frozen.capabilities != grant.capabilities ||
                frozen.knowledgeBaseIds != grant.knowledgeBaseIds ||
                frozen.hosts != grant.hosts ||
                frozen.methods != grant.methods ||
                frozen.scopesJson != grant.scopesJson
            ) {
                return AuthorizationDecision.RESOURCE_OUT_OF_SCOPE
            }
        }
        policy.expectedGrantRevision?.let { if (it != grant.revision) return AuthorizationDecision.POLICY_VERSION_MISMATCH }
        policy.expectedPackageHash?.let { if (it != grant.packageHash) return AuthorizationDecision.OWNER_CHANGED }
        if (capability != null && capability.isNotBlank() && capability !in grantedCapabilities) {
            return AuthorizationDecision.RESOURCE_OUT_OF_SCOPE
        }
        if (resourceKnowledgeIds.isNotEmpty() && !grantedKnowledgeIds.containsAll(resourceKnowledgeIds)) {
            return AuthorizationDecision.RESOURCE_OUT_OF_SCOPE
        }
        return AuthorizationDecision.GRANTED
    }

    /** True when an `expiresAt` scope exists and is reached, missing, or malformed. */
    fun isExpired(scopesJson: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (scopesJson.isNullOrBlank()) return false
        val root = runCatching { Json.parseToJsonElement(scopesJson).jsonObject }.getOrNull() ?: return false
        if ("expiresAt" !in root) return false
        val raw = root["expiresAt"]?.jsonPrimitive?.contentOrNull ?: return true
        val expiry = runCatching { Instant.parse(raw) }.getOrNull() ?: return true
        return !expiry.isAfter(Instant.ofEpochMilli(nowMillis))
    }

    fun AuthorizationDecision.toDeniedReason(): String = deniedReason(this)

    fun deniedReason(decision: AuthorizationDecision): String = when (decision) {
        AuthorizationDecision.GRANTED -> "authorized"
        AuthorizationDecision.REVOKED -> "Grant revoked; cached tool output is unavailable"
        AuthorizationDecision.EXPIRED -> "Grant expired; cached tool output is unavailable"
        AuthorizationDecision.OWNER_CHANGED -> "Tool ownership changed; cached tool output is unavailable"
        AuthorizationDecision.RESOURCE_OUT_OF_SCOPE -> "Resource is outside the authorized scope"
        AuthorizationDecision.POLICY_VERSION_MISMATCH -> "Grant revision changed; cached tool output is unavailable"
        AuthorizationDecision.AUTHORITY_UNAVAILABLE -> "Authorizing grant is unavailable"
    }
}
