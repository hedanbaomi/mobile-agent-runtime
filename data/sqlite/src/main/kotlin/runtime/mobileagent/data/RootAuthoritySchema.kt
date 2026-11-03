// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** v40 adds consent without deriving Root trust from any pre-existing row. */
internal object RootAuthoritySchema {
    const val POLICY_SQL = "CREATE TABLE IF NOT EXISTS authority_policy (id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1), selected_authority TEXT NOT NULL CHECK(selected_authority IN('NONE','SHIZUKU','WIRED_ADB','ROOT_DIRECT','SHIZUKU_ROOT')), dangerous_mode TEXT NOT NULL CHECK(dangerous_mode IN('DISABLED','ENABLED_CONFIRM_HIGH_RISK','ENABLED_AUTONOMOUS')), policy_version INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0), updated_at TEXT NOT NULL)"
    const val PREFERENCES_SQL = "CREATE TABLE IF NOT EXISTS authority_preferences (authority TEXT NOT NULL PRIMARY KEY CHECK(authority IN('NONE','SHIZUKU','WIRED_ADB','ROOT_DIRECT','SHIZUKU_ROOT')), user_intent_enabled INTEGER NOT NULL DEFAULT 0 CHECK(user_intent_enabled IN(0,1)), explicitly_configured INTEGER NOT NULL DEFAULT 0 CHECK(explicitly_configured IN(0,1)), updated_at TEXT NOT NULL)"

    const val APPROVAL_SQL = "CREATE TABLE IF NOT EXISTS approval_records (approval_id TEXT PRIMARY KEY, request_id TEXT NOT NULL, call_id TEXT NOT NULL, agent_id TEXT NOT NULL, skill_id TEXT, command_hash TEXT, cwd_hash TEXT, selected_authority TEXT NOT NULL CHECK(selected_authority IN('NONE','SHIZUKU','WIRED_ADB','ROOT_DIRECT','SHIZUKU_ROOT')), dangerous_mode TEXT NOT NULL CHECK(dangerous_mode IN('DISABLED','ENABLED_CONFIRM_HIGH_RISK','ENABLED_AUTONOMOUS')), tool_schema_version INTEGER NOT NULL CHECK(tool_schema_version > 0), policy_version INTEGER NOT NULL CHECK(policy_version >= 0), config_snapshot_hash TEXT NOT NULL, decision TEXT NOT NULL CHECK(decision IN('APPROVED','DENIED','EXPIRED','CONSUMED')), created_at TEXT NOT NULL, expires_at TEXT, consumed_at TEXT, UNIQUE(request_id))"
    const val AUDIT_SQL = "CREATE TABLE IF NOT EXISTS tool_audit_details (audit_id TEXT PRIMARY KEY, request_id TEXT NOT NULL, agent_id TEXT NOT NULL, skill_id TEXT, capability TEXT NOT NULL, workspace_id TEXT, relative_path_sha256 TEXT, authority TEXT NOT NULL CHECK(authority IN('NONE','SHIZUKU','WIRED_ADB','ROOT_DIRECT','SHIZUKU_ROOT')), approval_id TEXT, dangerous_mode TEXT, policy_version INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0), cwd_sha256 TEXT, command_sha256 TEXT, exit_code INTEGER, timed_out INTEGER NOT NULL DEFAULT 0 CHECK(timed_out IN(0,1)), cancelled INTEGER NOT NULL DEFAULT 0 CHECK(cancelled IN(0,1)), stdout_truncated INTEGER NOT NULL DEFAULT 0 CHECK(stdout_truncated IN(0,1)), stderr_truncated INTEGER NOT NULL DEFAULT 0 CHECK(stderr_truncated IN(0,1)), stdout_bytes INTEGER NOT NULL DEFAULT 0 CHECK(stdout_bytes >= 0), stderr_bytes INTEGER NOT NULL DEFAULT 0 CHECK(stderr_bytes >= 0), duration_ms INTEGER NOT NULL DEFAULT 0 CHECK(duration_ms >= 0), result TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY(audit_id) REFERENCES audit_events(id))"
    const val TOOL_INVOCATIONS_SQL = "CREATE TABLE IF NOT EXISTS tool_invocations (invocation_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, call_id TEXT NOT NULL, name TEXT NOT NULL, arguments_json TEXT NOT NULL, permission_decision TEXT NOT NULL, state TEXT NOT NULL, result_json TEXT, error_code TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(run_id) REFERENCES runs(run_id))"

