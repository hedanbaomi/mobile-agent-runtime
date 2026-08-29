// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.Utc

/** Append-only, sanitized audit records. Full prompts and secrets are not required fields. */
class AuditRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun append(event: AuditEvent): AuditEvent {
        validate(event)
        val existing = db.query("SELECT * FROM audit_events WHERE id=?", listOf(event.id)).singleOrNull()
        if (existing != null) {
            val stored = existing.toEvent()
            if (stored != event) throw invalid("Audit event ${event.id} is immutable")
            return stored
        }
        db.execute(
            "INSERT INTO audit_events(id,run_id,created_at,component,action,result,error_code,summary,input_bytes,output_bytes,input_tokens,output_tokens,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                event.id, event.runId, event.createdAt, event.component, event.action, event.result, event.errorCode,
                event.summary, event.inputBytes, event.outputBytes, event.inputTokens, event.outputTokens, event.metadataJson,
            ),
        )
        return event
    }

    fun record(event: AuditEvent): AuditEvent = append(event)

    fun get(id: String): AuditEvent? = db.query("SELECT * FROM audit_events WHERE id=?", listOf(id)).singleOrNull()?.toEvent()

    fun list(runId: String? = null): List<AuditEvent> {
        val rows = if (runId == null) {
            db.query("SELECT * FROM audit_events ORDER BY created_at,id")
        } else {
            db.query("SELECT * FROM audit_events WHERE run_id=? ORDER BY created_at,id", listOf(runId))
        }
        return rows.map { it.toEvent() }
    }

    private fun validate(event: AuditEvent) {
        if (event.id.isBlank() || event.component.isBlank() || event.action.isBlank() || event.result.isBlank()) {
            throw invalid("Audit identity/action fields are required")
        }
        if (event.summary.length > 32_768) throw invalid("Audit summary is too long")
        if (event.inputBytes < 0 || event.outputBytes < 0 || event.inputTokens < 0 || event.outputTokens < 0) {
            throw invalid("Audit counters must not be negative")
        }
        val parsed = runCatching { json.parseToJsonElement(event.metadataJson) }.getOrElse { throw invalid("Audit metadata must be JSON") }
        if (parsed !is kotlinx.serialization.json.JsonObject) throw invalid("Audit metadata must be an object")
    }

    private fun SqlRow.toEvent(): AuditEvent = AuditEvent(
        id = string("id"),
        runId = string("run_id").ifBlank { null },
        createdAt = string("created_at"),
        component = string("component"),
        action = string("action"),
        result = string("result"),
        errorCode = string("error_code").ifBlank { null },
        summary = string("summary"),
        inputBytes = long("input_bytes"),
        outputBytes = long("output_bytes"),
        inputTokens = long("input_tokens").toInt(),
        outputTokens = long("output_tokens").toInt(),
        metadataJson = string("metadata_json").ifBlank { "{}" },
    )

    private fun invalid(message: String): AppException = AppError(
        code = ErrorCode.INVALID_CONFIG,
        userMessage = message,
        retryClass = RetryClass.USER_ACTION,
        stage = "audit-persistence",
        operationId = "audit-write",
        sanitizedDetails = message,
    ).asException()
}
