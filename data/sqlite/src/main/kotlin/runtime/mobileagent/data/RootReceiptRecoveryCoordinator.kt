// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.SerializationException
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

    fun recoverInterrupted(excludedRequestIds: Set<String> = emptySet(), authority: Authority? = null): List<String> {
        requireNoOrphanInvocations()
        val ids = ledger.listOpen(authority)
            .filter { it.claim.requestId !in excludedRequestIds }
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
        Authority.entries.filter { it.isRootAuthority }.forEach { authority ->
                val state = roots.get(authority)
                val degradedEpoch = state.degradedEpoch ?: return@forEach
                // A request whose drain was already proven must not be re-attached by a later
                // startup scan: partial progress is durable per request, not only via the global
                // acknowledgement epoch (which only advances after every session drained).
                val proven = drains.provenRequests(authority)
                ledger.readableUnknownForBackfill(authority, state.recoveryAckEpoch, degradedEpoch).forEach { row ->
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

    /** Persist the authenticated kernel boot_id before attaching each new healthy session. */
    fun recordSessionBoot(authority: Authority, kernelBootId: String?, elapsedRealtimeMs: Long? = null): Boolean = db.transaction {
        if (RootAuthorityRepository(db, clock).get(authority).degraded ||
            RootPendingDrainRepository(db, clock).load(authority) !is RootPendingDrainLoad.Missing
        ) return@transaction false
        writeBoot(authority, kernelBootId?.takeIf(RootKernelBootIdentity::isValid) ?: "unknown")
        writeElapsed(authority, elapsedRealtimeMs)
        true
    }

    /**
     * Controller-only recovery, with no live local sessions/permits. A later OS boot proves that
     * old workers cannot survive, but says nothing about their effects: UNKNOWN/audit stay intact.
     */
    fun acknowledgeDeviceRestart(
        authority: Authority, kernelBootId: String?, userConfirmed: Boolean, expectedRevision: Long,
        elapsedRealtimeMs: Long? = null,
    ): RootAuthorityRecord? =
        db.transaction {
            if (!userConfirmed) return@transaction null
            val current = RootAuthorityRepository(db, clock).get(authority)
            if (current.revision != expectedRevision) return@transaction null
            if (!current.degraded) return@transaction current
            val drains = RootPendingDrainRepository(db, clock)
            if (drains.load(authority) is RootPendingDrainLoad.Invalid) return@transaction null
            val previous = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(bootKey(authority)))
                .singleOrNull()?.string("value")
            val previousElapsed = db.query("SELECT value FROM app_prefs WHERE key=?", listOf(elapsedKey(authority)))
                .singleOrNull()?.string("value")?.toLongOrNull()
            val hasBootIds = RootKernelBootIdentity.isValid(previous) && RootKernelBootIdentity.isValid(kernelBootId)
            // CLOCK_BOOTTIME cannot decrease during one boot. When the UUID is unreadable, a
            // decrease from a previously authenticated observation still proves a later boot.
            val restarted = if (hasBootIds) kernelBootId != previous else
                elapsedRealtimeMs != null && elapsedRealtimeMs > 0 && previousElapsed != null &&
                    elapsedRealtimeMs < previousElapsed
            if (!restarted) {
                if (!RootKernelBootIdentity.isValid(previous) && RootKernelBootIdentity.isValid(kernelBootId)) {
                    writeBoot(authority, requireNotNull(kernelBootId))
                }
                if (elapsedRealtimeMs != null && elapsedRealtimeMs > (previousElapsed ?: 0L)) {
                    writeElapsed(authority, elapsedRealtimeMs)
                }
                return@transaction null
            }
            recoverInterrupted(authority = authority)
            val relevant = ledger.listForAuthority(authority)
            relevant.forEach {
                check(it.stage == RootDispatchStage.TERMINAL && it.closed)
                terminalizer.requireAudit(it)
            }
            drains.clearAfterVerifiedDeviceRestart(authority)
            val recovered = checkNotNull(rotateEpochAndAcknowledgeRecovery(authority, true, null, null))
            writeBoot(authority, kernelBootId?.takeIf(RootKernelBootIdentity::isValid) ?: "unknown")
            writeElapsed(authority, elapsedRealtimeMs)
            recovered
        }

    private fun elapsedKey(authority: Authority) = RootPendingDrainRepository.BOOT_PREFIX + "elapsed-v1:" + authority.name

    private fun writeElapsed(authority: Authority, elapsedRealtimeMs: Long?) {
        db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            listOf(elapsedKey(authority), elapsedRealtimeMs?.takeIf { it > 0 }?.toString() ?: "unknown"))
    }

    private fun bootKey(authority: Authority) = RootPendingDrainRepository.KERNEL_BOOT_PREFIX + authority.name

    private fun writeBoot(authority: Authority, value: String) {
        db.execute("INSERT INTO app_prefs(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            listOf(bootKey(authority), value))
    }

    /** Closed UNKNOWN effects may recover when every uncertain request has its own drain proof. */
    fun allUncertainRequestsProven(authority: Authority): Boolean = db.transaction {
        if (RootPendingDrainRepository(db, clock).load(authority) !is RootPendingDrainLoad.Missing) return@transaction false
        val state = RootAuthorityRepository(db, clock).get(authority)
        val rows = ledger.listForAuthority(authority, state.identityEpoch)
        val uncertain = rows.filter { it.outcome == RootDispatchOutcome.UNKNOWN_OUTCOME &&
            it.claim.approval.request.execution.identity.identityEpoch > state.recoveryAckEpoch }
        val proven = RootPendingDrainRepository(db, clock).provenRequests(authority)
        uncertain.isNotEmpty() && rows.all { it.stage == RootDispatchStage.TERMINAL && it.closed } &&
            uncertain.all { it.claim.requestId in proven }
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
        val relevant = ledger.listForAuthority(authority, current.identityEpoch)
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
        if (verifiedIdentityEpoch <= 0) return@transaction null
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
        val relevant = ledger.listForAuthority(authority, verifiedIdentityEpoch - 1)
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

    /**
     * Bounded startup maintenance.
     *
     * Only the receipt row is removed. Parent approvals/invocations and audit evidence are
     * intentionally retained: the receipt FKs point at them with ON DELETE RESTRICT and ADR 0057
     * §10 keeps audit/last-recovery evidence. A malformed historical claim is a row-local defect:
     * skip and preserve that receipt instead of aborting the optional maintenance transaction.
     */
    fun pruneSettledReceipts(excludedRequestIds: Set<String> = emptySet(), limit: Int = 128): Int = db.transaction {
        require(limit in 1..256)
        val cutoff = java.time.Instant.parse(clock()).minus(java.time.Duration.ofDays(30)).toString()
        val candidates = db.query("""SELECT r.request_id FROM root_dispatch_receipts r
            JOIN runs u ON u.run_id=r.run_id WHERE r.closed=1 AND r.stage='TERMINAL' AND julianday(r.finished_at)<julianday(?)
            AND u.state IN('COMPLETED','FAILED','CANCELLED','BUDGET_EXHAUSTED','UNKNOWN_OUTCOME')
            AND (r.outcome<>'UNKNOWN_OUTCOME' OR r.identity_epoch<=COALESCE(
                (SELECT recovery_ack_epoch FROM root_authority_state a WHERE a.authority=r.authority),0))
            ORDER BY julianday(r.finished_at),r.request_id LIMIT ?""".trimIndent(), listOf(cutoff, limit))
        candidates.count { it.string("request_id") !in excludedRequestIds && pruneSettledReceipt(it.string("request_id")) }
    }

    /**
     * Row-local deletion attempt for startup maintenance. Decode/evidence failures are expected
     * for historical rows written by older builds; they must not roll back the whole page or make
     * startup recovery unavailable. Genuine database failures still propagate.
     */
    private fun pruneSettledReceipt(requestId: String): Boolean = try {
        deleteSettledReceipt(requestId)
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun deleteSettledReceipt(requestId: String): Boolean = db.transaction {
        val row = ledger.get(requestId) ?: return@transaction false
        require(row.stage == RootDispatchStage.TERMINAL && row.closed)
        terminalizer.requireAudit(row)
        val identity = row.claim.approval.request.execution.identity
        val drains = RootPendingDrainRepository(db, clock)
        if (row.outcome == RootDispatchOutcome.UNKNOWN_OUTCOME) {
            val state = RootAuthorityRepository(db, clock).get(identity.authority)
            val pending = drains.load(identity.authority)
            if (pending is RootPendingDrainLoad.Invalid ||
                pending is RootPendingDrainLoad.Present && pending.state.sessions.any {
                    it.matches(identity) && (it.sessionWide || requestId in it.requestIds)
                } ||
                // Keep uncertain history until the authority acknowledges the proof; it may be the
                // only durable evidence permitting same-boot recovery after the helper dies.
                identity.identityEpoch > state.recoveryAckEpoch
            ) return@transaction false
        }
        val invocation = db.query("SELECT state FROM tool_invocations WHERE invocation_id=?",
            listOf(row.claim.approval.request.invocationId)).single().string("state")
        require(invocation in setOf("SUCCEEDED","FAILED","CANCELLED","UNKNOWN_OUTCOME","COMPLETED_WITHHELD"))
        db.execute("DELETE FROM root_dispatch_receipts WHERE request_id=?",listOf(requestId))
        check(ledger.get(requestId) == null)
        drains.forgetDeletedReceipt(identity.authority, requestId)
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
