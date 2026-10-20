// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime

/** Android 8's ISO_INSTANT parser accepts Z only; offset parsing must be explicit there. */
internal object MessageChronologyCompatibility {
    private val seconds = Regex("[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}")

    fun parse(raw: String): Instant = try {
        // Preserve the full Instant range and its existing UTC/leap-second behavior.
        Instant.parse(raw)
    } catch (failure: DateTimeException) {
        // ISO_OFFSET_DATE_TIME alone also accepts a clock without seconds, which
        // was previously an invalid legacy timestamp. Do not reclassify that form.
        if (!seconds.containsMatchIn(raw)) throw failure
        OffsetDateTime.parse(raw).toInstant()
    }

    /** Called inside migration38's transaction; repair derived data, never source messages. */
    fun repair(db: SqlConnection, previousVersion: Long) {
        if (previousVersion !in 33L..37L) return
        var after: Long? = null
        while (true) {
            val scope = if (after == null) "" else " AND rowid>?"
            val rows = db.query("SELECT rowid AS sequence,id,created_at FROM messages WHERE chronology_key='1'$scope ORDER BY rowid LIMIT 128",
                after?.let { listOf(it) }.orEmpty())
            if (rows.isEmpty()) return
            rows.forEach { row ->
                val key = MessageChronology.key(row.string("created_at"))
                if (key != "1") db.execute("UPDATE messages SET chronology_key=? WHERE id=? AND chronology_key='1'",
                    listOf(key, row.string("id")))
            }
            // Advance past invalid rows too, including an entirely invalid batch.
            after = rows.last().long("sequence")
        }
    }
}