    fun install(db: SqlConnection, previousVersion: Long) {
        widenAuthorityTable(db, previousVersion, "authority_policy", POLICY_SQL,
            setOf("id", "selected_authority", "dangerous_mode", "policy_version", "updated_at"))
        widenAuthorityTable(db, previousVersion, "authority_preferences", PREFERENCES_SQL,
            setOf("authority", "user_intent_enabled", "explicitly_configured", "updated_at"))
        widenAuthorityTable(db, previousVersion, "approval_records", APPROVAL_SQL,
            setOf("approval_id","request_id","call_id","agent_id","skill_id","command_hash","cwd_hash",
                "selected_authority","dangerous_mode","tool_schema_version","policy_version","config_snapshot_hash",
                "decision","created_at","expires_at","consumed_at"),
            mapOf("idx_approval_records_call" to "CREATE INDEX idx_approval_records_call ON approval_records(call_id, created_at)"),
            allowDraft40 = true)
        widenAuthorityTable(db, previousVersion, "tool_audit_details", AUDIT_SQL,
            setOf("audit_id","request_id","agent_id","skill_id","capability","workspace_id","relative_path_sha256",
                "authority","approval_id","dangerous_mode","policy_version","cwd_sha256","command_sha256","exit_code",
                "timed_out","cancelled","stdout_truncated","stderr_truncated","stdout_bytes","stderr_bytes",
                "duration_ms","result","created_at"),
            mapOf("idx_tool_audit_details_request" to "CREATE INDEX idx_tool_audit_details_request ON tool_audit_details(request_id, created_at)"),
            allowDraft40 = true)
        db.execute(
            "CREATE TABLE IF NOT EXISTS root_authority_state (" +
                "authority TEXT NOT NULL PRIMARY KEY CHECK(authority IN('ROOT_DIRECT','SHIZUKU_ROOT')), " +
                "enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN(0,1)), " +
                "shell_enabled INTEGER NOT NULL DEFAULT 0 CHECK(shell_enabled IN(0,1)), " +
                "consent_revision INTEGER NOT NULL DEFAULT 0 CHECK(consent_revision >= 0), " +
                "shell_revision INTEGER NOT NULL DEFAULT 0 CHECK(shell_revision >= 0), " +
                "policy_version INTEGER NOT NULL DEFAULT 0 CHECK(policy_version >= 0), " +
                "revision INTEGER NOT NULL CHECK(revision > 0), " +
                "identity_epoch INTEGER NOT NULL DEFAULT 0 CHECK(identity_epoch >= 0), " +
                "degraded_epoch INTEGER CHECK(degraded_epoch > 0 AND degraded_epoch <= identity_epoch), " +
                "recovery_ack_epoch INTEGER NOT NULL DEFAULT 0 CHECK(recovery_ack_epoch >= 0 AND (recovery_ack_epoch = 0 OR recovery_ack_epoch < identity_epoch)), " +
                "updated_at TEXT NOT NULL, " +
                "CHECK(enabled = 0 OR consent_revision > 0), " +
                "CHECK(shell_enabled = 0 OR (enabled = 1 AND shell_revision > 0)))",
        )
        val rootStateColumns = db.query("PRAGMA table_info(root_authority_state)").map { it.string("name") }
        if ("recovery_ack_epoch" !in rootStateColumns) {
            // Preserve old uncertainty. No prior row implies a foreground recovery acknowledgement.
            db.execute("ALTER TABLE root_authority_state ADD COLUMN recovery_ack_epoch INTEGER NOT NULL DEFAULT 0 CHECK(recovery_ack_epoch >= 0 AND (recovery_ack_epoch = 0 OR recovery_ack_epoch < identity_epoch))")
        }
        RootWorkspaceRepository.install(db)
        RootWorkspaceLocatorRepository.install(db)
        RootGrantRepository.install(db)
        RootSnapshotGrantRepository.install(db)
        RootAuditRepository.install(db)
        widenToolInvocationsTable(db, previousVersion)
        RootApprovalRepository.install(db)
        RootDispatchSchema.install(db)
    }

    // The accepted index grammar stays fixed; formatting from older SQLite is irrelevant.
    private fun normalizeIndexSql(sql: String): String = sql
        .replace(Regex("(?i)\\bIF\\s+NOT\\s+EXISTS\\s+"),"")
        .replace(Regex("\\s+"),"").lowercase(java.util.Locale.ROOT)

