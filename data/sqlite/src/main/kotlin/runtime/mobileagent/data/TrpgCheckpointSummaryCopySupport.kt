// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.UUID
import runtime.mobileagent.domain.ContextCompactionDigest
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.ContextCompactionSource
import runtime.mobileagent.domain.ContextCompactionState
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus

/**
 * Materializes a validated summary chain into a branch. Source ids, parent links and the digest
 * are re-derived from the copied prefix; summary text itself is never invented.
 */
internal class TrpgCheckpointSummaryCopySupport(
    private val db: SqlConnection,
    private val clock: () -> String,
    private val codec: TrpgCheckpointPayloadCodec,
) {
    fun copy(
        newConversationId: String,
        snapshotId: String,
        idMap: Map<String, String>,
        summaries: List<ContextCompactionRecord>,
        at: String,
    ) {
        if (summaries.isEmpty()) return
        val summaryIds = summaries.associate { it.id to UUID.randomUUID().toString() }
        val runs = RunRepository(db, clock)
        summaries.forEach { record ->
            val newId = summaryIds.getValue(record.id)
            val newSources = record.sourceMessageIds.map { id ->
                idMap[id] ?: TrpgCheckpointGuard.fail("TRPG checkpoint summary source is outside its prefix")
            }
            val parent = record.parentId?.let {
                summaryIds[it] ?: TrpgCheckpointGuard.fail("TRPG checkpoint summary parent chain is incomplete")
            }
            // The digest covers message identity, so the remapped prefix needs a fresh digest.
            val sourceHash = sourceHash(newConversationId, newSources)
            // context_compactions requires a run row. This synthetic row records provenance only:
            // it is terminal, spends no tokens and can never be executed or resumed.
            val runId = UUID.randomUUID().toString()
            runs.create(
                RunRecord(
                    runId = runId,
                    snapshotId = snapshotId,
                    conversationId = newConversationId,
                    state = RunStatus.COMPLETED,
                    budgetJson = "{}",
                    stopReason = TrpgCheckpointGuard.HISTORICAL_BRANCH_STOP_REASON,
                    modelRounds = 0,
                    toolCalls = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    startedAt = at,
                    finishedAt = at,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
            db.execute(
                INSERT_COMPACTION,
                listOf(
                    newId, newConversationId, snapshotId, runId, codec.encodeIds(newSources), sourceHash,
                    record.inputHash, record.modelId, record.modelFingerprint, record.authorizationFingerprint,
                    record.reason, ContextCompactionState.SUCCEEDED.name, record.summaryJson, parent, record.version,
                    record.beforeUnits, record.afterUnits, record.inputTokens, record.outputTokens,
                    record.createdAt, record.updatedAt,
                ),
            )
        }
    }

    private fun sourceHash(conversationId: String, ids: List<String>): String {
        MessageChronology.backfill(db, conversationId)
        val rows = db.query(
            "SELECT id,created_at,role,text,parts_json,status FROM messages WHERE conversation_id=? ORDER BY chronology_key,chronology_ordinal",
            listOf(conversationId),
        )
        val byId = rows.associateBy { it.string("id") }
        val sources = ids.map { id ->
            val row = byId[id] ?: TrpgCheckpointGuard.fail("TRPG checkpoint summary source is missing")
            val status = row.string("status")
            if (status != TrpgCheckpointGuard.COMPLETE_STATUS) {
                TrpgCheckpointGuard.fail("TRPG checkpoint summary covers an unsettled message")
            }
            ContextCompactionSource(id, row.string("created_at"), 0, row.string("role"), row.string("text"),
                row.string("parts_json").ifBlank { "[]" }, status)
        }
        val durableOrder = rows.map { it.string("id") }.filter { it in ids.toSet() }
        if (durableOrder != ids) TrpgCheckpointGuard.fail("TRPG checkpoint summary sources are out of order")
        return ContextCompactionDigest.sourceContentHash(sources)
    }

    private companion object {
        // Mirrors ContextCompactionRepository's row shape; SUCCEEDED copies are inserted directly
        // because the public state machine deliberately refuses to synthesize a history row.
        const val INSERT_COMPACTION =
            "INSERT INTO context_compactions(id,conversation_id,snapshot_id,run_id,source_message_ids_json,source_content_hash,input_hash,model_id,model_fingerprint,authorization_fingerprint,reason,state,summary_json,parent_id,version,before_units,after_units,input_tokens,output_tokens,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    }
}
