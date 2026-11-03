// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.domain.AuthorityPolicy
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootExecutionStamp

/**
 * Read-only dispatch readiness and execution-stamp derivation.
 *
 * Extracted from [RootRuntimeCoordinator] for the conditional-token ratchet. Semantics are
 * unchanged: a live session needs enabled, non-degraded consent, an unrevoked host gate, a UID0
 * service, and (for direct su) a bound GRANTED manager authorization.
 */
internal object RootCoordinatorReadiness {
    fun readyForDispatch(
        authority: Authority,
        record: RootAuthorityRecord,
        active: ManagedRootSessionState?,
        authorization: RootManagerAuthorization?,
        shizukuDenied: Boolean = false,
        livePolicyVersion: Long = record.consent.policyVersion,
    ): Boolean {
        if (!authority.isRootAuthority || active == null) return false
        if (shizukuDenied) return false
        // A consent bound to an older policy version can no longer dispatch; readiness must not
        // advertise Ready while its execution stamp would be null.
        if (record.consent.policyVersion != livePolicyVersion) return false
        val live = record.consent.enabled && !record.degraded &&
            !active.hostControlGate.isRevoked() && active.session.identity.serviceUid == 0
        if (!live) return false
        if (authority == Authority.ROOT_DIRECT) {
            return authorization == RootManagerAuthorization.GRANTED
        }
        return true
    }

    fun resolveExecutionStamp(
        authority: Authority,
        active: ManagedRootSessionState?,
        loadRecord: (Authority) -> RootAuthorityRecord,
        policy: AuthorityPolicy,
        requireShellConsent: Boolean,
    ): RootExecutionStamp? {
        if (!authority.isRootAuthority || active == null) return null
        return resolveExecutionStamp(active, loadRecord(authority), policy, requireShellConsent)
    }

    fun resolveExecutionStamp(
        active: ManagedRootSessionState?,
        record: RootAuthorityRecord,
        policy: AuthorityPolicy,
        requireShellConsent: Boolean,
    ): RootExecutionStamp? {
        if (active == null) return null
        val consentValid = record.consent.enabled && !record.degraded &&
            record.consent.policyVersion == policy.policyVersion &&
            (!requireShellConsent ||
                (record.consent.shellEnabled && policy.dangerousMode != DangerousMode.DISABLED))
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
}
