// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.trpg.TrpgCheckpoint

/**
 * Captures a checkpoint inside the caller's transaction: the CHECKPOINT journal event, the
 * metadata row and the payload either all commit or all roll back.
 */
internal class TrpgCheckpointCaptureSupport(
    private val db: SqlConnection,
    private val clock: () -> String,
    private val trpg: TrpgRepository,
    private val conversations: ConversationRepository,
    private val images: TrpgCheckpointImageSupport,
    private val journal: TrpgCheckpointJournalSupport,
    private val summaries: TrpgCheckpointSummarySupport,
    private val codec: TrpgCheckpointPayloadCodec,
) {
    fun capture(
        conversationId: String,
        snapshotId: String,
        expectedRevision: Long,
        name: String,
        checkpointId: String,
    ): TrpgCheckpoint {
        requireSettledBoundary(conversationId)
        requireNoActiveRun(conversationId)
        requireNoActiveCompaction(conversationId)

        // Messages are immutable, so the prefix read here is exactly the checkpoint payload prefix.
        val messages = conversations.messages(conversationId)
        images.requireConfigured(messages)
        images.validate(conversationId, messages)
        val records = summaries.load(conversationId, snapshotId, messages)

        val session = trpg.recordCheckpoint(conversationId, expectedRevision, "checkpoint:$checkpointId", name)
        val events = journal.prefix(conversationId, session.revision)
        val entries = messages.map {
            TrpgCheckpointMessage(it.id, it.createdAt, TrpgCheckpointDigest.messageFingerprint(it))
        }
        val payload = TrpgCheckpointPayload(
            version = TrpgCheckpointGuard.PAYLOAD_VERSION,
            revision = session.revision,
            conversationId = conversationId,
            state = session.state,
            messages = entries,
            prefixHash = TrpgCheckpointDigest.prefixHash(entries),
            worldBooks = WorldBookRepository(db, clock).conversationConfig(conversationId),
            summaries = records,
            journal = TrpgCheckpointJournalBoundary(events.size, events.last().id, TrpgCheckpointDigest.journalPrefixHash(events)),
        )
        val payloadJson = codec.encode(payload)
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8).size.toLong()
        if (payloadBytes > TrpgCheckpointGuard.MAX_PAYLOAD_BYTES) {
            TrpgCheckpointGuard.fail("TRPG checkpoint exceeds the ${TrpgCheckpointGuard.MAX_PAYLOAD_BYTES} byte limit")
        }
        TrpgCheckpointGuard.requirePayloadBudget(db, conversationId, payloadBytes)
        val at = clock()
        db.execute(
            "INSERT INTO trpg_checkpoints(conversation_id,checkpoint_id,name,created_at,payload_json) VALUES(?,?,?,?,?)",
            listOf(conversationId, checkpointId, name, at, payloadJson),
        )
        return TrpgCheckpoint(conversationId, checkpointId, name, at, session.revision)
    }

    private fun requireSettledBoundary(conversationId: String) {
        if (db.query(
                "SELECT id FROM messages WHERE conversation_id=? AND status=? LIMIT 1",
                listOf(conversationId, TrpgCheckpointGuard.STREAMING_STATUS),
            ).isNotEmpty()
        ) {
            TrpgCheckpointGuard.fail("TRPG checkpoints require every message to be settled")
        }
    }

    private fun requireNoActiveRun(conversationId: String) {
        if (db.query(
                "SELECT run_id FROM runs WHERE conversation_id=? AND state NOT IN ('COMPLETED','CANCELLED','FAILED','BUDGET_EXHAUSTED','UNKNOWN_OUTCOME') LIMIT 1",
                listOf(conversationId),
            ).isNotEmpty()
        ) {
            TrpgCheckpointGuard.fail("TRPG checkpoints require the active Run to finish")
        }
    }

    private fun requireNoActiveCompaction(conversationId: String) {
        if (db.query(
                "SELECT id FROM context_compactions WHERE conversation_id=? AND state IN ('PREPARED','DISPATCHED') LIMIT 1",
                listOf(conversationId),
            ).isNotEmpty()
        ) {
            TrpgCheckpointGuard.fail("TRPG checkpoints require the active context compaction to finish")
        }
    }
}
