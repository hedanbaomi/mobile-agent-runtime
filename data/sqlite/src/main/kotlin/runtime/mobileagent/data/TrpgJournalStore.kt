// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.trpg.TrpgVisibility

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import runtime.mobileagent.domain.trpg.*

/** All writes run inside the owning state transaction. History contains no executable receipts. */
internal class TrpgJournalStore(private val db: SqlConnection, private val clock: () -> String) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val stateHashJson = Json { encodeDefaults = false; explicitNulls = false }

    fun replay(conversationId: String, actionId: String, requestHash: String): Boolean {
        require(actionId.isNotBlank() && actionId.length <= 256)
        val row = db.query("SELECT request_hash FROM trpg_events WHERE conversation_id=? AND action_id=?", listOf(conversationId, actionId)).singleOrNull() ?: return false
        require(row.string("request_hash") == requestHash) { "TRPG action arguments changed" }
        return true
    }

    fun append(before: TrpgSession, after: TrpgSession, kind: TrpgEventKind, actionId: String,
        requestHash: String, title: String, detail: String = "", runId: String? = null, targetId: String? = null, visibility: TrpgVisibility = TrpgVisibility.PLAYER) {
        val oldJson = json.parseToJsonElement(json.encodeToString(before.state)).jsonObject
        val newJson = json.parseToJsonElement(json.encodeToString(after.state)).jsonObject
        val oldResources = before.state.resources.associateBy { it.name }
        val newResources = after.state.resources.associateBy { it.name }
        val beforeActors = before.state.campaign.party.actors.associateBy { it.id }
        val afterActors = after.state.campaign.party.actors.associateBy { it.id }
        val actorChanges = (beforeActors.keys + afterActors.keys).flatMap { id ->
            val oldActor = beforeActors[id]
            val newActor = afterActors[id]
            val oldStats = oldActor?.resources.orEmpty().associateBy { it.name }
            val newStats = newActor?.resources.orEmpty().associateBy { it.name }
            val private = oldActor?.visibility == TrpgVisibility.GAME_MASTER || newActor?.visibility == TrpgVisibility.GAME_MASTER
            (oldStats.keys + newStats.keys).mapNotNull { name ->
                val old = oldStats[name]?.current
                val new = newStats[name]?.current
                if (old == new) null else TrpgResourceChange(name, old, new, id, newActor?.name ?: oldActor?.name,
                    if (private) TrpgVisibility.GAME_MASTER else TrpgVisibility.PLAYER)
            }
        }
        val event = TrpgEvent(UUID.randomUUID().toString(), after.revision, kind, title, detail, clock(), runId, targetId,
            (oldJson.keys + newJson.keys).filter { oldJson[it] != newJson[it] },
            (oldResources.keys + newResources.keys).mapNotNull { name ->
                val old = oldResources[name]?.current
                val new = newResources[name]?.current
                if (old == new) null else TrpgResourceChange(name, old, new)
            } + actorChanges, visibility)
        TrpgJournalValidation.validate(TrpgJournal(listOf(event)), after.revision)
        db.execute("INSERT INTO trpg_events(conversation_id,event_id,revision,action_id,request_hash,event_json,run_id,kind) VALUES(?,?,?,?,?,?,?,?)",
            listOf(after.conversationId, event.id, after.revision, actionId, requestHash, json.encodeToString(event), runId, kind.name))
        if (kind == TrpgEventKind.UNDO || before.state != after.state) {
            db.execute("DELETE FROM trpg_undo WHERE conversation_id=?", listOf(after.conversationId))
            if (kind != TrpgEventKind.UNDO && kind != TrpgEventKind.ROLL && before.state != after.state) {
                val undo = TrpgUndo(event.id, before.state, hash(stateHashJson.encodeToString(after.state)))
                db.execute("INSERT INTO trpg_undo(conversation_id,undo_json) VALUES(?,?)", listOf(after.conversationId, json.encodeToString(undo)))
            }
        }
    }

    fun read(conversationId: String): TrpgJournal = TrpgJournal(
        db.query("SELECT event_json FROM trpg_events WHERE conversation_id=? ORDER BY revision", listOf(conversationId)).map { json.decodeFromString<TrpgEvent>(it.string("event_json")) },
        undo(conversationId),
    )

    fun recent(conversationId: String, limit: Int, beforeRevision: Long): List<TrpgEvent> =
        db.query("SELECT event_json FROM trpg_events WHERE conversation_id=? AND revision<? ORDER BY revision DESC LIMIT ?", listOf(conversationId, beforeRevision, limit.coerceIn(1, 100)))
            .map { json.decodeFromString<TrpgEvent>(it.string("event_json")) }

    fun undo(conversationId: String): TrpgUndo? = db.query("SELECT undo_json FROM trpg_undo WHERE conversation_id=?", listOf(conversationId))
        .singleOrNull()?.let { json.decodeFromString<TrpgUndo>(it.string("undo_json")) }

    fun latestTurn(conversationId: String): List<TrpgEvent> {
        val runId = db.query("SELECT run_id FROM trpg_events WHERE conversation_id=? AND run_id IS NOT NULL ORDER BY revision DESC LIMIT 1", listOf(conversationId))
            .singleOrNull()?.string("run_id") ?: return emptyList()
        return db.query("SELECT event_json FROM trpg_events WHERE conversation_id=? AND run_id=? ORDER BY revision", listOf(conversationId, runId))
            .map { json.decodeFromString<TrpgEvent>(it.string("event_json")) }
    }

    fun matches(state: TrpgState, undo: TrpgUndo): Boolean = hash(stateHashJson.encodeToString(state)) == undo.afterHash

    fun restore(conversationId: String, revision: Long, journal: TrpgJournal) {
        TrpgJournalValidation.validate(journal, revision)
        require(db.query("SELECT event_id FROM trpg_events WHERE conversation_id=? LIMIT 1", listOf(conversationId)).isEmpty())
        journal.events.forEach { event ->
            db.execute("INSERT INTO trpg_events(conversation_id,event_id,revision,action_id,request_hash,event_json,run_id,kind) VALUES(?,?,?,?,?,?,?,?)",
                listOf(conversationId, event.id, event.revision, "import:${event.id}", "", json.encodeToString(event), event.runId, event.kind.name))
        }
        journal.undo?.let { db.execute("INSERT INTO trpg_undo(conversation_id,undo_json) VALUES(?,?)", listOf(conversationId, json.encodeToString(it))) }
    }

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
