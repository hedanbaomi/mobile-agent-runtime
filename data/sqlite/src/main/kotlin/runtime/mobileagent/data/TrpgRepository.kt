// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
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

/** One bounded mutable state per immutable TRPG conversation, with durable tool replay receipts. */
class TrpgRepository(private val db: SqlConnection, private val clock: () -> String = { Utc.nowIso() }) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = false }

    fun get(conversationId: String): TrpgSession = db.transaction {
        requireConversation(conversationId)
        read(conversationId)
    }

    fun update(conversationId: String, expectedRevision: Long, state: TrpgState): TrpgSession = db.transaction {
        requireConversation(conversationId)
        requireWritable(conversationId)
        if (db.query("SELECT run_id FROM runs WHERE conversation_id=? AND state NOT IN ('COMPLETED','CANCELLED','FAILED','BUDGET_EXHAUSTED','UNKNOWN_OUTCOME') LIMIT 1", listOf(conversationId)).isNotEmpty()) {
            fail("TRPG manual edits require the active Run to finish")
        }
        mutate(conversationId, expectedRevision, state)
    }

    fun updateFromRun(
        conversationId: String, snapshotId: String, runId: String, invocationId: String,
        requestJson: String, expectedRevision: Long, state: TrpgState,
    ): TrpgSession = db.transaction {
        val currentSnapshot = requireConversation(conversationId)
        requireWritable(conversationId)
        if (currentSnapshot != snapshotId) fail("TRPG snapshot does not match this conversation")
        val run = db.query("SELECT * FROM runs WHERE run_id=?", listOf(runId)).singleOrNull() ?: fail("TRPG Run is missing")
        if (run.string("conversation_id") != conversationId || run.string("snapshot_id") != snapshotId) fail("TRPG Run binding does not match")
        if (run.string("state") !in LIVE_RUN_STATES) fail("TRPG Run is no longer active")
        if (invocationId.isBlank() || invocationId.length > 256 || requestJson.toByteArray(Charsets.UTF_8).size > 128 * 1024) fail("Invalid TRPG invocation")
        validate(state)
        // Hash every effective argument as well as original request bytes; callers cannot hide changed state.
        val requestHash = MessageDigest.getInstance("SHA-256").digest(json.encodeToString(listOf(
            conversationId, snapshotId, runId, requestJson, expectedRevision.toString(), json.encodeToString(state),
        )).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val receipt = db.query("SELECT * FROM trpg_run_receipts WHERE invocation_id=?", listOf(invocationId)).singleOrNull()
        if (receipt != null) {
            if (receipt.string("conversation_id") != conversationId || receipt.string("snapshot_id") != snapshotId || receipt.string("run_id") != runId || receipt.string("request_hash") != requestHash) fail("TRPG invocation arguments changed")
            return@transaction json.decodeFromString<TrpgSession>(receipt.string("session_json")).also { TrpgStateValidation.validate(it) }
        }
        val result = mutate(conversationId, expectedRevision, state)
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

    private fun mutate(conversationId: String, expectedRevision: Long, state: TrpgState): TrpgSession {
        validate(state)
        val current = read(conversationId)
        if (expectedRevision < 0 || expectedRevision != current.revision || expectedRevision == Long.MAX_VALUE) fail("TRPG state revision conflict")
        val result = TrpgSession(conversationId, current.revision + 1, state)
        db.execute("INSERT INTO trpg_sessions(conversation_id,revision,state_json,updated_at) VALUES(?,?,?,?) ON CONFLICT(conversation_id) DO UPDATE SET revision=excluded.revision,state_json=excluded.state_json,updated_at=excluded.updated_at WHERE trpg_sessions.revision=?",
            listOf(conversationId, result.revision, json.encodeToString(state), clock(), expectedRevision))
        if (db.query("SELECT changes() AS count").single().long("count") != 1L) fail("TRPG state revision conflict")
        return result
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
