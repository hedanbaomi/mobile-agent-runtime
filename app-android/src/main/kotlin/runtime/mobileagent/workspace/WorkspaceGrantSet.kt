// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.time.Instant
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.Utc

internal val READ_ONLY_WORKSPACE_CAPABILITIES = setOf(
    CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
    CapabilityId(CapabilityId.FILE_LIST),
    CapabilityId(CapabilityId.FILE_STAT),
    CapabilityId(CapabilityId.FILE_READ_TEXT),
)

/** Persistent Agent grants for an exact workspace and path, including the supplied revocation history. */
internal class WorkspaceGrantSet(
    private val agentId: String,
    private val workspaceId: String,
    grants: List<CapabilityGrant>,
    private val pathScope: String? = null,
) {
    val rows = grants.filter(::matchesScope)

    fun matchesScope(grant: CapabilityGrant): Boolean =
        grant.agentId == agentId && grant.workspaceId == workspaceId && grant.pathScope == pathScope &&
            grant.lifetime == GrantLifetime.PERSISTENT && grant.taskId == null && grant.sessionId == null &&
            grant.skillInstallId == null && grant.packageHash == null

    fun activeAt(policy: Long, now: Instant) = rows.filter { it.policyVersion == policy && it.isActiveFor(now) }

    fun newGrant(capability: CapabilityId, policy: Long, createdAt: String = Utc.nowIso()) = CapabilityGrant(
        grantId = EntityId.random().value, agentId = agentId, workspaceId = workspaceId,
        pathScope = pathScope, capability = capability, lifetime = GrantLifetime.PERSISTENT,
        policyVersion = policy, createdAt = createdAt,
    )

    fun renewableGrants(currentPolicy: Long, now: Instant): List<CapabilityGrant> {
        require(pathScope == null) { "Only whole-directory grants may be renewed" }
        // Even a fully revoked or expired generation supersedes the previous consent.
        val latest = rows.maxOfOrNull { it.policyVersion } ?: return emptyList()
        if (latest > currentPolicy) return emptyList()
        return activeAt(latest, now).groupBy { it.capability }.values.map { sameCapability ->
            // Overlapping live grants permit the capability until their last expiry.
            sameCapability.maxBy { it.expiresAt?.takeUnless(String::isBlank)?.let(Instant::parse) ?: Instant.MAX }
        }.sortedBy { it.capability.value }
    }
}
