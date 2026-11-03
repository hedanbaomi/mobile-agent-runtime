// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootDispatchBoundaryTest {
    private val fixture = RootApprovalFixture()
    private val args = """{"relativePath":"private-scope-marker/file.txt","workspaceId":"root-workspace"}"""
    private data class Ready(val repo: RootDispatchRepository,val permit: RootDispatchPermit)
    private fun ready(db: SqlConnection, clock: () -> String = { fixture.now }, expiresAt: String? = null): Ready {
        fixture.setup(db,"private-scope-marker",GrantLifetime.ONCE,expiresAt)
        db.execute("UPDATE tool_invocations SET arguments_json=?",listOf(args))
        val approvals = RootApprovalRepository(db,clock={fixture.now})
        val request = fixture.request().copy(normalizedArgumentsSha256=RootReceiptDigests.text(args))
        val binding = approvals.approve(approvals.createPending(request).approval.approvalId)
        val repo = RootDispatchRepository(db,clock)
        return Ready(repo,repo.claim(binding.approval.approvalId,request))
    }

    private fun mutations(): List<Pair<String,(SqlConnection) -> Unit>> = listOf(
        "sealed member" to { it.execute("DELETE FROM root_snapshot_grants") },
        "ordinary snapshot binding" to { it.execute("DELETE FROM snapshot_grant_bindings") },
        "disabled workspace" to { it.execute("UPDATE workspaces SET enabled=0") },
        "changed canonical scope" to { it.execute("UPDATE capability_grants SET path_scope='changed'") },
        "different ONCE consumption" to { it.execute("UPDATE capability_grants SET consumed_at='2026-10-29T11:00:01Z'") },
        "Root consent revoked" to {
            val repo=RootAuthorityRepository(it)
            repo.revokeRoot(Authority.ROOT_DIRECT,repo.get(Authority.ROOT_DIRECT).revision)
        },
        "identity rotated" to {
            val repo=RootAuthorityRepository(it)
            repo.advanceIdentityEpoch(Authority.ROOT_DIRECT,repo.get(Authority.ROOT_DIRECT).revision)
        },
    )

    @Test fun aConsumedOnceGrantCannotSkipAnyPreSubmissionBoundary() {
        mutations().forEach { (label,change) ->
            listOf(false,true).forEach { alreadyAttempted ->
                JdbcSqlConnection().use { db ->
                    val r=ready(db)
                    val ticket=if(alreadyAttempted) r.repo.beginAttempt(r.permit) else null
                    change(db)
                    assertThrows(Exception::class.java,{
                        if(ticket == null) r.repo.beginAttempt(r.permit) else r.repo.takeAttempt(ticket,args)
                    },label)
                    assertEquals(if(alreadyAttempted) RootDispatchStage.ATTEMPTED else RootDispatchStage.CLAIMED,
                        r.repo.getReceipt(r.permit.requestId)!!.stage,label)
                }
            }
        }
    }

    @Test fun successfulFactsSurviveInvalidationButNoReceiptCanBypassDisclosureRevalidation() {
        mutations().forEach { (label,change) ->
            JdbcSqlConnection().use { db ->
                val r=ready(db)
                r.repo.takeAttempt(r.repo.beginAttempt(r.permit),args)
                r.repo.finish(r.permit,RootDispatchOutcome.SUCCEEDED)
                assertTrue(r.repo.authorizeDisclosure(r.permit),label)
                change(db)
                assertThrows(Exception::class.java,{ r.repo.closePermit(r.permit,true) },label)
                assertFalse(r.repo.authorizeDisclosure(r.permit),label)
                val closed=r.repo.closePermit(r.permit,false)
                assertEquals(RootDispatchOutcome.SUCCEEDED,closed.outcome,label)
                assertEquals(RootDisclosureState.WITHHELD,closed.disclosure,label)
            }
        }
    }

    @Test fun grantExpiryBlocksDisclosureWhileConsumedApprovalExpiryDoesNotEraseAValidReservation() {
        JdbcSqlConnection().use { db ->
            var now=fixture.now
            val r=ready(db,{now},"2026-10-29T11:01:00Z")
            r.repo.takeAttempt(r.repo.beginAttempt(r.permit),args)
            now="2026-10-29T11:01:00Z"
            r.repo.finish(r.permit,RootDispatchOutcome.SUCCEEDED)
            assertFalse(r.repo.authorizeDisclosure(r.permit))
            r.repo.closePermit(r.permit,false)
        }
        JdbcSqlConnection().use { db ->
            var now=fixture.now
            val r=ready(db,{now})
            r.repo.takeAttempt(r.repo.beginAttempt(r.permit),args)
            now="2026-10-30T11:00:00Z"
            r.repo.finish(r.permit,RootDispatchOutcome.SUCCEEDED)
            assertTrue(r.repo.authorizeDisclosure(r.permit))
            r.repo.closePermit(r.permit,true)
        }
    }

    @Test fun ordinaryInvocationUpdatesCannotStealRootReceiptOwnership() {
        JdbcSqlConnection().use { db ->
            val r = ready(db)
            r.repo.takeAttempt(r.repo.beginAttempt(r.permit), args)
            r.repo.acknowledge(r.permit)
            val runs = RunRepository(db)
            val invocation = runs.invocations("run-root").single()
            assertThrows(Exception::class.java) {
                runs.updateInvocation(invocation.copy(state = "FAILED", resultJson = "{}"))
            }
            assertEquals("ROOT_DISPATCH_ACKNOWLEDGED", runs.invocations("run-root").single().state)
            RootReceiptRecoveryCoordinator(db) { fixture.now }.recoverInterrupted()
            assertEquals(RootDispatchOutcome.UNKNOWN_OUTCOME, r.repo.getReceipt(r.permit.requestId)!!.outcome)
            assertTrue(RootAuthorityRepository(db).get(Authority.ROOT_DIRECT).degraded)
        }
    }

    @Test fun ignoredAttemptAndCloseUpdatesDoNotLeakTicketsOrReleaseMemoryOwnership() {
        JdbcSqlConnection().use { db ->
            val r=ready(db)
            db.execute("CREATE TRIGGER ignore_receipt_update BEFORE UPDATE ON root_dispatch_receipts BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { r.repo.beginAttempt(r.permit) }
            assertEquals(RootDispatchStage.CLAIMED,r.repo.getReceipt(r.permit.requestId)!!.stage)
            assertEquals("ROOT_DISPATCH_CLAIMED",RunRepository(db).invocations("run-root").single().state)
            db.execute("DROP TRIGGER ignore_receipt_update")
            r.repo.takeAttempt(r.repo.beginAttempt(r.permit),args)
            r.repo.finish(r.permit,RootDispatchOutcome.SUCCEEDED)
            db.execute("CREATE TRIGGER ignore_receipt_update BEFORE UPDATE ON root_dispatch_receipts BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { r.repo.closePermit(r.permit,false) }
            assertFalse(r.repo.canRotate(fixture.execution().identity.sessionId,0))
            assertFalse(r.repo.getReceipt(r.permit.requestId)!!.closed)
            assertEquals("ROOT_DISPATCH_RESULT_PENDING",RunRepository(db).invocations("run-root").single().state)
            db.execute("DROP TRIGGER ignore_receipt_update")
            r.repo.closePermit(r.permit,false)
            assertFalse(r.repo.canRotate(fixture.execution().identity.sessionId,1))
            assertTrue(r.repo.canRotate(fixture.execution().identity.sessionId,0))
        }
    }
}
