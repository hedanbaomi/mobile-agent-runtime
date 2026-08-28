// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.announcements.AnnouncementItem
import runtime.mobileagent.announcements.AnnouncementLocalState
import runtime.mobileagent.announcements.CachedAnnouncement
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.announcements.FeedVerifier
import runtime.mobileagent.announcements.FeedVerifyResult
import runtime.mobileagent.domain.Utc
import java.time.Instant
import java.util.UUID

class AnnouncementRepository(private val db: SqlConnection) {
    private val json = Json { ignoreUnknownKeys = false }

    fun installId(): String {
        val existing = pref(PREF_INSTALL_ID)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString().lowercase()
        setPref(PREF_INSTALL_ID, created)
        return created
    }

    fun statsEnabled(): Boolean = pref(PREF_STATS) == "1"

    fun setStatsEnabled(enabled: Boolean) {
        setPref(PREF_STATS, if (enabled) "1" else "0")
        if (!enabled) setPref(PREF_EVENT_QUEUE, "[]")
    }

    fun baseUrl(): String = pref(PREF_BASE_URL).orEmpty()

    fun setBaseUrl(value: String) = setPref(PREF_BASE_URL, value.trim())

    fun publicKeyHex(): String = pref(PREF_PUBLIC_KEY).orEmpty()

    fun setPublicKeyHex(value: String) = setPref(PREF_PUBLIC_KEY, value.trim().lowercase())

    fun publicKeys(): Map<String, ByteArray> {
        val hex = publicKeyHex()
        val bytes = parseHex(hex) ?: return emptyMap()
        val keyId = pref(PREF_KEY_ID) ?: "local-dev-1"
        return mapOf(keyId to bytes, "test-only-1" to bytes)
    }

    fun setKeyId(value: String) = setPref(PREF_KEY_ID, value.trim())

    fun etag(): String? = cacheRow()?.string("etag")?.ifBlank { null }

    fun lastFetchedAt(): String? = cacheRow()?.string("fetched_at")?.ifBlank { null }

    fun lastAttemptAt(): String? = cacheRow()?.string("last_attempt_at")?.ifBlank { null }

    fun markAttempt() {
        val now = Utc.nowIso()
        val row = cacheRow()
        if (row == null) {
            db.execute(
                "INSERT INTO announcement_feed_cache(cache_key,etag,envelope_json,payload_json,feed_version,issued_at,expires_at,fetched_at,last_attempt_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf(CACHE_KEY, "", "", "", 0L, "", "", "", now),
            )
        } else {
            db.execute("UPDATE announcement_feed_cache SET last_attempt_at=? WHERE cache_key=?", listOf(now, CACHE_KEY))
        }
    }

