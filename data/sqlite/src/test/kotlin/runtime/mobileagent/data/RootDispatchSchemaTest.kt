// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootDispatchSchemaTest {
    private fun sql(db: SqlConnection,name: String) =
        db.query("SELECT sql FROM sqlite_master WHERE name=?",listOf(name)).single().string("sql")

    @Test fun weakerSameNameTriggerIsRejectedAndExistingEvidenceIsPreserved() {
        JdbcSqlConnection().use { db ->
            val fixture=RootApprovalFixture()
            fixture.setup(db)
            val approvals=RootApprovalRepository(db,clock={fixture.now})
            val approval=approvals.approve(approvals.createPending(fixture.request()).approval.approvalId)
            val repo=RootDispatchRepository(db) { fixture.now }
            val permit=repo.claim(approval.approval.approvalId,approval.request)
            val before=repo.getReceipt(permit.requestId)
            db.execute("DROP TRIGGER root_dispatch_transition")
            db.execute("CREATE TRIGGER root_dispatch_transition BEFORE UPDATE ON root_dispatch_receipts BEGIN SELECT 1; END")
            val unsupported=sql(db,"root_dispatch_transition")
            assertEquals(runtime.mobileagent.domain.ErrorCode.SCHEMA_UNSUPPORTED,
                assertThrows(runtime.mobileagent.domain.AppException::class.java) { Migrations.apply(db) }.error.code)
            assertEquals(unsupported,sql(db,"root_dispatch_transition"))
            assertEquals(before,repo.getReceipt(permit.requestId))
            assertEquals(Migrations.VERSION.toLong(),db.query("SELECT version FROM schema_version").single().long("version"))
        }
    }

    @Test fun sameColumnsAndForeignKeysCannotHideMissingChecksOrChangedQuotedValues() {
        listOf(
            { s: String -> s.replace("CHECK(revision>0)","") },
            { s: String -> s.replace("'CLAIMED'","'CL AIMED'") },
            { s: String -> s.replace("request_id TEXT NOT NULL PRIMARY KEY","request_id TEXT") },
        ).forEach { weaken ->
            JdbcSqlConnection().use { db ->
                Migrations.apply(db)
                val original=sql(db,"root_dispatch_receipts")
                val unsupported=weaken(original)
                assertNotEquals(original,unsupported)
                db.execute("DROP TABLE root_dispatch_receipts")
                db.execute(unsupported)
                assertEquals(runtime.mobileagent.domain.ErrorCode.SCHEMA_UNSUPPORTED,
                assertThrows(runtime.mobileagent.domain.AppException::class.java) { Migrations.apply(db) }.error.code)
                assertEquals(unsupported,sql(db,"root_dispatch_receipts"))
            }
        }
    }

    @Test fun wrongIndexDefinitionAndUnexpectedTriggerAreRejectedWithoutDroppingThem() {
        listOf(
            "CREATE INDEX idx_root_dispatch_invocation ON root_dispatch_receipts(run_id)" to "idx_root_dispatch_invocation",
            "CREATE TRIGGER unknown_receipt_hook BEFORE UPDATE ON root_dispatch_receipts BEGIN SELECT 1; END" to "unknown_receipt_hook",
        ).forEach { (declaration,name) ->
            JdbcSqlConnection().use { db ->
                Migrations.apply(db)
                if(name.startsWith("idx_")) db.execute("DROP INDEX idx_root_dispatch_invocation")
                db.execute(declaration)
                val unsupported=sql(db,name)
                assertEquals(runtime.mobileagent.domain.ErrorCode.SCHEMA_UNSUPPORTED,
                assertThrows(runtime.mobileagent.domain.AppException::class.java) { Migrations.apply(db) }.error.code)
                assertEquals(unsupported,sql(db,name))
            }
        }
    }

    @Test fun formattingDifferencesAndMissingKnownProtectionAreHandledWithoutChangingRows() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val original=sql(db,"root_dispatch_receipts")
            db.execute("DROP TABLE root_dispatch_receipts")
            db.execute(original.replace("CREATE TABLE","create  table").replace("CHECK","check").replace(","," , "))
            Migrations.apply(db)
            db.execute("DROP TRIGGER root_dispatch_initial")
            db.execute("DROP INDEX idx_root_dispatch_approval")
            Migrations.apply(db)
            assertTrue(sql(db,"root_dispatch_initial").contains("Root receipt must start"))
            assertTrue(sql(db,"idx_root_dispatch_approval").contains("UNIQUE"))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun existingV40ReceiptsSurviveAdditiveQueryIndexes() {
        JdbcSqlConnection().use { db ->
            val fixture = RootApprovalFixture()
            fixture.setup(db)
            val approvals = RootApprovalRepository(db, clock = { fixture.now })
            val approved = approvals.approve(approvals.createPending(fixture.request()).approval.approvalId)
            val repository = RootDispatchRepository(db) { fixture.now }
            val permit = repository.claim(approved.approval.approvalId, approved.request)
            val before = repository.getReceipt(permit.requestId)
            val indexes = listOf("idx_root_dispatch_open", "idx_root_dispatch_authority_epoch", "idx_root_dispatch_unknown")
            indexes.forEach { db.execute("DROP INDEX $it") }
            db.execute("UPDATE schema_version SET version=40")
            Migrations.apply(db)
            indexes.forEach { assertTrue(sql(db, it).contains("root_dispatch_receipts")) }
            assertEquals(before, repository.getReceipt(permit.requestId))
            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }
}
