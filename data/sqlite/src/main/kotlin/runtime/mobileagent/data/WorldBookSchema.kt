// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** Additive v35 schema. Every statement runs inside the owning migration transaction. */
internal object WorldBookSchema {
    fun install(db: SqlConnection) {
        statements.forEach { db.execute(it) }
        listOf("agent_profiles", "agent_snapshots").forEach { table ->
            if (db.query("PRAGMA table_info($table)").none { it.string("name") == "world_book_ids" }) {
                db.execute("ALTER TABLE $table ADD COLUMN world_book_ids TEXT NOT NULL DEFAULT '[]'")
            }
        }
        requiredColumns.forEach { (table, columns) ->
            val present = db.query("PRAGMA table_info($table)").map { it.string("name") }.toSet()
            check(present.containsAll(columns)) { "World book schema is incomplete" }
        }
    }

    private val statements = listOf(
        """CREATE TABLE IF NOT EXISTS world_book_revocations (
            book_id TEXT PRIMARY KEY, epoch INTEGER NOT NULL CHECK(epoch>0))""",
        """CREATE TABLE IF NOT EXISTS world_books (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
            enabled INTEGER NOT NULL CHECK(enabled IN(0,1)), revision INTEGER NOT NULL CHECK(revision>0),
            entry_count INTEGER NOT NULL CHECK(entry_count>=0), revocation_epoch INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS world_book_revisions (
            book_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0),
            document_json TEXT NOT NULL, content_hash TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length>=0),
            created_at TEXT NOT NULL, PRIMARY KEY(book_id,revision),
            FOREIGN KEY(book_id) REFERENCES world_books(id) ON DELETE CASCADE)""",
        """CREATE TABLE IF NOT EXISTS world_book_conversations (
            conversation_id TEXT PRIMARY KEY, book_ids_json TEXT NOT NULL,
            revision INTEGER NOT NULL CHECK(revision>0), settings_json TEXT NOT NULL, context_json TEXT NOT NULL,
            updated_at TEXT NOT NULL, FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)""",
        """CREATE TABLE IF NOT EXISTS world_book_effects (
            conversation_id TEXT PRIMARY KEY, binding_revision INTEGER NOT NULL,
            effects_json TEXT NOT NULL, message_count INTEGER NOT NULL CHECK(message_count>=0),
            FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)""",
        """CREATE TABLE IF NOT EXISTS world_book_runs (
            run_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, snapshot_id TEXT NOT NULL, agent_id TEXT NOT NULL,
            binding_revision INTEGER NOT NULL, pins_json TEXT NOT NULL, evaluation_json TEXT NOT NULL,
            next_effects_json TEXT NOT NULL, message_count INTEGER NOT NULL CHECK(message_count>=0),
            budget_units INTEGER NOT NULL CHECK(budget_units>=0), seed INTEGER NOT NULL,
            dispatched INTEGER NOT NULL DEFAULT 0 CHECK(dispatched IN(0,1)), created_at TEXT NOT NULL,
            FOREIGN KEY(run_id) REFERENCES runs(run_id) ON DELETE CASCADE,
            FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)""",
        "CREATE INDEX IF NOT EXISTS world_book_runs_conversation ON world_book_runs(conversation_id,created_at,run_id)",
    )

    private val requiredColumns = mapOf(
        "world_book_revocations" to setOf("book_id", "epoch"),
        "world_books" to setOf("id", "name", "description", "enabled", "revision", "entry_count", "revocation_epoch", "created_at", "updated_at"),
        "world_book_revisions" to setOf("book_id", "revision", "document_json", "content_hash", "byte_length", "created_at"),
        "world_book_conversations" to setOf("conversation_id", "book_ids_json", "revision", "settings_json", "context_json", "updated_at"),
        "world_book_effects" to setOf("conversation_id", "binding_revision", "effects_json", "message_count"),
        "world_book_runs" to setOf("run_id", "conversation_id", "snapshot_id", "agent_id", "binding_revision", "pins_json", "evaluation_json", "next_effects_json", "message_count", "budget_units", "seed", "dispatched", "created_at"),
    )
}
