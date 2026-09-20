// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

/** Derived UTF-16 lengths. SQLite length(text) counts Unicode scalars, not Kotlin offsets. */
internal object ChunkTextMetadata {
    fun backfill(db: SqlConnection, version: String? = null) {
        val scope = if (version == null) "" else " AND document_version_id = ?"
        val args = version?.let { listOf(it) }.orEmpty()
        while (true) {
            val rows = db.query("SELECT id, text FROM chunks WHERE text_utf16_length < 0$scope ORDER BY id LIMIT 128", args)
            if (rows.isEmpty()) return
            rows.forEach { row ->
                db.execute("UPDATE chunks SET text_utf16_length = ? WHERE id = ? AND text_utf16_length < 0",
                    listOf(row.string("text").length, row.string("id")))
            }
        }
    }

    fun read(db: SqlConnection, version: String, maxChars: Int, offset: Int): KnowledgeDocumentRange {
        // Production writes and the v25 migration fill lengths. This bounded repair
        // also supports legacy backup rows; subsequent pages never reread their bodies.
        backfill(db, version)
        val totals = db.query("SELECT COUNT(*) AS n, COALESCE(SUM(text_utf16_length),0) AS chars FROM chunks WHERE document_version_id = ?",
            listOf(version)).single()
        val total = totals.long("chars") + (totals.long("n") - 1).coerceAtLeast(0)
        check(total in 0..Int.MAX_VALUE.toLong()) { "DOCUMENT_TOO_LARGE: UTF-16 offsets exceed supported range" }
        val length = total.toInt()
        val start = offset.coerceAtMost(length)
        val cap = maxChars.coerceIn(0, 16_384)
        var end = minOf(start.toLong() + cap, total).toInt()
        if (start == length) return KnowledgeDocumentRange("", start, null, length, version)
        // Include one character on each side to validate scalar boundaries without
        // reading any unrelated chunk bodies, even for a page near the document end.
        val low = (start - 1).coerceAtLeast(0)
        val high = minOf(end.toLong() + 1, total).toInt()
        val rows = db.query("""
            WITH positions AS (
                SELECT id, text_utf16_length,
                    COALESCE(SUM(text_utf16_length + 1) OVER (
                        ORDER BY ordinal ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) AS start_offset
                FROM chunks WHERE document_version_id = ?
            )
            SELECT c.text, p.text_utf16_length, p.start_offset
            FROM positions p JOIN chunks c ON c.id = p.id
            WHERE p.start_offset + p.text_utf16_length > ? AND p.start_offset - 1 < ?
            ORDER BY p.start_offset
        """.trimIndent(), listOf(version, low, high))
        val window = buildString(high - low) {
            rows.forEach { row ->
                val text = row.string("text")
                check(text.length.toLong() == row.long("text_utf16_length")) { "DOCUMENT_TEXT_METADATA_MISMATCH" }
                val begin = row.long("start_offset").toInt()
                if (begin > 0 && begin - 1 >= low && begin - 1 < high) append('\n')
                val from = (low - begin).coerceAtLeast(0)
                val to = (high - begin).coerceAtMost(text.length)
                if (to > from) append(text, from, to)
            }
        }
        check(window.length == high - low) { "DOCUMENT_TEXT_METADATA_MISMATCH" }
        fun splits(at: Int): Boolean = at > 0 && at < length &&
            window[at - low].isLowSurrogate() && window[at - low - 1].isHighSurrogate()
        require(!splits(start)) { "Document offset splits a Unicode scalar; use the returned nextOffset" }
        if (end > start && splits(end)) end--
        require(end > start || cap == 0) { "maxChars cannot fit the next Unicode scalar" }
        return KnowledgeDocumentRange(window.substring(start - low, end - low), start, end.takeIf { it < length }, length, version)
    }
}
