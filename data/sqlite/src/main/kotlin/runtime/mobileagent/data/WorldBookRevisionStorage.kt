// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
import runtime.mobileagent.serialization.worldbook.WorldBookCodec

/** A metadata revision points directly to an earlier immutable body in the same book. */
internal object WorldBookRevisionStorage {
    fun body(db: SqlConnection, row: SqlRow): SqlRow {
        val target = row.longOrNull("document_revision")
        if (target == null) return row
        check(target > 0 && target < row.long("revision") && row.string("document_json").isEmpty() &&
            row.long("byte_length") == 0L) { "Invalid world book revision reference" }
        val body = checkNotNull(db.query(
            "SELECT * FROM world_book_revisions WHERE book_id=? AND revision=?",
            listOf(row.string("book_id"), target),
        ).singleOrNull()) { "World book revision content is missing" }
        check(body.columns["document_revision"] == null && body.string("content_hash") == row.string("content_hash")) {
            "Invalid world book revision reference"
        }
        return body
    }

    fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Runs inside Migrations' transaction. History IDs/pins survive representation compaction. */
    fun install(db: SqlConnection, previousVersion: Long) {
        val columns = db.query("PRAGMA table_info(world_book_revisions)").map { it.string("name") }
        if ("document_revision" !in columns) {
            check(previousVersion < 37L) { "World book revision schema is incomplete" }
            db.execute("ALTER TABLE world_book_revisions ADD COLUMN document_revision INTEGER CHECK(document_revision IS NULL OR (document_revision>0 AND document_revision<revision))")
        }
        if (previousVersion >= 37L) return
        // Select only IDs up front: never hold the whole historical payload in memory.
        db.query("SELECT id FROM world_books ORDER BY id").forEach { book ->
            compactBook(db, book.string("id"))
        }
    }

    private fun compactBook(db: SqlConnection, id: String) {
        val bodies = mutableMapOf<String, Long>()
        db.query("SELECT revision FROM world_book_revisions WHERE book_id=? ORDER BY revision", listOf(id)).forEach { key ->
            val revision = key.long("revision")
            val row = db.query("SELECT * FROM world_book_revisions WHERE book_id=? AND revision=?", listOf(id, revision)).single()
            check(row.columns["document_revision"] == null) { "Unexpected legacy world book revision reference" }
            val old = row.string("document_json")
            check(hash(old) == row.string("content_hash")) { "World book revision integrity check failed" }
            val document = WorldBookCodec.decode(old).document
            val compact = WorldBookCodec.encodeNative(document)
            check(WorldBookCodec.decode(compact).document == document) { "World book compaction changed content" }
            val hash = hash(compact)
            val target = bodies[hash]
            db.execute(
                "UPDATE world_book_revisions SET document_json=?,content_hash=?,byte_length=?,document_revision=? WHERE book_id=? AND revision=?",
                listOf(if (target == null) compact else "", hash,
                    if (target == null) compact.toByteArray(Charsets.UTF_8).size.toLong() else 0L,
                    target, id, revision),
            )
            if (target == null) bodies[hash] = revision
        }
    }
}
