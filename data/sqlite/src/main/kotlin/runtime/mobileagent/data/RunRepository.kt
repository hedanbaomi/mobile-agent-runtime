// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc

/** Durable Run and ToolInvocation state, including explicit crash recovery to UNKNOWN_OUTCOME. */
class RunRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun create(record: RunRecord): RunRecord {
        validate(record)
        if (db.query("SELECT run_id FROM runs WHERE run_id=?", listOf(record.runId)).isNotEmpty()) {
            throw invalid("Run ${record.runId} already exists")
        }
        requireReferences(record)
        db.execute(
            "INSERT INTO runs(run_id,snapshot_id,conversation_id,state,budget_json,stop_reason,error_code,model_rounds,tool_calls,input_tokens,output_tokens,started_at,finished_at,created_at,updated_at,retry_acknowledged_at,manifest_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            record.args(),
        )
        return record
    }

    fun save(record: RunRecord): RunRecord {
        validate(record)
        val existing = get(record.runId)
        return if (existing == null) {
            create(record)
        } else {
            if (existing.snapshotId != record.snapshotId || existing.conversationId != record.conversationId) {
                throw invalid("Run identity cannot be changed")
            }
            if (existing.state == RunStatus.UNKNOWN_OUTCOME && record.state != RunStatus.UNKNOWN_OUTCOME) {
                throw invalid(
                    "Run ${record.runId} has UNKNOWN_OUTCOME and cannot be overwritten; acknowledge it and create a new run",
                )
            }
            db.execute(
                "UPDATE runs SET state=?,budget_json=?,stop_reason=?,error_code=?,model_rounds=?,tool_calls=?,input_tokens=?,output_tokens=?,started_at=?,finished_at=?,updated_at=?,retry_acknowledged_at=?,manifest_json=? WHERE run_id=?",
                listOf(
                    record.state.name, record.budgetJson, record.stopReason, record.errorCode, record.modelRounds,
                    record.toolCalls, record.inputTokens, record.outputTokens, record.startedAt, record.finishedAt,
                    record.updatedAt, record.retryAcknowledgedAt, record.manifestJson, record.runId,
                ),
            )
            record
        }
    }

    fun upsert(record: RunRecord): RunRecord = save(record)

    fun get(runId: String): RunRecord? =
        db.query("SELECT * FROM runs WHERE run_id=?", listOf(runId)).singleOrNull()?.toRun()

    fun list(conversationId: String? = null): List<RunRecord> {
        val rows = if (conversationId == null) {
            db.query("SELECT * FROM runs ORDER BY created_at,run_id")
        } else {
            db.query("SELECT * FROM runs WHERE conversation_id=? ORDER BY created_at,run_id", listOf(conversationId))
        }
        return rows.map { it.toRun() }
    }

    /**
     * Converts every non-terminal run and invocation to UNKNOWN_OUTCOME after process death. This
     * is an explicit state transition; no model or tool is replayed by this method.
     */
    fun markInFlightUnknown(at: String = clock()): List<String> = db.transaction {
        val rows = db.query(
            "SELECT run_id FROM runs WHERE state NOT IN (?,?,?,?,?)",
            listOf(
                RunStatus.COMPLETED.name,
                RunStatus.CANCELLED.name,
                RunStatus.FAILED.name,
                RunStatus.BUDGET_EXHAUSTED.name,
                RunStatus.UNKNOWN_OUTCOME.name,
            ),
        )
        val ids = rows.map { it.string("run_id") }
        if (ids.isNotEmpty()) {
            db.execute(
                "UPDATE runs SET state=?,stop_reason=?,error_code=?,finished_at=?,updated_at=? WHERE state NOT IN (?,?,?,?,?)",
                listOf(
                    RunStatus.UNKNOWN_OUTCOME.name,
                    "Process ended before a terminal provider/tool outcome was observed",
                    ErrorCode.UNKNOWN_OUTCOME.name,
                    at,
                    at,
                    RunStatus.COMPLETED.name,
                    RunStatus.CANCELLED.name,
                    RunStatus.FAILED.name,
                    RunStatus.BUDGET_EXHAUSTED.name,
                    RunStatus.UNKNOWN_OUTCOME.name,
                ),
            )
            db.execute(
                "UPDATE tool_invocations SET state=?,error_code=?,updated_at=? WHERE run_id IN (SELECT run_id FROM runs WHERE state=?) AND state NOT IN (?, ?, ?, ?)",
                listOf(
                    "UNKNOWN_OUTCOME", ErrorCode.UNKNOWN_OUTCOME.name, at, RunStatus.UNKNOWN_OUTCOME.name,
                    "SUCCEEDED", "FAILED", "CANCELLED",
                    "UNKNOWN_OUTCOME",
                ),
            )
            db.execute(
                "UPDATE messages SET status=? WHERE conversation_id IN (SELECT conversation_id FROM runs WHERE state=?) AND status=?",
                listOf("UNKNOWN_OUTCOME", RunStatus.UNKNOWN_OUTCOME.name, "STREAMING"),
            )
        }
        ids
    }

    fun recoverInterruptedRuns(at: String = clock()): List<String> = markInFlightUnknown(at)

    /** User acknowledgement is required before a caller may create a new retry run. */
    fun acknowledgeUnknown(runId: String, acknowledgeDuplicateCharge: Boolean, at: String = clock()): RunRecord {
        if (!acknowledgeDuplicateCharge) throw invalid("Retrying an unknown outcome requires duplicate-charge acknowledgement")
        val run = get(runId) ?: throw invalid("Run $runId does not exist")
        if (run.state != RunStatus.UNKNOWN_OUTCOME) throw invalid("Run $runId is not an unknown outcome")
        // Keep the original execution outcome auditable while persisting the explicit retry gate.
        val acknowledged = run.copy(
            stopReason = "Retry acknowledged at $at; create a new run to replay explicitly",
            updatedAt = at,
            retryAcknowledgedAt = at,
        )
        db.execute(
            "UPDATE runs SET stop_reason=?,retry_acknowledged_at=?,updated_at=? WHERE run_id=?",
            listOf(acknowledged.stopReason, acknowledged.retryAcknowledgedAt, acknowledged.updatedAt, runId),
        )
        return acknowledged
    }

    fun addInvocation(invocation: ToolInvocation): ToolInvocation {
        validate(invocation)
        if (get(invocation.runId) == null) throw invalid("Run ${invocation.runId} does not exist")
        val existing = db.query("SELECT * FROM tool_invocations WHERE invocation_id=?", listOf(invocation.invocationId)).singleOrNull()
        if (existing != null) {
            val stored = existing.toInvocation()
            if (stored != invocation) throw invalid("Tool invocation ${invocation.invocationId} is immutable")
            return stored
        }
        db.execute(
            "INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,result_json,error_code,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                invocation.invocationId, invocation.runId, invocation.callId, invocation.name, invocation.argumentsJson,
                invocation.permissionDecision, invocation.state, invocation.resultJson, invocation.errorCode,
                invocation.createdAt, invocation.updatedAt,
            ),
        )
        return invocation
    }

    fun recordInvocation(invocation: ToolInvocation): ToolInvocation = addInvocation(invocation)

    fun updateInvocation(invocation: ToolInvocation): ToolInvocation {
        validate(invocation)
        val existing = db.query("SELECT * FROM tool_invocations WHERE invocation_id=?", listOf(invocation.invocationId)).singleOrNull()
            ?: throw invalid("Tool invocation ${invocation.invocationId} does not exist")
        if (existing.string("run_id") != invocation.runId || existing.string("call_id") != invocation.callId) {
            throw invalid("Tool invocation identity cannot be changed")
        }
        db.execute(
            "UPDATE tool_invocations SET name=?,arguments_json=?,permission_decision=?,state=?,result_json=?,error_code=?,updated_at=? WHERE invocation_id=?",
            listOf(invocation.name, invocation.argumentsJson, invocation.permissionDecision, invocation.state, invocation.resultJson, invocation.errorCode, invocation.updatedAt, invocation.invocationId),
        )
        return invocation
    }

    fun invocations(runId: String): List<ToolInvocation> =
        db.query("SELECT * FROM tool_invocations WHERE run_id=? ORDER BY created_at,invocation_id", listOf(runId))
            .map { it.toInvocation() }

    private fun requireReferences(record: RunRecord) {
        if (db.query("SELECT id FROM agent_snapshots WHERE id=?", listOf(record.snapshotId)).isEmpty()) {
            throw invalid("Agent snapshot ${record.snapshotId} does not exist")
        }
        if (db.query("SELECT id FROM conversations WHERE id=?", listOf(record.conversationId)).isEmpty()) {
            throw invalid("Conversation ${record.conversationId} does not exist")
        }
    }

    private fun validate(record: RunRecord) {
        requireId(record.runId, "runId")
        requireId(record.snapshotId, "snapshotId")
        requireId(record.conversationId, "conversationId")
        if (record.modelRounds < 0 || record.toolCalls < 0 || record.inputTokens < 0 || record.outputTokens < 0) {
            throw invalid("Run counters must not be negative")
        }
        val budget = runCatching { json.parseToJsonElement(record.budgetJson) }.getOrElse { throw invalid("Run budget must be JSON") }
        if (budget !is kotlinx.serialization.json.JsonObject) throw invalid("Run budget must be a JSON object")
        val manifest = runCatching { json.parseToJsonElement(record.manifestJson) }.getOrElse { throw invalid("Run manifest must be JSON") }
        if (manifest !is kotlinx.serialization.json.JsonObject) throw invalid("Run manifest must be a JSON object")
        if (record.manifestJson.toByteArray(Charsets.UTF_8).size > MAX_JSON) throw invalid("Run manifest is too large")
    }

    private fun validate(invocation: ToolInvocation) {
        requireId(invocation.invocationId, "invocationId")
        requireId(invocation.runId, "runId")
        requireId(invocation.callId, "callId")
        if (invocation.name.isBlank() || invocation.name.length > 256) throw invalid("Tool name is empty or too long")
        if (invocation.argumentsJson.length > MAX_JSON) throw invalid("Tool arguments are too large")
        val arguments = runCatching { json.parseToJsonElement(invocation.argumentsJson) }
            .getOrElse { throw invalid("Tool arguments must be JSON") }
        if (arguments !is kotlinx.serialization.json.JsonObject) throw invalid("Tool arguments must be a JSON object")
    }

    private fun SqlRow.toRun(): RunRecord = RunRecord(
        runId = string("run_id"),
        snapshotId = string("snapshot_id"),
        conversationId = string("conversation_id"),
        state = runCatching { RunStatus.valueOf(string("state")) }.getOrElse { throw invalid("Persisted run state is invalid") },
        budgetJson = string("budget_json").ifBlank { "{}" },
        stopReason = string("stop_reason").ifBlank { null },
        errorCode = string("error_code").ifBlank { null },
        modelRounds = long("model_rounds").toInt(),
        toolCalls = long("tool_calls").toInt(),
        inputTokens = long("input_tokens").toInt(),
        outputTokens = long("output_tokens").toInt(),
        startedAt = string("started_at").ifBlank { null },
        finishedAt = string("finished_at").ifBlank { null },
        createdAt = string("created_at"),
        updatedAt = string("updated_at"),
        retryAcknowledgedAt = string("retry_acknowledged_at").ifBlank { null },
        manifestJson = string("manifest_json").ifBlank { "{}" },
    )

    private fun SqlRow.toInvocation(): ToolInvocation = ToolInvocation(
        invocationId = string("invocation_id"),
        runId = string("run_id"),
        callId = string("call_id"),
        name = string("name"),
        argumentsJson = string("arguments_json").ifBlank { "{}" },
        permissionDecision = string("permission_decision"),
        state = string("state"),
        resultJson = string("result_json").ifBlank { null },
        errorCode = string("error_code").ifBlank { null },
        createdAt = string("created_at"),
        updatedAt = string("updated_at"),
    )

    private fun RunRecord.args(): List<Any?> = listOf(
        runId, snapshotId, conversationId, state.name, budgetJson, stopReason, errorCode, modelRounds,
        toolCalls, inputTokens, outputTokens, startedAt, finishedAt, createdAt, updatedAt, retryAcknowledgedAt,
        manifestJson,
    )

    private fun requireId(value: String, field: String) {
        if (value.isBlank() || value.length > 256 || !SAFE_ID.matches(value)) throw invalid("$field contains unsafe characters")
    }

    private fun invalid(message: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "run-persistence",
        operationId = "run-write",
        sanitizedDetails = message,
    ).asException()

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        private const val MAX_JSON = 1_000_000
    }
}