    private fun widenAuthorityTable(
        db: SqlConnection,
        previousVersion: Long,
        table: String,
        declaration: String,
        expectedColumns: Set<String>,
        expectedIndexes: Map<String,String> = emptyMap(),
        allowDraft40: Boolean = false,
    ) {
        val stored = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", listOf(table))
            .single().string("sql")
        if (stored.contains("'ROOT_DIRECT'") && stored.contains("'SHIZUKU_ROOT'")) return
        check(previousVersion < 40 || (allowDraft40 && previousVersion == 40L)) {
            "Current authority schema is missing Root constraints"
        }
        val columns = db.query("PRAGMA table_info($table)").map { it.string("name") }
        check(columns.toSet() == expectedColumns) { "Authority migration cannot discard unknown columns" }
        // Both tables are independent control-plane roots; never disable foreign keys to rebuild them.
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it.string("name") }
        check(tables.none { name ->
            val quoted = name.replace("\"", "\"\"")
            db.query("PRAGMA foreign_key_list(\"$quoted\")").any { it.string("table") == table }
        }) { "Authority migration found an unexpected foreign-key dependent" }
        val adjuncts = db.query(
            "SELECT name,type,sql FROM sqlite_master WHERE tbl_name = ? AND type IN('index','trigger') AND sql IS NOT NULL",
            listOf(table),
        )
        check(adjuncts.all { it.string("type") == "index" &&
            expectedIndexes[it.string("name")]?.let { expected ->
                normalizeIndexSql(it.string("sql")) == normalizeIndexSql(expected)
            } == true }) {
            "Authority migration found an unexpected index or trigger"
        }
        val temporary = "${table}_root_v40"
        check(db.query("SELECT name FROM sqlite_master WHERE name = ?", listOf(temporary)).isEmpty()) {
            "Authority migration temporary table already exists"
        }
        db.execute(declaration.replace("IF NOT EXISTS $table ", "$temporary "))
        val projection = columns.joinToString(",") { "\"$it\"" }
        db.execute("INSERT INTO $temporary ($projection) SELECT $projection FROM $table")
        db.execute("DROP TABLE $table")
        db.execute("ALTER TABLE $temporary RENAME TO $table")
        expectedIndexes.values.forEach { db.execute(it) }
    }

    private fun widenToolInvocationsTable(db: SqlConnection, previousVersion: Long) {
        val stored = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'tool_invocations'")
            .single().string("sql")
        if (!stored.contains("UNIQUE(run_id, call_id)")) return
        check(previousVersion <= 40L) { "Current tool_invocations schema has legacy UNIQUE(run_id, call_id)" }
        val expectedColumns = setOf(
            "invocation_id", "run_id", "call_id", "name", "arguments_json",
            "permission_decision", "state", "result_json", "error_code", "created_at", "updated_at",
        )
        val columns = db.query("PRAGMA table_info(tool_invocations)").map { it.string("name") }
        check(columns.toSet() == expectedColumns) { "tool_invocations migration cannot discard unknown columns" }
        val hasReceipts = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'root_dispatch_receipts'").isNotEmpty()
        if (hasReceipts) {
            RootDispatchSchema.verifyExisting(db)
            db.execute("CREATE TEMP TABLE root_dispatch_receipts_v40_bak AS SELECT * FROM root_dispatch_receipts")
            db.execute("DROP TABLE root_dispatch_receipts")
        }
        val hasApprovals = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'root_approval_bindings'").isNotEmpty()
        if (hasApprovals) {
            db.execute("CREATE TEMP TABLE root_approval_bindings_v40_bak AS SELECT * FROM root_approval_bindings")
            db.execute("DROP TABLE root_approval_bindings")
        }
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it.string("name") }
        check(tables.none { name ->
            val quoted = name.replace("\"", "\"\"")
            db.query("PRAGMA foreign_key_list(\"$quoted\")").any { it.string("table") == "tool_invocations" }
        }) { "tool_invocations migration found an unexpected foreign-key dependent" }
        val temporary = "tool_invocations_root_v40"
        check(db.query("SELECT name FROM sqlite_master WHERE name = ?", listOf(temporary)).isEmpty()) {
            "tool_invocations migration temporary table already exists"
        }
        db.execute(TOOL_INVOCATIONS_SQL.replace("IF NOT EXISTS tool_invocations ", "$temporary "))
        val projection = columns.joinToString(",") { "\"$it\"" }
        db.execute("INSERT INTO $temporary ($projection) SELECT $projection FROM tool_invocations")
        db.execute("DROP TABLE tool_invocations")
        db.execute("ALTER TABLE $temporary RENAME TO tool_invocations")
        db.execute("CREATE INDEX IF NOT EXISTS idx_tool_invocations_run ON tool_invocations(run_id, created_at)")
        if (hasApprovals) {
            RootApprovalRepository.install(db)
            val approvalCols = db.query("PRAGMA table_info(root_approval_bindings_v40_bak)")
                .map { "\"${it.string("name")}\"" }.joinToString(",")
            db.execute("INSERT INTO root_approval_bindings ($approvalCols) SELECT $approvalCols FROM temp.root_approval_bindings_v40_bak")
            db.execute("DROP TABLE temp.root_approval_bindings_v40_bak")
        }
        if (hasReceipts) {
            RootDispatchSchema.restoreFromBackup(db, "temp.root_dispatch_receipts_v40_bak")
            db.execute("DROP TABLE temp.root_dispatch_receipts_v40_bak")
        }
    }
}
