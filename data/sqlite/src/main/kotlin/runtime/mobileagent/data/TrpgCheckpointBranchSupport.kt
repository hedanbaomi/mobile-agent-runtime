// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.UUID
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.trpg.TrpgJournal
import runtime.mobileagent.domain.trpg.TrpgSession

/**
 * Creates a checkpoint-derived branch. Only the recorded message prefix and old state are copied;
 * tool results stay historical data, and no source run, invocation or replay receipt becomes
 * executable state. Everything runs inside the caller's transaction so a failure leaves nothing.
 */
internal class TrpgCheckpointBranchSupport(
    private val db: SqlConnection,
    private val clock: () -> String,
    private val conversations: ConversationRepository,
    private val trpg: TrpgRepository,
    private val images: TrpgCheckpointImageSupport,
    private val journal: TrpgCheckpointJournalSupport,
    private val summaries: TrpgCheckpointSummarySupport,
    private val summaryCopy: TrpgCheckpointSummaryCopySupport,
) {
    private val prefix = TrpgCheckpointPrefixSupport(conversations)
    private val worldBooks = TrpgCheckpointWorldBookSupport(db, clock)

    fun branch(
        conversationId: String,
        snapshotId: String,
        checkpointId: String,
        title: String,
        newConversationId: String,
        payload: TrpgCheckpointPayload,
    ): Conversation {
        val messages = prefix.requirePrefix(conversationId, payload)
        TrpgCheckpointGuard.validateState(payload.state)
        TrpgCheckpointRemapSupport.requireSources(payload.state, payload)
        // The journal is append-only and shared with the conversation entry: read the live prefix
        // back and prove it still matches the checkpoint's boundary digest before copying it.
        val events = journal.requireBoundary(conversationId, payload)
        val records = summaries.require(payload, conversationId, snapshotId, messages, strictLive = true)
        images.requireConfigured(messages)

        val at = clock()
        conversations.create(snapshotId, title, newConversationId, at)
        worldBooks.restore(newConversationId, payload.worldBooks)
        val idMap = LinkedHashMap<String, String>()
        messages.forEach { idMap[it.id] = UUID.randomUUID().toString() }
        messages.forEach { message ->
            val remappedParent = message.parentMessageId?.let {
                idMap[it] ?: TrpgCheckpointGuard.fail("TRPG checkpoint message parent is outside its prefix")
            }
            conversations.appendMessage(
                message.copy(
                    id = idMap.getValue(message.id),
                    conversationId = newConversationId,
                    parentMessageId = remappedParent,
                    metadataJson = TrpgCheckpointRemapSupport.metadata(message.metadataJson, idMap),
                ),
            )
        }
        // Ownership is per conversation: verify the copies actually own every referenced upload.
        images.validate(newConversationId, messages)

        val session = TrpgSession(
            newConversationId,
            payload.revision,
            TrpgCheckpointRemapSupport.state(payload.state, idMap, conversationId, checkpointId, payload.revision),
        )
        trpg.restore(session)
        // The undo slot is deliberately dropped: remapped fact/story sources change its digest.
        trpg.restoreJournal(session, TrpgJournal(events, undo = null))
        summaryCopy.copy(newConversationId, snapshotId, idMap, records, at)

        db.execute("UPDATE conversations SET updated_at=? WHERE id=?", listOf(at, newConversationId))
        return conversations.get(newConversationId) ?: TrpgCheckpointGuard.fail("Branch conversation disappeared")
    }
}
