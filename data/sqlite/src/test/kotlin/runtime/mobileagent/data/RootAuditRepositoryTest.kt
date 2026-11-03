// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class RootAuditRepositoryTest {
    private val fixture = RootApprovalFixture()
    private fun approved(db: SqlConnection): RootApprovalBinding {
        fixture.setup(db,"private-scope-marker")
        val repo = RootApprovalRepository(db,clock={fixture.now})
        return repo.approve(repo.createPending(fixture.request()).approval.approvalId)
    }
    private fun detail(binding: RootApprovalBinding, id: String, result: String = "STARTED") =
        ToolAuditDetail.builder(id,binding.approval.requestId,binding.approval.agentId,
            binding.capability,result,fixture.now)
            .authority(binding.approval.selectedAuthority).workspaceHash(binding.workspaceId)
            .approval(binding.approval.approvalId).dangerousMode(binding.approval.dangerousMode)
            .policyVersion(binding.request.execution.policyVersion).build()

    @Test fun auditAndFrozenRootContextCommitTogetherWithoutRawScopesOrArguments() {
        JdbcSqlConnection().use { db ->
            val approval = approved(db)
            val repo = RootAuditRepository(db)
            val started = detail(approval,"root-audit-start")
            val bound = repo.append(started,approval)
            assertEquals(bound,repo.get(started.auditId))
            assertEquals(started,AuditRepository(db).getDetail(started.auditId))
            assertEquals(bound,repo.append(started,approval))
            repo.append(detail(approval,"root-audit-done","COMPLETED"),approval)
            assertEquals(2,AuditRepository(db).listDetails(approval.approval.requestId).size)
            assertEquals(2,AuditRepository(db).list(approval.request.runId).size)
            assertFalse(db.query("SELECT binding_json FROM root_audit_bindings").any {
                it.string("binding_json").contains("private-scope-marker")
            })
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun mismatchedRootRouteOrRequestNeverCreatesAnOrdinaryAuditPair() {
        JdbcSqlConnection().use { db ->
            val approval = approved(db)
            val repo = RootAuditRepository(db)
            val original = detail(approval,"wrong-context")
            listOf(original.copy(authority=Authority.SHIZUKU),
                original.copy(requestId="another-request"),
                original.copy(capability=CapabilityId(CapabilityId.FILE_WRITE_TEXT))).forEach {
                assertThrows(IllegalArgumentException::class.java) { repo.append(it,approval) }
            }
            assertNull(AuditRepository(db).getDetail(original.auditId))
            assertTrue(db.query("SELECT * FROM audit_events").isEmpty())
        }
    }

    @Test fun revocationDoesNotPreventRecordingAnUncertainOutcomeOrEraseItsHistory() {
        JdbcSqlConnection().use { db ->
            val approval = approved(db)
            ApprovalRepository(db,clock={fixture.now}).consume(approval.approval.approvalId)
            val roots = RootAuthorityRepository(db)
            roots.revokeRoot(Authority.ROOT_DIRECT,roots.get(Authority.ROOT_DIRECT).revision)
            assertNull(RootApprovalRepository(db,clock={fixture.now})
                .getCurrent(approval.approval.approvalId,fixture.request()))
            val repo = RootAuditRepository(db)
            val bound = repo.append(detail(approval,"uncertain-root","UNKNOWN_OUTCOME"),approval)
            db.execute("DELETE FROM root_approval_bindings")
            assertEquals(bound,repo.get("uncertain-root"))
            assertThrows(Exception::class.java) {
                db.execute("UPDATE root_audit_bindings SET binding_json='{}'")
            }
        }
    }

    @Test fun deniedOrExpiredCanonicalDecisionsCannotBePresentedAsAnApprovedAuditContext() {
        listOf(ApprovalDecision.DENIED,ApprovalDecision.EXPIRED).forEach { decision ->
            JdbcSqlConnection().use { db ->
                val approval = approved(db)
                db.execute("UPDATE approval_records SET decision=?",listOf(decision.name))
                assertThrows(IllegalArgumentException::class.java) {
                    RootAuditRepository(db).append(detail(approval,"wrong-decision"),approval)
                }
                assertTrue(db.query("SELECT * FROM audit_events").isEmpty())
            }
        }
    }

    @Test fun theDatabaseAlsoRejectsOversizedHistoricalContext() {
        JdbcSqlConnection().use { db ->
            val approval = approved(db)
            val item = detail(approval,"oversized-context")
            AuditRepository(db).append(item,approval.request.runId)
            assertThrows(Exception::class.java) {
                db.execute("INSERT INTO root_audit_bindings(audit_id,binding_json) VALUES(?,?)",
                    listOf(item.auditId,"x".repeat(65_537)))
            }
            assertNull(RootAuditRepository(db).get(item.auditId))
        }
    }

    @Test fun ignoredRootContextInsertRollsBackBothOrdinaryAuditRows() {
        JdbcSqlConnection().use { db ->
            val approval = approved(db)
            db.execute("CREATE TRIGGER ignore_root_audit BEFORE INSERT ON root_audit_bindings BEGIN SELECT RAISE(IGNORE); END")
            val item = detail(approval,"ignored-root-audit")
            assertThrows(IllegalStateException::class.java) { RootAuditRepository(db).append(item,approval) }
            assertNull(AuditRepository(db).getDetail(item.auditId))
            assertTrue(db.query("SELECT * FROM audit_events").isEmpty())
        }
    }
}
