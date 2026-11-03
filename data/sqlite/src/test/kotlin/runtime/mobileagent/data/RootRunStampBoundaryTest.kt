// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootRunStampBoundaryTest {
    private val fixture = RootApprovalFixture()
    private fun request() = fixture.request().copy(normalizedArgumentsSha256 = RootReceiptDigests.text("{}"))
    private fun approvals(db: SqlConnection) = RootApprovalRepository(db, clock = { fixture.now })
    private fun approve(db: SqlConnection): RootApprovalBinding = approvals(db).let {
        it.approve(it.createPending(request()).approval.approvalId)
    }
    private fun changeManifest(db: SqlConnection, stamp: RootExecutionStamp?) {
        val manifest = stamp?.let { "{\"rootExecutionStamp\":${Json.encodeToString(it)}}" } ?: "{}"
        db.execute("UPDATE runs SET manifest_json=? WHERE run_id='run-root'", listOf(manifest))
    }
    private fun changedStamp() = fixture.execution().let {
        it.copy(identity = it.identity.copy(sessionId = "session-instance-002"))
    }

    @Test fun oldRunCannotApproveNextInvocationInANewEpochEvenWithTheSamePersistentGrant() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            val dispatch = RootDispatchRepository(db) { fixture.now }
            val first = approve(db)
            val permit = dispatch.claim(first.approval.approvalId, first.request)
            dispatch.finish(permit, RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT)
            dispatch.closePermit(permit)
            val roots = RootAuthorityRepository(db)
            val nextState = roots.advanceIdentityEpoch(Authority.ROOT_DIRECT, roots.get(Authority.ROOT_DIRECT).revision)!!
            val old = fixture.execution()
            val next = old.copy(identity = old.identity.copy(identityEpoch = nextState.identityEpoch,
                serviceInstanceId = "service-instance-002", sessionId = "session-instance-002"))
            assertNotNull(RootSnapshotGrantRepository(db) { fixture.now }
                .revalidate("snapshot-root", "root-grant", next))
            db.execute("UPDATE runs SET state='WAITING_TOOL_APPROVAL' WHERE run_id='run-root'")
            RunRepository(db).addInvocation(ToolInvocation("invocation-next", "run-root", "model-next",
                "file_read_text", createdAt = fixture.now))
            val second = request().copy(invocationId = "invocation-next", execution = next)
            assertThrows(IllegalArgumentException::class.java) { approvals(db).createPending(second) }
            assertEquals(1, db.query("SELECT * FROM root_approval_bindings").size)
            assertEquals(1, db.query("SELECT * FROM root_dispatch_receipts").size)
            assertEquals("PENDING", RunRepository(db).invocations("run-root").single { it.invocationId == "invocation-next" }.state)
        }
    }

    @Test fun runWithoutStampCannotAcquireRootAtApprovalTime() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            changeManifest(db, null)
            assertThrows(IllegalArgumentException::class.java) { approvals(db).createPending(request()) }
            assertEquals("{}", RunRepository(db).get("run-root")!!.manifestJson)
            assertTrue(db.query("SELECT * FROM root_approval_bindings").isEmpty())
        }
    }

    @Test fun sameEpochWithDifferentSessionCannotUseTheRunStamp() {
        JdbcSqlConnection().use { db ->
            fixture.setup(db)
            assertThrows(IllegalArgumentException::class.java) {
                approvals(db).createPending(request().copy(execution = changedStamp()))
            }
        }
    }

    @Test fun removingOrChangingStampWhilePromptIsPendingCannotApprove() {
        listOf(null, changedStamp()).forEach { stamp ->
            JdbcSqlConnection().use { db ->
                fixture.setup(db)
                val approvals = approvals(db)
                val pending = approvals.createPending(request())
                changeManifest(db, stamp)
                assertThrows(IllegalArgumentException::class.java) { approvals.approve(pending.approval.approvalId) }
                assertNull(ApprovalRepository(db).get(pending.approval.approvalId))
            }
        }
    }

    @Test fun removingOrChangingStampAfterApprovalCannotConsumeApprovalOrOnceGrant() {
        listOf(null, changedStamp()).forEach { stamp ->
            JdbcSqlConnection().use { db ->
                fixture.setup(db, lifetime = GrantLifetime.ONCE)
                val approved = approve(db)
                val grant = CapabilityGrantRepository(db).get("root-grant")
                changeManifest(db, stamp)
                assertNull(approvals(db).getCurrent(approved.approval.approvalId, approved.request))
                assertThrows(IllegalArgumentException::class.java) {
                    RootDispatchRepository(db) { fixture.now }.claim(approved.approval.approvalId, approved.request)
                }
                assertEquals(approved.approval, ApprovalRepository(db).get(approved.approval.approvalId))
                assertEquals(grant, CapabilityGrantRepository(db).get("root-grant"))
                assertTrue(db.query("SELECT * FROM root_dispatch_receipts").isEmpty())
            }
        }
    }

    @Test fun removingOrChangingStampAfterClaimCannotBeginAttempt() {
        listOf(null, changedStamp()).forEach { stamp ->
            JdbcSqlConnection().use { db ->
                fixture.setup(db)
                val approved = approve(db)
                val dispatch = RootDispatchRepository(db) { fixture.now }
                val permit = dispatch.claim(approved.approval.approvalId, approved.request)
                changeManifest(db, stamp)
                assertThrows(IllegalArgumentException::class.java) { dispatch.beginAttempt(permit) }
                assertEquals(RootDispatchStage.CLAIMED, dispatch.getReceipt(permit.requestId)!!.stage)
                dispatch.finish(permit, RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT)
                dispatch.closePermit(permit)
            }
        }
    }

    @Test fun removingOrChangingStampAfterBeginAttemptCannotReachTransport() {
        listOf(null, changedStamp()).forEach { stamp ->
            JdbcSqlConnection().use { db ->
                fixture.setup(db)
                val approved = approve(db)
                val dispatch = RootDispatchRepository(db) { fixture.now }
                val permit = dispatch.claim(approved.approval.approvalId, approved.request)
                val ticket = dispatch.beginAttempt(permit)
                changeManifest(db, stamp)
                assertThrows(IllegalArgumentException::class.java) { dispatch.takeAttempt(ticket, "{}") }
                assertEquals(RootDispatchStage.ATTEMPTED, dispatch.getReceipt(permit.requestId)!!.stage)
            }
        }
    }

    @Test fun malformedNullOrFutureStampFailsClosedWithoutBackfillingTheRun() {
        val valid = Json.encodeToString(fixture.execution())
        listOf("null", "{}", "[]", "\"invalid\"", valid.dropLast(1) + ",\"futureVersion\":2}").forEach { raw ->
            JdbcSqlConnection().use { db ->
                fixture.setup(db)
                val manifest = "{\"rootExecutionStamp\":$raw,\"providerRevision\":17}"
                db.execute("UPDATE runs SET manifest_json=? WHERE run_id='run-root'", listOf(manifest))
                assertThrows(IllegalArgumentException::class.java) { approvals(db).createPending(request()) }
                assertEquals(manifest, RunRepository(db).get("run-root")!!.manifestJson)
                assertTrue(db.query("SELECT * FROM root_approval_bindings").isEmpty())
            }
        }
    }

    @Test fun removingOrChangingStampBeforeDisclosureWithholdsTheKnownResult() {
        listOf(null, changedStamp()).forEach { stamp ->
            JdbcSqlConnection().use { db ->
                fixture.setup(db)
                val approved = approve(db)
                val dispatch = RootDispatchRepository(db) { fixture.now }
                val permit = dispatch.claim(approved.approval.approvalId, approved.request)
                dispatch.takeAttempt(dispatch.beginAttempt(permit), "{}")
                dispatch.finish(permit, RootDispatchOutcome.SUCCEEDED, "d".repeat(64))
                assertTrue(dispatch.authorizeDisclosure(permit))
                changeManifest(db, stamp)
                assertThrows(IllegalArgumentException::class.java) { dispatch.closePermit(permit, disclosed = true) }
                assertFalse(dispatch.authorizeDisclosure(permit))
                val closed = dispatch.closePermit(permit)
                assertEquals(RootDispatchOutcome.SUCCEEDED, closed.outcome)
                assertEquals(RootDisclosureState.WITHHELD, closed.disclosure)
                assertEquals("COMPLETED_WITHHELD", RunRepository(db).invocations("run-root").single().state)
            }
        }
    }
}
