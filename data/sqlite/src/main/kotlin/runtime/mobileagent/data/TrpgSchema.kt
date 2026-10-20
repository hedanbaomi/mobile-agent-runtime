// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

internal object TrpgSchema {
    fun install(db: SqlConnection) {
        listOf("agent_profiles", "agent_snapshots").forEach { table ->
            if (db.query("PRAGMA table_info($table)").none { it.string("name") == "mode" }) {
                db.execute("ALTER TABLE $table ADD COLUMN mode TEXT NOT NULL DEFAULT 'NORMAL' CHECK(mode IN ('NORMAL','TRPG'))")
            }
        }
        db.execute("CREATE TRIGGER IF NOT EXISTS agent_mode_immutable BEFORE UPDATE OF mode ON agent_profiles WHEN NEW.mode<>OLD.mode BEGIN SELECT RAISE(ABORT,'Agent mode is immutable'); END")
        db.execute("CREATE TRIGGER IF NOT EXISTS snapshot_mode_immutable BEFORE UPDATE OF mode ON agent_snapshots WHEN NEW.mode<>OLD.mode BEGIN SELECT RAISE(ABORT,'Snapshot mode is immutable'); END")
        db.execute("CREATE TABLE IF NOT EXISTS trpg_sessions(conversation_id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision>=0), state_json TEXT NOT NULL CHECK(length(CAST(state_json AS BLOB))<=65536), updated_at TEXT NOT NULL, FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execute("CREATE TABLE IF NOT EXISTS trpg_run_receipts(invocation_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, snapshot_id TEXT NOT NULL, run_id TEXT NOT NULL, request_hash TEXT NOT NULL, session_json TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE, FOREIGN KEY(run_id) REFERENCES runs(run_id) ON DELETE CASCADE)")
    }
}
