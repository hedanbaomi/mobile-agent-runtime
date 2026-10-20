// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.DateTimeException
import java.time.Instant

/** Derived, fixed-width ASCII keys; the original timestamp remains immutable. */
internal object MessageChronology {
    fun key(raw: String): String = try {
        val instant = MessageChronologyCompatibility.parse(raw)
        // The entire Instant range is 63,113,904,031,622,399 seconds: 17 digits.
        "0" + (instant.epochSecond - Instant.MIN.epochSecond).toString().padStart(17, '0') +
            instant.nano.toString().padStart(9, '0')
    } catch (_: DateTimeException) {
        // Legacy nonblank values were accepted without parsing. Keep them after valid
        // instants, in rowid order, without duplicating arbitrarily large raw values.
        "1"
    }

    fun install(db: SqlConnection) {
        db.execute("CREATE INDEX IF NOT EXISTS messages_chronology ON messages(conversation_id,chronology_key,chronology_ordinal)")
        db.execute("CREATE INDEX IF NOT EXISTS messages_missing_chronology ON messages(conversation_id,id) WHERE chronology_key='' OR chronology_ordinal IS NULL")
        backfill(db)
    }

    /** Indexed batches project no message bodies. Exhaust missing metadata before any page. */
    fun backfill(db: SqlConnection, conversationId: String? = null) {
        val scope = if (conversationId == null) "" else " AND conversation_id=?"
        val args = conversationId?.let { listOf(it) }.orEmpty()
        val query = "SELECT id,created_at FROM messages INDEXED BY messages_missing_chronology WHERE (chronology_key='' OR chronology_ordinal IS NULL)$scope ORDER BY conversation_id,id"
        // Normal histories need no writer transaction at all. A positive read is rechecked
        // in the write transaction, so another completed repair cannot overwrite metadata.
        if (db.query("$query LIMIT 1", args).isEmpty()) return
        db.transaction {
            while (true) {
                val rows = db.query("$query LIMIT 128", args)
                if (rows.isEmpty()) return@transaction
                rows.forEach { row ->
                    db.execute("UPDATE messages SET chronology_key=?,chronology_ordinal=rowid WHERE id=? AND (chronology_key='' OR chronology_ordinal IS NULL)",
                        listOf(key(row.string("created_at")), row.string("id")))
                }
            }
        }
    }
}
