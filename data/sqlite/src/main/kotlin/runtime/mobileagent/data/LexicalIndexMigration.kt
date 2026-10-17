// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.CjkLexical

/**
 * Owns the shape of the derived lexical index, `chunks_fts`.
 *
 * The index used to be external content (`content='chunks'`), so it was a projection of a source
 * row with no guarantee that its postings agreed with `chunks.text`. It is now contentless-delete:
 * `fts5(text, content='', contentless_delete=1)` stores postings only, supports a plain rowid
 * DELETE, and can never become a second source of chunk text. Postings are rebuilt from the real
 * `chunks.text` through the canonical [CjkLexical.indexText] tokenizer input, so a wrong or
 * missing legacy posting cannot survive.
 *
 * Only the derived index (and its shadow tables) is dropped. `chunks`, embeddings, generations,
 * permission/consent rows and every other durable row are read, never rewritten. The caller runs
 * this inside the existing migration transaction, so a failure or interruption restores the
 * previous index and leaves `schema_version` at its old value for a retry.
 */
internal object LexicalIndexMigration {
    private const val BATCH = 128
    private const val CHUNK_PAGE = "SELECT rowid AS rid, text FROM chunks"

    /**
     * A current-version database with the correct index is left completely alone. Everything else
     * is rebuilt: a pre-v34 database, a database whose index was dropped by a restore (the
     * historical `CREATE VIRTUAL TABLE IF NOT EXISTS` statement runs first on every open and would
     * otherwise leave an external-content table behind at v34, where the plain rowid DELETE used by
     * maintenance would be wrong), and an index whose definition does not match this type.
     */
    fun ensure(connection: SqlConnection, current: Long) {
        if (current >= Migrations.VERSION && isContentlessDeleteIndex(connection)) return
        convert(connection)
    }

    private fun convert(connection: SqlConnection) {
        // Drop only the derived index; this also covers a database whose FTS index was already
        // removed by a restore. A real error on the source table is never swallowed below.
        connection.execute("DROP TABLE IF EXISTS chunks_fts")
        connection.execute("CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='', contentless_delete=1)")
        // Keyset paging on chunks.rowid keeps memory bounded and preserves the rowid identity
        // that retrieval joins on. No batch rewrites or reorders a source row.
        var after: Long? = null
        while (true) {
            checkInterrupted()
            val where = if (after == null) "" else " WHERE rowid > ?"
            val rows = connection.query(
                "$CHUNK_PAGE$where ORDER BY rowid LIMIT ?",
                listOfNotNull(after) + listOf(BATCH),
            )
            if (rows.isEmpty()) return
            connection.execute(
                "INSERT INTO chunks_fts(rowid,text) VALUES " + rows.joinToString(",") { "(?,?)" },
                rows.flatMap { listOf(it.long("rid"), CjkLexical.indexText(it.string("text"))) },
            )
            after = rows.last().long("rid")
        }
    }

    /** `contentless_delete=1` is only accepted by SQLite for a contentless table. */
    private fun isContentlessDeleteIndex(connection: SqlConnection): Boolean = connection.query(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='chunks_fts'",
    ).singleOrNull()?.string("sql")?.contains("contentless_delete=1") ?: false

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Lexical index migration interrupted")
        }
    }
}
