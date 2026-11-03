// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootAuthorityConsent
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.isRootAuthority

/**
 * Local control-plane API: UI/verified bootstrap own writes; tools and imported
 * Agent settings must never be given this repository. CAS never retries a stale action.
 */
class RootAuthorityRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val changeRevision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changeRevision.asStateFlow()

    fun get(authority: Authority): RootAuthorityRecord {
        require(authority.isRootAuthority)
        val row = db.query("SELECT * FROM root_authority_state WHERE authority = ?", listOf(authority.name))
            .singleOrNull() ?: return RootAuthorityRecord(RootAuthorityConsent(authority))
        return RootAuthorityRecord(
            consent = RootAuthorityConsent(
                authority, row.long("enabled") != 0L, row.long("shell_enabled") != 0L,
                row.long("consent_revision"), row.long("shell_revision"), row.long("policy_version"),
            ),
            revision = row.long("revision"),
            identityEpoch = row.long("identity_epoch"),
            degradedEpoch = row.longOrNull("degraded_epoch"),
            recoveryAckEpoch = row.long("recovery_ack_epoch"),
        )
    }

    /** Explicitly confirming Root always starts a fresh consent revision and does not inherit Shell. */
    fun confirmRoot(authority: Authority, expectedRevision: Long, policyVersion: Long): RootAuthorityRecord? =
        mutate(authority, expectedRevision) { current ->
            requireCurrentPolicy(policyVersion)
            current.copy(consent = RootAuthorityConsent(
                authority, enabled = true, shellEnabled = false,
                revision = next(current.consent.revision),
                shellRevision = next(current.consent.shellRevision),
                policyVersion = policyVersion,
            ))
        }

    /** Root Shell changes leave the typed Root consent revision unchanged. */
    fun setShellConsent(
        authority: Authority,
        expectedRevision: Long,
        enabled: Boolean,
        policyVersion: Long,
    ): RootAuthorityRecord? = mutate(authority, expectedRevision) { current ->
        requireCurrentPolicy(policyVersion)
        require(current.consent.enabled && current.consent.policyVersion == policyVersion) {
            "Current Root consent is required"
        }
        current.copy(consent = current.consent.copy(
            shellEnabled = enabled, shellRevision = next(current.consent.shellRevision),
        ))
    }

    fun revokeRoot(authority: Authority, expectedRevision: Long): RootAuthorityRecord? =
        mutate(authority, expectedRevision) { current ->
            current.copy(consent = current.consent.copy(
                enabled = false, shellEnabled = false,
                revision = next(current.consent.revision),
                shellRevision = next(current.consent.shellRevision),
            ))
        }

    /** A verified platform bootstrap calls this before admitting its new session. */
    fun advanceIdentityEpoch(authority: Authority, expectedRevision: Long): RootAuthorityRecord? =
        mutate(authority, expectedRevision) { current ->
            current.copy(identityEpoch = next(current.identityEpoch))
        }

    fun markDegraded(authority: Authority, expectedRevision: Long, identityEpoch: Long): RootAuthorityRecord? =
        db.transaction {
            require(expectedRevision >= 0)
            val current = get(authority)
            if (current.revision != expectedRevision) return@transaction null
            require(identityEpoch in 1..current.identityEpoch)
            // Explicit recovery covered every older epoch; a late callback cannot relock it.
            if (identityEpoch <= current.recoveryAckEpoch) return@transaction current
            mutate(authority,expectedRevision) { live ->
                live.copy(degradedEpoch = maxOf(live.degradedEpoch ?: identityEpoch,identityEpoch))
            }
        }

    /**
     * Only the explicit recovery UI may invoke this after a newly verified
     * identity. A reconnect/boot alone cannot clear uncertain Root descendants.
     */
    internal fun acknowledgeRecovery(
        authority: Authority,
        expectedRevision: Long,
        verifiedIdentityEpoch: Long,
        userConfirmed: Boolean,
    ): RootAuthorityRecord? = mutate(authority, expectedRevision) { current ->
        require(userConfirmed && current.degradedEpoch != null)
        require(verifiedIdentityEpoch == current.identityEpoch &&
            verifiedIdentityEpoch > requireNotNull(current.degradedEpoch))
        current.copy(degradedEpoch = null,
            recoveryAckEpoch = maxOf(current.recoveryAckEpoch,verifiedIdentityEpoch - 1L))
    }

    private fun mutate(
        authority: Authority,
        expectedRevision: Long,
        transform: (RootAuthorityRecord) -> RootAuthorityRecord,
    ): RootAuthorityRecord? {
        require(authority.isRootAuthority && expectedRevision >= 0)
        val saved = db.transaction {
            val current = get(authority)
            if (current.revision != expectedRevision) return@transaction null
            val candidate = transform(current).copy(revision = next(current.revision))
            check(candidate.consent.authority == authority)
            val consent = candidate.consent
            val values = listOf(
                if (consent.enabled) 1 else 0, if (consent.shellEnabled) 1 else 0,
                consent.revision, consent.shellRevision, consent.policyVersion,
                candidate.revision, candidate.identityEpoch, candidate.degradedEpoch, candidate.recoveryAckEpoch, clock(),
            )
            if (current.revision == 0L) {
                db.execute(
                    "INSERT INTO root_authority_state(enabled,shell_enabled,consent_revision,shell_revision,policy_version,revision,identity_epoch,degraded_epoch,recovery_ack_epoch,updated_at,authority) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    values + authority.name,
                )
            } else {
                db.execute(
                    "UPDATE root_authority_state SET enabled=?,shell_enabled=?,consent_revision=?,shell_revision=?,policy_version=?,revision=?,identity_epoch=?,degraded_epoch=?,recovery_ack_epoch=?,updated_at=? WHERE authority=? AND revision=?",
                    values + listOf(authority.name, expectedRevision),
                )
            }
            check(get(authority) == candidate) { "Root authority CAS did not persist the exact candidate" }
            candidate
        }
        if (saved != null) changeRevision.update { next(it) }
        return saved
    }

    private fun requireCurrentPolicy(version: Long) {
        require(version >= 0 && AuthorityPolicyRepository(db).getPolicy().policyVersion == version) {
            "Authority policy changed before consent was saved"
        }
    }

    private fun next(value: Long): Long = Math.addExact(value, 1L)
}
