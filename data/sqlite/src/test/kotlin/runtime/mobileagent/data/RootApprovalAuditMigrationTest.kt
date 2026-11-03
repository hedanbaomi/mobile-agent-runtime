// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

/** Migrates the real legacy approval/audit table shapes; no row becomes Root authorization. */
class RootApprovalAuditMigrationTest {
    private val now = "2026-10-29T11:00:00Z"

    private fun legacyTables(db: SqlConnection, version: Int) {
        Migrations.apply(db)
        db.execute("DROP TABLE root_dispatch_receipts")
        db.execute("DROP TABLE root_audit_bindings")
        db.execute("DROP TABLE root_approval_bindings")
        db.execute("DROP TABLE approval_records")
        db.execute("DROP TABLE tool_audit_details")
        db.execute(RootAuthoritySchema.APPROVAL_SQL.replace(",'ROOT_DIRECT','SHIZUKU_ROOT'",""))
        db.execute(RootAuthoritySchema.AUDIT_SQL.replace(",'ROOT_DIRECT','SHIZUKU_ROOT'",""))
        db.execute("CREATE INDEX idx_approval_records_call ON approval_records(call_id, created_at)")
        db.execute("CREATE INDEX idx_tool_audit_details_request ON tool_audit_details(request_id, created_at)")
        db.execute("UPDATE schema_version SET version=?",listOf(version))
    }

    private fun approval(db: SqlConnection, authority: Authority): ApprovalRecord {
        val repo = ApprovalRepository(db, clock={ now })
        val pending = repo.create("call-fixture","agent-fixture","a".repeat(64),authority,DangerousMode.DISABLED,0)
        return repo.approve(pending.approvalId)
    }

    private fun audit(id: String, authority: Authority) = ToolAuditDetail.builder(
        id,"request-$id","agent-fixture",CapabilityId(CapabilityId.FILE_READ_TEXT),"DENIED",now,
    ).authority(authority).build()

    @Test fun legacyRowsIndexesUniqueRequestAndAuditForeignKeySurviveMigration() {
        JdbcSqlConnection().use { db ->
            legacyTables(db,39)
            val oldApproval = approval(db,Authority.SHIZUKU)
            val oldAudit = AuditRepository(db).append(audit("old-audit",Authority.WIRED_ADB))
            Migrations.apply(db)
            assertEquals(oldApproval,ApprovalRepository(db).get(oldApproval.approvalId))
            assertEquals(oldAudit,AuditRepository(db).getDetail(oldAudit.auditId))
            val names = db.query("SELECT name FROM sqlite_master WHERE type='index'").map { it.string("name") }
            assertTrue("idx_approval_records_call" in names)
            assertTrue("idx_tool_audit_details_request" in names)
            assertThrows(Exception::class.java) {
                ApprovalRepository(db).save(oldApproval.copy(approvalId="duplicate-approval"))
            }
            assertThrows(Exception::class.java) {
                db.execute("DELETE FROM audit_events WHERE id=?",listOf(oldAudit.auditId))
            }
            listOf(Authority.ROOT_DIRECT,Authority.SHIZUKU_ROOT).forEach { route ->
                assertEquals(route,approval(db,route).selectedAuthority)
                assertEquals(route,AuditRepository(db).append(audit("new-${route.name}",route)).authority)
                assertFalse(RootAuthorityRepository(db).get(route).consent.enabled)
            }
            assertTrue(db.query("SELECT * FROM root_grant_bindings").isEmpty())
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
            Migrations.apply(db)
            assertEquals(oldAudit,AuditRepository(db).getDetail(oldAudit.auditId))
        }
    }

    @Test fun unpublishedSchema40DraftUpgradesWithoutInferringRootTrust() {
        JdbcSqlConnection().use { db ->
            legacyTables(db,40)
            val old = approval(db,Authority.WIRED_ADB)
            Migrations.apply(db)
            assertEquals(old,ApprovalRepository(db).get(old.approvalId))
            assertEquals(Authority.ROOT_DIRECT,approval(db,Authority.ROOT_DIRECT).selectedAuthority)
            assertTrue(db.query("SELECT * FROM root_authority_state").isEmpty())
        }
    }

    @Test fun knownIndexFormattingAndMissingIndexesRecoverToTheCanonicalShape() {
        JdbcSqlConnection().use { db ->
            legacyTables(db,39)
            db.execute("DROP INDEX idx_approval_records_call")
            db.execute("create index idx_approval_records_call ON approval_records( call_id ,  created_at )")
            db.execute("DROP INDEX idx_tool_audit_details_request")
            Migrations.apply(db)
            assertEquals(listOf("call_id","created_at"),db.query("PRAGMA index_info(idx_approval_records_call)").map { it.string("name") })
            assertEquals(listOf("request_id","created_at"),db.query("PRAGMA index_info(idx_tool_audit_details_request)").map { it.string("name") })
        }
    }

    @Test fun unexpectedIndexRejectsAndRollsBackTheWholeMigration() {
        JdbcSqlConnection().use { db ->
            legacyTables(db,39)
            val old = approval(db,Authority.SHIZUKU)
            db.execute("CREATE INDEX custom_approval_agent ON approval_records(agent_id)")
            assertThrows(IllegalStateException::class.java) { Migrations.apply(db) }
            assertEquals(39L,db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(old,ApprovalRepository(db).get(old.approvalId))
            val sql = db.query("SELECT sql FROM sqlite_master WHERE name='approval_records'").single().string("sql")
            assertFalse(sql.contains("'ROOT_DIRECT'"))
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='custom_approval_agent'").isNotEmpty())
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }
}
