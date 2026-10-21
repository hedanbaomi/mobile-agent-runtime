// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgStateValidation
import runtime.mobileagent.domain.trpg.TrpgEvent
import runtime.mobileagent.domain.trpg.TrpgEventKind
import runtime.mobileagent.domain.trpg.TrpgJournal
import runtime.mobileagent.domain.trpg.TrpgJournalView
import runtime.mobileagent.domain.trpg.TrpgCampaign
import runtime.mobileagent.domain.trpg.TrpgCommand
import runtime.mobileagent.domain.trpg.TrpgCommandReducer
import runtime.mobileagent.domain.trpg.TrpgDiceMode
import runtime.mobileagent.domain.trpg.TrpgOperation
import runtime.mobileagent.domain.trpg.TrpgVisibility

/** One bounded mutable state per immutable TRPG conversation, with durable tool replay receipts. */
class TrpgRepository(private val db: SqlConnection, private val clock: () -> String = { Utc.nowIso() }) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = false }
    private val journal = TrpgJournalStore(db, clock)

    fun get(conversationId: String): TrpgSession = db.transaction {
        requireConversation(conversationId)
        read(conversationId)
    }

    fun update(conversationId: String, expectedRevision: Long, state: TrpgState, actionId: String = UUID.randomUUID().toString()): TrpgSession = db.transaction {
        requireManualWrite(conversationId)
        validate(state)
        val hash = TrpgJournalStore.hash(json.encodeToString(listOf("edit", expectedRevision.toString(), json.encodeToString(state))))
        if (replayed(conversationId, actionId, hash)) return@transaction read(conversationId)
        mutate(conversationId, expectedRevision, state, TrpgEventKind.EDIT, actionId, hash, "State edited")
    }

    /** Checkpoint metadata and this revision event are committed by the caller's enclosing transaction. */
    fun recordCheckpoint(conversationId: String, expectedRevision: Long, actionId: String, title: String): TrpgSession = db.transaction {
        requireManualWrite(conversationId)
        require(title.isNotBlank() && title.length <= 128)
        val hash = TrpgJournalStore.hash(json.encodeToString(listOf("checkpoint", expectedRevision.toString(), title)))
        if (replayed(conversationId, actionId, hash)) return@transaction read(conversationId)
        val current = read(conversationId)
        mutate(conversationId, expectedRevision, current.state, TrpgEventKind.CHECKPOINT, actionId, hash, title)
    }

    /** The already-generated result is stored verbatim. Retrying never draws another random number. */
    fun recordRoll(conversationId: String, expectedRevision: Long? = null, actionId: String, title: String, detail: String): TrpgSession = db.transaction {
        requireManualWrite(conversationId)
        if (title.isBlank() || title.length > 1024 || detail.length > 16384) fail("Invalid TRPG roll record")
        val hash = TrpgJournalStore.hash(json.encodeToString(listOf("roll", title, detail)))
        if (replayed(conversationId, actionId, hash)) return@transaction read(conversationId)
        val current = read(conversationId)
        mutate(conversationId, expectedRevision ?: current.revision, current.state, TrpgEventKind.ROLL, actionId, hash, title, detail)
    }

    fun events(conversationId: String, limit: Int = 50, beforeRevision: Long = Long.MAX_VALUE): List<TrpgEvent> = db.transaction {
        requireConversation(conversationId)
        journal.recent(conversationId, limit, beforeRevision)
    }

    fun journalView(conversationId: String): TrpgJournalView = db.transaction {
        requireConversation(conversationId)
        val session = read(conversationId)
        TrpgJournalView(session, journal.recent(conversationId, 50, Long.MAX_VALUE),
            journal.undo(conversationId)?.takeIf { journal.matches(session.state, it) }?.eventId, journal.latestTurn(conversationId))
    }

    fun undoableEventId(conversationId: String): String? = db.transaction {
        requireConversation(conversationId)
        journal.undo(conversationId)?.takeIf { journal.matches(read(conversationId).state, it) }?.eventId
    }

    fun undo(conversationId: String, expectedRevision: Long, eventId: String, actionId: String): TrpgSession = db.transaction {
        requireManualWrite(conversationId)
        val hash = TrpgJournalStore.hash(json.encodeToString(listOf("undo", expectedRevision.toString(), eventId)))
        if (replayed(conversationId, actionId, hash)) return@transaction read(conversationId)
        val undo = journal.undo(conversationId) ?: fail("No TRPG state change can be undone")
        if (undo.eventId != eventId || !journal.matches(read(conversationId).state, undo)) fail("Only the latest matching state change can be undone")
        mutate(conversationId, expectedRevision, undo.before, TrpgEventKind.UNDO, actionId, hash, "State change undone", targetId = eventId)
    }

    internal fun exportJournal(conversationId: String): TrpgJournal = journal.read(conversationId)
    internal fun restoreJournal(session: TrpgSession, value: TrpgJournal) {
        if (value.undo?.let { !journal.matches(session.state, it) } == true) fail("TRPG undo does not match its state")
        journal.restore(session.conversationId, session.revision, value)
    }

    fun updateFromRun(
        conversationId: String, snapshotId: String, runId: String, invocationId: String,
        requestJson: String, expectedRevision: Long, state: TrpgState,
    ): TrpgSession = mutateFromRun(conversationId, snapshotId, runId, invocationId, requestJson, expectedRevision,
        json.encodeToString(state)) { current ->
        validate(state)
        if (state.campaign != TrpgCampaign() && state.campaign != current.campaign) fail("Use campaign_action for campaign changes")
        // A legacy full update must never erase campaign fields it does not understand.
        state.copy(campaign = current.campaign)
    }

    fun commandFromRun(conversationId: String, snapshotId: String, runId: String, invocationId: String,
        requestJson: String, expectedRevision: Long, command: TrpgCommand, random: Random = Random.Default): TrpgSession =
        mutateFromRun(conversationId, snapshotId, runId, invocationId, requestJson, expectedRevision,
            "command:" + json.encodeToString(command)) { current -> applyCommand(conversationId, current, command, random) }

    fun command(conversationId: String, expectedRevision: Long, actionId: String, command: TrpgCommand,
        random: Random = Random.Default): TrpgSession = db.transaction {
        requireManualWrite(conversationId)
        val hash = TrpgJournalStore.hash(json.encodeToString(listOf("command", expectedRevision.toString(), json.encodeToString(command))))
        if (replayed(conversationId, actionId, hash)) return@transaction read(conversationId)
        val before = read(conversationId)
        requireRevision(before, expectedRevision)
        val next = applyCommand(conversationId, before.state, command, random)
        val rolled = next.campaign.checks.firstOrNull { it.roll != null && before.state.campaign.checks.none { old -> old.roll?.id == it.roll?.id } }
        mutate(conversationId, expectedRevision, next, if (rolled == null) TrpgEventKind.EDIT else TrpgEventKind.ROLL,
            actionId, hash, rolled?.roll?.summary ?: "Campaign updated", rolled?.roll?.resultJson.orEmpty(),
            visibility = rolled?.request?.visibility ?: TrpgVisibility.PLAYER)
    }

    fun rollCheck(conversationId: String, expectedRevision: Long, checkId: String, actionId: String,
        random: Random = Random.Default): TrpgSession = db.transaction {
        requireManualWrite(conversationId)
        val hash = TrpgJournalStore.hash(json.encodeToString(listOf("check-roll", expectedRevision.toString(), checkId)))
        if (replayed(conversationId, actionId, hash)) return@transaction read(conversationId)
        val before = read(conversationId)
        requireRevision(before, expectedRevision)
        val next = TrpgCheckSupport.roll(before.state, checkId, clock(), random)
        val check = next.campaign.checks.single { it.request.id == checkId }
        val evidence = checkNotNull(check.roll)
        mutate(conversationId, expectedRevision, next, TrpgEventKind.ROLL, actionId, hash,
            evidence.summary, evidence.resultJson, targetId = checkId, visibility = check.request.visibility)
    }

    private fun applyCommand(conversationId: String, current: TrpgState, command: TrpgCommand, random: Random): TrpgState {
        command.validate()
        listOfNotNull(command.fact?.sourceMessageId, command.story?.track?.sourceMessageId).forEach { messageId ->
            if (db.query("SELECT id FROM messages WHERE conversation_id=? AND id=?", listOf(conversationId, messageId)).isEmpty()) fail("Fact source is not in this conversation")
        }
        command.check?.let(TrpgCheckSupport::validate)
        val next = TrpgCommandReducer.apply(current, command)
        return if (command.operation == TrpgOperation.REQUEST_CHECK && current.campaign.preferences.diceMode == TrpgDiceMode.AUTOMATIC)
            TrpgCheckSupport.roll(next, checkNotNull(command.check).id, clock(), random) else next
    }

    private fun mutateFromRun(conversationId: String, snapshotId: String, runId: String, invocationId: String,
        requestJson: String, expectedRevision: Long, input: String, transform: (TrpgState) -> TrpgState): TrpgSession = db.transaction {
        val currentSnapshot = requireConversation(conversationId)
        requireWritable(conversationId)
        if (currentSnapshot != snapshotId) fail("TRPG snapshot does not match this conversation")
        val run = db.query("SELECT * FROM runs WHERE run_id=?", listOf(runId)).singleOrNull() ?: fail("TRPG Run is missing")
        if (run.string("conversation_id") != conversationId || run.string("snapshot_id") != snapshotId) fail("TRPG Run binding does not match")
        if (run.string("state") !in LIVE_RUN_STATES) fail("TRPG Run is no longer active")
        if (invocationId.isBlank() || invocationId.length > 256 || requestJson.toByteArray(Charsets.UTF_8).size > 128 * 1024) fail("Invalid TRPG invocation")
        // Hash every effective argument as well as original request bytes; callers cannot hide changed state.
        val requestHash = MessageDigest.getInstance("SHA-256").digest(json.encodeToString(listOf(
            conversationId, snapshotId, runId, requestJson, expectedRevision.toString(), input,
        )).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val receipt = db.query("SELECT * FROM trpg_run_receipts WHERE invocation_id=?", listOf(invocationId)).singleOrNull()
        if (receipt != null) {
            if (receipt.string("conversation_id") != conversationId || receipt.string("snapshot_id") != snapshotId || receipt.string("run_id") != runId || receipt.string("request_hash") != requestHash) fail("TRPG invocation arguments changed")
            return@transaction json.decodeFromString<TrpgSession>(receipt.string("session_json")).also { TrpgStateValidation.validate(it) }
        }
        val before = read(conversationId)
        requireRevision(before, expectedRevision)
        val next = transform(before.state)
        val rolled = next.campaign.checks.firstOrNull { it.roll != null && before.state.campaign.checks.none { old -> old.roll?.id == it.roll?.id } }
        val result = mutate(conversationId, expectedRevision, next, if (rolled == null) TrpgEventKind.TOOL else TrpgEventKind.ROLL,
            "tool:$invocationId", requestHash, rolled?.roll?.summary ?: "GM settlement", rolled?.roll?.resultJson.orEmpty(), runId = runId,
            visibility = rolled?.request?.visibility ?: TrpgVisibility.PLAYER)
        db.execute("INSERT INTO trpg_run_receipts(invocation_id,conversation_id,snapshot_id,run_id,request_hash,session_json,created_at) VALUES(?,?,?,?,?,?,?)",
            listOf(invocationId, conversationId, snapshotId, runId, requestHash, json.encodeToString(result), clock()))
        result
    }

    /** Transfer restores only into a newly inserted conversation; callers preserve existing state. */
    internal fun restore(session: TrpgSession) {
        requireConversation(session.conversationId)
        TrpgStateValidation.validate(session)
        if (db.query("SELECT conversation_id FROM trpg_sessions WHERE conversation_id=?", listOf(session.conversationId)).isNotEmpty()) fail("TRPG state already exists")
        db.execute("INSERT INTO trpg_sessions(conversation_id,revision,state_json,updated_at) VALUES(?,?,?,?)",
            listOf(session.conversationId, session.revision, json.encodeToString(session.state), clock()))
    }

    private fun mutate(conversationId: String, expectedRevision: Long, state: TrpgState, kind: TrpgEventKind,
        actionId: String, requestHash: String, title: String, detail: String = "", runId: String? = null, targetId: String? = null,
        visibility: TrpgVisibility = TrpgVisibility.PLAYER): TrpgSession {
        validate(state)
        val current = read(conversationId)
        requireRevision(current, expectedRevision)
        val result = TrpgSession(conversationId, current.revision + 1, state)
        db.execute("INSERT INTO trpg_sessions(conversation_id,revision,state_json,updated_at) VALUES(?,?,?,?) ON CONFLICT(conversation_id) DO UPDATE SET revision=excluded.revision,state_json=excluded.state_json,updated_at=excluded.updated_at WHERE trpg_sessions.revision=?",
            listOf(conversationId, result.revision, json.encodeToString(state), clock(), expectedRevision))
        if (db.query("SELECT changes() AS count").single().long("count") != 1L) fail("TRPG state revision conflict")
        journal.append(current, result, kind, actionId, requestHash, title, detail, runId, targetId, visibility)
        return result
    }

    private fun requireRevision(current: TrpgSession, expectedRevision: Long) {
        if (expectedRevision < 0 || expectedRevision != current.revision || expectedRevision == Long.MAX_VALUE) fail("TRPG state revision conflict")
    }

    private fun replayed(conversationId: String, actionId: String, hash: String): Boolean = try {
        journal.replay(conversationId, actionId, hash)
    } catch (error: IllegalArgumentException) { fail(error.message ?: "Invalid TRPG action") }

    private fun requireManualWrite(conversationId: String) {
        requireConversation(conversationId)
        requireWritable(conversationId)
        if (db.query("SELECT run_id FROM runs WHERE conversation_id=? AND state NOT IN ('COMPLETED','CANCELLED','FAILED','BUDGET_EXHAUSTED','UNKNOWN_OUTCOME') LIMIT 1", listOf(conversationId)).isNotEmpty()) {
            fail("TRPG manual edits require the active Run to finish")
        }
    }

    private fun read(conversationId: String): TrpgSession = db.query("SELECT * FROM trpg_sessions WHERE conversation_id=?", listOf(conversationId)).singleOrNull()?.let {
        TrpgSession(conversationId, it.long("revision"), json.decodeFromString<TrpgState>(it.string("state_json"))).also { session -> TrpgStateValidation.validate(session) }
    } ?: TrpgSession(conversationId)

    private fun requireConversation(id: String): String {
        val conversation = db.query("SELECT snapshot_id,agent_snapshot_id,archived FROM conversations WHERE id=?", listOf(id)).singleOrNull() ?: fail("TRPG conversation is missing")
        val snapshotId = conversation.string("snapshot_id")
        if (conversation.string("agent_snapshot_id").ifBlank { snapshotId } != snapshotId) fail("TRPG conversation snapshot binding is inconsistent")
        val snapshot = AgentRepository(db).resolveSnapshot(snapshotId).snapshot
        if (snapshot.mode != AgentMode.TRPG) fail("Conversation is not in TRPG mode")
        return snapshotId
    }

    private fun validate(state: TrpgState) { try { TrpgStateValidation.validate(state) } catch (error: IllegalArgumentException) { fail(error.message ?: "Invalid TRPG state") } }
    private fun requireWritable(id: String) {
        if (db.query("SELECT archived FROM conversations WHERE id=?", listOf(id)).single().long("archived") != 0L) fail("Archived TRPG conversations are read-only")
    }
    private fun fail(message: String): Nothing = throw AppError(ErrorCode.INVALID_CONFIG, message, RetryClass.USER_ACTION, "trpg-persistence", "trpg-state", message).asException()

    private companion object {
        val LIVE_RUN_STATES = setOf("CREATED", "VALIDATING", "RETRIEVING", "ASSEMBLING", "MODEL_STREAMING", "WAITING_TOOL_APPROVAL", "TOOL_EXECUTING", "WAITING_FOR_CONFIGURATION", "WAITING_FOR_USER")
    }
}
