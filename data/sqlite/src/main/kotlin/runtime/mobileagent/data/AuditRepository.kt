// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.RetryClass
import runtime.mobileagent.domain.ToolAuditDetail
import runtime.mobileagent.domain.Utc

/** Append-only, sanitized audit records. Full prompts and secrets are not required fields. */
class AuditRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun append(event: AuditEvent): AuditEvent {
        validate(event)
        // Keep the legacy event API's transaction ownership with its caller.  The typed tool
        // detail overload below owns one transaction because it writes the event/detail pair.
        return appendInTransaction(event)
    }

    private fun appendInTransaction(event: AuditEvent): AuditEvent {
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

    /** Tool audit accepts only the typed redacted detail; command/output/path plaintext is absent. */
    fun append(detail: ToolAuditDetail): ToolAuditDetail = db.transaction {
        val event = AuditEvent(
            id = detail.auditId,
            createdAt = detail.createdAt,
            component = "tool",
            action = "tool.${detail.capability.value}",
            result = detail.result,
            summary = "Tool ${detail.capability.value} completed",
            metadataJson = "{}",
        )
        appendInTransaction(event)
        val existing = db.query("SELECT * FROM tool_audit_details WHERE audit_id = ?", listOf(detail.auditId))
            .singleOrNull()
        if (existing != null) {
            val stored = existing.toToolAuditDetail()
            if (stored != detail) throw invalid("Tool audit detail ${detail.auditId} is immutable")
            return@transaction stored
        }
        db.execute(
            "INSERT INTO tool_audit_details(audit_id,request_id,agent_id,skill_id,capability,workspace_id,relative_path_sha256,authority,approval_id,dangerous_mode,policy_version,cwd_sha256,command_sha256,exit_code,timed_out,cancelled,stdout_truncated,stderr_truncated,stdout_bytes,stderr_bytes,duration_ms,result,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                detail.auditId, detail.requestId, detail.agentId, detail.skillId, detail.capability.value,
                detail.workspaceId, detail.relativePathSha256, detail.authority.name, detail.approvalId,
                detail.dangerousMode?.name, detail.policyVersion, detail.cwdSha256, detail.commandSha256,
                detail.exitCode, bool(detail.timedOut), bool(detail.cancelled),
                bool(detail.stdoutTruncated), bool(detail.stderrTruncated), detail.stdoutBytes, detail.stderrBytes,
                detail.durationMs, detail.result, detail.createdAt,
            ),
        )
        detail
    }

    fun record(detail: ToolAuditDetail): ToolAuditDetail = append(detail)

    fun getDetail(auditId: String): ToolAuditDetail? = db.query(
        "SELECT * FROM tool_audit_details WHERE audit_id = ?", listOf(auditId),
    ).singleOrNull()?.toToolAuditDetail()

    fun listDetails(requestId: String? = null): List<ToolAuditDetail> {
        val rows = if (requestId == null) {
            db.query("SELECT * FROM tool_audit_details ORDER BY created_at,audit_id")
        } else {
            db.query("SELECT * FROM tool_audit_details WHERE request_id = ? ORDER BY created_at,audit_id", listOf(requestId))
        }
        return rows.map { it.toToolAuditDetail() }
    }

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
        val forbidden = parsed.keys.firstOrNull { key ->
            val normalized = key.lowercase()
            FORBIDDEN_METADATA_TOKENS.any { token -> normalized.contains(token) }
        }
        if (forbidden != null) throw invalid("Audit metadata field is not allowed: $forbidden")
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

    private companion object {
        val FORBIDDEN_METADATA_TOKENS = setOf(
            "command", "stdout", "stderr", "uri", "serial", "secret", "session", "endpoint", "cwd", "path",
        )

        fun bool(value: Boolean): Int = if (value) 1 else 0
    }
}

private fun SqlRow.toToolAuditDetail() = ToolAuditDetail(
    auditId = string("audit_id"),
    requestId = string("request_id"),
    agentId = string("agent_id"),
    skillId = string("skill_id").ifBlank { null },
    capability = runtime.mobileagent.domain.CapabilityId(string("capability")),
    workspaceId = string("workspace_id").ifBlank { null },
    relativePathSha256 = string("relative_path_sha256").ifBlank { null },
    authority = Authority.valueOf(string("authority")),
    approvalId = string("approval_id").ifBlank { null },
    dangerousMode = string("dangerous_mode").ifBlank { null }?.let(DangerousMode::valueOf),
    policyVersion = long("policy_version"),
    cwdSha256 = string("cwd_sha256").ifBlank { null },
    commandSha256 = string("command_sha256").ifBlank { null },
    exitCode = columns["exit_code"]?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() },
    timedOut = long("timed_out") != 0L,
    cancelled = long("cancelled") != 0L,
    stdoutTruncated = long("stdout_truncated") != 0L,
    stderrTruncated = long("stderr_truncated") != 0L,
    stdoutBytes = long("stdout_bytes"),
    stderrBytes = long("stderr_bytes"),
    durationMs = long("duration_ms"),
    result = string("result"),
    createdAt = string("created_at"),
)
