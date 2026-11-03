// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootRemoteQuiescenceSnapshot
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.isRootAuthority

/** One Root session whose remote work/resource drain was never confirmed. */
@Serializable
data class RootPendingDrainSession(
    val serviceInstanceId: String,
    val sessionId: String,
    val identityEpoch: Long,
    val requestIds: List<String> = emptyList(),
) {
    fun matches(identity: PrivilegedSessionIdentity): Boolean =
        identity.serviceInstanceId == serviceInstanceId &&
            identity.sessionId == sessionId &&
            identity.identityEpoch == identityEpoch
}

/** One request whose remote drain was proven by an authenticated quiescence snapshot. */
@Serializable
data class ProvenDrainRequest(
    val requestId: String,
    val identityEpoch: Long,
)

/** Durable proof obligation for one authority; survives controller rebuild and process death. */
@Serializable
data class RootPendingDrain(
    val authority: String,
    val degradedEpoch: Long,
    val sessions: List<RootPendingDrainSession>,
    val updatedAt: String,
) {
    /** True when a proof covers exactly the given session but other sessions still block. */
    fun covers(identity: PrivilegedSessionIdentity): Boolean =
        sessions.isNotEmpty() && sessions.all { it.matches(identity) }

    fun provenBy(
        identity: PrivilegedSessionIdentity?,
        snapshot: RootRemoteQuiescenceSnapshot?,
    ): Boolean {
        if (identity == null || snapshot == null) return false
        if (identity.authority.name != authority) return false
        if (sessions.none { it.matches(identity) }) return false
        return snapshot.provesSessionQuiescent(identity)
    }

    fun without(identity: PrivilegedSessionIdentity): RootPendingDrain =
        copy(sessions = sessions.filterNot { it.matches(identity) })
}

/**
 * Load result distinguishes "no obligation was ever recorded" from "the stored obligation is
 * unreadable". The second one must block recovery and keep the stored value: parsing it as
 * "nothing pending" would turn a damaged record into a fail-open.
 */
sealed interface RootPendingDrainLoad {
    data object Missing : RootPendingDrainLoad

    data class Invalid(val reason: String) : RootPendingDrainLoad

    data class Present(val state: RootPendingDrain) : RootPendingDrainLoad {
        val blocked: Boolean get() = state.sessions.isNotEmpty()
    }
}

/**
 * Durable "the old remote may still hold work" obligation for Root recovery.
 *
 * The process-local session maps of a rebuilt controller are empty; this record is the only
 * authority-wide evidence that survives `reconcileStartupRecovery`. It is stored in the existing
 * `app_prefs` key/value table (the same durable control-plane slot used by index repair state), so
 * no schema migration rewrites Root evidence tables. The key prefix is reserved control-plane
 * state: it is not a user preference and is never carried by portable configuration transfer.
 */
class RootPendingDrainRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun load(authority: Authority): RootPendingDrainLoad {
        require(authority.isRootAuthority)
        val raw = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(key(authority)))
            .singleOrNull()?.string("value") ?: return RootPendingDrainLoad.Missing
        val decoded = runCatching {
            json.decodeFromString<RootPendingDrain>(raw)
        }.getOrElse { return RootPendingDrainLoad.Invalid("root_pending_drain_malformed") }
        if (decoded.authority != authority.name) {
            return RootPendingDrainLoad.Invalid("root_pending_drain_authority_mismatch")
        }
        if (decoded.sessions.isEmpty()) {
            return RootPendingDrainLoad.Invalid("root_pending_drain_empty_sessions")
        }
        return RootPendingDrainLoad.Present(decoded)
    }

    /**
     * Records (merges) one unconfirmed session. Called exactly where a dispatch outcome makes the
     * authority degraded, so a later controller cannot infer "nothing was running".
     *
     * A damaged existing value is never overwritten: it already blocks recovery, and replacing it
     * with a fresh record would erase the evidence that made it unreadable.
     */
    fun record(
        identity: PrivilegedSessionIdentity,
        requestId: String?,
        degradedEpoch: Long,
    ): RootPendingDrainLoad = db.transaction {
        val current = load(identity.authority)
        if (current is RootPendingDrainLoad.Invalid) return@transaction current
        val existing = (current as? RootPendingDrainLoad.Present)?.state
        val proven = provenEntriesLocked(identity.authority).mapTo(linkedSetOf()) { it.requestId }
        val requestKey = requestId?.takeIf { it.isNotBlank() && it !in proven }
        if (requestId != null && requestKey == null) {
            // This exact request was already proven. Replaying it must not create (or recreate) an
            // obligation, whether or not other sessions are still pending; an empty-request
            // resurrected session would otherwise block recovery forever.
            return@transaction current
        }
        val sessions = existing?.sessions.orEmpty().toMutableList()
        val index = sessions.indexOfFirst { it.matches(identity) }
        if (index >= 0) {
            val session = sessions[index]
            sessions[index] = session.copy(
                requestIds = (session.requestIds + listOfNotNull(requestKey)).distinct().sorted(),
            )
        } else {
            sessions += RootPendingDrainSession(
                serviceInstanceId = identity.serviceInstanceId,
                sessionId = identity.sessionId,
                identityEpoch = identity.identityEpoch,
                requestIds = listOfNotNull(requestKey).distinct().sorted(),
            )
        }
        val next = RootPendingDrain(
            authority = identity.authority.name,
            degradedEpoch = maxOf(existing?.degradedEpoch ?: 0L, degradedEpoch),
            sessions = sessions,
            updatedAt = clock(),
        )
        db.execute(
            "INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            listOf(key(identity.authority), json.encodeToString(next)),
        )
        check(load(identity.authority) == RootPendingDrainLoad.Present(next)) {
            "Root pending drain record was not persisted"
        }
        RootPendingDrainLoad.Present(next)
    }

    /**
     * Per-request tombstones for work whose drain was actually proven.
     *
     * A partial recovery (session A proven, session B still pending) must survive a later startup
     * backfill: the global `recoveryAckEpoch` only advances when *everything* drained, so it cannot
     * prove A on its own. Tombs are keyed by request id, so a future UNKNOWN with a new request id
     * for the same identity is still recorded instead of being silently skipped.
     *
     * Entries are garbage-collected only when the authenticated recovery watermark already covers
     * their identity epoch (the backfill ignores those rows anyway). Capacity is never resolved by
     * dropping a live proof: exceeding it aborts the surrounding transaction and keeps the pending
     * obligation intact.
     */
    fun markProvenRequests(authority: Authority, proofs: Collection<ProvenDrainRequest>) {
        if (proofs.isEmpty()) return
        db.transaction {
            val watermark = RootAuthorityRepository(db, clock).get(authority).recoveryAckEpoch
            val kept = provenEntriesLocked(authority)
                .filter { it.identityEpoch > watermark }
                .toMutableList()
            proofs.filter { it.requestId.isNotBlank() }
                .forEach { proof -> if (kept.none { it.requestId == proof.requestId }) kept += proof }
            check(kept.size <= MAX_PROVEN_REQUESTS) {
                "root_drain_proven_capacity_exceeded:${kept.size}"
            }
            db.execute(
                "INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                listOf(provenKey(authority), json.encodeToString(kept.toList())),
            )
        }
    }

    fun provenRequests(authority: Authority): Set<String> =
        db.transaction { provenEntriesLocked(authority).mapTo(linkedSetOf()) { it.requestId } }

    private fun provenEntriesLocked(authority: Authority): List<ProvenDrainRequest> {
        val raw = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(provenKey(authority)))
            .singleOrNull()?.string("value") ?: return emptyList()
        return runCatching { json.decodeFromString<List<ProvenDrainRequest>>(raw) }.getOrDefault(emptyList())
    }

    /**
     * Removes exactly the proven session. Other identities keep their own obligation; the key is
     * deleted only when every recorded identity was individually proven. An unreadable record is
     * left in place and reported, never deleted by a proof for some unrelated session.
     */
    fun clearProvenSession(
        authority: Authority,
        identity: PrivilegedSessionIdentity,
        snapshot: RootRemoteQuiescenceSnapshot?,
    ): RootPendingDrainLoad = db.transaction {
        val current = load(authority)
        if (current !is RootPendingDrainLoad.Present) return@transaction current
        if (!current.state.provenBy(identity, snapshot)) return@transaction current
        val proven = current.state.sessions.filter { it.matches(identity) }.flatMap { session ->
            session.requestIds.map { ProvenDrainRequest(it, session.identityEpoch) }
        }
        if (proven.isNotEmpty()) markProvenRequests(authority, proven)
        val remaining = current.state.without(identity)
        if (remaining.sessions.isEmpty()) {
            db.execute("DELETE FROM app_prefs WHERE key=?", listOf(key(authority)))
            return@transaction RootPendingDrainLoad.Missing
        }
        val next = remaining.copy(updatedAt = clock())
        db.execute("UPDATE app_prefs SET value=? WHERE key=?", listOf(json.encodeToString(next), key(authority)))
        load(authority)
    }

    /** True only when nothing at all remains recorded for this authority. */
    fun isMissing(load: RootPendingDrainLoad): Boolean = load is RootPendingDrainLoad.Missing

    private fun key(authority: Authority): String = "$KEY_PREFIX${authority.name}"

    private fun provenKey(authority: Authority): String = "$PROVEN_PREFIX${authority.name}"

    companion object {
        const val KEY_PREFIX = "root_pending_drain:"
        const val PROVEN_PREFIX = "root_drain_proven:"
        private const val MAX_PROVEN_REQUESTS = 4_096

        fun isPendingDrainKey(key: String): Boolean =
            key.startsWith(KEY_PREFIX) || key.startsWith(PROVEN_PREFIX)
    }

    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
}
