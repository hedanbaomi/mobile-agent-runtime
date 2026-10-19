// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.serialization.worldbook.WorldBookCodec

/** Local user content only. World books do not create tools, grants or provider configuration. */
class WorldBookRepository(private val db: SqlConnection, private val clock: () -> String = { Utc.nowIso() }) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun list(): List<WorldBookSummary> =
        db.query("SELECT * FROM world_books ORDER BY name COLLATE NOCASE,id").map(::summary)

    fun get(id: String, revision: Int? = null): StoredWorldBook? {
        val head = db.query("SELECT * FROM world_books WHERE id=?", listOf(id)).singleOrNull() ?: return null
        val selected = revision ?: head.long("revision").toInt()
        val row = db.query("SELECT * FROM world_book_revisions WHERE book_id=? AND revision=?", listOf(id, selected))
            .singleOrNull() ?: return null
        val raw = row.string("document_json")
        check(hash(raw) == row.string("content_hash")) { "World book revision integrity check failed" }
        val document = WorldBookCodec.decode(raw).document
        return StoredWorldBook(summary(head).copy(revision = selected, name = document.name,
            description = document.description, entryCount = document.entries.size), document,
            row.string("content_hash"), head.long("revocation_epoch"))
    }

    /** Optimistic concurrency is checked inside the same transaction that publishes a revision. */
    fun save(document: WorldBookDocument, id: String = EntityId.random().value,
        expectedRevision: Int = 0, enabled: Boolean = true): StoredWorldBook {
        requireId(id)
        require(document.name.isNotBlank() && document.name.length <= 256) { "World book name is required (maximum 256 characters)" }
        require(document.description.length <= 4096) { "World book description is too long" }
        val raw = WorldBookCodec.encodeNative(document)
        // Validate programmatically constructed values through the same bounded import path.
        val validated = WorldBookCodec.decode(raw).document
        require(validated.settings == document.settings) { "World book safety settings exceed the supported limits" }
        val bytes = raw.toByteArray(Charsets.UTF_8).size.toLong()
        require(bytes <= MAX_REVISION_BYTES) { "World book exceeds the storage limit" }
        return db.transaction {
            val current = db.query("SELECT * FROM world_books WHERE id=?", listOf(id)).singleOrNull()
            check((current?.long("revision")?.toInt() ?: 0) == expectedRevision) { "World book changed; reload before saving" }
            check(expectedRevision < Int.MAX_VALUE) { "World book revision limit reached" }
            if (current == null) check(db.query("SELECT COUNT(*) AS n FROM world_books").single().long("n") < MAX_BOOKS) {
                "World book count limit reached"
            }
            val total = db.query("SELECT COALESCE(SUM(byte_length),0) AS n FROM world_book_revisions").single().long("n")
            check(total <= MAX_STORED_BYTES - bytes) { "World book storage is full; export and remove unused books" }
            val revision = expectedRevision + 1
            val now = clock()
            val previousEpoch = current?.long("revocation_epoch") ?: db.query(
                "SELECT epoch FROM world_book_revocations WHERE book_id=?", listOf(id)).singleOrNull()?.long("epoch") ?: 0L
            check(previousEpoch < Long.MAX_VALUE) { "World book revocation limit reached" }
            val epoch = previousEpoch +
                if (current != null && (current.long("enabled") == 1L) != enabled) 1L else 0L
            if (current == null) {
                db.execute("INSERT INTO world_books(id,name,description,enabled,revision,entry_count,revocation_epoch,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    listOf(id, document.name, document.description, enabled.bit(), revision, document.entries.size, epoch, now, now))
            } else {
                db.execute("UPDATE world_books SET name=?,description=?,enabled=?,revision=?,entry_count=?,revocation_epoch=?,updated_at=? WHERE id=?",
                    listOf(document.name, document.description, enabled.bit(), revision, document.entries.size, epoch, now, id))
            }
            db.execute("INSERT INTO world_book_revisions(book_id,revision,document_json,content_hash,byte_length,created_at) VALUES(?,?,?,?,?,?)",
                listOf(id, revision, raw, hash(raw), bytes, now))
            checkNotNull(get(id))
        }
    }

    fun setEnabled(id: String, enabled: Boolean, expectedRevision: Int): StoredWorldBook = db.transaction {
        val current = checkNotNull(get(id)) { "World book was removed" }
        check(current.summary.revision == expectedRevision) { "World book changed; reload before saving" }
        if (current.summary.enabled == enabled) current else save(current.document, id, expectedRevision, enabled)
    }

    fun duplicate(id: String): StoredWorldBook {
        val current = checkNotNull(get(id)) { "World book was removed" }
        return save(current.document.copy(name = current.document.name.take(240) + " (copy)"), enabled = current.summary.enabled)
    }

    /** Delete content, not transcripts. Immutable snapshot IDs remain historical references. */
    fun delete(id: String, expectedRevision: Int): Boolean = db.transaction {
        val current = get(id) ?: return@transaction false
        check(current.summary.revision == expectedRevision) { "World book changed; reload before deleting" }
        db.query("SELECT id,world_book_ids FROM agent_profiles").forEach { row ->
            val ids = decodeIds(row.string("world_book_ids"))
            if (id in ids) db.execute("UPDATE agent_profiles SET world_book_ids=?,revision=revision+1 WHERE id=?",
                listOf(json.encodeToString(ids - id), row.string("id")))
        }
        // Explicit removal is also safe on legacy test drivers without FK cascades.
        check(current.revocationEpoch < Long.MAX_VALUE) { "World book revocation limit reached" }
        db.execute("INSERT INTO world_book_revocations(book_id,epoch) VALUES(?,?) ON CONFLICT(book_id) DO UPDATE SET epoch=excluded.epoch",
            listOf(id, current.revocationEpoch + 1))
        db.execute("DELETE FROM world_book_revisions WHERE book_id=?", listOf(id))
        db.execute("DELETE FROM world_books WHERE id=?", listOf(id))
        true
    }

    fun initializeConversation(conversationId: String) {
        db.transaction {
            if (db.query("SELECT conversation_id FROM world_book_conversations WHERE conversation_id=?", listOf(conversationId)).isNotEmpty()) return@transaction
            val owner = owner(conversationId)
            db.execute("INSERT INTO world_book_conversations(conversation_id,book_ids_json,revision,settings_json,context_json,updated_at) VALUES(?,?,?,?,?,?)",
                listOf(conversationId, owner.string("world_book_ids").ifBlank { "[]" }, 1,
                    json.encodeToString<WorldBookSettings?>(null), json.encodeToString(WorldBookContext()), clock()))
        }
    }

    fun conversationConfig(conversationId: String): WorldBookConversationConfig {
        val owner = owner(conversationId)
        val row = db.query("SELECT * FROM world_book_conversations WHERE conversation_id=?", listOf(conversationId)).singleOrNull()
            ?: return WorldBookConversationConfig(conversationId, decodeIds(owner.string("world_book_ids")))
        return WorldBookConversationConfig(conversationId, decodeIds(row.string("book_ids_json")), row.long("revision").toInt(),
            json.decodeFromString(row.string("settings_json")), json.decodeFromString(row.string("context_json")))
    }

    fun saveConversationConfig(config: WorldBookConversationConfig, expectedRevision: Int): WorldBookConversationConfig =
        writeConversationConfig(config, expectedRevision, false)

    internal fun restoreConversationConfig(config: WorldBookConversationConfig, expectedRevision: Int): WorldBookConversationConfig =
        writeConversationConfig(config, expectedRevision, true)

    private fun writeConversationConfig(config: WorldBookConversationConfig, expectedRevision: Int, restoring: Boolean): WorldBookConversationConfig = db.transaction {
        val owner = owner(config.conversationId)
        check(restoring || owner.long("archived") == 0L) { "Archived conversations are read-only" }
        val current = conversationConfig(config.conversationId)
        check(current.revision == expectedRevision && expectedRevision < Int.MAX_VALUE) { "World book binding changed; reload before saving" }
        validateIds(config.bookIds)
        config.bookIds.forEach { check(get(it) != null) { "Selected world book was removed" } }
        val settings = json.encodeToString(config.settings)
        val context = json.encodeToString(config.context)
        require(settings.length <= MAX_CONFIG_CHARS && context.length <= MAX_CONFIG_CHARS) { "World book context is too large" }
        val revision = expectedRevision + 1
        db.execute("INSERT INTO world_book_conversations(conversation_id,book_ids_json,revision,settings_json,context_json,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(conversation_id) DO UPDATE SET book_ids_json=excluded.book_ids_json,revision=excluded.revision,settings_json=excluded.settings_json,context_json=excluded.context_json,updated_at=excluded.updated_at",
            listOf(config.conversationId, json.encodeToString(config.bookIds), revision, settings, context, clock()))
        db.execute("DELETE FROM world_book_effects WHERE conversation_id=?", listOf(config.conversationId))
        config.copy(revision = revision)
    }

    /** Selection and revision pins are captured together, never re-read after prompt assembly. */
    fun prepare(conversationId: String, agentId: String, snapshotId: String): PreparedWorldBooks = db.transaction {
        val owner = owner(conversationId)
        check(owner.string("agent_id") == agentId && owner.string("snapshot_id") == snapshotId) { "World book conversation ownership mismatch" }
        check(owner.long("archived") == 0L) { "Archived conversations are read-only" }
        val config = conversationConfig(conversationId)
        validateIds(config.bookIds)
        val sources = mutableListOf<WorldBookSource>()
        val pins = mutableListOf<WorldBookRunPin>()
        val unavailable = mutableListOf<WorldBookAvailability>()
        config.bookIds.forEach { id ->
            val stored = get(id)
            when {
                stored == null -> unavailable += WorldBookAvailability(id, id, "REMOVED")
                !stored.summary.enabled -> unavailable += WorldBookAvailability(id, stored.summary.name, "DISABLED")
                else -> {
                    sources += WorldBookSource(id, stored.summary.revision, stored.document)
                    pins += WorldBookRunPin(id, stored.summary.revision, stored.revocationEpoch)
                }
            }
        }
        val settings = config.settings ?: sources.firstOrNull()?.document?.settings ?: WorldBookSettings()
        PreparedWorldBooks(conversationId, agentId, snapshotId, config.revision, sources, pins, settings, config.context, unavailable)
    }

    fun previousEffects(conversationId: String, bindingRevision: Int): String =
        db.query("SELECT effects_json FROM world_book_effects WHERE conversation_id=? AND binding_revision=?",
            listOf(conversationId, bindingRevision)).singleOrNull()?.string("effects_json") ?: "[]"

    /** Body-free evaluation JSON is a local inspector record, never a diagnostic event. */
    fun recordEvaluation(runId: String, prepared: PreparedWorldBooks, evaluationJson: String,
        nextEffectsJson: String, messageCount: Int, budgetUnits: Int, seed: Long) = db.transaction {
        require(messageCount >= 0 && budgetUnits >= 0)
        require(evaluationJson.length <= MAX_RECEIPT_CHARS && json.parseToJsonElement(evaluationJson) is JsonObject)
        require(nextEffectsJson.length <= MAX_RECEIPT_CHARS && json.parseToJsonElement(nextEffectsJson) is JsonArray)
        val run = db.query("SELECT conversation_id,snapshot_id FROM runs WHERE run_id=?", listOf(runId)).singleOrNull()
        check(run?.string("conversation_id") == prepared.conversationId && run.string("snapshot_id") == prepared.snapshotId) { "World book run ownership mismatch" }
        validatePrepared(prepared)
        db.execute("INSERT INTO world_book_runs(run_id,conversation_id,snapshot_id,agent_id,binding_revision,pins_json,evaluation_json,next_effects_json,message_count,budget_units,seed,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(runId, prepared.conversationId, prepared.snapshotId, prepared.agentId, prepared.bindingRevision,
                json.encodeToString(prepared.pins), evaluationJson, nextEffectsJson, messageCount, budgetUnits, seed, clock()))
    }

    /** Called before every model dispatch, including tool continuation and compaction. */
    fun beforeDispatch(runId: String) = db.transaction {
        val receipt = db.query("SELECT * FROM world_book_runs WHERE run_id=?", listOf(runId)).singleOrNull() ?: return@transaction
        val owner = owner(receipt.string("conversation_id"))
        check(owner.string("agent_id") == receipt.string("agent_id") && owner.string("snapshot_id") == receipt.string("snapshot_id")) {
            "PERMISSION_DENIED: world book run ownership changed"
        }
        val config = conversationConfig(receipt.string("conversation_id"))
        check(config.revision == receipt.long("binding_revision").toInt()) { "PERMISSION_DENIED: world book binding changed; send again" }
        validatePins(json.decodeFromString(receipt.string("pins_json")))
        if (receipt.long("dispatched") == 0L) {
            db.execute("INSERT INTO world_book_effects(conversation_id,binding_revision,effects_json,message_count) VALUES(?,?,?,?) ON CONFLICT(conversation_id) DO UPDATE SET binding_revision=excluded.binding_revision,effects_json=excluded.effects_json,message_count=excluded.message_count",
                listOf(config.conversationId, config.revision, receipt.string("next_effects_json"), receipt.long("message_count")))
            db.execute("UPDATE world_book_runs SET dispatched=1 WHERE run_id=?", listOf(runId))
        }
    }

    fun latestReceipt(conversationId: String): WorldBookRunReceipt? =
        db.query("SELECT run_id,conversation_id,evaluation_json,budget_units,created_at FROM world_book_runs WHERE conversation_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1", listOf(conversationId))
            .singleOrNull()?.let { WorldBookRunReceipt(it.string("run_id"), conversationId, it.string("evaluation_json"), it.long("budget_units").toInt(), it.string("created_at")) }

    private fun validatePrepared(prepared: PreparedWorldBooks) {
        val owner = owner(prepared.conversationId)
        check(owner.string("agent_id") == prepared.agentId && owner.string("snapshot_id") == prepared.snapshotId) { "World book ownership changed" }
        check(conversationConfig(prepared.conversationId).revision == prepared.bindingRevision) { "World book binding changed" }
        validatePins(prepared.pins)
    }

    private fun validatePins(pins: List<WorldBookRunPin>) {
        pins.forEach { pin ->
            val head = db.query("SELECT enabled,revocation_epoch FROM world_books WHERE id=?", listOf(pin.bookId)).singleOrNull()
            check(head?.long("enabled") == 1L && head.long("revocation_epoch") == pin.revocationEpoch) {
                "PERMISSION_DENIED: world book was disabled or removed; send again"
            }
            check(db.query("SELECT revision FROM world_book_revisions WHERE book_id=? AND revision=?", listOf(pin.bookId, pin.revision)).isNotEmpty()) {
                "PERMISSION_DENIED: world book revision was removed"
            }
        }
    }

    private fun owner(conversationId: String): SqlRow =
        db.query("SELECT c.snapshot_id,c.archived,s.agent_id,s.world_book_ids FROM conversations c JOIN agent_snapshots s ON s.id=c.snapshot_id WHERE c.id=?", listOf(conversationId))
            .singleOrNull() ?: error("World book conversation is unavailable")

    private fun decodeIds(raw: String): List<String> = json.decodeFromString<List<String>>(raw.ifBlank { "[]" }).also(::validateIds)
    private fun validateIds(ids: List<String>) {
        require(ids.size <= MAX_BINDINGS && ids.distinct().size == ids.size) { "Too many or duplicate world book bindings" }
        ids.forEach(::requireId)
    }
    private fun requireId(id: String) { require(SAFE_ID.matches(id)) { "Invalid world book identifier" } }
    private fun Boolean.bit() = if (this) 1 else 0
    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun summary(row: SqlRow) = WorldBookSummary(row.string("id"), row.string("name"), row.string("description"),
        row.long("enabled") == 1L, row.long("revision").toInt(), row.long("entry_count").toInt(), row.string("created_at"), row.string("updated_at"))

    companion object {
        const val MAX_BINDINGS = 64
        private const val MAX_BOOKS = 512
        private const val MAX_REVISION_BYTES = 16L * 1024 * 1024
        private const val MAX_STORED_BYTES = 128L * 1024 * 1024
        private const val MAX_CONFIG_CHARS = 256_000
        private const val MAX_RECEIPT_CHARS = 1_000_000
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    }
}
