// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** Unpublished v40 addition. Unsupported definitions are rejected without rewriting evidence. */
internal object RootDispatchSchema {
    fun install(db: SqlConnection) = RootReceiptSchemaVerifier.install(db,tableSql,adjuncts)

    fun verifyExisting(db: SqlConnection) = RootReceiptSchemaVerifier.verifyExisting(db, tableSql, adjuncts)

    fun restoreFromBackup(db: SqlConnection, backupTable: String) {
        db.execute(tableSql)
        val cols = db.query("PRAGMA table_info(root_dispatch_receipts)")
            .map { "\"${it.string("name")}\"" }.joinToString(",")
        db.execute("INSERT INTO root_dispatch_receipts ($cols) SELECT $cols FROM $backupTable")
        install(db)
    }

    private val tableSql = """CREATE TABLE IF NOT EXISTS root_dispatch_receipts(
            request_id TEXT NOT NULL PRIMARY KEY,
            approval_id TEXT NOT NULL UNIQUE REFERENCES approval_records(approval_id) ON DELETE RESTRICT,
            run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE RESTRICT,
            invocation_id TEXT NOT NULL UNIQUE REFERENCES tool_invocations(invocation_id) ON DELETE RESTRICT,
            snapshot_id TEXT NOT NULL REFERENCES agent_snapshots(id) ON DELETE RESTRICT,
            grant_id TEXT NOT NULL REFERENCES capability_grants(grant_id) ON DELETE RESTRICT,
            authority TEXT NOT NULL CHECK(authority IN('ROOT_DIRECT','SHIZUKU_ROOT')),
            identity_epoch INTEGER NOT NULL CHECK(identity_epoch>0),controller_id TEXT NOT NULL,
            receipt_version INTEGER NOT NULL CHECK(receipt_version=1),
            claim_json TEXT NOT NULL CHECK(length(CAST(claim_json AS BLOB)) BETWEEN 1 AND 65536),
            stage TEXT NOT NULL CHECK(stage IN('CLAIMED','ATTEMPTED','ACKNOWLEDGED','TERMINAL')),
            revision INTEGER NOT NULL CHECK(revision>0),
            outcome TEXT CHECK(outcome IN('SUCCEEDED','FAILED','REJECTED_BEFORE_ACCEPTED','CANCELLED_BEFORE_ATTEMPT','UNKNOWN_OUTCOME')),
            terminal_audit_id TEXT REFERENCES root_audit_bindings(audit_id) ON DELETE RESTRICT,
            finished_at TEXT,result_sha256 TEXT,closed INTEGER NOT NULL DEFAULT 0 CHECK(closed IN(0,1)),
            disclosure TEXT NOT NULL DEFAULT 'PENDING' CHECK(disclosure IN('PENDING','RELEASED','WITHHELD')),
            FOREIGN KEY(approval_id) REFERENCES root_approval_bindings(approval_id) ON DELETE RESTRICT,
            CHECK((stage='TERMINAL' AND outcome IS NOT NULL AND terminal_audit_id IS NOT NULL AND finished_at IS NOT NULL)
              OR (stage<>'TERMINAL' AND outcome IS NULL AND terminal_audit_id IS NULL AND finished_at IS NULL AND result_sha256 IS NULL)),
            CHECK((closed=0 AND disclosure='PENDING') OR (closed=1 AND stage='TERMINAL' AND disclosure<>'PENDING'))
        )""".trimIndent()

    private val adjuncts = mapOf(
        "idx_root_dispatch_invocation" to """CREATE UNIQUE INDEX IF NOT EXISTS idx_root_dispatch_invocation ON root_dispatch_receipts(invocation_id)""".trimIndent(),
        "idx_root_dispatch_approval" to """CREATE UNIQUE INDEX IF NOT EXISTS idx_root_dispatch_approval ON root_dispatch_receipts(approval_id)""".trimIndent(),
        "root_dispatch_initial" to """CREATE TRIGGER IF NOT EXISTS root_dispatch_initial BEFORE INSERT ON root_dispatch_receipts
            WHEN NEW.stage<>'CLAIMED' OR NEW.revision<>1 OR NEW.closed<>0 OR NEW.disclosure<>'PENDING'
              OR NEW.outcome IS NOT NULL OR NEW.terminal_audit_id IS NOT NULL OR NEW.finished_at IS NOT NULL
              OR NEW.result_sha256 IS NOT NULL OR NEW.receipt_version<>1
            BEGIN SELECT RAISE(ABORT,'Root receipt must start as a fresh claim'); END""".trimIndent(),
        "root_dispatch_claim_immutable" to """CREATE TRIGGER IF NOT EXISTS root_dispatch_claim_immutable BEFORE UPDATE ON root_dispatch_receipts
            WHEN NEW.request_id<>OLD.request_id OR NEW.approval_id<>OLD.approval_id OR NEW.run_id<>OLD.run_id
              OR NEW.invocation_id<>OLD.invocation_id OR NEW.snapshot_id<>OLD.snapshot_id OR NEW.grant_id<>OLD.grant_id
              OR NEW.authority<>OLD.authority OR NEW.identity_epoch<>OLD.identity_epoch OR NEW.controller_id<>OLD.controller_id
              OR NEW.receipt_version<>OLD.receipt_version OR NEW.claim_json<>OLD.claim_json
            BEGIN SELECT RAISE(ABORT,'Root dispatch claim is immutable'); END""".trimIndent(),
        "root_dispatch_transition" to """CREATE TRIGGER IF NOT EXISTS root_dispatch_transition BEFORE UPDATE ON root_dispatch_receipts
            WHEN NEW.revision<>OLD.revision+1 OR NOT(
              (OLD.stage='CLAIMED' AND NEW.stage IN('ATTEMPTED','TERMINAL')) OR
              (OLD.stage='ATTEMPTED' AND NEW.stage IN('ACKNOWLEDGED','TERMINAL')) OR
              (OLD.stage='ACKNOWLEDGED' AND NEW.stage='TERMINAL') OR
              (OLD.stage='TERMINAL' AND NEW.stage='TERMINAL' AND OLD.closed=0 AND NEW.closed=1
               AND NEW.outcome IS OLD.outcome AND NEW.terminal_audit_id IS OLD.terminal_audit_id
               AND NEW.finished_at IS OLD.finished_at AND NEW.result_sha256 IS OLD.result_sha256))
            BEGIN SELECT RAISE(ABORT,'Invalid Root dispatch transition'); END""".trimIndent(),
        "root_dispatch_cleanup_guard" to """CREATE TRIGGER IF NOT EXISTS root_dispatch_cleanup_guard BEFORE DELETE ON root_dispatch_receipts
            WHEN OLD.stage<>'TERMINAL' OR OLD.closed<>1 OR EXISTS(
              SELECT 1 FROM tool_invocations i WHERE i.invocation_id=OLD.invocation_id AND i.state GLOB 'ROOT_DISPATCH_*') OR NOT EXISTS(
              SELECT 1 FROM root_audit_bindings b JOIN tool_audit_details d ON d.audit_id=b.audit_id
                JOIN audit_events a ON a.id=d.audit_id
              WHERE b.audit_id=OLD.terminal_audit_id AND d.request_id=OLD.request_id
                AND d.approval_id=OLD.approval_id AND d.authority=OLD.authority)
            BEGIN SELECT RAISE(ABORT,'Root dispatch evidence must settle before cleanup'); END""".trimIndent(),
    )
}
