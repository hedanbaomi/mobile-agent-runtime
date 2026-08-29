// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.announcements.AnnouncementItem
import runtime.mobileagent.announcements.AnnouncementLocalState
import runtime.mobileagent.announcements.CachedAnnouncement
import runtime.mobileagent.announcements.ClientContext
import runtime.mobileagent.announcements.FeedPayload
import runtime.mobileagent.announcements.FeedVerifier
import runtime.mobileagent.announcements.FeedVerifyResult
import runtime.mobileagent.domain.Utc
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A bounded, content-free event batch ready for the announcement endpoint. */
data class AnnouncementTelemetryBatch(
    val json: String,
    val eventIds: Set<String>,
)

/**
 * SQLite-backed announcement state.
 *
 * The feed rollout identity is deliberately independent from the optional telemetry identity.
 * Feed cache rows remain useful while telemetry is disabled, and disabling telemetry removes
 * the telemetry identity and queued events without touching the feed identity or cache.
 */
class AnnouncementRepository(private val db: SqlConnection) {
    private val json = Json { ignoreUnknownKeys = false }
    private val stateLock = Any()
    @Volatile private var statsChangeListener: ((Boolean) -> Unit)? = null

    fun installId(): String = synchronized(stateLock) {
        pref(PREF_INSTALL_ID)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().lowercase().also {
            setPref(PREF_INSTALL_ID, it)
        }
    }

    fun statsEnabled(): Boolean = synchronized(stateLock) { statsEnabledUnsafe() }

    /**
     * Return the identity used only in opted-in event payloads. It is never created by a feed
     * read, so a disabled/default installation cannot accidentally acquire a telemetry identity.
     */
    fun telemetryIdentity(): String? = synchronized(stateLock) {
        if (!statsEnabledUnsafe()) null else pref(PREF_TELEMETRY_ID)?.ifBlank { null }
    }

