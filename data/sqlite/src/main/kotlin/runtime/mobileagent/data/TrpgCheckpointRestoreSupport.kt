// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.trpg.TrpgCheckpointTransfer

/**
 * Restores checkpoint rows into an already imported conversation. Callers insert the transcript,
 * TRPG state and journal first. Old packages carry no entries and stay exactly as they were.
 */
internal class TrpgCheckpointRestoreSupport(
    private val db: SqlConnection,
    private val clock: () -> String,
    private val trpg: TrpgRepository,
    private val images: TrpgCheckpointImageSupport,
    private val journal: TrpgCheckpointJournalSupport,
    private val summaries: TrpgCheckpointSummarySupport,
    private val codec: TrpgCheckpointPayloadCodec,
) {
    private val prefix = TrpgCheckpointPrefixSupport(ConversationRepository(db, clock))

    fun restore(conversationId: String, entries: List<TrpgCheckpointTransfer>) {
        val conversation = TrpgCheckpointGuard.conversationRow(db, conversationId)
            ?: TrpgCheckpointGuard.fail("TRPG conversation is missing")
        TrpgCheckpointGuard.requireTrpgSnapshot(db, conversation.string("snapshot_id"), conversation.string("agent_snapshot_id"))
        if (entries.size > TrpgCheckpointGuard.MAX_CHECKPOINTS) {
            TrpgCheckpointGuard.fail("A TRPG conversation keeps at most ${TrpgCheckpointGuard.MAX_CHECKPOINTS} checkpoints")
        }
        val snapshotId = conversation.string("snapshot_id")
        val currentRevision = trpg.get(conversationId).revision
        val existing = db.query("SELECT checkpoint_id FROM trpg_checkpoints WHERE conversation_id=?", listOf(conversationId))
            .map { it.string("checkpoint_id") }
            .toMutableSet()
        TrpgCheckpointGuard.requirePayloadBudget(
            db,
            conversationId,
            entries.sumOf { it.payloadJson.toByteArray(Charsets.UTF_8).size.toLong() },
        )
        entries.forEach { entry ->
            insert(conversationId, snapshotId, currentRevision, existing, entry)
            existing += entry.checkpointId
        }
    }

    private fun insert(
        conversationId: String,
        snapshotId: String,
        currentRevision: Long,
        existing: Set<String>,
        entry: TrpgCheckpointTransfer,
    ) {
        if (entry.conversationId != conversationId) TrpgCheckpointGuard.fail("TRPG checkpoint entry belongs to another conversation")
        TrpgCheckpointGuard.requireCheckpointId(entry.checkpointId)
        TrpgCheckpointGuard.requireCheckpointName(entry.name)
        if (entry.createdAt.isBlank() || entry.createdAt.length > 64) TrpgCheckpointGuard.fail("Invalid TRPG checkpoint timestamp")
        if (entry.checkpointId in existing) TrpgCheckpointGuard.fail("TRPG checkpoint ${entry.checkpointId} already exists")
        if (entry.payloadJson.toByteArray(Charsets.UTF_8).size > TrpgCheckpointGuard.MAX_PAYLOAD_BYTES) {
            TrpgCheckpointGuard.fail("TRPG checkpoint payload exceeds the ${TrpgCheckpointGuard.MAX_PAYLOAD_BYTES} byte limit")
        }
        val payload = codec.decode(entry.payloadJson)
        if (payload.conversationId != conversationId) TrpgCheckpointGuard.fail("TRPG checkpoint payload belongs to another conversation")
        if (payload.revision != entry.revision) TrpgCheckpointGuard.fail("TRPG checkpoint revision does not match its payload")
        if (payload.revision > currentRevision) TrpgCheckpointGuard.fail("TRPG checkpoint is newer than the restored conversation state")
        TrpgCheckpointGuard.validateState(payload.state)
        val messages = prefix.requirePrefix(conversationId, payload)
        TrpgCheckpointRemapSupport.requireSources(payload.state, payload)
        // The imported conversation already carries the journal; prove the checkpoint boundary
        // still matches it instead of storing a second copy inside the payload.
        journal.requireBoundary(conversationId, payload)
        // Import re-encodes parts_json, so the byte-level source digest may differ across
        // devices; the content fingerprints above and the summary coverage below are the
        // integrity boundary here.
        summaries.require(payload, conversationId, snapshotId, messages, strictLive = false)
        images.requireConfigured(messages)
        images.validate(conversationId, messages)
        val compactions = ContextCompactionRepository(db, clock)
        val restoredPayload = payload.copy(summaries = payload.summaries.map { record ->
            record.copy(sourceContentHash = compactions.sourceContentHash(conversationId, record.sourceMessageIds))
        })
        val restoredJson = codec.encode(restoredPayload)
        if (restoredJson.toByteArray(Charsets.UTF_8).size > TrpgCheckpointGuard.MAX_PAYLOAD_BYTES) {
            TrpgCheckpointGuard.fail("Restored TRPG checkpoint exceeds its payload limit")
        }
        TrpgCheckpointGuard.requirePayloadBudget(db, conversationId, restoredJson.toByteArray(Charsets.UTF_8).size.toLong())
        db.execute(
            "INSERT INTO trpg_checkpoints(conversation_id,checkpoint_id,name,created_at,payload_json) VALUES(?,?,?,?,?)",
            listOf(conversationId, entry.checkpointId, entry.name, entry.createdAt, restoredJson),
        )
    }
}
