// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootApprovalRepositoryTest {
    private val fixture = RootApprovalFixture()
    private val now = fixture.now
    private fun request() = fixture.request()
    private fun setup(db: SqlConnection, pathScope: String? = null) = fixture.setup(db,pathScope)

    @Test fun pendingIsLocalAndTerminalApprovalFreezesInvocationAndRootContext() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootApprovalRepository(db,clock={now})
            val pending = repo.createPending(request())
            assertNotEquals("model-call",pending.approval.requestId)
            assertEquals(ApprovalDecision.PENDING,pending.approval.decision)
            assertNull(ApprovalRepository(db).get(pending.approval.approvalId))
            assertTrue(db.query("SELECT * FROM root_approval_bindings").isEmpty())
            val approved = repo.approve(pending.approval.approvalId)
            assertEquals(approved,repo.getCurrent(approved.approval.approvalId,request()))
            assertEquals(approved,RootApprovalRepository(db,clock={now}).stored(approved.approval.approvalId))
            assertNull(repo.getCurrent(approved.approval.approvalId,request().copy(normalizedArgumentsSha256="d".repeat(64))))
            assertThrows(IllegalStateException::class.java) { repo.approve(approved.approval.approvalId) }
            ApprovalRepository(db,clock={now}).consume(approved.approval.approvalId)
            assertNull(repo.getCurrent(approved.approval.approvalId,request()))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun changedArgumentsNameRunAndConsentCannotApproveTheFrozenPrompt() {
        val mutations: List<(SqlConnection) -> Unit> = listOf(
            { it.execute("UPDATE tool_invocations SET arguments_json='{\"path\":\"changed\"}'") },
            { it.execute("UPDATE tool_invocations SET name='file_write_text'") },
            { it.execute("UPDATE runs SET state='COMPLETED'") },
            { it.execute("UPDATE runs SET state='TOOL_EXECUTING'") },
            { it.execute("UPDATE tool_invocations SET state='RUNNING'") },
            { db -> val roots=RootAuthorityRepository(db); roots.revokeRoot(Authority.ROOT_DIRECT,roots.get(Authority.ROOT_DIRECT).revision); Unit },
        )
        mutations.forEach { mutate ->
            JdbcSqlConnection().use { db ->
                setup(db)
                val repo = RootApprovalRepository(db,clock={now})
                val pending = repo.createPending(request())
                mutate(db)
                assertThrows(Exception::class.java) { repo.approve(pending.approval.approvalId) }
                assertNull(ApprovalRepository(db).get(pending.approval.approvalId))
                assertTrue(db.query("SELECT * FROM root_approval_bindings").isEmpty())
            }
        }
    }

    @Test fun oldGenericApprovalsAndRestartedPendingPromptsNeverAcquireRootTrust() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val generic = ApprovalRepository(db,clock={now})
            val old = generic.create("model-call","agent-root","c".repeat(64),Authority.SHIZUKU,DangerousMode.DISABLED,1)
            generic.approve(old.approvalId)
            val repo = RootApprovalRepository(db,clock={now})
            assertThrows(IllegalStateException::class.java) { repo.approve(old.approvalId) }
            assertNull(repo.getCurrent(old.approvalId,request()))
            val pending = repo.createPending(request())
            assertThrows(IllegalStateException::class.java) {
                RootApprovalRepository(db,clock={now}).approve(pending.approval.approvalId)
            }
            assertNull(ApprovalRepository(db).get(pending.approval.approvalId))
        }
    }

    @Test fun ignoredBindingInsertRollsBackCanonicalApprovalAndCanRetryTheSamePrompt() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootApprovalRepository(db,clock={now})
            val pending = repo.createPending(request())
            db.execute("CREATE TRIGGER ignore_root_approval BEFORE INSERT ON root_approval_bindings BEGIN SELECT RAISE(IGNORE); END")
            assertThrows(IllegalStateException::class.java) { repo.approve(pending.approval.approvalId) }
            assertNull(ApprovalRepository(db).get(pending.approval.approvalId))
            db.execute("DROP TRIGGER ignore_root_approval")
            assertEquals(ApprovalDecision.APPROVED,repo.approve(pending.approval.approvalId).approval.decision)
        }
    }

    @Test fun expiryAndDenyAreDurableEvidenceAndNeverDispatchAuthority() {
        JdbcSqlConnection().use { db ->
            setup(db)
            var elapsed = 0L
            val repo = RootApprovalRepository(db,clock={now},elapsedMillis={elapsed})
            val pending = repo.createPending(request())
            elapsed = 300_000
            val expired = repo.approve(pending.approval.approvalId)
            assertEquals(ApprovalDecision.EXPIRED,expired.approval.decision)
            assertNull(repo.getCurrent(expired.approval.approvalId,request()))
            assertThrows(IllegalArgumentException::class.java) { repo.createPending(request()) }
            db.execute("INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("invocation-denied","run-root","another-call","file_read_text","{}","PENDING","PENDING",now,now))
            val denied = repo.deny(repo.createPending(request().copy(invocationId="invocation-denied")).approval.approvalId)
            assertEquals(ApprovalDecision.DENIED,denied.approval.decision)
            assertNull(repo.getCurrent(denied.approval.approvalId,request()))
        }
    }

    @Test fun anInvocationHasOnlyOneApprovalAcrossPendingAndRepositoryInstances() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val first = RootApprovalRepository(db,clock={now})
            val second = RootApprovalRepository(db,clock={now})
            val one = first.createPending(request())
            assertThrows(IllegalArgumentException::class.java) { first.createPending(request()) }
            val competing = second.createPending(request())
            first.approve(one.approval.approvalId)
            assertThrows(IllegalArgumentException::class.java) { second.approve(competing.approval.approvalId) }
            assertNull(ApprovalRepository(db).get(competing.approval.approvalId))
            assertEquals(1,db.query("SELECT * FROM root_approval_bindings").size)
            assertThrows(IllegalArgumentException::class.java) { second.createPending(request()) }
        }
    }

    @Test fun expiredStaleCompetingPromptDoesNotWedgeSubsequentApprovalCreation() {
        JdbcSqlConnection().use { db ->
            setup(db)
            var elapsed = 0L
            val first = RootApprovalRepository(db, clock = { now }, elapsedMillis = { elapsed })
            val second = RootApprovalRepository(db, clock = { now }, elapsedMillis = { elapsed })
            val one = first.createPending(request())
            second.createPending(request())
            first.approve(one.approval.approvalId)
            elapsed = 300_001L
            db.execute(
                "INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("invocation-next", "run-root", "call-next", "file_read_text", "{}", "PENDING", "PENDING", now, now),
            )
            val nextPending = second.createPending(request().copy(invocationId = "invocation-next"))
            assertEquals(ApprovalDecision.PENDING, nextPending.approval.decision)
        }
    }

    @Test fun creationAfterInvocationStartedIsRejectedAndWeakDraftEvidenceIsQuarantined() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootApprovalRepository(db,clock={now})
            db.execute("UPDATE runs SET state='TOOL_EXECUTING'")
            assertThrows(IllegalArgumentException::class.java) { repo.createPending(request()) }
            db.execute("UPDATE runs SET state='WAITING_TOOL_APPROVAL'")
            val approved = repo.approve(repo.createPending(request()).approval.approvalId)
            db.execute("DROP INDEX idx_root_approval_invocation")
            db.execute("ALTER TABLE root_approval_bindings DROP COLUMN binding_version")
            Migrations.apply(db)
            assertEquals(1,db.query("SELECT * FROM root_approval_bindings").size)
            assertNotNull(ApprovalRepository(db).get(approved.approval.approvalId))
            assertNull(repo.getCurrent(approved.approval.approvalId,request()))
            assertEquals(0,db.query("SELECT binding_version FROM root_approval_bindings").single().long("binding_version"))
            assertThrows(IllegalArgumentException::class.java) { repo.createPending(request()) }
        }
    }

    @Test fun persistedApprovalContainsScopeAndArgumentsDigestsWithoutDuplicatingPaths() {
        JdbcSqlConnection().use { db ->
            setup(db,"private-scope-marker")
            db.execute("UPDATE tool_invocations SET arguments_json=?",listOf("{\"relativePath\":\"private-scope-marker/file.txt\"}"))
            val repo = RootApprovalRepository(db,clock={now})
            val approved = repo.approve(repo.createPending(request()).approval.approvalId)
            val encoded = db.query("SELECT binding_json FROM root_approval_bindings").single().string("binding_json")
            assertFalse(encoded.contains("private-scope-marker"))
            assertEquals(approved,repo.getCurrent(approved.approval.approvalId,request()))
        }
    }

    @Test fun aDifferentOrUnknownToolCannotBorrowTheReadGrantBeforeApproval() {
        listOf("file_write_text","process_signal").forEach { tool ->
            JdbcSqlConnection().use { db ->
                setup(db)
                db.execute("UPDATE tool_invocations SET name=?",listOf(tool))
                assertThrows(IllegalArgumentException::class.java) {
                    RootApprovalRepository(db,clock={now}).createPending(request())
                }
                assertTrue(db.query("SELECT * FROM root_approval_bindings").isEmpty())
            }
        }
    }

    @Test fun grantRevocationAfterApprovalBlocksLocalRevalidation() {
        JdbcSqlConnection().use { db ->
            setup(db)
            val repo = RootApprovalRepository(db,clock={now})
            val approved = repo.approve(repo.createPending(request()).approval.approvalId)
            db.execute("UPDATE capability_grants SET revoked_at=?",listOf(now))
            assertNull(repo.getCurrent(approved.approval.approvalId,request()))
            assertNotNull(repo.stored(approved.approval.approvalId))
        }
    }
}