    fun setStatsEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            db.transaction {
                if (enabled) {
                    val wasEnabled = statsEnabledUnsafe()
                    setPref(PREF_STATS, "1")
                    if (!wasEnabled || pref(PREF_TELEMETRY_ID).isNullOrBlank()) {
                        setPref(PREF_TELEMETRY_ID, UUID.randomUUID().toString().lowercase())
                        deletePref(PREF_INSTALL_SEEN_ID)
                        deletePref(PREF_ACTIVE_ID)
                        deletePref(PREF_ACTIVE_VERSION)
                        deletePref(PREF_ACTIVE_AT)
                    }
                } else {
                    setPref(PREF_STATS, "0")
                    // A consent withdrawal is a hard boundary: nothing queued can be uploaded later.
                    setPref(PREF_EVENT_QUEUE, "[]")
                    deletePref(PREF_TELEMETRY_ID)
                    deletePref(PREF_INSTALL_SEEN_ID)
                    deletePref(PREF_ACTIVE_ID)
                    deletePref(PREF_ACTIVE_VERSION)
                    deletePref(PREF_ACTIVE_AT)
                }
            }
        }
        // Notify outside the DB lock so a coordinator can cancel an upload without creating a
        // repository/listener lock inversion. This also covers settings code that calls the
        // repository directly instead of going through the ViewModel.
        statsChangeListener?.invoke(enabled)
    }

    /** The process coordinator uses this to stop an in-flight telemetry job on withdrawal. */
    fun setStatsChangeListener(listener: ((Boolean) -> Unit)?) {
        statsChangeListener = listener
    }

    fun baseUrl(): String = synchronized(stateLock) { pref(PREF_BASE_URL).orEmpty() }

    fun setBaseUrl(value: String) = synchronized(stateLock) { setPref(PREF_BASE_URL, value.trim()) }

    fun publicKeyHex(): String = synchronized(stateLock) { pref(PREF_PUBLIC_KEY).orEmpty() }

    fun setPublicKeyHex(value: String) = synchronized(stateLock) {
        setPref(PREF_PUBLIC_KEY, value.trim().lowercase())
    }

    fun publicKeys(): Map<String, ByteArray> = synchronized(stateLock) { publicKeysUnsafe() }

    fun setKeyId(value: String) = synchronized(stateLock) { setPref(PREF_KEY_ID, value.trim()) }

    fun etag(client: ClientContext): String? = synchronized(stateLock) {
        cacheRow(cacheKey(client))?.string("etag")?.ifBlank { null }
    }

    fun lastFetchedAt(): String? = synchronized(stateLock) {
        cacheRow(pref(PREF_CACHE_CONTEXT) ?: CACHE_KEY)?.string("fetched_at")?.ifBlank { null }
    }

    fun lastFetchedAt(client: ClientContext): String? = synchronized(stateLock) {
        cacheRow(cacheKey(client))?.string("fetched_at")?.ifBlank { null }
    }

    fun lastAttemptAt(client: ClientContext): String? = synchronized(stateLock) {
        cacheRow(cacheKey(client))?.string("last_attempt_at")?.ifBlank { null }
    }

    /** Ordinary automatic checks are based on the last successful fetch, never last attempt. */
    fun needsFetch(client: ClientContext, now: Instant = Instant.now()): Boolean =
        shouldFetch(client, now, force = false, foreground = false, failureBackoff = Duration.ZERO)

    /**
     * Decide whether a coordinator request may hit the network. Foreground requests bypass the
     * six-hour success throttle but still respect a short failure backoff. Explicit force requests
     * bypass both success throttling and that backoff.
     */
    fun shouldFetch(
        client: ClientContext,
        now: Instant = Instant.now(),
        force: Boolean = false,
        foreground: Boolean = false,
        failureBackoff: Duration = DEFAULT_FAILURE_BACKOFF,
    ): Boolean = synchronized(stateLock) {
        val key = cacheKey(client)
        val contextChanged = key != pref(PREF_CACHE_CONTEXT)
        val appVersionChanged = client.versionCode.toString() != pref(PREF_CACHE_APP_VERSION)
        if (contextChanged || appVersionChanged) {
            if (!force && failureBackoffActiveUnsafe(key, now, failureBackoff)) return@synchronized false
            return@synchronized true
        }
        if (!force && !foreground) {
            val fetched = cacheRow(key)?.string("fetched_at")?.let(::parseInstant)
            if (fetched != null && !elapsedAtLeast(fetched, now, SUCCESS_THROTTLE)) return@synchronized false
        }
        if (!force && failureBackoffActiveUnsafe(key, now, failureBackoff)) return@synchronized false
        return true
    }

    fun failureAt(client: ClientContext): String? = synchronized(stateLock) {
        pref(failurePrefKey(cacheKey(client)))
    }

    fun markAttempt(client: ClientContext, now: Instant = Instant.now()) = synchronized(stateLock) {
        val timestamp = now.toString()
        val key = cacheKey(client)
        val row = cacheRow(key)
        if (row == null) {
            db.execute(
                "INSERT INTO announcement_feed_cache(cache_key,etag,envelope_json,payload_json,feed_version,issued_at,expires_at,fetched_at,last_attempt_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf(key, "", "", "", 0L, "", "", "", timestamp),
            )
        } else {
            db.execute("UPDATE announcement_feed_cache SET last_attempt_at=? WHERE cache_key=?", listOf(timestamp, key))
        }
    }

    fun recordFetchFailure(client: ClientContext, now: Instant = Instant.now(), reason: String = "fetch failed") =
        synchronized(stateLock) {
            // Keep only a timestamp. The feed body, URL and exception/server response never enter
            // persistent announcement state; `reason` is intentionally diagnostic-only at the API.
            setPref(failurePrefKey(cacheKey(client)), now.toString())
        }

    fun recordFetchSuccess(client: ClientContext, now: Instant = Instant.now()) = synchronized(stateLock) {
        val key = cacheKey(client)
        val row = cacheRow(key)
        if (row != null && row.string("envelope_json").isNotBlank()) {
            db.execute("UPDATE announcement_feed_cache SET fetched_at=? WHERE cache_key=?", listOf(now.toString(), key))
        }
        clearFetchFailureUnsafe(key)
    }

    fun hasCachedFeed(client: ClientContext, now: Instant = Instant.now()): Boolean = synchronized(stateLock) {
        val row = cacheRow(cacheKey(client)) ?: return@synchronized false
        if (row.string("envelope_json").isBlank()) return@synchronized false
        val expiresAt = row.string("expires_at").takeIf { it.isNotBlank() }?.let(::parseInstant)
        expiresAt != null && !now.isAfter(expiresAt)
    }

    fun applyEnvelope(
        envelopeJson: String,
        etag: String,
        client: ClientContext,
        now: Instant = Instant.now(),
    ): String? = synchronized(stateLock) {
        val previous = cacheRow(cacheKey(client))?.long("feed_version")?.takeIf { it > 0 }
        when (val result = FeedVerifier.verify(envelopeJson, publicKeysUnsafe(), client, now, previous)) {
            is FeedVerifyResult.Rejected -> result.reason
            is FeedVerifyResult.Accepted -> {
                val key = cacheKey(client)
                val sanitizedPayload = result.payload.copy(items = result.items)
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
                    db.execute("DELETE FROM announcement_feed_cache WHERE cache_key=?", listOf(key))
                    db.execute(
                        "INSERT INTO announcement_feed_cache(cache_key,etag,envelope_json,payload_json,feed_version,issued_at,expires_at,fetched_at,last_attempt_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        listOf(
                            key,
                            etag,
                            envelopeJson,
                            json.encodeToString(sanitizedPayload),
                            result.payload.feedVersion,
                            result.payload.issuedAt,
                            result.payload.expiresAt,
                            now.toString(),
                            now.toString(),
                        ),
                    )
                    setPref(PREF_CACHE_CONTEXT, key)
                    setPref(PREF_CACHE_APP_VERSION, client.versionCode.toString())
                    clearFetchFailureUnsafe(key)
                }
                null
            }
        }
    }

    fun records(now: Instant = Instant.now(), client: ClientContext? = null): List<CachedAnnouncement> =
        synchronized(stateLock) {
            val key = client?.let(::cacheKey) ?: pref(PREF_CACHE_CONTEXT) ?: CACHE_KEY
            val cache = cacheRow(key)
            val expiresAt = cache?.string("expires_at").orEmpty()
            val expired = expiresAt.isNotBlank() && parseInstant(expiresAt)?.isBefore(now) == true
            val contextStale = client != null && pref(PREF_CACHE_CONTEXT) != cacheKey(client)
            val states = db.query("SELECT * FROM announcement_state").associate { row ->
                (row.string("announcement_id") to row.long("revision").toInt()) to AnnouncementLocalState(
                    readAt = row.string("read_at").ifBlank { null },
                    displayedAt = row.string("displayed_at").ifBlank { null },
                    dismissedAt = row.string("dismissed_at").ifBlank { null },
                    acknowledgedAt = row.string("acknowledged_at").ifBlank { null },
                )
            }
            val global = db.query("SELECT * FROM announcement_items").mapNotNull { row ->
                runCatching { json.decodeFromString<AnnouncementItem>(row.string("item_json")) }
                    .getOrNull()
                    ?.let { item -> item to row }
            }
            val globalByKey = global.associateBy { it.first.id to it.first.revision }
            val selected: List<Pair<AnnouncementItem, SqlRow>> = if (client == null) {
                global
            } else {
                val payload = cache?.string("payload_json")?.takeIf { it.isNotBlank() }?.let {
                    runCatching { json.decodeFromString<FeedPayload>(it) }.getOrNull()
                }
                if (payload == null) {
                    emptyList()
                } else {
                    payload.items.map { it to (globalByKey[it.id to it.revision]?.second ?: syntheticItemRow(it)) } +
                        payload.withdrawn.mapNotNull { ref -> globalByKey[ref.id to ref.revision] }
                }
            }
            selected.mapNotNull { (item, row) ->
                if (client != null && !runtime.mobileagent.announcements.Targeting.matches(item.target.toTarget(), client, item.id)) {
                    return@mapNotNull null
                }
                val active = row.long("active") == 1L
                CachedAnnouncement(
                    item = item,
                    state = states[item.id to item.revision] ?: AnnouncementLocalState(),
                    withdrawn = row.long("withdrawn") == 1L,
                    signatureExpired = expired || !active || contextStale,
                )
            }
        }

    fun markRead(id: String, revision: Int): Boolean = synchronized(stateLock) { touch(id, revision, "read_at") }

    fun markDisplayed(id: String, revision: Int): Boolean = synchronized(stateLock) { touch(id, revision, "displayed_at") }

    fun markDismissed(id: String, revision: Int): Boolean = synchronized(stateLock) { touch(id, revision, "dismissed_at") }

    fun markAcknowledged(id: String, revision: Int): Boolean = synchronized(stateLock) {
        val changed = touch(id, revision, "acknowledged_at")
        touch(id, revision, "read_at")
        changed
    }

    fun markAllRead(records: List<CachedAnnouncement>) = synchronized(stateLock) {
        records.filter { it.state.readAt == null }.forEach { touch(it.item.id, it.item.revision, "read_at") }
    }

    /** Queue one of the fixed announcement telemetry event types, with no free-form fields. */
    fun recordAnnouncementEvent(
        type: String,
        client: ClientContext,
        announcementId: String? = null,
        revision: Int? = null,
        actionId: String? = null,
        now: Instant = Instant.now(),
    ): Boolean = synchronized(stateLock) {
        queueEventUnsafe(type, client, announcementId, revision, actionId, now)
    }

    fun recordInstallSeen(client: ClientContext, now: Instant = Instant.now()): Boolean = synchronized(stateLock) {
        val identity = telemetryIdentityUnsafe() ?: return@synchronized false
        if (pref(PREF_INSTALL_SEEN_ID) == identity) return@synchronized false
        queueEventUnsafe("install_seen", client, now = now).also { queued ->
            if (queued) setPref(PREF_INSTALL_SEEN_ID, identity)
        }
    }

    fun recordAppActive(client: ClientContext, now: Instant = Instant.now()): Boolean = synchronized(stateLock) {
        val identity = telemetryIdentityUnsafe() ?: return@synchronized false
        val previousIdentity = pref(PREF_ACTIVE_ID)
        val previousVersion = pref(PREF_ACTIVE_VERSION)?.toIntOrNull()
        val previousAt = pref(PREF_ACTIVE_AT)?.let(::parseInstant)
        val versionChanged = previousVersion != client.versionCode
        val due = previousIdentity != identity || versionChanged || previousAt == null ||
            elapsedAtLeast(previousAt, now, ACTIVE_DEDUP_WINDOW)
        if (!due) return@synchronized false
        queueEventUnsafe("app_active", client, now = now).also { queued ->
            if (queued) {
                setPref(PREF_ACTIVE_ID, identity)
                setPref(PREF_ACTIVE_VERSION, client.versionCode.toString())
                setPref(PREF_ACTIVE_AT, now.toString())
            }
        }
    }

    fun pendingTelemetryJson(): String? = pendingTelemetryBatch()?.json

    fun pendingTelemetryBatch(): AnnouncementTelemetryBatch? = synchronized(stateLock) {
        if (!statsEnabledUnsafe() || telemetryIdentityUnsafe() == null) return@synchronized null
        val events = telemetryEventsUnsafe()
        if (events.isEmpty()) return@synchronized null
        val selected = events.take(MAX_EVENT_BATCH)
        AnnouncementTelemetryBatch(
            json = buildJsonObject { put("events", JsonArray(selected)) }.toString(),
            eventIds = selected.mapNotNull { it["eventId"]?.jsonPrimitive?.contentOrNull }.toSet(),
        )
    }

    /** Remove only events acknowledged by a successful HTTP response. */
    fun acknowledgeTelemetry(eventIds: Set<String>) = synchronized(stateLock) {
        if (eventIds.isEmpty()) return@synchronized
        val keep = telemetryEventsUnsafe().filterNot { it["eventId"]?.jsonPrimitive?.contentOrNull in eventIds }
        setPref(PREF_EVENT_QUEUE, JsonArray(keep).toString())
    }

    private fun touch(id: String, revision: Int, column: String): Boolean {
        val existing = db.query(
            "SELECT $column FROM announcement_state WHERE announcement_id=? AND revision=?",
            listOf(id, revision),
        ).singleOrNull()?.string(column).orEmpty()
        db.execute(
            "INSERT OR IGNORE INTO announcement_state(announcement_id,revision,read_at,displayed_at,dismissed_at,acknowledged_at) VALUES(?,?,?,?,?,?)",
            listOf(id, revision, null, null, null, null),
        )
        if (existing.isNotBlank()) return false
        db.execute(
            "UPDATE announcement_state SET $column=COALESCE($column, ?) WHERE announcement_id=? AND revision=?",
            listOf(Utc.nowIso(), id, revision),
        )
        return true
    }

    private fun queueEventUnsafe(
        type: String,
        client: ClientContext,
        announcementId: String? = null,
        revision: Int? = null,
        actionId: String? = null,
        now: Instant,
    ): Boolean {
        if (!statsEnabledUnsafe() || telemetryIdentityUnsafe() == null || type !in EVENT_TYPES) return false
        if (announcementId == null && revision != null) return false
        if (announcementId != null && !TOKEN.matches(announcementId)) return false
        if (revision != null && revision < 1) return false
        if (type == "action_clicked" && !ACTION_TOKEN.matches(actionId.orEmpty())) return false
        if (actionId != null && type != "action_clicked") return false
        val event = buildJsonObject {
            put("eventId", UUID.randomUUID().toString().lowercase())
            put("type", type)
            put("installId", telemetryIdentityUnsafe()!!)
            put("platform", client.platform)
            put("channel", client.channel)
            put("versionCode", client.versionCode)
            put("locale", client.locale)
            if (announcementId != null) put("announcementId", announcementId)
            if (revision != null) put("revision", revision)
            if (actionId != null) put("actionId", actionId)
            put("occurredAt", now.toString())
        }
        val events = telemetryEventsUnsafe().toMutableList()
        events += event
        while (events.size > MAX_EVENT_BATCH || JsonArray(events).toString().toByteArray(Charsets.UTF_8).size > MAX_EVENT_QUEUE_BYTES) {
            if (events.isEmpty()) return false
            events.removeAt(0)
        }
        if (event !in events) return false
        setPref(PREF_EVENT_QUEUE, JsonArray(events).toString())
        return true
    }

    private fun telemetryEventsUnsafe(): List<JsonObject> {
        val raw = pref(PREF_EVENT_QUEUE).orEmpty()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        val identity = telemetryIdentityUnsafe() ?: return emptyList()
        return root.mapNotNull { it as? JsonObject }.filter { event ->
            val type = event["type"]?.jsonPrimitive?.contentOrNull
            val announcementId = event["announcementId"]?.jsonPrimitive?.contentOrNull
            val actionId = event["actionId"]?.jsonPrimitive?.contentOrNull
            event.keys.all { it in EVENT_FIELDS } &&
                event["eventId"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
                type in EVENT_TYPES &&
                event["installId"]?.jsonPrimitive?.contentOrNull == identity &&
                event["platform"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
                event["channel"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
                event["versionCode"]?.jsonPrimitive?.intOrNull != null &&
                event["locale"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
                event["occurredAt"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
                (announcementId == null || TOKEN.matches(announcementId)) &&
                (type != "action_clicked" || ACTION_TOKEN.matches(actionId.orEmpty())) &&
                (type == "action_clicked" || actionId == null)
        }
    }

    private fun statsEnabledUnsafe(): Boolean = pref(PREF_STATS) == "1"

    private fun telemetryIdentityUnsafe(): String? = if (statsEnabledUnsafe()) pref(PREF_TELEMETRY_ID)?.ifBlank { null } else null

    private fun publicKeysUnsafe(): Map<String, ByteArray> {
        val bytes = parseHex(pref(PREF_PUBLIC_KEY).orEmpty()) ?: return emptyMap()
        val keyId = pref(PREF_KEY_ID) ?: "local-dev-1"
        return mapOf(keyId to bytes, "test-only-1" to bytes)
    }

    private fun failureBackoffActiveUnsafe(key: String, now: Instant, backoff: Duration): Boolean {
        if (backoff.isZero || backoff.isNegative) return false
        val failure = pref(failurePrefKey(key))?.let(::parseInstant) ?: return false
        return !failure.isAfter(now) && !elapsedAtLeast(failure, now, backoff)
    }

    private fun clearFetchFailureUnsafe(key: String) {
        deletePref(failurePrefKey(key))
    }

    private fun cacheRow(key: String) =
        db.query("SELECT * FROM announcement_feed_cache WHERE cache_key=?", listOf(key)).singleOrNull()

    private fun pref(key: String): String? =
        db.query("SELECT value FROM app_prefs WHERE key=?", listOf(key)).firstOrNull()?.string("value")

    private fun setPref(key: String, value: String) {
        db.execute("INSERT OR REPLACE INTO app_prefs(key,value) VALUES(?,?)", listOf(key, value))
    }

    private fun deletePref(key: String) {
        db.execute("DELETE FROM app_prefs WHERE key=?", listOf(key))
    }

    private fun syntheticItemRow(item: AnnouncementItem): SqlRow = SqlRow(
        mapOf("active" to 1L, "withdrawn" to 0L, "item_json" to json.encodeToString(item)),
    )

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private fun elapsedAtLeast(start: Instant, now: Instant, duration: Duration): Boolean =
        !now.isBefore(start) && now.toEpochMilli() - start.toEpochMilli() >= duration.toMillis()

    companion object {
        private const val CACHE_KEY = "default"
        private const val PREF_INSTALL_ID = "install_id"
        private const val PREF_STATS = "stats_enabled"
        private const val PREF_TELEMETRY_ID = "announcement_telemetry_id"
        private const val PREF_INSTALL_SEEN_ID = "announcement_install_seen_identity"
        private const val PREF_ACTIVE_ID = "announcement_active_identity"
        private const val PREF_ACTIVE_VERSION = "announcement_active_version"
        private const val PREF_ACTIVE_AT = "announcement_active_at"
        private const val PREF_BASE_URL = "announce_base_url"
        private const val PREF_PUBLIC_KEY = "announce_public_key_hex"
        private const val PREF_KEY_ID = "announce_key_id"
        private const val PREF_EVENT_QUEUE = "announce_event_queue"
        private const val PREF_CACHE_CONTEXT = "announce_cache_context"
        private const val PREF_CACHE_APP_VERSION = "announce_cache_app_version"
        private const val PREF_FAILURE_PREFIX = "announce_failure_at:"
        private const val MAX_EVENT_BATCH = 50
        private const val MAX_EVENT_QUEUE_BYTES = 64 * 1024
        private val SUCCESS_THROTTLE: Duration = Duration.ofHours(6)
        private val ACTIVE_DEDUP_WINDOW: Duration = Duration.ofHours(6)
        val DEFAULT_FAILURE_BACKOFF: Duration = Duration.ofSeconds(60)
        private val EVENT_TYPES = setOf(
            "install_seen",
            "app_active",
            "announcement_fetched",
            "announcement_displayed",
            "announcement_opened",
            "announcement_acknowledged",
            "action_clicked",
        )
        private val EVENT_FIELDS = setOf(
            "eventId", "type", "installId", "platform", "channel", "versionCode", "locale",
            "announcementId", "revision", "actionId", "occurredAt",
        )

        fun cacheKey(client: ClientContext): String =
            listOf(client.platform, client.channel, client.versionCode.toString(), client.locale, client.installId).joinToString("|")

        fun parseHex(hex: String): ByteArray? {
            if (hex.length != 64 || hex.any { it !in HEX }) return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun failurePrefKey(key: String): String = PREF_FAILURE_PREFIX + key

        private val HEX = ('0'..'9') + ('a'..'f') + ('A'..'F')
        private val TOKEN = Regex("[A-Za-z0-9._-]{1,128}")
        private val ACTION_TOKEN = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
