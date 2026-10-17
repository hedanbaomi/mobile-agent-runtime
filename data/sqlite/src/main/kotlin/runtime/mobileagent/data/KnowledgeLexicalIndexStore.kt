// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.CjkLexical

/** Canonical CJK postings in a contentless-delete index; chunks remain the source of text. */
internal class KnowledgeLexicalIndexStore(private val db: SqlConnection) {
    data class Source(val rowId: Long, val text: String)

    /** Callers supply immutable chunks. Presence is evidence of a prior insertion, not of its contents. */
    fun insertMissing(rows: List<Source>) {
        rows.chunked(128).forEach { batch ->
            checkInterrupted()
            val indexed = indexedRows(batch.map { it.rowId }) ?: return
            insert(batch.filter { it.rowId !in indexed })
        }
    }

    /** Rowid deletion removes every prior token, including an inconsistent old tokenizer input. */
    fun remove(rowIds: List<Long>) {
        rowIds.chunked(128).forEach { batch ->
            checkInterrupted()
            try {
                db.execute("DELETE FROM chunks_fts WHERE rowid IN (${batch.joinToString(",") { "?" }})", batch)
            } catch (failure: Exception) {
                if (exists()) throw failure else return
            }
        }
    }

    /** Explicit repair touches only the selected generation's chunks, in the publication transaction. */
    fun replace(rows: List<Source>) {
        rows.chunked(128).forEach { batch ->
            checkInterrupted()
            indexedRows(batch.map { it.rowId }) ?: return
            remove(batch.map { it.rowId })
            insert(batch)
        }
    }

    private fun indexedRows(ids: List<Long>): Set<Long>? = try {
        db.query("SELECT rowid AS rid FROM chunks_fts WHERE rowid IN (${ids.joinToString(",") { "?" }})", ids)
            .mapTo(hashSetOf()) { it.long("rid") }
    } catch (failure: Exception) {
        if (exists()) throw failure else null
    }

    private fun insert(rows: List<Source>) {
        if (rows.isEmpty()) return
        db.execute("INSERT INTO chunks_fts(rowid,text) VALUES " + rows.joinToString(",") { "(?,?)" },
            rows.flatMap { listOf(it.rowId, CjkLexical.indexText(it.text)) })
    }

    private fun exists(): Boolean = db.query(
        "SELECT 1 AS available FROM sqlite_master WHERE type='table' AND name='chunks_fts' LIMIT 1",
    ).isNotEmpty()

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Lexical index maintenance interrupted")
    }
}
