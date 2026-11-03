// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.*

/**
 * Control-plane API only. The App must stop/close old transports and streams before
 * recovery; nothing here restarts an OS request or reconstructs a disclosure permit.
 */
class RootReceiptRecoveryCoordinator(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val ledger = RootDispatchLedger(db)
    private val terminalizer = RootDispatchTerminalizer(db,clock)

    fun recoverInterrupted(excludedRequestIds: Set<String> = emptySet()): List<String> {
        requireNoOrphanInvocations()
        val ids = ledger.list()
            .filter { (it.stage != RootDispatchStage.TERMINAL || !it.closed) && it.claim.requestId !in excludedRequestIds }
            .map { it.claim.requestId }
        ids.forEach { id ->
            db.transaction {
                var row = requireNotNull(ledger.get(id))
                if (row.stage != RootDispatchStage.TERMINAL) {
                    val outcome = if(row.stage == RootDispatchStage.CLAIMED)
                        RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT else RootDispatchOutcome.UNKNOWN_OUTCOME
                    // finish() records the durable pending-drain obligation for UNKNOWN_OUTCOME.
                    row = terminalizer.finish(row,outcome,null)
                } else if (!row.closed && row.outcome == RootDispatchOutcome.UNKNOWN_OUTCOME) {
                    // A previous process already settled the receipt but died before the remote
                    // drain was confirmed: keep the same obligation for the rebuilt controller.
                    RootPendingDrainRepository(db,clock).record(
                        identity = row.claim.approval.request.execution.identity,
                        requestId = row.claim.requestId,
                        degradedEpoch = row.claim.approval.request.execution.identity.identityEpoch,
                    )
                }
                if (!row.closed) terminalizer.close(row,false)
            }
        }
        recordHistoricalUnconfirmedDrains()
        return ids
    }

    /**
     * Backfills the drain obligation for authorities that are still degraded but whose uncertain
     * receipts a previous build closed without remote proof (`TERMINAL` + `closed=1`).
     *
     * The authenticated recovery watermark is the tombstone for sessions that already drained:
     * `recoveryAckEpoch` only advances after a proof, so any receipt at or below it is covered and
     * must not be resurrected by a later, independent degradation (its transport is long gone).
     */
    private fun recordHistoricalUnconfirmedDrains() = db.transaction {
        val roots = RootAuthorityRepository(db, clock)
        val drains = RootPendingDrainRepository(db, clock)
        ledger.list()
            .filter { it.outcome == RootDispatchOutcome.UNKNOWN_OUTCOME }
            .groupBy { it.claim.approval.request.execution.identity.authority }
            .forEach { (authority, rows) ->
                val state = roots.get(authority)
                val degradedEpoch = state.degradedEpoch ?: return@forEach
                // A request whose drain was already proven must not be re-attached by a later
                // startup scan: partial progress is durable per request, not only via the global
                // acknowledgement epoch (which only advances after every session drained).
                val proven = drains.provenRequests(authority)
                rows.forEach { row ->
                    val identity = row.claim.approval.request.execution.identity
                    if (identity.identityEpoch <= degradedEpoch &&
                        identity.identityEpoch > state.recoveryAckEpoch &&
                        row.claim.requestId !in proven
                    ) {
                        drains.record(identity, row.claim.requestId, degradedEpoch)
                    }
                }
            }
    }

    /**
     * Rejects recovery unless the durable obligation is readable and discharged by a snapshot that
     * proves the exact recorded identity quiescent.
     *
     * Multiple leaked identities are drained one by one: a proof covering one of them removes only
     * that obligation and returns `false`, so the remaining identities keep the authority degraded.
     * Returns `true` only when every recorded session is the proven one.
     */
    private fun settleDrainObligation(
        authority: Authority,
        identity: PrivilegedSessionIdentity?,
        snapshot: RootRemoteQuiescenceSnapshot?,
    ): Boolean {
        val drains = RootPendingDrainRepository(db, clock)
        return when (val load = drains.load(authority)) {
            RootPendingDrainLoad.Missing -> true
            is RootPendingDrainLoad.Invalid -> throw IllegalArgumentException(
                "Root recovery requires readable drain evidence: ${load.reason}",
            )
            is RootPendingDrainLoad.Present -> {
                require(load.state.provenBy(identity, snapshot)) {
                    "Root recovery requires verified remote quiescence for every unconfirmed session"
                }
                if (load.state.sessions.all { identity != null && it.matches(identity) }) {
                    true
                } else {
                    drains.clearProvenSession(authority, requireNotNull(identity), snapshot)
                    false
                }
            }
        }
    }

    /** Atomic epoch advance + watermark acknowledgement after remote quiescence is proven. */
    fun rotateEpochAndAcknowledgeRecovery(
        authority: Authority,
        userConfirmed: Boolean,
        expectedSessionIdentity: PrivilegedSessionIdentity?,
        quiescenceSnapshot: RootRemoteQuiescenceSnapshot?,
    ): RootAuthorityRecord? = db.transaction {
        if (!userConfirmed) return@transaction null
        requireNoOrphanInvocations()
        if (expectedSessionIdentity != null || quiescenceSnapshot != null) {
            require(
                expectedSessionIdentity != null &&
                    quiescenceSnapshot != null &&
                    quiescenceSnapshot.provesSessionQuiescent(expectedSessionIdentity),
            ) { "Root recovery requires verified remote quiescence proof" }
        }
        val drains = RootPendingDrainRepository(db, clock)
        // The durable obligation replaces the process-local session maps: a missing argument is
        // never evidence that the old remote drained.
        if (!settleDrainObligation(authority, expectedSessionIdentity, quiescenceSnapshot)) {
            return@transaction null
        }
        val roots = RootAuthorityRepository(db, clock)
        val current = roots.get(authority)
        if (!current.degraded) return@transaction current
        val relevant = ledger.list().filter {
            it.claim.approval.request.execution.identity.authority == authority &&
                it.claim.approval.request.execution.identity.identityEpoch <= current.identityEpoch
        }
        relevant.forEach {
            require(it.stage == RootDispatchStage.TERMINAL && it.closed) { "Root requests must drain before recovery" }
            terminalizer.requireAudit(it)
        }
        val rotated = roots.advanceIdentityEpoch(authority, current.revision) ?: return@transaction null
        val acknowledged = roots.acknowledgeRecovery(authority, rotated.revision, rotated.identityEpoch, userConfirmed = true)
        if (acknowledged != null && expectedSessionIdentity != null) {
            drains.clearProvenSession(authority, expectedSessionIdentity, quiescenceSnapshot)
        }
        acknowledged
    }

    /** The only public acknowledgement route: draining and advancing the watermark are atomic. */
    fun acknowledgeRecovery(
        authority: Authority,
        expectedRevision: Long,
        verifiedIdentityEpoch: Long,
        userConfirmed: Boolean,
        expectedSessionIdentity: PrivilegedSessionIdentity? = null,
        quiescenceSnapshot: RootRemoteQuiescenceSnapshot? = null,
    ): RootAuthorityRecord? = db.transaction {
        requireNoOrphanInvocations()
        if (expectedSessionIdentity != null || quiescenceSnapshot != null) {
            require(
                expectedSessionIdentity != null &&
                    quiescenceSnapshot != null &&
                    quiescenceSnapshot.provesSessionQuiescent(expectedSessionIdentity),
            ) { "Root recovery requires verified remote quiescence proof" }
        }
        val drains = RootPendingDrainRepository(db, clock)
        if (!settleDrainObligation(authority, expectedSessionIdentity, quiescenceSnapshot)) {
            return@transaction null
        }
        val relevant = ledger.list().filter { it.claim.approval.request.execution.identity.authority == authority &&
            it.claim.approval.request.execution.identity.identityEpoch < verifiedIdentityEpoch }
        relevant.forEach {
            require(it.stage == RootDispatchStage.TERMINAL && it.closed) { "Root requests must drain before recovery" }
            terminalizer.requireAudit(it)
        }
        val acknowledged = RootAuthorityRepository(db,clock)
            .acknowledgeRecovery(authority,expectedRevision,verifiedIdentityEpoch,userConfirmed)
        if (acknowledged != null && expectedSessionIdentity != null) {
            drains.clearProvenSession(authority, expectedSessionIdentity, quiescenceSnapshot)
        }
        acknowledged
    }

    fun deleteSettledReceipt(requestId: String): Boolean = db.transaction {
        val row = ledger.get(requestId) ?: return@transaction false
        require(row.stage == RootDispatchStage.TERMINAL && row.closed)
        terminalizer.requireAudit(row)
        val invocation = db.query("SELECT state FROM tool_invocations WHERE invocation_id=?",
            listOf(row.claim.approval.request.invocationId)).single().string("state")
        require(invocation in setOf("SUCCEEDED","FAILED","CANCELLED","UNKNOWN_OUTCOME","COMPLETED_WITHHELD"))
        db.execute("DELETE FROM root_dispatch_receipts WHERE request_id=?",listOf(requestId))
        check(ledger.get(requestId) == null)
        true
    }

    internal fun requireNoOrphanInvocations() {
        require(db.query("""SELECT i.invocation_id FROM tool_invocations i
            LEFT JOIN root_dispatch_receipts r ON r.invocation_id=i.invocation_id
            WHERE i.state GLOB 'ROOT_DISPATCH_*' AND r.request_id IS NULL LIMIT 1""".trimIndent()).isEmpty()) {
            "Root invocation has no dispatch evidence; manual recovery is required"
        }
    }
}
