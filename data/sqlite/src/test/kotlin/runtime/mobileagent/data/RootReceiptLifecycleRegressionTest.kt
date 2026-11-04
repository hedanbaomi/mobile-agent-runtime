// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

/** Real SQLite approval/dispatch/drain/retention seams; no OS authentication or device claims. */
class RootReceiptLifecycleRegressionTest {
    private val authority = Authority.ROOT_DIRECT

    @Test
    fun claimedWorkIsLocallyCancellableButAttemptedAcknowledgedAndUnknownWorkRemainUnconfirmed() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val local = h.claim()
            assertTrue(h.dispatch.hasActivePermitsForAuthority(authority))
            assertFalse(h.dispatch.hasUnconfirmedRemoteWork(authority), "A reservation is not a wire attempt")
            h.dispatch.abortClaimBeforeAttempt(local)
            assertEquals(RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT, h.receipt(local).outcome)
            assertFalse(h.dispatch.hasUnconfirmedRemoteWork(authority))
            assertFalse(RootAuthorityRepository(db).get(authority).degraded)

            val remote = h.claim()
            h.dispatch.takeAttempt(h.dispatch.beginAttempt(remote), h.arguments)
            assertEquals(RootDispatchStage.ATTEMPTED, h.receipt(remote).stage)
            assertTrue(h.dispatch.hasUnconfirmedRemoteWork(authority))
            h.dispatch.acknowledge(remote)
            assertEquals(RootDispatchStage.ACKNOWLEDGED, h.receipt(remote).stage)
            assertTrue(h.dispatch.hasUnconfirmedRemoteWork(authority))
            h.dispatch.finish(remote, RootDispatchOutcome.UNKNOWN_OUTCOME)
            assertTrue(h.dispatch.hasUnconfirmedRemoteWork(authority))
            val unknown = h.receipt(remote)
            val audit = h.audit(unknown)
            h.dispatch.markRemoteAttemptQuiescent(remote.requestId)
            assertFalse(h.dispatch.hasUnconfirmedRemoteWork(authority))
            assertEquals(RootDispatchOutcome.UNKNOWN_OUTCOME, h.receipt(remote).outcome)
            assertEquals(RootDisclosureState.WITHHELD, h.receipt(remote).disclosure)
            assertEquals(audit, h.audit(h.receipt(remote)))
            assertTrue(h.drains.load(authority) is RootPendingDrainLoad.Missing)
        }
    }

    @Test
    fun lateRequestProofAfterCancellationClearsOnlyItsRequestForBothSettlementRoutes() {
        for (throughFinish in listOf(false, true)) {
            JdbcSqlConnection().use { db ->
                val h = Harness(db)
                val first = h.attempt(remoteActive = true)
                val second = h.attempt(remoteActive = true)
                h.cancel(first)
                h.cancel(second)
                val firstUnknown = h.receipt(first)
                val firstAudit = h.audit(firstUnknown)
                val secondUnknown = h.receipt(second)
                val otherIdentity = h.identity.copy(serviceInstanceId = "other-root-service", sessionId = "other-root-session")
                h.drains.record(otherIdentity, "other-session-request", h.identity.identityEpoch)

                if (throughFinish) {
                    assertEquals(firstUnknown, h.dispatch.finish(first, RootDispatchOutcome.UNKNOWN_OUTCOME, remoteQuiescent = true))
                    h.dispatch.markRemoteAttemptQuiescent(first.requestId)
                } else h.dispatch.markRemoteAttemptQuiescent(first.requestId)

                val pending = h.pending()
                val sameSession = pending.sessions.single { it.matches(h.identity) }
                assertEquals(listOf(second.requestId), sameSession.requestIds)
                assertFalse(sameSession.sessionWide)
                assertEquals(listOf("other-session-request"), pending.sessions.single { it.matches(otherIdentity) }.requestIds)
                assertTrue(first.requestId in h.drains.provenRequests(authority))
                assertFalse(second.requestId in h.drains.provenRequests(authority))
                assertEquals(RootDispatchOutcome.UNKNOWN_OUTCOME, h.receipt(first).outcome)
                assertEquals(RootDisclosureState.WITHHELD, h.receipt(first).disclosure)
                assertEquals(firstAudit, h.audit(h.receipt(first)))
                assertEquals(secondUnknown, h.receipt(second), "A proof for A must not terminalize or close B")

                RootReceiptRecoveryCoordinator(db) { h.now }.recoverInterrupted()
                assertFalse(h.pending().sessions.any { first.requestId in it.requestIds }, "Startup must not resurrect the proven request")
                assertTrue(h.pending().sessions.any { second.requestId in it.requestIds })
                assertTrue(h.pending().sessions.any { it.matches(otherIdentity) })
            }
        }
    }

    @Test
    fun lateProofPreservesAnExplicitSessionWideObligationEvenAfterItsLastRequestDrains() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val request = h.attempt(remoteActive = true)
            h.cancel(request)
            h.drains.record(h.identity, null, h.identity.identityEpoch)
            val audit = h.audit(h.receipt(request))
            h.dispatch.markRemoteAttemptQuiescent(request.requestId)
            val remaining = h.pending().sessions.single()
            assertTrue(remaining.matches(h.identity))
            assertTrue(remaining.sessionWide)
            assertTrue(remaining.requestIds.isEmpty())
            assertFalse(h.recovery.allUncertainRequestsProven(authority))
            assertEquals(audit, h.audit(h.receipt(request)))
        }
    }

    @Test
    fun allPerRequestProofsPermitEpochRecoveryWithoutRewritingUnknownEffects() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val first = h.attempt(remoteActive = true)
            val second = h.attempt(remoteActive = true)
            h.cancel(first)
            h.cancel(second)
            val firstAudit = h.audit(h.receipt(first))
            assertFalse(h.recovery.allUncertainRequestsProven(authority))
            h.dispatch.markRemoteAttemptQuiescent(first.requestId)
            assertFalse(h.recovery.allUncertainRequestsProven(authority), "One unproven request must still block")
            h.dispatch.markRemoteAttemptQuiescent(second.requestId)
            assertTrue(h.recovery.allUncertainRequestsProven(authority))
            val before = RootAuthorityRepository(db).get(authority)
            assertTrue(before.degraded)
            val recovered = h.recovery.rotateEpochAndAcknowledgeRecovery(authority, true, null, null)!!
            assertFalse(recovered.degraded)
            assertEquals(before.identityEpoch + 1, recovered.identityEpoch)
            assertEquals(before.consent, recovered.consent, "Recovery is not new Root/Shell consent")
            assertEquals(RootDispatchOutcome.UNKNOWN_OUTCOME, h.receipt(first).outcome)
            assertEquals(RootDisclosureState.WITHHELD, h.receipt(first).disclosure)
            assertEquals(firstAudit, h.audit(h.receipt(first)))
        }
    }

    @Test
    fun pruningRetainsUnacknowledgedUnknownsNewRowsAndTheirAuditWhileDeletingOldKnownHistory() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val oldSuccess = h.attempt()
            val oldProvenUnknown = h.attempt()
            val oldUnresolvedUnknown = h.attempt()
            val recent = h.attempt()
            val exactlyThirtyDays = h.attempt()
            val youngerByFraction = h.attempt()
            h.settle(oldSuccess, RootDispatchOutcome.SUCCEEDED)
            h.settle(oldProvenUnknown, RootDispatchOutcome.UNKNOWN_OUTCOME, proven = true)
            h.settle(oldUnresolvedUnknown, RootDispatchOutcome.UNKNOWN_OUTCOME)
            h.now = "2026-11-15T11:00:00Z"
            h.settle(recent, RootDispatchOutcome.SUCCEEDED)
            h.now = "2026-11-01T11:00:00Z"
            h.settle(exactlyThirtyDays, RootDispatchOutcome.FAILED)
            h.now = "2026-11-01T11:00:00.500Z"
            h.settle(youngerByFraction, RootDispatchOutcome.SUCCEEDED)
            h.completeRun()
            val deleted = listOf(oldSuccess).associateWith { h.receipt(it) }
            val audit = deleted.mapValues { h.audit(it.value) }
            val invocations = deleted.mapValues { h.invocation(it.key) }
            h.now = "2026-12-01T11:00:00Z"
            assertEquals(1, h.recovery.pruneSettledReceipts())
            deleted.forEach { (permit, receipt) ->
                assertNull(h.dispatch.getReceipt(permit.requestId))
                assertEquals(audit.getValue(permit), h.audit(receipt))
                assertEquals(invocations.getValue(permit), h.invocation(permit))
            }
            assertNotNull(h.dispatch.getReceipt(recent.requestId))
            assertNotNull(h.dispatch.getReceipt(exactlyThirtyDays.requestId), "The thirty-day boundary is retained")
            assertNotNull(h.dispatch.getReceipt(youngerByFraction.requestId), "Variable ISO fractional precision must not delete history before thirty days")
            assertNotNull(h.dispatch.getReceipt(oldUnresolvedUnknown.requestId))
            assertNotNull(h.dispatch.getReceipt(oldProvenUnknown.requestId), "Per-request proof remains until epoch acknowledgement")
            assertTrue(h.pending().sessions.any { oldUnresolvedUnknown.requestId in it.requestIds })
            assertTrue(oldProvenUnknown.requestId in h.drains.provenRequests(authority))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    fun pruningDeletesAtMost128AndSkipsExcludedReceiptsWithoutLosingTheirHistory() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val permits = List(131) { h.attempt() }
            permits.forEachIndexed { index, permit ->
                h.now = Instant.parse(h.fixture.now).plusSeconds(index.toLong()).toString()
                h.settle(permit, RootDispatchOutcome.SUCCEEDED)
            }
            h.completeRun()
            h.now = "2026-12-01T11:00:00Z"
            assertEquals(0, h.recovery.pruneSettledReceipts(permits.mapTo(mutableSetOf()) { it.requestId }))
            permits.forEach { assertNotNull(h.dispatch.getReceipt(it.requestId), "Excluded receipts must be retained") }
            assertEquals(128, h.recovery.pruneSettledReceipts())
            val removed = permits.filter { h.dispatch.getReceipt(it.requestId) == null }
            assertEquals(128, removed.size)
            assertEquals(3, permits.count { h.dispatch.getReceipt(it.requestId) != null })
            assertEquals(131, db.query("SELECT invocation_id FROM tool_invocations").size)
            assertEquals(131, db.query("SELECT audit_id FROM root_audit_bindings").size)
            // Parent approval/invocation rows and audit history are retained by contract. The
            // receipt FK is ON DELETE RESTRICT and ADR 0057 keeps audit/last-recovery evidence;
            // maintenance must not grow into an unbounded audit purge.
            assertEquals(131, db.query("SELECT approval_id FROM approval_records").size)
            assertEquals(131, db.query("SELECT approval_id FROM root_approval_bindings").size)
            assertEquals(131, db.query("SELECT audit_id FROM tool_audit_details").size)
            assertEquals(131, db.query("SELECT id FROM audit_events").size)
        }
    }

    @Test
    fun malformedHistoricalClaimIsSkippedAndPreservedWithoutBlockingStartupMaintenance() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val corrupt = h.attempt()
            val healthy = h.attempt()
            h.settle(corrupt, RootDispatchOutcome.SUCCEEDED)
            h.now = Instant.parse(h.fixture.now).plusSeconds(1).toString()
            h.settle(healthy, RootDispatchOutcome.SUCCEEDED)
            h.completeRun()
            // Simulate a row written by an older/broken build without rewriting its evidence.
            db.execute("DROP TRIGGER root_dispatch_claim_immutable")
            db.execute("DROP TRIGGER root_dispatch_transition")
            db.execute("UPDATE root_dispatch_receipts SET claim_json=? WHERE request_id=?",
                listOf("{broken-claim", corrupt.requestId))
            h.now = "2026-12-01T11:00:00Z"

            assertEquals(1, h.recovery.pruneSettledReceipts(), "A malformed row must not abort the page")
            assertNull(h.dispatch.getReceipt(healthy.requestId))
            assertEquals(1, db.query("SELECT request_id FROM root_dispatch_receipts WHERE request_id=?",
                listOf(corrupt.requestId)).size, "The malformed receipt is skipped and preserved")
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    fun corruptClosedUnknownDoesNotBlockHealthyBackfillButCannotAuthorizeRecovery() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val corrupt = h.attempt()
            val healthy = h.attempt()
            h.settle(corrupt, RootDispatchOutcome.UNKNOWN_OUTCOME)
            h.settle(healthy, RootDispatchOutcome.UNKNOWN_OUTCOME)
            // Old builds could close unknown receipts without a durable drain obligation.
            db.execute("DELETE FROM app_prefs WHERE key=?",
                listOf(RootPendingDrainRepository.KEY_PREFIX + authority.name))
            db.execute("DROP TRIGGER root_dispatch_claim_immutable")
            db.execute("DROP TRIGGER root_dispatch_transition")
            db.execute("UPDATE root_dispatch_receipts SET claim_json=? WHERE request_id=?",
                listOf("{broken-unknown", corrupt.requestId))

            assertTrue(h.recovery.recoverInterrupted().isEmpty())
            assertEquals(listOf(healthy.requestId), h.pending().sessions.single().requestIds)
            assertEquals("{broken-unknown", db.query(
                "SELECT claim_json FROM root_dispatch_receipts WHERE request_id=?", listOf(corrupt.requestId),
            ).single().string("claim_json"))
            h.drains.markRequestQuiescent(h.identity, healthy.requestId)
            assertTrue(h.drains.load(authority) is RootPendingDrainLoad.Missing)
            // Backfill's row-local tolerance must never be used by proof/acknowledgement reads.
            assertThrows(kotlinx.serialization.SerializationException::class.java) {
                h.recovery.allUncertainRequestsProven(authority)
            }
            assertThrows(kotlinx.serialization.SerializationException::class.java) {
                h.recovery.rotateEpochAndAcknowledgeRecovery(authority, true, null, null)
            }
            assertTrue(RootAuthorityRepository(db).get(authority).degraded)
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    fun unresolvedOldUnknownsDoNotPermanentlyStarveALaterDeletableReceipt() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val unresolved = List(129) { h.attempt() }
            val safe = h.attempt()
            unresolved.forEach { h.settle(it, RootDispatchOutcome.UNKNOWN_OUTCOME) }
            h.now = Instant.parse(h.fixture.now).plusSeconds(1).toString()
            h.settle(safe, RootDispatchOutcome.SUCCEEDED)
            h.completeRun()
            h.now = "2026-12-01T11:00:00Z"
            assertEquals(1, h.recovery.pruneSettledReceipts(), "The oldest unresolved page must not block all later retention work")
            assertNull(h.dispatch.getReceipt(safe.requestId))
            unresolved.forEach { assertNotNull(h.dispatch.getReceipt(it.requestId)) }
            assertEquals(129, h.pending().sessions.single().requestIds.size)
        }
    }

    @Test
    fun pruningCannotEraseTheLastProofNeededForSameBootRecovery() {
        JdbcSqlConnection().use { db ->
            val h = Harness(db)
            val request = h.attempt(remoteActive = true)
            h.cancel(request)
            h.dispatch.markRemoteAttemptQuiescent(request.requestId)
            h.completeRun()
            val history = h.receipt(request)
            val audit = h.audit(history)
            val invocation = h.invocation(request)
            h.now = "2026-12-01T11:00:00Z"
            assertTrue(h.recovery.allUncertainRequestsProven(authority))
            h.recovery.pruneSettledReceipts()
            assertTrue(h.recovery.allUncertainRequestsProven(authority), "Maintenance must preserve the durable recovery proof until its epoch is acknowledged")
            val recovered = h.recovery.rotateEpochAndAcknowledgeRecovery(authority, true, null, null)!!
            assertFalse(recovered.degraded)
            h.recovery.pruneSettledReceipts()
            assertNull(h.dispatch.getReceipt(request.requestId), "Acknowledged history may then be pruned")
            assertEquals(audit, h.audit(history))
            assertEquals(invocation, h.invocation(request))
            assertFalse(request.requestId in h.drains.provenRequests(authority))
        }
    }

    private class Harness(private val db: SqlConnection) {
        val fixture = RootApprovalFixture()
        val identity = fixture.execution().identity
        val arguments = """{"relativePath":"file.txt","workspaceId":"root-workspace"}"""
        var now = fixture.now
        val dispatch = RootDispatchRepository(db) { now }
        val drains = RootPendingDrainRepository(db) { now }
        val recovery = RootReceiptRecoveryCoordinator(db) { now }
        private val approvals = RootApprovalRepository(db, clock = { now })
        private var next = 0

        init { fixture.setup(db, lifetime = GrantLifetime.PERSISTENT) }

        fun claim(): RootDispatchPermit {
            val index = next++
            val id = if (index == 0) "invocation-root" else "lifecycle-invocation-$index"
            db.execute("UPDATE runs SET state=? WHERE run_id='run-root'", listOf(RunStatus.WAITING_TOOL_APPROVAL.name))
            if (index == 0) db.execute("UPDATE tool_invocations SET arguments_json=? WHERE invocation_id=?", listOf(arguments, id))
            else db.execute(
                "INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf(id, "run-root", "lifecycle-model-$index", "file_read_text", arguments, "PENDING", "PENDING", now, now),
            )
            val request = fixture.request().copy(invocationId = id, normalizedArgumentsSha256 = RootReceiptDigests.text(arguments))
            val approval = approvals.approve(approvals.createPending(request).approval.approvalId)
            return dispatch.claim(approval.approval.approvalId, approval.request)
        }

        fun attempt(remoteActive: Boolean = false): RootDispatchPermit = claim().also {
            dispatch.takeAttempt(dispatch.beginAttempt(it), arguments)
            if (remoteActive) dispatch.markRemoteAttemptActive(it)
        }

        fun cancel(permit: RootDispatchPermit) = dispatch.cancelUnconfirmedInvocation(
            fixture.execution(), permit.claim.approval.request.invocationId,
        )

        fun settle(permit: RootDispatchPermit, outcome: RootDispatchOutcome, proven: Boolean = false) {
            dispatch.finish(permit, outcome, remoteQuiescent = proven)
            dispatch.closePermit(permit, disclosed = false)
        }

        fun receipt(permit: RootDispatchPermit) = requireNotNull(dispatch.getReceipt(permit.requestId))
        fun pending() = (drains.load(Authority.ROOT_DIRECT) as RootPendingDrainLoad.Present).state
        fun audit(receipt: RootDispatchReceipt): List<Any?> {
            val id = requireNotNull(receipt.terminalAuditId)
            return listOf(AuditRepository(db).get(id), AuditRepository(db).getDetail(id), RootAuditRepository(db).get(id))
                .also { values -> assertTrue(values.all { it != null }) }
        }
        fun invocation(permit: RootDispatchPermit) = RunRepository(db).invocations("run-root")
            .single { it.invocationId == permit.claim.approval.request.invocationId }
        fun completeRun() { db.execute("UPDATE runs SET state=? WHERE run_id='run-root'", listOf(RunStatus.COMPLETED.name)) }
    }
}
