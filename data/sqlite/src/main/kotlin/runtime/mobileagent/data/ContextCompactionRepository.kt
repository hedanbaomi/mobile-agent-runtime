// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ContextCompactionDigest
import runtime.mobileagent.domain.ContextCompactionLimits
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.ContextCompactionSource
import runtime.mobileagent.domain.ContextCompactionState
import runtime.mobileagent.domain.ContextSummary
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.Utc

/**
 * Durable session-context compaction attempts (schema v18).
 *
 * A record is continuity bookkeeping, not a model execution request: this repository never sends
 * anything, never deletes or rewrites transcript rows, and never re-derives a summary. The
 * transcript is append-only and its content/status remains owned by [ConversationRepository].
 *
 * Invariants enforced here:
 * - the attempt binds one conversation, one agent snapshot and one run, and proves the three agree;
 * - [ContextCompactionRecord.sourceMessageIds] is unique and ordered in the same durable order as
 *   `ConversationRepository.messages(conversationId)`, every source row exists in that conversation
 *   and is COMPLETE, and [ContextCompactionRecord.sourceContentHash] is derived from those rows;
 * - a summary may only be committed by moving DISPATCHED to SUCCEEDED, only when the source rows and
 *   the parent chain are still exactly what the attempt was created against;
 * - a summary is reusable only while its source rows still verify and it is still the conversation's
 *   latest successful summary for that model and authorization fingerprint;
 * - terminal attempts are immutable, and at most one attempt per conversation may be active.
 */
class ContextCompactionRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    /**
     * Persists a new PREPARED attempt.
     *
     * [ContextCompactionRecord.sourceContentHash] is computed here from the durable rows; a caller
     * supplied value is accepted only when it already matches. [ContextCompactionRecord.parentId]
     * must be the id of the conversation's latest successful summary, or null when there is none:
     * a summary that forgot its predecessor would silently drop transcript that predecessor covers.
     */
    fun create(record: ContextCompactionRecord): ContextCompactionRecord = db.transaction {
        requireId(record.id, "id")
        requireId(record.conversationId, "conversationId")
        requireId(record.snapshotId, "snapshotId")
        requireId(record.runId, "runId")
        requireText(record.inputHash, "inputHash")
        requireText(record.modelId, "modelId")
        requireText(record.modelFingerprint, "modelFingerprint")
        requireText(record.authorizationFingerprint, "authorizationFingerprint")
        requireText(record.reason, "reason")
        if (record.state != ContextCompactionState.PREPARED) {
            throw invalid("A new compaction attempt must start PREPARED, not ${record.state.name}")
        }
        if (record.summaryJson != null) throw invalid("A PREPARED compaction attempt must not carry a summary")
        record.parentId?.let { requireId(it, "parentId") }
        if (record.parentId == record.id) throw invalid("A compaction attempt cannot parent itself")
        if (record.version != 1) throw invalid("A new compaction attempt must start at version 1")
        if (record.beforeUnits < 0 || record.afterUnits < 0 || record.inputTokens < 0 || record.outputTokens < 0) {
            throw invalid("Compaction counters must not be negative")
        }
        val ids = requireSourceIds(record.sourceMessageIds)
        requireBinding(record.conversationId, record.snapshotId, record.runId)
        requireRunAcceptsDispatch(record.runId)
        requireParent(record.parentId, record.id, chainTipId(record.conversationId, record.snapshotId))
        if (db.query("SELECT id FROM context_compactions WHERE id=?", listOf(record.id)).isNotEmpty()) {
            throw invalid("Context compaction attempt ${record.id} already exists")
        }
        val active = db.query(
            "SELECT id FROM context_compactions WHERE conversation_id=? AND state IN(?,?) LIMIT 1",
            listOf(
                record.conversationId,
                ContextCompactionState.PREPARED.name, ContextCompactionState.DISPATCHED.name,
            ),
        )
        if (active.isNotEmpty()) {
            throw invalid(
                "Context compaction attempt ${active.single().string("id")} is still active for this conversation, " +
                    "snapshot, model fingerprint and authorization fingerprint",
            )
        }
        val sourceHash = hashSources(record.conversationId, ids)
        if (record.sourceContentHash.isNotBlank() && record.sourceContentHash != sourceHash) {
            throw invalid("Source messages do not match the supplied sourceContentHash")
        }
        val at = clock()
        val prepared = record.copy(
            sourceMessageIds = ids,
            sourceContentHash = sourceHash,
            createdAt = record.createdAt.ifBlank { at },
            updatedAt = at,
        )
        try {
            db.execute(INSERT_SQL, prepared.args())
        } catch (t: Throwable) {
            throw invalid("Context compaction attempt ${record.id} could not be stored: ${t.message}")
        }
        prepared
    }

    /**
     * Moves an attempt along the explicit state machine
     * `PREPARED -> DISPATCHED -> SUCCEEDED|FAILED|UNKNOWN_OUTCOME` and `PREPARED -> CANCELLED|FAILED`.
     *
     * A summary and replacement size may only be supplied on DISPATCHED -> SUCCEEDED.
     * Observed usage is retained for failed/unknown outcomes because they may also incur cost.
     * That step re-reads the bound transcript, re-verifies the source digest and re-checks that the
     * attempt still declares the conversation's latest successful summary as its parent, so an
     * attempt that was overtaken cannot overwrite a newer summary.
     */
    fun transition(
        id: String,
        state: ContextCompactionState,
        summaryJson: String? = null,
        afterUnits: Long = 0,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
    ): ContextCompactionRecord = db.transaction {
        requireId(id, "id")
        val current = storedRow(id)?.toRecord(json) ?: throw invalid("Context compaction attempt $id does not exist")
        if (current.isTerminal) {
            throw invalid(
                "Context compaction attempt $id is terminal (${current.state.name}) and cannot be modified or re-sent",
            )
        }
        if (state == ContextCompactionState.PREPARED) throw invalid("A compaction attempt cannot return to PREPARED")
        requireTransitionAllowed(current.state, state)
        if (state == ContextCompactionState.DISPATCHED) requireRunAcceptsDispatch(current.runId)
        if (afterUnits < 0 || inputTokens < 0 || outputTokens < 0) {
            throw invalid("Compaction counters must not be negative")
        }
        val at = clock()
        if (state == ContextCompactionState.SUCCEEDED) {
            requireRunAcceptsSummary(current.runId)
            if (!validateSources(current)) {
                throw invalid("Source messages for compaction attempt $id changed; the summary cannot be committed")
            }
            requireParent(current.parentId, current.id, chainTipId(current.conversationId, current.snapshotId))
            val normalized = ContextSummary.canonicalize(
                summaryJson ?: throw invalid("A SUCCEEDED compaction requires a summary"),
            )
            db.execute(
                "UPDATE context_compactions SET state=?,summary_json=?,after_units=?,input_tokens=?,output_tokens=?,updated_at=? WHERE id=?",
                listOf(state.name, normalized, afterUnits, inputTokens, outputTokens, at, id),
            )
        } else {
            if (summaryJson != null) throw invalid("Only a SUCCEEDED compaction may carry a summary")
            if (afterUnits != 0L || (state == ContextCompactionState.DISPATCHED && (inputTokens != 0 || outputTokens != 0))) {
                throw invalid("Only a completed attempt may record usage; replacement size requires success")
            }
            db.execute(
                "UPDATE context_compactions SET state=?,input_tokens=?,output_tokens=?,updated_at=? WHERE id=?",
                listOf(state.name, inputTokens, outputTokens, at, id),
            )
        }
        storedRow(id)?.toRecord(json) ?: throw invalid("Context compaction attempt $id disappeared")
    }

    fun get(id: String): ContextCompactionRecord? =
        db.query("SELECT * FROM context_compactions WHERE id=?", listOf(id)).singleOrNull()?.toRecord(json)

    fun list(conversationId: String): List<ContextCompactionRecord> {
        requireId(conversationId, "conversationId")
        return db.query(
            "SELECT * FROM context_compactions WHERE conversation_id=? ORDER BY rowid",
            listOf(conversationId),
        ).map { it.toRecord(json) }
    }

    /**
     * The newest SUCCEEDED summary that may actually be reused as context: it must still be the
     * conversation's latest successful summary, its model and authorization fingerprints must match
     * exactly, and its source rows must still verify. A newer success under another model or
     * authorization therefore makes an older summary non-reusable instead of letting it resurface.
     */
    fun latestSucceeded(
        conversationId: String,
        snapshotId: String,
        modelFingerprint: String,
        authorizationFingerprint: String,
    ): ContextCompactionRecord? {
        requireId(conversationId, "conversationId")
        requireId(snapshotId, "snapshotId")
        if (modelFingerprint.isBlank() || authorizationFingerprint.isBlank()) return null
        val candidate = db.query(
            "SELECT * FROM context_compactions WHERE conversation_id=? AND snapshot_id=? AND model_fingerprint=? AND authorization_fingerprint=? AND state=? ORDER BY rowid DESC",
            listOf(
                conversationId, snapshotId, modelFingerprint, authorizationFingerprint,
                ContextCompactionState.SUCCEEDED.name,
            ),
        ).firstOrNull()?.toRecord(json) ?: return null
        if (candidate.id != chainTipId(conversationId, snapshotId)) return null
        if (candidate.parentId?.let { get(it) == null } == true) return null
        return candidate.takeIf { validateSources(it) }
    }

    /**
     * Re-reads the bound transcript and re-verifies that the listed ids still exist in the
     * conversation in the recorded order, are still COMPLETE, and still hash to
     * [ContextCompactionRecord.sourceContentHash]. Returns false (never throws) for a stale,
     * tampered or malformed source set.
     */
    fun validateSources(record: ContextCompactionRecord): Boolean {
        val result = runCatching {
            if (record.sourceContentHash.isBlank()) return@runCatching false
            val ids = try {
                requireSourceIds(record.sourceMessageIds)
            } catch (t: AppException) {
                return@runCatching false
            }
            if (ids != record.sourceMessageIds) return@runCatching false
            val sources = loadSources(record.conversationId, ids)
            if (ContextCompactionDigest.sourceContentHash(sources) != record.sourceContentHash) return@runCatching false
            val stored = storedRow(record.id)
            if (stored != null) {
                if (stored.string("conversation_id") != record.conversationId) return@runCatching false
                if (stored.string("snapshot_id") != record.snapshotId) return@runCatching false
                if (stored.string("run_id") != record.runId) return@runCatching false
                if (stored.string("source_content_hash") != record.sourceContentHash) return@runCatching false
                if (decodeSourceIds(stored.string("source_message_ids_json"), record.id) != ids) {
                    return@runCatching false
                }
            }
            true
        }
        return result.getOrDefault(false)
    }

    /**
     * Crash/restart recovery, reached through `RunRepository.markInFlightUnknown`. PREPARED becomes
     * CANCELLED and DISPATCHED becomes UNKNOWN_OUTCOME. This method sends nothing and never turns an
     * interrupted attempt into a success.
     */
    fun markInFlightUnknown(at: String = clock()): List<String> = db.transaction {
        requireText(at, "at")
        val prepared = db.query(
            "SELECT id FROM context_compactions WHERE state=? ORDER BY created_at,id",
            listOf(ContextCompactionState.PREPARED.name),
        ).map { it.string("id") }
        val dispatched = db.query(
            "SELECT id FROM context_compactions WHERE state=? ORDER BY created_at,id",
            listOf(ContextCompactionState.DISPATCHED.name),
        ).map { it.string("id") }
        if (prepared.isNotEmpty()) {
            db.execute(
                "UPDATE context_compactions SET state=?,updated_at=? WHERE state=?",
                listOf(ContextCompactionState.CANCELLED.name, at, ContextCompactionState.PREPARED.name),
            )
        }
        if (dispatched.isNotEmpty()) {
            db.execute(
                "UPDATE context_compactions SET state=?,updated_at=? WHERE state=?",
                listOf(ContextCompactionState.UNKNOWN_OUTCOME.name, at, ContextCompactionState.DISPATCHED.name),
            )
        }
        prepared + dispatched
    }

    private fun requireSourceIds(ids: List<String>): List<String> {
        if (ids.isEmpty()) throw invalid("A compaction attempt must list the messages it covers")
        // Coverage is derived from the local transcript, never from model output. Bounded
        // summarization batches must not impose a permanent lifetime conversation cap.
        ids.forEach { requireId(it, "sourceMessageId") }
        if (ids.distinct().size != ids.size) throw invalid("Source message ids must be unique")
        return ids
    }

    /**
     * Loads every listed row from the bound conversation. A missing row, a row that is not
     * COMPLETE, or a list whose order does not match the durable `created_at,rowid` conversation
     * order is rejected: a summary may only cover transcript that is still present, still settled
     * and still in the order the reader replays it.
     */
    private fun loadSources(conversationId: String, ids: List<String>): List<ContextCompactionSource> {
        val rows = db.query(
            "SELECT rowid AS source_ordinal,id,role,text,status,parts_json,created_at FROM messages WHERE conversation_id=?",
            listOf(conversationId),
        )
        val byId = rows.associateBy { it.string("id") }
        val sources = ids.map { id ->
            val row = byId[id]
                ?: throw invalid("Source message $id does not exist in conversation $conversationId")
            val status = row.string("status")
            if (status != COMPLETE_STATUS) {
                throw invalid("Source message $id is $status; only COMPLETE messages may be compacted")
            }
            ContextCompactionSource(
                messageId = row.string("id"),
                createdAt = row.string("created_at"),
                ordinal = row.long("source_ordinal"),
                role = row.string("role"),
                text = row.string("text"),
                partsJson = row.string("parts_json").ifBlank { "[]" },
                status = status,
            )
        }
        val durableOrder = sources.sortedWith(compareBy({ it.createdAt }, { it.ordinal })).map { it.messageId }
        if (durableOrder != ids) {
            throw invalid("Source message ids must be listed in durable conversation order")
        }
        return sources
    }
    private fun hashSources(conversationId: String, ids: List<String>): String =
        ContextCompactionDigest.sourceContentHash(loadSources(conversationId, ids))

    /** The latest successful summary of the conversation/snapshot chain, reusable or not. */
    private fun chainTipId(conversationId: String, snapshotId: String): String? = db.query(
        "SELECT id FROM context_compactions WHERE conversation_id=? AND snapshot_id=? AND state=? ORDER BY rowid DESC LIMIT 1",
        listOf(conversationId, snapshotId, ContextCompactionState.SUCCEEDED.name),
    ).singleOrNull()?.string("id")

    private fun requireParent(parentId: String?, attemptId: String, chainTip: String?) {
        if (parentId == null) {
            if (chainTip != null) {
                throw invalid(
                    "Compaction attempt $attemptId must declare the latest successful summary $chainTip as its parent",
                )
            }
            return
        }
        if (chainTip == null) {
            throw invalid("Compaction attempt $attemptId declared parent $parentId but there is no successful summary")
        }
        if (parentId != chainTip) {
            throw invalid(
                "Compaction attempt $attemptId declared parent $parentId but the latest successful summary is $chainTip",
            )
        }
    }

    private fun requireBinding(conversationId: String, snapshotId: String, runId: String) {
        val conversation = db.query(
            "SELECT snapshot_id,agent_snapshot_id FROM conversations WHERE id=?",
            listOf(conversationId),
        ).singleOrNull() ?: throw invalid("Conversation $conversationId does not exist")
        if (snapshotId != conversation.string("snapshot_id") || snapshotId != conversation.string("agent_snapshot_id")) {
            throw invalid("Snapshot $snapshotId does not belong to conversation $conversationId")
        }
        if (db.query("SELECT id FROM agent_snapshots WHERE id=?", listOf(snapshotId)).isEmpty()) {
            throw invalid("Agent snapshot $snapshotId does not exist")
        }
        val run = db.query(
            "SELECT conversation_id,snapshot_id FROM runs WHERE run_id=?",
            listOf(runId),
        ).singleOrNull() ?: throw invalid("Run $runId does not exist")
        if (run.string("conversation_id") != conversationId) {
            throw invalid("Run $runId belongs to conversation ${run.string("conversation_id")}, not $conversationId")
        }
        if (run.string("snapshot_id") != snapshotId) {
            throw invalid("Run $runId belongs to snapshot ${run.string("snapshot_id")}, not $snapshotId")
        }
    }

    /** A run marked UNKNOWN_OUTCOME after process death must not start new side work. */
    private fun requireRunAcceptsDispatch(runId: String) {
        val state = runState(runId)
        if (state in terminalRuns) {
            throw invalid("Run $runId has UNKNOWN_OUTCOME and cannot start a new compaction attempt")
        }
    }

    /** A cancelled or crash-recovered run must not accept a late summary as the new reusable one. */
    private fun requireRunAcceptsSummary(runId: String) {
        val state = runState(runId)
        if (state in terminalRuns) {
            throw invalid("Run $runId is ${state.name} and cannot commit a compaction summary")
        }
    }

    private fun runState(runId: String): RunStatus {
        val raw = db.query("SELECT state FROM runs WHERE run_id=?", listOf(runId)).singleOrNull()?.string("state")
            ?: throw invalid("Run $runId does not exist")
        return runCatching { RunStatus.valueOf(raw) }.getOrElse { throw invalid("Persisted run state is invalid") }
    }

    private fun requireTransitionAllowed(from: ContextCompactionState, to: ContextCompactionState) {
        val allowed = when (from) {
            ContextCompactionState.PREPARED -> setOf(
                ContextCompactionState.DISPATCHED,
                ContextCompactionState.CANCELLED,
                ContextCompactionState.FAILED,
            )
            ContextCompactionState.DISPATCHED -> setOf(
                ContextCompactionState.SUCCEEDED,
                ContextCompactionState.FAILED,
                ContextCompactionState.UNKNOWN_OUTCOME,
            )
            else -> emptySet()
        }
        if (to !in allowed) throw invalid("Invalid compaction transition ${from.name} -> ${to.name}")
    }

    private fun storedRow(id: String): SqlRow? =
        db.query("SELECT * FROM context_compactions WHERE id=?", listOf(id)).singleOrNull()

    private fun decodeSourceIds(raw: String, id: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse {
            throw invalid("Context compaction attempt $id has an invalid source id list")
        }

    private fun SqlRow.toRecord(json: Json): ContextCompactionRecord {
        val recordId = string("id")
        val state = runCatching { ContextCompactionState.valueOf(string("state")) }.getOrElse {
            throw invalid("Context compaction attempt $recordId has an invalid state")
        }
        val summary = string("summary_json").ifBlank { null }
        if (state == ContextCompactionState.SUCCEEDED) {
            if (summary == null) throw invalid("Context compaction attempt $recordId is SUCCEEDED without a summary")
            val normalized = runCatching { ContextSummary.canonicalize(summary) }.getOrElse {
                throw invalid("Context compaction attempt $recordId has an invalid summary")
            }
            if (normalized != summary) throw invalid("Context compaction attempt $recordId has a non-canonical summary")
        } else if (summary != null) {
            throw invalid("Context compaction attempt $recordId carries a summary while ${state.name}")
        }
        return ContextCompactionRecord(
            id = recordId,
            conversationId = string("conversation_id"),
            snapshotId = string("snapshot_id"),
            runId = string("run_id"),
            sourceMessageIds = decodeSourceIds(string("source_message_ids_json"), recordId),
            inputHash = string("input_hash"),
            modelId = string("model_id"),
            modelFingerprint = string("model_fingerprint"),
            authorizationFingerprint = string("authorization_fingerprint"),
            reason = string("reason"),
            state = state,
            summaryJson = summary,
            parentId = string("parent_id").ifBlank { null },
            version = long("version").toInt(),
            beforeUnits = long("before_units"),
            afterUnits = long("after_units"),
            inputTokens = long("input_tokens").toInt(),
            outputTokens = long("output_tokens").toInt(),
            createdAt = string("created_at"),
            updatedAt = string("updated_at"),
            sourceContentHash = string("source_content_hash"),
        )
    }

    private fun ContextCompactionRecord.args(): List<Any?> = listOf(
        id, conversationId, snapshotId, runId, encodeSourceIds(sourceMessageIds), sourceContentHash,
        inputHash, modelId, modelFingerprint, authorizationFingerprint, reason, state.name, summaryJson,
        parentId, version, beforeUnits, afterUnits, inputTokens, outputTokens, createdAt, updatedAt,
    )

    private fun encodeSourceIds(ids: List<String>): String = json.encodeToString(ids)

    private fun requireId(value: String, field: String) {
        if (!SAFE_ID.matches(value)) throw invalid("$field contains unsafe characters")
    }

    private fun requireText(value: String, field: String) {
        if (value.isBlank() || value.length > ContextCompactionLimits.MAX_FIELD_CHARS) {
            throw invalid("$field is empty or too long")
        }
    }

    private fun invalid(message: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "context-compaction",
        operationId = "context-compaction-write",
        sanitizedDetails = message,
    ).asException()

    companion object {
        private val terminalRuns = setOf(RunStatus.COMPLETED, RunStatus.CANCELLED, RunStatus.FAILED,
            RunStatus.BUDGET_EXHAUSTED, RunStatus.UNKNOWN_OUTCOME)
        private const val COMPLETE_STATUS = "COMPLETE"
        private const val INSERT_SQL =
            "INSERT INTO context_compactions(id,conversation_id,snapshot_id,run_id,source_message_ids_json,source_content_hash,input_hash,model_id,model_fingerprint,authorization_fingerprint,reason,state,summary_json,parent_id,version,before_units,after_units,input_tokens,output_tokens,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    }
}
