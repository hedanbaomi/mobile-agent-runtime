// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootDispatchRepositoryTest {
    private val fixture = RootApprovalFixture()
    private val normalized = """{"relativePath":"private-scope-marker/file.txt","workspaceId":"root-workspace"}"""
    private data class Ready(val repo: RootDispatchRepository, val approval: RootApprovalBinding)
    private fun ready(db: SqlConnection, lifetime: GrantLifetime = GrantLifetime.ONCE): Ready {
        fixture.setup(db,"private-scope-marker",lifetime)
        db.execute("UPDATE tool_invocations SET arguments_json=?",listOf(normalized))
        val approvals = RootApprovalRepository(db,clock={fixture.now})
        val request = fixture.request().copy(normalizedArgumentsSha256=RootReceiptDigests.text(normalized))
        val approved = approvals.approve(approvals.createPending(request).approval.approvalId)
        return Ready(RootDispatchRepository(db) { fixture.now },approved)
    }
    private fun claim(ready: Ready) = ready.repo.claim(ready.approval.approval.approvalId,ready.approval.request)
    private fun attempted(ready: Ready): RootDispatchPermit {
        val permit = claim(ready)
        ready.repo.takeAttempt(ready.repo.beginAttempt(permit),normalized)
        return permit
    }

    @Test fun onceClaimConsumesAtomicallyAndOnlyItsLivePermitCanDiscloseTheResult() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = claim(r)
            val after = CapabilityGrantRepository(db).get("root-grant")!!
            assertEquals(2,after.revision)
            assertNotNull(after.consumedAt)
            assertEquals(ApprovalDecision.CONSUMED,ApprovalRepository(db).get(r.approval.approval.approvalId)!!.decision)
            assertNull(RootSnapshotGrantRepository(db) { fixture.now }
                .revalidate("snapshot-root","root-grant",fixture.execution()))
            assertThrows(Exception::class.java) { claim(r) }
            val ticket = r.repo.beginAttempt(permit)
            val payload = r.repo.takeAttempt(ticket,normalized)
            assertEquals(normalized,payload.normalizedArgumentsJson)
            assertEquals("private-scope-marker",payload.snapshot.grant.pathScope)
            assertThrows(IllegalArgumentException::class.java) { r.repo.takeAttempt(ticket,normalized) }
            r.repo.acknowledge(permit)
            r.repo.finish(permit,RootDispatchOutcome.SUCCEEDED,"d".repeat(64))
            assertTrue(r.repo.authorizeDisclosure(permit))
            assertFalse(r.repo.canRotate(fixture.execution().identity.sessionId,0))
            val closed = r.repo.closePermit(permit,true)
            assertEquals(RootDisclosureState.RELEASED,closed.disclosure)
            assertTrue(r.repo.canRotate(fixture.execution().identity.sessionId,0))
            assertFalse(Json.encodeToString(closed).contains("private-scope-marker"))
            assertThrows(IllegalArgumentException::class.java) { r.repo.authorizeDisclosure(permit) }
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun persistentGrantRemainsUnconsumedButTheApprovalIsStillSingleUse() {
        JdbcSqlConnection().use { db ->
            val r = ready(db,GrantLifetime.PERSISTENT)
            val before = CapabilityGrantRepository(db).get("root-grant")
            val permit = attempted(r)
            assertEquals(before,CapabilityGrantRepository(db).get("root-grant"))
            r.repo.finish(permit,RootDispatchOutcome.FAILED)
            assertTrue(r.repo.authorizeDisclosure(permit))
            r.repo.closePermit(permit,true)
            assertThrows(Exception::class.java) { claim(r) }
        }
    }

    @Test fun ignoredClaimInsertRollsBackApprovalGrantAndInvocationTogether() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val before = CapabilityGrantRepository(db).get("root-grant")
            db.execute("CREATE TRIGGER ignore_dispatch BEFORE INSERT ON root_dispatch_receipts BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { claim(r) }
            assertEquals(before,CapabilityGrantRepository(db).get("root-grant"))
            assertEquals(r.approval.approval,ApprovalRepository(db).get(r.approval.approval.approvalId))
            assertEquals("PENDING",RunRepository(db).invocations("run-root").single().state)
            assertNull(r.repo.getReceipt(r.approval.approval.requestId))
            db.execute("DROP TRIGGER ignore_dispatch")
            assertEquals(RootDispatchStage.CLAIMED,r.repo.getReceipt(claim(r).requestId)!!.stage)
        }
    }

    @Test fun identicalReceiptFactsDoNotAllowAForeignOrForgedPermit() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = claim(r)
            val foreign = RootDispatchRepository(db) { fixture.now }
            assertNotNull(foreign.getReceipt(permit.requestId))
            assertThrows(IllegalArgumentException::class.java) { foreign.beginAttempt(permit) }
            val forged = RootDispatchPermit(permit.claim,permit.snapshot,permit.afterGrant)
            assertThrows(IllegalArgumentException::class.java) { r.repo.beginAttempt(forged) }
            RootReceiptRecoveryCoordinator(db) { fixture.now }.recoverInterrupted()
            assertEquals(RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT,r.repo.getReceipt(permit.requestId)!!.outcome)
            assertThrows(Exception::class.java) { r.repo.beginAttempt(permit) }
            r.repo.closePermit(permit,false)
        }
    }

    @Test fun changedRawOrNormalizedArgumentsCannotReachTheTransportTicket() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = claim(r)
            val ticket = r.repo.beginAttempt(permit)
            assertThrows(IllegalArgumentException::class.java) { r.repo.takeAttempt(ticket,"{}") }
            db.execute("UPDATE tool_invocations SET arguments_json='{}'")
            assertThrows(IllegalArgumentException::class.java) { r.repo.takeAttempt(ticket,normalized) }
            assertEquals(RootDispatchStage.ATTEMPTED,r.repo.getReceipt(permit.requestId)!!.stage)
        }
    }

    @Test fun revocationWithholdsDataWithoutErasingAConfirmedCompletion() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            CapabilityGrantRepository(db).revoke("root-grant")
            r.repo.finish(permit,RootDispatchOutcome.SUCCEEDED)
            assertFalse(r.repo.authorizeDisclosure(permit))
            assertThrows(IllegalArgumentException::class.java) { r.repo.closePermit(permit,true) }
            assertEquals(RootDispatchOutcome.SUCCEEDED,r.repo.closePermit(permit,false).outcome)
            assertEquals("COMPLETED_WITHHELD",RunRepository(db).invocations("run-root").single().state)
            assertNotNull(AuditRepository(db).getDetail(r.repo.getReceipt(permit.requestId)!!.terminalAuditId!!))
        }
    }

    @Test fun ignoredTerminalAuditRollsBackOutcomeAndDegradedState() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            db.execute("CREATE TRIGGER ignore_end_audit BEFORE INSERT ON root_audit_bindings BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { r.repo.finish(permit,RootDispatchOutcome.UNKNOWN_OUTCOME) }
            assertEquals(RootDispatchStage.ATTEMPTED,r.repo.getReceipt(permit.requestId)!!.stage)
            assertFalse(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded)
            assertTrue(db.query("SELECT * FROM audit_events").isEmpty())
            db.execute("DROP TRIGGER ignore_end_audit")
            r.repo.finish(permit,RootDispatchOutcome.UNKNOWN_OUTCOME)
            assertEquals(1,RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degradedEpoch)
            r.repo.closePermit(permit,false)
        }
    }

    @Test fun cascadeCleanupCannotRemoveInFlightEvidenceAndOnlySettledRowsCanBeDeleted() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = claim(r)
            val recovery = RootReceiptRecoveryCoordinator(db) { fixture.now }
            assertThrows(Exception::class.java) {
                db.execute("DELETE FROM approval_records WHERE approval_id=?",listOf(r.approval.approval.approvalId))
            }
            assertNotNull(RootApprovalRepository(db).stored(r.approval.approval.approvalId))
            assertThrows(Exception::class.java) { db.execute("DELETE FROM root_dispatch_receipts") }
            assertThrows(IllegalArgumentException::class.java) { recovery.deleteSettledReceipt(permit.requestId) }
            r.repo.finish(permit,RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT)
            assertThrows(IllegalArgumentException::class.java) { recovery.deleteSettledReceipt(permit.requestId) }
            r.repo.closePermit(permit,false)
            assertTrue(recovery.deleteSettledReceipt(permit.requestId))
            db.execute("DELETE FROM approval_records WHERE approval_id=?",listOf(r.approval.approval.approvalId))
            assertNull(ApprovalRepository(db).get(r.approval.approval.approvalId))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun eitherRecoveryOrderPreservesRootOwnershipAndNeverReplays() {
        listOf(false,true).forEach { genericFirst ->
            JdbcSqlConnection().use { db ->
                val r = ready(db)
                val permit = attempted(r)
                val runs = RunRepository(db) { fixture.now }
                val recovery = RootReceiptRecoveryCoordinator(db) { fixture.now }
                if (genericFirst) {
                    runs.markInFlightUnknown()
                    assertEquals("ROOT_DISPATCH_ATTEMPTED",runs.invocations("run-root").single().state)
                }
                assertEquals(listOf(permit.requestId),recovery.recoverInterrupted())
                if (!genericFirst) runs.markInFlightUnknown()
                assertEquals(RunStatus.UNKNOWN_OUTCOME,runs.get("run-root")!!.state)
                assertEquals("UNKNOWN_OUTCOME",runs.invocations("run-root").single().state)
                assertTrue(r.repo.getReceipt(permit.requestId)!!.closed)
                assertTrue(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded)
                assertTrue(recovery.recoverInterrupted().isEmpty())
            }
        }
    }

    @Test fun recoveryAcknowledgementCannotPassAnUndrainedReceipt() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            val roots = RootAuthorityRepository(db) { fixture.now }
            var state = roots.get(Authority.ROOT_DIRECT)
            state = roots.markDegraded(Authority.ROOT_DIRECT,state.revision,1)!!
            state = roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            val recovery = RootReceiptRecoveryCoordinator(db) { fixture.now }
            assertThrows(IllegalArgumentException::class.java) {
                recovery.acknowledgeRecovery(Authority.ROOT_DIRECT,state.revision,2,true)
            }
            assertEquals(0,roots.get(Authority.ROOT_DIRECT).recoveryAckEpoch)
            recovery.recoverInterrupted()
            state = roots.get(Authority.ROOT_DIRECT)
            // Local settlement no longer discharges the obligation: without a proof for the exact
            // recorded session the watermark stays put.
            assertThrows(IllegalArgumentException::class.java) {
                recovery.acknowledgeRecovery(Authority.ROOT_DIRECT,state.revision,2,true)
            }
            assertEquals(0,roots.get(Authority.ROOT_DIRECT).recoveryAckEpoch)
            val identity = requireNotNull(r.repo.getReceipt(permit.requestId)).claim.approval.request.execution.identity
            val recovered = recovery.acknowledgeRecovery(
                Authority.ROOT_DIRECT,
                state.revision,
                2,
                true,
                expectedSessionIdentity = identity,
                quiescenceSnapshot = provenQuiescent(identity),
            )!!
            assertFalse(recovered.degraded)
            assertEquals(1,recovered.recoveryAckEpoch)
            assertEquals(RootDispatchOutcome.UNKNOWN_OUTCOME,r.repo.getReceipt(permit.requestId)!!.outcome)
        }
    }

    @Test fun aDamagedDrainRecordBlocksRecoveryAndIsNeverOverwritten() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            val roots = RootAuthorityRepository(db) { fixture.now }
            var state = roots.get(Authority.ROOT_DIRECT)
            state = roots.markDegraded(Authority.ROOT_DIRECT,state.revision,1)!!
            state = roots.advanceIdentityEpoch(Authority.ROOT_DIRECT,state.revision)!!
            val key = RootPendingDrainRepository.KEY_PREFIX + Authority.ROOT_DIRECT.name
            db.execute("INSERT OR REPLACE INTO app_prefs(key,value) VALUES(?,?)", listOf(key, "{not-a-record"))
            val recovery = RootReceiptRecoveryCoordinator(db) { fixture.now }
            recovery.recoverInterrupted()
            state = roots.get(Authority.ROOT_DIRECT)
            assertTrue(state.degraded)
            val identity = requireNotNull(r.repo.getReceipt(permit.requestId)).claim.approval.request.execution.identity
            assertThrows(IllegalArgumentException::class.java) {
                recovery.acknowledgeRecovery(
                    Authority.ROOT_DIRECT, state.revision, 2, true,
                    expectedSessionIdentity = identity,
                    quiescenceSnapshot = provenQuiescent(identity),
                )
            }
            assertEquals(
                "{not-a-record",
                db.query("SELECT value FROM app_prefs WHERE key=?", listOf(key)).single().string("value"),
                "An unreadable obligation must be preserved, not replaced or cleared",
            )
            assertTrue(roots.get(Authority.ROOT_DIRECT).degraded)
        }
    }

    @Test fun aProofForAnotherSessionDoesNotClearThePersistedObligation() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            val identity = requireNotNull(r.repo.getReceipt(permit.requestId)).claim.approval.request.execution.identity
            r.repo.finish(permit, RootDispatchOutcome.UNKNOWN_OUTCOME)
            r.repo.closePermit(permit, false)
            val roots = RootAuthorityRepository(db) { fixture.now }
            var state = roots.get(Authority.ROOT_DIRECT)
            state = roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, state.revision)!!
            // A different identity epoch is a different session identity: an older proof cannot
            // discharge the obligation recorded for the leaked session.
            val foreign = identity.copy(identityEpoch = identity.identityEpoch + 1L)
            val recovery = RootReceiptRecoveryCoordinator(db) { fixture.now }
            assertThrows(IllegalArgumentException::class.java) {
                recovery.acknowledgeRecovery(
                    Authority.ROOT_DIRECT, state.revision, state.identityEpoch, true,
                    expectedSessionIdentity = foreign,
                    quiescenceSnapshot = provenQuiescent(foreign),
                )
            }
            val drain = RootPendingDrainRepository(db) { fixture.now }.load(Authority.ROOT_DIRECT)
            assertTrue(drain is RootPendingDrainLoad.Present, "The original session's obligation must survive a foreign proof")
            assertTrue(roots.get(Authority.ROOT_DIRECT).degraded)
        }
    }

    @Test fun historicalClosedUnknownReceiptStillObligatesRemoteProof() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            r.repo.finish(permit, RootDispatchOutcome.UNKNOWN_OUTCOME)
            r.repo.closePermit(permit, false)
            // Emulate a build that closed the receipt without recording the obligation.
            db.execute(
                "DELETE FROM app_prefs WHERE key=?",
                listOf(RootPendingDrainRepository.KEY_PREFIX + Authority.ROOT_DIRECT.name),
            )
            val roots = RootAuthorityRepository(db) { fixture.now }
            var state = roots.get(Authority.ROOT_DIRECT)
            state = roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, state.revision)!!
            val recovery = RootReceiptRecoveryCoordinator(db) { fixture.now }
            recovery.recoverInterrupted()
            assertThrows(IllegalArgumentException::class.java) {
                recovery.acknowledgeRecovery(Authority.ROOT_DIRECT, state.revision, state.identityEpoch, true)
            }
            val identity = requireNotNull(r.repo.getReceipt(permit.requestId)).claim.approval.request.execution.identity
            val recovered = recovery.acknowledgeRecovery(
                Authority.ROOT_DIRECT, state.revision, state.identityEpoch, true,
                expectedSessionIdentity = identity,
                quiescenceSnapshot = provenQuiescent(identity),
            )!!
            assertFalse(recovered.degraded)
        }
    }

    /**
     * A session whose drain was already acknowledged must not be resurrected by a later,
     * independent degradation: its transport is gone, so an old obligation would block forever.
     */
    @Test fun anAcknowledgedSessionIsNotResurrectedByALaterDegradation() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            val identity = requireNotNull(r.repo.getReceipt(permit.requestId)).claim.approval.request.execution.identity
            r.repo.finish(permit, RootDispatchOutcome.UNKNOWN_OUTCOME)
            r.repo.closePermit(permit, false)
            val roots = RootAuthorityRepository(db) { fixture.now }
            var state = roots.get(Authority.ROOT_DIRECT)
            state = roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, state.revision)!!
            val recovery = RootReceiptRecoveryCoordinator(db) { fixture.now }
            recovery.recoverInterrupted()
            val first = recovery.acknowledgeRecovery(
                Authority.ROOT_DIRECT, state.revision, state.identityEpoch, true,
                expectedSessionIdentity = identity,
                quiescenceSnapshot = provenQuiescent(identity),
            )!!
            assertFalse(first.degraded)
            assertEquals(1L, first.recoveryAckEpoch)
            assertEquals(RootPendingDrainLoad.Missing, RootPendingDrainRepository(db).load(Authority.ROOT_DIRECT))

            // Second, unrelated degradation at the next identity epoch.
            state = roots.get(Authority.ROOT_DIRECT)
            state = roots.markDegraded(Authority.ROOT_DIRECT, state.revision, state.identityEpoch)!!
            assertTrue(state.degraded)
            recovery.recoverInterrupted()
            assertEquals(
                RootPendingDrainLoad.Missing,
                RootPendingDrainRepository(db) { fixture.now }.load(Authority.ROOT_DIRECT),
                "An already acknowledged epoch must stay tombstoned by recoveryAckEpoch",
            )
            val second = recovery.rotateEpochAndAcknowledgeRecovery(
                Authority.ROOT_DIRECT, userConfirmed = true,
                expectedSessionIdentity = null, quiescenceSnapshot = null,
            )!!
            assertFalse(second.degraded)
            assertEquals(2L, second.recoveryAckEpoch)
        }
    }

    @Test fun leakedIdentitiesAreDrainedOneAtATime() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val drains = RootPendingDrainRepository(db) { fixture.now }
            val first = fixture.execution().identity
            val second = first.copy(identityEpoch = first.identityEpoch + 1L)
            drains.record(first, "req-first", first.identityEpoch)
            drains.record(second, "req-second", second.identityEpoch)
            val loaded = drains.load(Authority.ROOT_DIRECT)
            assertTrue(loaded is RootPendingDrainLoad.Present)
            assertEquals(2, (loaded as RootPendingDrainLoad.Present).state.sessions.size)

            // A proof for the first identity removes only its own obligation.
            val afterFirst = drains.clearProvenSession(Authority.ROOT_DIRECT, first, provenQuiescent(first))
            assertTrue(afterFirst is RootPendingDrainLoad.Present)
            assertEquals(
                listOf(second.identityEpoch),
                (afterFirst as RootPendingDrainLoad.Present).state.sessions.map { it.identityEpoch },
            )

            // Replaying the same proof cannot clear the remaining identity.
            val stillBlocked = drains.clearProvenSession(Authority.ROOT_DIRECT, first, provenQuiescent(first))
            assertTrue(stillBlocked is RootPendingDrainLoad.Present)

            assertTrue(drains.clearProvenSession(Authority.ROOT_DIRECT, second, provenQuiescent(second)) is RootPendingDrainLoad.Missing)
        }
    }


    /** Snapshot shape the remote service returns after all workers/leases/descendants drained. */
    private fun provenQuiescent(identity: PrivilegedSessionIdentity) = RootRemoteQuiescenceSnapshot(
        accepted = true,
        authority = identity.authority,
        serviceInstanceId = identity.serviceInstanceId,
        sessionId = identity.sessionId,
        identityEpoch = identity.identityEpoch,
        cancelStage = RootRemoteCancelStage.QUIESCENT_CONFIRMED,
        quiescent = true,
    )


    @Test fun anAcceptedRequestCannotLaterBeReportedAsRejectedBeforeAcceptance() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            r.repo.acknowledge(permit)
            assertThrows(IllegalArgumentException::class.java) {
                r.repo.finish(permit,RootDispatchOutcome.REJECTED_BEFORE_ACCEPTED)
            }
            r.repo.finish(permit,RootDispatchOutcome.UNKNOWN_OUTCOME)
            r.repo.closePermit(permit,false)
        }
    }

    @Test fun knownNonAcceptanceDoesNotDegradeButLostAttemptDoes() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            r.repo.finish(permit,RootDispatchOutcome.REJECTED_BEFORE_ACCEPTED)
            assertFalse(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded)
            assertFalse(r.repo.authorizeDisclosure(permit))
            r.repo.closePermit(permit,false)
        }
    }

    @Test fun lateUnconfirmedCancellationAfterForgetSessionDoesNotResurrectSessionTombstones() {
        JdbcSqlConnection().use { db ->
            val repo = RootDispatchRepository(db) { fixture.now }
            val base = fixture.execution()
            repeat(260) { i ->
                val sessionId = "session-retired-${i.toString().padStart(4, '0')}"
                val execution = base.copy(
                    identity = base.identity.copy(sessionId = sessionId, identityEpoch = (i + 1).toLong()),
                )
                repo.cancelUnconfirmedInvocation(execution, "inv-initial-$i")
                repo.forgetSession(execution.identity.sessionId)
                repo.cancelUnconfirmedInvocation(execution, "inv-late-$i")
            }
            val field = RootDispatchRepository::class.java.getDeclaredField("cancelledInvocations")
            field.isAccessible = true
            val retained = field.get(repo) as Set<*>
            assertEquals(0, retained.size, "Retired sessions must not re-accumulate in cancelledInvocations")
        }
    }

    @Test fun draftV40WithLegacyUniqueAndInstalledRootTriggersMustStillReopen() =
        reopenDraftV40WithLegacyUnique(withReceipt = false)

    @Test fun draftV40WithLegacyUniqueAndRootReceiptMustStillReopenAndAllowReusedCallId() =
        reopenDraftV40WithLegacyUnique(withReceipt = true)

    private fun reopenDraftV40WithLegacyUnique(withReceipt: Boolean) {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            db.transaction {
                val triggers = db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND sql LIKE '%tool_invocations%'")
                triggers.forEach { db.execute("DROP TRIGGER \"${it.string("name").replace("\"", "\"\"")}\"") }
                val sql = RootAuthoritySchema.TOOL_INVOCATIONS_SQL
                    .replace("IF NOT EXISTS tool_invocations ", "tool_invocations_legacy_fixture ")
                    .replace("FOREIGN KEY(run_id)", "UNIQUE(run_id, call_id), FOREIGN KEY(run_id)")
                db.execute(sql)
                db.execute("INSERT INTO tool_invocations_legacy_fixture SELECT * FROM tool_invocations")
                db.execute("DROP TABLE tool_invocations")
                db.execute("ALTER TABLE tool_invocations_legacy_fixture RENAME TO tool_invocations")
                db.execute("CREATE INDEX idx_tool_invocations_run ON tool_invocations(run_id, created_at)")
                triggers.forEach { db.execute(it.string("sql")) }
            }
            val args = """{"relativePath":"file.txt","workspaceId":"root-workspace"}"""
            var claimedRequestId: String? = null
            if (withReceipt) {
                db.execute("UPDATE tool_invocations SET arguments_json=?", listOf(args))
                val request = fixture.request().copy(normalizedArgumentsSha256 = RootReceiptDigests.text(args))
                val approvals = RootApprovalRepository(db, clock = { fixture.now })
                val binding = approvals.approve(approvals.createPending(request).approval.approvalId)
                val repository = RootDispatchRepository(db, clock = { fixture.now })
                claimedRequestId = repository.claim(binding.approval.approvalId, request).requestId
            }
            assertDoesNotThrow(
                { Migrations.apply(db) },
                "Reopening the supported draft-v40 schema must preserve rows referenced by Root receipts and triggers",
            )
            // Original row, index, and receipt must remain intact, and duplicate (run_id, call_id) must now be permitted.
            db.execute(
                "INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("invocation-root-round-2", "run-root", "model-call", "file_read_text", "{}", "NOT_REQUESTED", "SUCCEEDED", fixture.now, fixture.now),
            )
            val invocations = RunRepository(db).invocations("run-root")
            assertEquals(listOf("invocation-root", "invocation-root-round-2"), invocations.map { it.invocationId })
            assertTrue(
                db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_tool_invocations_run'").isNotEmpty(),
            )
            assertTrue(
                db.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name='root_dispatch_cleanup_guard'").isNotEmpty(),
            )
            if (claimedRequestId != null) {
                val receipt = RootDispatchRepository(db, clock = { fixture.now }).getReceipt(claimedRequestId)
                assertNotNull(receipt)
                assertEquals(RootDispatchStage.CLAIMED, receipt?.stage)
                assertEquals(args, invocations.first().argumentsJson)
            }
        }
    }

    @Test fun newActiveSessionHasCapacityAfterOneUnrelatedCancel() =
        retirementRolloverScenario(withLateCallbacks = false)

    @Test fun lateCallbacksAfterRetirementHistoryRolloverMustNotExhaustActiveSession() =
        retirementRolloverScenario(withLateCallbacks = true)

    private fun retirementRolloverScenario(withLateCallbacks: Boolean) {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val args = """{"relativePath":"file.txt","workspaceId":"root-workspace"}"""
            db.execute("UPDATE tool_invocations SET arguments_json=?", listOf(args))
            val request = fixture.request().copy(normalizedArgumentsSha256 = RootReceiptDigests.text(args))
            val approvals = RootApprovalRepository(db, clock = { fixture.now })
            val binding = approvals.approve(approvals.createPending(request).approval.approvalId)
            val repository = RootDispatchRepository(db, clock = { fixture.now })
            val execution = fixture.execution()
            if (withLateCallbacks) {
                val oldest = execution.copy(identity = execution.identity.copy(sessionId = "retired-session-0"))
                for (i in 0..256) repository.forgetSession("retired-session-$i")
                for (i in 0 until 256) repository.cancelUnconfirmedInvocation(oldest, "late-$i")
            }
            repository.cancelUnconfirmedInvocation(execution, "unrelated-cancel")
            assertDoesNotThrow(
                { repository.claim(binding.approval.approvalId, request) },
                "Late callbacks from retired sessions must not consume this active session's capacity",
            )
        }
    }

    /**
     * A partial recovery (session A proven, session B still pending) must not be undone by the
     * next startup backfill: the global acknowledgement epoch only advances once everything
     * drained, so per-request proof progress has to be durable on its own.
     */
    @Test fun partialDrainProofSurvivesAnotherStartupBackfill() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            val receipt = requireNotNull(r.repo.getReceipt(permit.requestId))
            val identityA = receipt.claim.approval.request.execution.identity
            r.repo.finish(permit, RootDispatchOutcome.UNKNOWN_OUTCOME)
            r.repo.closePermit(permit, false)
            val drains = RootPendingDrainRepository(db) { fixture.now }
            val identityB = identityA.copy(identityEpoch = identityA.identityEpoch + 1L)
            drains.record(identityA, receipt.claim.requestId, identityA.identityEpoch)
            drains.record(identityB, "req-b", identityA.identityEpoch)

            val afterA = drains.clearProvenSession(Authority.ROOT_DIRECT, identityA, provenQuiescent(identityA))
            assertTrue(afterA is RootPendingDrainLoad.Present)
            assertEquals(
                listOf(identityB.identityEpoch),
                (afterA as RootPendingDrainLoad.Present).state.sessions.map { it.identityEpoch },
            )

            RootReceiptRecoveryCoordinator(db) { fixture.now }.recoverInterrupted()
            val afterBackfill = drains.load(Authority.ROOT_DIRECT)
            assertTrue(afterBackfill is RootPendingDrainLoad.Present)
            val sessions = (afterBackfill as RootPendingDrainLoad.Present).state.sessions
            assertEquals(listOf(identityB.identityEpoch), sessions.map { it.identityEpoch })
            assertTrue(
                sessions.single().requestIds.none { it == receipt.claim.requestId },
                "the proven request must stay tombstoned after another backfill",
            )

            // The tombstone is per request: a new UNKNOWN for the same identity is still recorded.
            drains.record(identityA, "req-new", identityA.identityEpoch)
            val afterNew = drains.load(Authority.ROOT_DIRECT)
            assertTrue(afterNew is RootPendingDrainLoad.Present)
            assertTrue(
                (afterNew as RootPendingDrainLoad.Present).state.sessions
                    .any { it.matches(identityA) && it.requestIds.contains("req-new") },
                "a future request for the same identity must not be silently skipped",
            )
        }
    }


    /**
     * Tombstone capacity must never be resolved by dropping the oldest live proof: with more than
     * the old 512-entry window, the earliest proven request would be re-attached by the next
     * startup backfill and could never be cleared again (its transport is gone).
     */
    @Test fun manyProvenRequestsDoNotEvictTheEarliestProof() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            val permit = attempted(r)
            val receipt = requireNotNull(r.repo.getReceipt(permit.requestId))
            val identity = receipt.claim.approval.request.execution.identity
            r.repo.finish(permit, RootDispatchOutcome.UNKNOWN_OUTCOME)
            r.repo.closePermit(permit, false)
            val roots = RootAuthorityRepository(db) { fixture.now }
            val current = roots.get(Authority.ROOT_DIRECT)
            roots.markDegraded(Authority.ROOT_DIRECT, current.revision, identity.identityEpoch)
            val drains = RootPendingDrainRepository(db) { fixture.now }
            val proofs = buildList {
                // The receipt's request is the OLDEST tombstone.
                add(ProvenDrainRequest(receipt.claim.requestId, identity.identityEpoch))
                repeat(519) { index ->
                    add(ProvenDrainRequest("synthetic-${index.toString().padStart(4, '0')}", identity.identityEpoch))
                }
            }
            drains.markProvenRequests(Authority.ROOT_DIRECT, proofs)
            assertEquals(520, drains.provenRequests(Authority.ROOT_DIRECT).size)
            // The real drain also discharged the pending session before the restart.
            drains.clearProvenSession(Authority.ROOT_DIRECT, identity, provenQuiescent(identity))
            assertEquals(RootPendingDrainLoad.Missing, drains.load(Authority.ROOT_DIRECT))

            RootReceiptRecoveryCoordinator(db) { fixture.now }.recoverInterrupted()
            assertEquals(
                RootPendingDrainLoad.Missing,
                drains.load(Authority.ROOT_DIRECT),
                "a proven request must not be resurrected by another startup backfill",
            )
        }
    }

    /** Capacity exhaustion aborts the transaction and keeps the live obligation, never drops. */
    @Test fun tombstoneCapacityExhaustionBlocksInsteadOfDroppingProofs() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val drains = RootPendingDrainRepository(db) { fixture.now }
            val identity = fixture.execution().identity
            drains.record(identity, "req-live", identity.identityEpoch)
            val tooMany = (0..4_096).map {
                ProvenDrainRequest("req-${it.toString().padStart(5, '0')}", identity.identityEpoch)
            }
            assertThrows(IllegalStateException::class.java) {
                drains.markProvenRequests(Authority.ROOT_DIRECT, tooMany)
            }
            val after = drains.load(Authority.ROOT_DIRECT)
            assertTrue(after is RootPendingDrainLoad.Present)
            assertTrue(
                (after as RootPendingDrainLoad.Present).state.sessions.single().requestIds.contains("req-live"),
                "the pending obligation must survive the aborted proof write",
            )
            assertTrue(drains.provenRequests(Authority.ROOT_DIRECT).isEmpty())
        }
    }


    /**
     * F4: replaying an already proven request must not resurrect its session — not even with an
     * empty request list while another session is still pending. A genuinely new request id for
     * the same identity must still be recorded.
     */
    @Test fun replayingAProvenRequestDoesNotResurrectItsSession() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val drains = RootPendingDrainRepository(db) { fixture.now }
            val identityA = fixture.execution().identity
            val identityB = identityA.copy(identityEpoch = identityA.identityEpoch + 1L)
            val provenRequest = "req-proven"
            drains.record(identityA, provenRequest, identityA.identityEpoch)
            drains.record(identityB, "req-b", identityA.identityEpoch)
            drains.clearProvenSession(Authority.ROOT_DIRECT, identityA, provenQuiescent(identityA))
            val beforeReplay = drains.load(Authority.ROOT_DIRECT) as RootPendingDrainLoad.Present
            assertEquals(listOf(identityB.identityEpoch), beforeReplay.state.sessions.map { it.identityEpoch })

            drains.record(identityA, provenRequest, identityA.identityEpoch)
            val afterReplay = drains.load(Authority.ROOT_DIRECT) as RootPendingDrainLoad.Present
            assertEquals(
                listOf(identityB.identityEpoch),
                afterReplay.state.sessions.map { it.identityEpoch },
                "a proven request replay must not re-add its session",
            )

            drains.record(identityA, "req-new", identityA.identityEpoch)
            val afterNew = drains.load(Authority.ROOT_DIRECT) as RootPendingDrainLoad.Present
            assertTrue(
                afterNew.state.sessions.any { it.matches(identityA) && it.requestIds == listOf("req-new") },
                "a new request for the same identity must still be tracked",
            )
        }
    }

}