    fun applyEnvelope(envelopeJson: String, etag: String, client: ClientContext, now: Instant = Instant.now()): String? {
        val previous = cacheRow()?.long("feed_version")?.takeIf { it > 0 }
        return when (val result = FeedVerifier.verify(envelopeJson, publicKeys(), client, now, previous)) {
            is FeedVerifyResult.Rejected -> result.reason
            is FeedVerifyResult.Accepted -> {
                db.transaction {
                    db.execute("UPDATE announcement_items SET active=0")
                    result.items.forEach { item ->
                        db.execute(
                            "INSERT OR REPLACE INTO announcement_items(announcement_id,revision,item_json,withdrawn,active) VALUES(?,?,?,?,?)",
                            listOf(item.id, item.revision, json.encodeToString(item), 0, 1),
                        )
                    }
                    result.payload.withdrawn.forEach { ref ->
                        db.execute(
                            "UPDATE announcement_items SET withdrawn=1, active=0 WHERE announcement_id=? AND revision=?",
                            listOf(ref.id, ref.revision),
                        )
                    }
                    db.execute("DELETE FROM announcement_feed_cache WHERE cache_key=?", listOf(CACHE_KEY))
                    db.execute(
                        "INSERT INTO announcement_feed_cache(cache_key,etag,envelope_json,payload_json,feed_version,issued_at,expires_at,fetched_at,last_attempt_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        listOf(
                            CACHE_KEY,
                            etag,
                            envelopeJson,
                            json.encodeToString(result.payload),
                            result.payload.feedVersion,
                            result.payload.issuedAt,
                            result.payload.expiresAt,
                            Utc.nowIso(),
                            Utc.nowIso(),
                        ),
                    )
                }
                null
            }
        }
    }

    fun records(now: Instant = Instant.now()): List<CachedAnnouncement> {
        val expiresAt = cacheRow()?.string("expires_at").orEmpty()
        val expired = expiresAt.isNotBlank() && runCatching { Instant.parse(expiresAt) }.getOrNull()?.isBefore(now) == true
        val states = db.query("SELECT * FROM announcement_state").associate { row ->
            (row.string("announcement_id") to row.long("revision").toInt()) to AnnouncementLocalState(
                readAt = row.string("read_at").ifBlank { null },
                displayedAt = row.string("displayed_at").ifBlank { null },
                dismissedAt = row.string("dismissed_at").ifBlank { null },
                acknowledgedAt = row.string("acknowledged_at").ifBlank { null },
            )
        }
        return db.query("SELECT * FROM announcement_items").map { row ->
            val item = json.decodeFromString<AnnouncementItem>(row.string("item_json"))
            val active = row.long("active") == 1L
            CachedAnnouncement(
                item = item,
                state = states[item.id to item.revision] ?: AnnouncementLocalState(),
                withdrawn = row.long("withdrawn") == 1L,
                signatureExpired = expired || !active,
            )
        }
    }

    fun markRead(id: String, revision: Int) = touch(id, revision, "read_at")

    fun markDisplayed(id: String, revision: Int) = touch(id, revision, "displayed_at")

    fun markDismissed(id: String, revision: Int) = touch(id, revision, "dismissed_at")

    fun markAcknowledged(id: String, revision: Int) {
        touch(id, revision, "acknowledged_at")
        touch(id, revision, "read_at")
    }

    fun markAllRead(records: List<CachedAnnouncement>) {
        records.filter { it.state.readAt == null }.forEach { markRead(it.item.id, it.item.revision) }
    }

    private fun touch(id: String, revision: Int, column: String) {
        val now = Utc.nowIso()
        db.execute(
            "INSERT OR IGNORE INTO announcement_state(announcement_id,revision,read_at,displayed_at,dismissed_at,acknowledged_at) VALUES(?,?,?,?,?,?)",
            listOf(id, revision, null, null, null, null),
        )
        db.execute(
            "UPDATE announcement_state SET $column=COALESCE($column, ?) WHERE announcement_id=? AND revision=?",
            listOf(now, id, revision),
        )
    }

    private fun cacheRow() = db.query("SELECT * FROM announcement_feed_cache WHERE cache_key=?", listOf(CACHE_KEY)).singleOrNull()

    private fun pref(key: String): String? =
        db.query("SELECT value FROM app_prefs WHERE key=?", listOf(key)).firstOrNull()?.string("value")

    private fun setPref(key: String, value: String) {
        db.execute("INSERT OR REPLACE INTO app_prefs(key,value) VALUES(?,?)", listOf(key, value))
    }

    companion object {
        private const val CACHE_KEY = "default"
        private const val PREF_INSTALL_ID = "install_id"
        private const val PREF_STATS = "stats_enabled"
        private const val PREF_BASE_URL = "announce_base_url"
        private const val PREF_PUBLIC_KEY = "announce_public_key_hex"
        private const val PREF_KEY_ID = "announce_key_id"
        private const val PREF_EVENT_QUEUE = "announce_event_queue"

        fun parseHex(hex: String): ByteArray? {
            if (hex.length != 64 || hex.any { it !in HEX }) return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private val HEX = ('0'..'9') + ('a'..'f') + ('A'..'F')
    }
}
