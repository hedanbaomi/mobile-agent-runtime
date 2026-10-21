// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.trpg.TrpgCheckpoint
import runtime.mobileagent.domain.trpg.TrpgCheckpointTransfer

/**
 * Durable conversation checkpoints and checkpoint-derived branches for TRPG sessions.
 *
 * A checkpoint is a read-only historical boundary. It records the complete mutable state, the
 * ordered identity/content digest of the message prefix, the world-book conversation
 * configuration at that instant, the still-valid successful summary chain and the *boundary* of
 * the journal (count, last event and a digest of its prefix up to the checkpoint revision). The
 * journal itself is append-only and already travels with the full conversation entry, so a
 * checkpoint stores only its digest instead of duplicating it up to 32 times.
 *
 * Every write path runs in the caller's enclosing transaction; a failed branch therefore rolls
 * back the new conversation, its messages, image checks, summary rows and synthetic audit runs
 * instead of leaving a half-copied conversation behind. History is data: nothing here replays a
 * tool call, a run or a model request.
 */
class TrpgCheckpointRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
    imageValidator: ((conversationId: String, image: ImagePart) -> Unit)? = null,
) {
    private val codec = TrpgCheckpointPayloadCodec()
    private val trpg = TrpgRepository(db, clock)
    private val conversations = ConversationRepository(db, clock)
    private val images = TrpgCheckpointImageSupport(db, imageValidator)
    private val journal = TrpgCheckpointJournalSupport(trpg)
    private val summaries = TrpgCheckpointSummarySupport(db, clock)
    private val capture = TrpgCheckpointCaptureSupport(db, clock, trpg, conversations, images, journal, summaries, codec)
    private val branchSupport = TrpgCheckpointBranchSupport(
        db, clock, conversations, trpg, images, journal, summaries,
        TrpgCheckpointSummaryCopySupport(db, clock, codec),
    )
    private val restoreSupport = TrpgCheckpointRestoreSupport(db, clock, trpg, images, journal, summaries, codec)

    /** Bounded metadata listing; payload text is only parsed for its revision. */
    fun list(conversationId: String): List<TrpgCheckpoint> = db.transaction {
        if (TrpgCheckpointGuard.conversationRow(db, conversationId) == null) {
            TrpgCheckpointGuard.fail("TRPG conversation is missing")
        }
        db.query("SELECT * FROM trpg_checkpoints WHERE conversation_id=? ORDER BY created_at,rowid", listOf(conversationId)).map { row ->
            val payloadJson = row.string("payload_json")
            val revision = codec.quickRevision(payloadJson) ?: codec.decode(payloadJson).let { payload ->
                if (payload.conversationId != conversationId) TrpgCheckpointGuard.fail("TRPG checkpoint belongs to another conversation")
                payload.revision
            }
            TrpgCheckpoint(conversationId, row.string("checkpoint_id"), row.string("name"), row.string("created_at"), revision)
        }
    }

    /**
     * Captures the current settled message boundary. The CHECKPOINT journal event and this row
     * commit in one transaction; an identical retry returns the original result, while changed
     * arguments for the same checkpoint id are rejected.
     */
    fun create(
        conversationId: String,
        expectedRevision: Long,
        name: String,
        checkpointId: String = java.util.UUID.randomUUID().toString(),
    ): TrpgCheckpoint = db.transaction {
        val conversation = TrpgCheckpointGuard.conversationRow(db, conversationId)
            ?: TrpgCheckpointGuard.fail("TRPG conversation is missing")
        TrpgCheckpointGuard.requireTrpgSnapshot(db, conversation.string("snapshot_id"), conversation.string("agent_snapshot_id"))
        TrpgCheckpointGuard.requireCheckpointName(name)
        TrpgCheckpointGuard.requireCheckpointId(checkpointId)
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) TrpgCheckpointGuard.fail("TRPG state revision conflict")

        // Replay identity is the checkpoint id: the same request returns the stored result.
        val existing = db.query(
            "SELECT * FROM trpg_checkpoints WHERE conversation_id=? AND checkpoint_id=?",
            listOf(conversationId, checkpointId),
        ).singleOrNull()
        if (existing != null) {
            val payload = codec.decode(existing.string("payload_json"))
            if (payload.conversationId != conversationId || existing.string("name") != name || payload.revision != expectedRevision + 1) {
                TrpgCheckpointGuard.fail("TRPG checkpoint $checkpointId already exists with different arguments")
            }
            return@transaction TrpgCheckpoint(conversationId, checkpointId, name, existing.string("created_at"), payload.revision)
        }
        if (db.query("SELECT archived FROM conversations WHERE id=?", listOf(conversationId)).single().long("archived") != 0L) {
            TrpgCheckpointGuard.fail("Archived TRPG conversations are read-only")
        }
        val stored = db.query("SELECT COUNT(*) AS count FROM trpg_checkpoints WHERE conversation_id=?", listOf(conversationId))
            .single().long("count")
        if (stored >= TrpgCheckpointGuard.MAX_CHECKPOINTS) {
            TrpgCheckpointGuard.fail("A TRPG conversation keeps at most ${TrpgCheckpointGuard.MAX_CHECKPOINTS} checkpoints")
        }
        capture.capture(conversationId, conversation.string("snapshot_id"), expectedRevision, name, checkpointId)
    }

    /**
     * Creates a new conversation with the same frozen snapshot, copying only the checkpoint's
     * message prefix and old state. Tool results stay historical data and are never re-executed;
     * no source run, invocation or receipt becomes part of the new conversation's execution state.
     */
    fun branch(
        conversationId: String,
        checkpointId: String,
        title: String,
        newConversationId: String = java.util.UUID.randomUUID().toString(),
    ): Conversation = db.transaction {
        val source = TrpgCheckpointGuard.conversationRow(db, conversationId)
            ?: TrpgCheckpointGuard.fail("TRPG conversation is missing")
        val payload = readPayload(conversationId, checkpointId)
        TrpgCheckpointGuard.requireTrpgSnapshot(db, source.string("snapshot_id"), source.string("agent_snapshot_id"))
        if (title.isBlank() || title.length > TrpgCheckpointGuard.MAX_TITLE_CHARS) TrpgCheckpointGuard.fail("Invalid TRPG branch title")
        TrpgCheckpointGuard.requireSafeId(newConversationId, "newConversationId")
        if (TrpgCheckpointGuard.conversationRow(db, newConversationId) != null) {
            TrpgCheckpointGuard.fail("Branch conversation $newConversationId already exists")
        }
        branchSupport.branch(
            conversationId, source.string("snapshot_id"), checkpointId, title, newConversationId, payload,
        )
    }

    /** Streamed conversation-entry export; checkpoint payloads never enter the manifest. */
    internal fun export(conversationId: String): List<TrpgCheckpointTransfer> = db.transaction {
        db.query("SELECT * FROM trpg_checkpoints WHERE conversation_id=? ORDER BY created_at,rowid", listOf(conversationId)).map { row ->
            val payload = codec.decode(row.string("payload_json"))
            if (payload.conversationId != conversationId) TrpgCheckpointGuard.fail("TRPG checkpoint belongs to another conversation")
            TrpgCheckpointTransfer(
                conversationId = conversationId,
                checkpointId = row.string("checkpoint_id"),
                name = row.string("name"),
                createdAt = row.string("created_at"),
                revision = payload.revision,
                payloadJson = row.string("payload_json"),
            )
        }
    }

    /** Restores checkpoint rows after transcript, state and journal are already present. */
    internal fun restore(conversationId: String, entries: List<TrpgCheckpointTransfer>) = db.transaction {
        if (entries.isEmpty()) return@transaction
        restoreSupport.restore(conversationId, entries)
    }

    private fun readPayload(conversationId: String, checkpointId: String): TrpgCheckpointPayload {
        val row = db.query(
            "SELECT payload_json FROM trpg_checkpoints WHERE conversation_id=? AND checkpoint_id=?",
            listOf(conversationId, checkpointId),
        ).singleOrNull() ?: TrpgCheckpointGuard.fail("TRPG checkpoint is missing")
        val payload = codec.decode(row.string("payload_json"))
        if (payload.conversationId != conversationId) TrpgCheckpointGuard.fail("TRPG checkpoint belongs to another conversation")
        return payload
    }
}
