// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootExecutionStamp

/**
 * Live authorization facts sampled by the owning coordinator immediately before an execution
 * stamp is issued. [managerAuthorization] is the latest bound Magisk observation (`null` before
 * the first observation); [shizukuDenied] is the latest platform denial fence result.
 */
internal data class RootLiveAuthorizationFacts(
    val managerAuthorization: RootManagerAuthorization?,
    val shizukuDenied: Boolean,
)

/**
 * Read-only dispatch readiness and execution-stamp derivation.
 *
 * Extracted from [RootRuntimeCoordinator] for the conditional-token ratchet. Semantics are
 * unchanged: a live session needs enabled, non-degraded consent, an unrevoked host gate, a UID0
 * service, and (for direct su) a bound manager or generic-session authorization.
 */
internal object RootCoordinatorReadiness {
    fun matchesRootCaller(
        authority: Authority,
        identity: runtime.mobileagent.domain.PrivilegedSessionIdentity,
        caller: runtime.mobileagent.domain.RootCallerObservation,
    ): Boolean = identity.authority == authority && identity.privilegeTier == runtime.mobileagent.domain.PrivilegeTier.ROOT &&
        identity.serviceUid == 0 && caller.matches(identity) &&
        identity.protocolVersion == runtime.mobileagent.domain.PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION


    fun readyForDispatch(
        authority: Authority,
        record: RootAuthorityRecord,
        active: ManagedRootSessionState?,
        authorization: RootManagerAuthorization?,
        shizukuDenied: Boolean = false,
        livePolicyVersion: Long = record.consent.policyVersion,
    ): Boolean {
        if (!authority.isRootAuthority || active == null || !active.transport.isAlive()) return false
        if (shizukuDenied) return false
        // A consent bound to an older policy version can no longer dispatch; readiness must not
        // advertise Ready while its execution stamp would be null.
        if (record.consent.policyVersion != livePolicyVersion) return false
        val live = record.consent.enabled && !record.degraded &&
            !active.hostControlGate.isRevoked() && active.session.identity.serviceUid == 0
        if (!live) return false
        if (authority == Authority.ROOT_DIRECT) {
            return authorization?.permitsSession == true
        }
        return true
    }

    /**
     * Derives a stamp only when the live facts still permit this authority.
     *
     * Direct Root needs a positive manager observation bound to the current session; unknown,
     * unsupported and revoked observations fail closed. The Shizuku platform denial fence applies
     * to `SHIZUKU_ROOT` only (the fence's own contract excludes direct Root).
     */
    fun resolveExecutionStamp(
        authority: Authority,
        active: ManagedRootSessionState?,
        loadRecord: (Authority) -> RootAuthorityRecord,
        policy: AuthorityPolicy,
        requireShellConsent: Boolean,
        live: RootLiveAuthorizationFacts,
    ): RootExecutionStamp? {
        if (!authority.isRootAuthority || active == null) return null
        if (!liveAllows(authority, live)) return null
        return deriveExecutionStamp(active, loadRecord(authority), policy, requireShellConsent)
    }

    /** Pure consent/gate derivation; the public path must run [liveAllows] first. */
    private fun deriveExecutionStamp(
        active: ManagedRootSessionState?,
        record: RootAuthorityRecord,
        policy: AuthorityPolicy,
        requireShellConsent: Boolean,
    ): RootExecutionStamp? {
        if (active == null || !active.transport.isAlive()) return null
        val consentValid = record.consent.enabled && !record.degraded &&
            record.consent.policyVersion == policy.policyVersion &&
            (!requireShellConsent || record.consent.shellEnabled)
        if (!consentValid || active.hostControlGate.isRevoked()) return null
        return runCatching {
            RootExecutionStamp(
                identity = active.session.identity,
                consentRevision = record.consent.revision,
                shellConsentRevision = record.consent.shellRevision,
                policyVersion = policy.policyVersion,
            )
        }.getOrNull()
    }

    private fun liveAllows(authority: Authority, live: RootLiveAuthorizationFacts): Boolean {
        // The fence is recorded for Shizuku facts; a Shizuku denial must not suppress ROOT_DIRECT.
        if (authority == Authority.SHIZUKU_ROOT && live.shizukuDenied) return false
        if (authority == Authority.ROOT_DIRECT && live.managerAuthorization?.permitsSession != true) {
            return false
        }
        return true
    }
}
