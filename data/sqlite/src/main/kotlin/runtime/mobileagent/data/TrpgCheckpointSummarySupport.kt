// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.ContextCompactionState
import runtime.mobileagent.domain.ContextSummary
import runtime.mobileagent.domain.Message

/**
 * Summary continuity: only successful, still-valid summaries may enter a checkpoint, and a branch
 * re-proves their coverage against the copied prefix before mapping them.
 */
internal class TrpgCheckpointSummarySupport(
    private val db: SqlConnection,
    private val clock: () -> String,
) {
    fun load(conversationId: String, snapshotId: String, messages: List<Message>): List<ContextCompactionRecord> {
        val compactions = ContextCompactionRepository(db, clock)
        // list() preserves durable rowid order, which is the summary parent-chain order.
        val succeeded = compactions.list(conversationId).filter { it.state == ContextCompactionState.SUCCEEDED }
        if (succeeded.isEmpty()) return emptyList()
        TrpgCheckpointSummaryValidation.requireChain(succeeded)
        val order = messages.withIndex().associate { it.value.id to it.index }
        succeeded.forEach { record ->
            if (record.snapshotId != snapshotId) TrpgCheckpointGuard.fail("TRPG summary snapshot does not match the conversation")
            val summary = record.summaryJson ?: TrpgCheckpointGuard.fail("TRPG summary is missing")
            if (runCatching { ContextSummary.canonicalize(summary) }.getOrNull() != summary) {
                TrpgCheckpointGuard.fail("TRPG summary is not canonical")
            }
            var previous = -1
            record.sourceMessageIds.forEach { id ->
                val index = order[id] ?: TrpgCheckpointGuard.fail("TRPG summary covers a message outside the checkpoint prefix")
                if (index <= previous) TrpgCheckpointGuard.fail("TRPG summary sources are out of order")
                previous = index
            }
            if (!compactions.validateSources(record)) {
                TrpgCheckpointGuard.fail("TRPG summary sources changed; cannot checkpoint a stale summary")
            }
        }
        return succeeded
    }

    fun require(
        payload: TrpgCheckpointPayload,
        conversationId: String,
        snapshotId: String,
        messages: List<Message>,
        strictLive: Boolean,
    ): List<ContextCompactionRecord> {
        if (payload.summaries.isEmpty()) return emptyList()
        TrpgCheckpointSummaryValidation.requireChain(payload.summaries)
        val order = messages.withIndex().associate { it.value.id to it.index }
        val ids = payload.summaries.map { it.id }
        if (ids.distinct().size != ids.size) TrpgCheckpointGuard.fail("TRPG checkpoint repeats a summary identity")
        val compactions = ContextCompactionRepository(db, clock)
        payload.summaries.forEach { record ->
            if (record.conversationId != conversationId) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summary belongs to another conversation")
            }
            if (record.snapshotId != snapshotId) TrpgCheckpointGuard.fail("TRPG checkpoint summary snapshot does not match")
            if (record.state != ContextCompactionState.SUCCEEDED) TrpgCheckpointGuard.fail("TRPG checkpoint summary is not SUCCEEDED")
            val summary = record.summaryJson ?: TrpgCheckpointGuard.fail("TRPG checkpoint summary is missing")
            if (runCatching { ContextSummary.canonicalize(summary) }.getOrNull() != summary) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summary is not canonical")
            }
            if (record.sourceMessageIds.isEmpty() || record.sourceMessageIds.distinct().size != record.sourceMessageIds.size) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summary source list is invalid")
            }
            var previous = -1
            record.sourceMessageIds.forEach { id ->
                val index = order[id] ?: TrpgCheckpointGuard.fail("TRPG checkpoint summary covers a message outside its prefix")
                if (index <= previous) TrpgCheckpointGuard.fail("TRPG checkpoint summary sources are out of order")
                if (messages[index].status != TrpgCheckpointGuard.COMPLETE_STATUS) {
                    TrpgCheckpointGuard.fail("TRPG checkpoint summary covers an unsettled message")
                }
                previous = index
            }
            record.parentId?.let { parent ->
                if (ids.none { it == parent }) TrpgCheckpointGuard.fail("TRPG checkpoint summary parent chain is incomplete")
            }
            // A same-device branch must prove the source rows are still exactly the summarized
            // ones; a cross-device restore only has the re-encoded rows to check coverage against.
            if (strictLive && !compactions.validateSources(record)) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summary sources changed; the checkpoint cannot be branched")
            }
            if (strictLive && compactions.get(record.id)?.let { it != record } == true) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summary provenance changed")
            }
        }
        return payload.summaries
    }
}
