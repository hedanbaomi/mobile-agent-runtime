// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.util.Locale
import java.util.UUID
import runtime.mobileagent.data.AuditRepository
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.DiagnosticApprovalState
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticOperation
import runtime.mobileagent.diagnostics.DiagnosticOperationState
import runtime.mobileagent.diagnostics.DiagnosticTerminalState
import runtime.mobileagent.diagnostics.DiagnosticWorkspaceBackendType
import runtime.mobileagent.diagnostics.ShellExecutionStateRecord
import runtime.mobileagent.diagnostics.WorkspaceOperationStateRecord
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.ToolAuditDetail
import runtime.mobileagent.skills.tooling.ApprovalLifecycleEvent
import runtime.mobileagent.skills.tooling.ApprovalLifecycleSink
import runtime.mobileagent.skills.tooling.ApprovalLifecycleTransition
import runtime.mobileagent.skills.tooling.ShellAuditSink
import runtime.mobileagent.tooling.WorkspaceAuditEvent
import runtime.mobileagent.tooling.WorkspaceAuditBackendType
import runtime.mobileagent.tooling.WorkspaceAuditSink

/** Redacted, append-only audit adapter shared by workspace and shell executors. */
internal class SqliteRuntimeAuditSink(
    private val repository: AuditRepository,
    private val diagnostics: AndroidDiagnosticLogger,
) : WorkspaceAuditSink, ShellAuditSink, ApprovalLifecycleSink {
    override suspend fun record(event: WorkspaceAuditEvent): Boolean {
        val detail = runCatching {
            ToolAuditDetail.builder(
                auditId = UUID.randomUUID().toString(),
                requestId = event.requestId,
                agentId = event.agentId,
                capability = event.capability,
                result = event.resultCode ?: event.phase.name,
                createdAt = java.time.Instant.now().toString(),
            // workspace_list enumerates the authorized set and therefore has no single
            // workspace id.  The executor represents that scope as an empty string, while the
            // canonical audit domain requires an absent id rather than an invalid blank id.
            // Normalize only at this persistence boundary; concrete workspace operations keep
            // their exact opaque id.
            ).workspaceHash(event.workspaceId.takeIf { it.isNotBlank() }, event.relativePathSha256)
                .approval(event.approvalId)
                .duration(event.durationMs)
                .build()
        }.getOrNull() ?: return false
        return runCatching {
            repository.append(detail)
            diagnostics.recordWorkspaceOperationState(
                WorkspaceOperationStateRecord(
                    workspaceId = event.workspaceId,
                    operation = event.toDiagnosticOperation(),
                    state = event.toDiagnosticOperationState(),
                    count = if (event.phase == runtime.mobileagent.tooling.WorkspaceAuditPhase.STARTED) 0 else 1,
                    requestRef = event.requestId,
                    errorCode = event.resultCode?.lowercase(Locale.ROOT) ?: "none",
                    backendType = event.backendType.toDiagnosticWorkspaceBackendType(),
                ),
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun recordStarted(event: runtime.mobileagent.skills.tooling.ShellAuditEvent): Boolean = appendShell(event)

    override suspend fun recordCompleted(event: runtime.mobileagent.skills.tooling.ShellAuditEvent): Boolean = appendShell(event)

    private fun appendShell(event: runtime.mobileagent.skills.tooling.ShellAuditEvent): Boolean {
        val detail = runCatching {
            ToolAuditDetail.builder(
                auditId = UUID.randomUUID().toString(),
                requestId = event.requestId,
                agentId = event.agentId,
                capability = CapabilityId(CapabilityId.SHELL_EXECUTE),
                result = event.status?.name ?: event.phase.name,
                createdAt = java.time.Instant.now().toString(),
            ).skill(event.skillId)
                .authority(event.authority ?: Authority.NONE)
                .approval(event.approvalId)
                .dangerousMode(event.dangerousMode)
                .commandHash(event.commandSha256)
                .cwdHash(event.cwdSha256)
                .exitCode(event.exitCode)
                .timeout(event.timedOut)
                .cancel(event.cancelled)
                .outputBytes(event.stdoutBytes, event.stderrBytes)
                .duration(event.durationMs)
                .build()
        }.getOrNull() ?: return false
        return runCatching {
            repository.append(detail)
            diagnostics.recordShellExecutionState(
                ShellExecutionStateRecord(
                    commandSha256 = event.commandSha256,
                    terminalState = when (event.status) {
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.SUCCEEDED -> DiagnosticTerminalState.SUCCEEDED
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.TIMED_OUT -> DiagnosticTerminalState.TIMED_OUT
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.CANCELLED -> DiagnosticTerminalState.CANCELLED
                        runtime.mobileagent.skills.tooling.ShellExecutionStatus.UNKNOWN_OUTCOME -> DiagnosticTerminalState.UNKNOWN
                        null -> DiagnosticTerminalState.RUNNING
                        else -> DiagnosticTerminalState.FAILED
                    },
                    authority = when (event.authority) {
                        Authority.SHIZUKU -> DiagnosticAuthority.SHIZUKU
                        Authority.WIRED_ADB -> DiagnosticAuthority.WIRED_ADB
                        else -> DiagnosticAuthority.NONE
                    },
                    stdoutBytes = event.stdoutBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    stderrBytes = event.stderrBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    requestRef = event.requestId,
                    callId = event.callId,
                    agentId = event.agentId,
                    skillId = event.skillId,
                ),
            )
            true
        }.getOrDefault(false)
    }

    override fun record(event: ApprovalLifecycleEvent): Boolean {
        val detail = runCatching {
            ToolAuditDetail.builder(
                auditId = UUID.randomUUID().toString(),
                requestId = event.requestId,
                agentId = event.agentId,
                capability = event.capability,
                result = "APPROVAL_${event.transition.name}",
                createdAt = java.time.Instant.ofEpochMilli(event.timestampMs).toString(),
            ).skill(event.skillId)
                .authority(event.authority)
                .approval(event.approvalId)
                .dangerousMode(event.dangerousMode)
                .policyVersion(event.policyVersion)
                .commandHash(event.bindingSha256)
                .build()
        }.getOrNull() ?: return false
        return runCatching {
            repository.append(detail)
            diagnostics.recordToolApprovalState(
                runtime.mobileagent.diagnostics.ToolApprovalStateRecord(
                    callId = event.requestId,
                    state = event.transition.toDiagnosticApprovalState(),
                    approvalId = event.approvalId,
                    requestRef = event.requestId,
                    agentId = event.agentId,
                    skillId = event.skillId,
                    reasonCode = event.reasonCode?.name ?: "unknown",
                    capability = event.capability.toDiagnosticToolCapability(),
                    authority = event.authority.toDiagnosticAuthority(),
                    sessionRef = event.sessionIdentity,
                ),
            )
            true
        }.getOrDefault(false)
    }
}

/** Map the provider-neutral workspace operation into the diagnostics vocabulary. */
internal fun WorkspaceAuditEvent.toDiagnosticOperation(): DiagnosticOperation = when (operation) {
    runtime.mobileagent.tooling.WorkspaceAuditOperation.ENUMERATE,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.LIST,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.STAT,
        -> DiagnosticOperation.ENUMERATE
    runtime.mobileagent.tooling.WorkspaceAuditOperation.READ -> DiagnosticOperation.READ
    runtime.mobileagent.tooling.WorkspaceAuditOperation.WRITE,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.MKDIR,
    runtime.mobileagent.tooling.WorkspaceAuditOperation.MOVE,
        -> DiagnosticOperation.WRITE
    runtime.mobileagent.tooling.WorkspaceAuditOperation.DELETE -> DiagnosticOperation.DELETE
}

private fun WorkspaceAuditBackendType.toDiagnosticWorkspaceBackendType(): DiagnosticWorkspaceBackendType = when (this) {
    WorkspaceAuditBackendType.INTERNAL -> DiagnosticWorkspaceBackendType.INTERNAL
    WorkspaceAuditBackendType.SAF_TREE -> DiagnosticWorkspaceBackendType.SAF_TREE
    WorkspaceAuditBackendType.SHIZUKU -> DiagnosticWorkspaceBackendType.SHIZUKU
    WorkspaceAuditBackendType.WIRED_ADB -> DiagnosticWorkspaceBackendType.WIRED_ADB
    WorkspaceAuditBackendType.UNKNOWN -> DiagnosticWorkspaceBackendType.UNKNOWN
}

/**
 * Terminal workspace diagnostics are derived from the redacted result code.
 * A terminal record with no known code remains UNKNOWN; it is never promoted
 * to success merely because the audit sink received the record.
 */
internal fun WorkspaceAuditEvent.toDiagnosticOperationState(): DiagnosticOperationState {
    if (phase == runtime.mobileagent.tooling.WorkspaceAuditPhase.STARTED) {
        return DiagnosticOperationState.STARTED
    }
    val code = resultCode?.trim()?.uppercase(Locale.ROOT)
    return when (code) {
        "SUCCEEDED", "SUCCESS", "COMPLETED", "COMPLETE" -> DiagnosticOperationState.SUCCEEDED
        "DENIED", "CAPABILITY_DENIED", "APPROVAL_REQUIRED", "APPROVAL_DENIED",
        "AUTHORITY_NOT_GRANTED", "AUTHORITY_PROVIDER_NOT_SELECTED", "AUTHORITY_TEMPORARILY_UNAVAILABLE",
        "PERMISSION_DENIED", "SHIZUKU_PERMISSION_DENIED", "SHIZUKU_SERVICE_UNAVAILABLE", "BRIDGE_NOT_PAIRED",
        "BRIDGE_DISCONNECTED", "ADB_DEVICE_UNAUTHORIZED", "ADB_DEVICE_OFFLINE", "ADB_DEVICE_DISCONNECTED",
        "ADB_APP_NOT_INSTALLED", "DANGEROUS_MODE_DISABLED", "SHELL_CAPABILITY_DENIED",
        "SHELL_HIGH_RISK_APPROVAL_REQUIRED", "WORKSPACE_NOT_FOUND", "WORKSPACE_READ_ONLY",
        "PATH_OUT_OF_SCOPE", "SYMLINK_FORBIDDEN", "ROOT_OPERATION_FORBIDDEN", "SNAPSHOT_STALE",
            -> DiagnosticOperationState.DENIED
        "CANCELLED", "CANCELED", "SHELL_CANCELLED", "REQUEST_CANCELLED" -> DiagnosticOperationState.CANCELLED
        "UNKNOWN", "UNKNOWN_OUTCOME" -> DiagnosticOperationState.UNKNOWN
        "FAILED", "ERROR", "IO_ERROR", "TIMEOUT", "FILE_TOO_LARGE", "QUOTA_EXCEEDED", "CONFLICT", "WORKSPACE_VERSION_UNSUPPORTED",
        "INVALID_REQUEST", "INVALID_CURSOR", "INVALID_PATH", "INVALID_ARGUMENT", "INVALID_UTF8",
        "INVALID_PATCH", "ENTRY_NOT_FOUND", "ENTRY_UNSUPPORTED", "UNSUPPORTED", "UNSUPPORTED_ENTRY",
        "OPERATION_UNAVAILABLE", "BRIDGE_PROTOCOL_MISMATCH", "INTERNAL_ERROR",
        "AUDIT_UNAVAILABLE", "AUDIT_FUSE_OPEN" -> DiagnosticOperationState.FAILED
        // resultCode is the redacted provider contract for terminal state.
        // Missing or newly introduced values must fail closed to UNKNOWN;
        // the typed outcome is deliberately not used as a success fallback.
        null, "" -> DiagnosticOperationState.UNKNOWN
        else -> DiagnosticOperationState.UNKNOWN
    }
}

private fun ApprovalLifecycleTransition.toDiagnosticApprovalState(): DiagnosticApprovalState = when (this) {
    ApprovalLifecycleTransition.REQUESTED -> DiagnosticApprovalState.REQUESTED
    ApprovalLifecycleTransition.APPROVED -> DiagnosticApprovalState.APPROVED
    ApprovalLifecycleTransition.DENIED -> DiagnosticApprovalState.DENIED
    ApprovalLifecycleTransition.EXPIRED -> DiagnosticApprovalState.EXPIRED
    ApprovalLifecycleTransition.INVALIDATED -> DiagnosticApprovalState.INVALIDATED
    // There is no separate terminal diagnostic state for consumption; it is
    // the successful use of an approved grant, so retain APPROVED semantics.
    ApprovalLifecycleTransition.CONSUMED -> DiagnosticApprovalState.APPROVED
}

private fun runtime.mobileagent.domain.CapabilityId.toDiagnosticToolCapability(): runtime.mobileagent.diagnostics.DiagnosticToolCapability = when {
    value == CapabilityId.SHELL_EXECUTE -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.SHELL_EXECUTE
    value == CapabilityId.MEMORY_READ || value == CapabilityId.MEMORY_SEARCH -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.MEMORY_READ
    value == CapabilityId.MEMORY_APPEND || value == CapabilityId.MEMORY_REPLACE -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.MEMORY_WRITE
    value == CapabilityId.WORKSPACE_ENUMERATE || value == CapabilityId.FILE_LIST || value == CapabilityId.FILE_STAT || value == CapabilityId.FILE_READ_TEXT ->
        runtime.mobileagent.diagnostics.DiagnosticToolCapability.WORKSPACE_READ
    value == CapabilityId.FILE_WRITE_TEXT || value == CapabilityId.FILE_CREATE_DIRECTORY || value == CapabilityId.FILE_MOVE || value == CapabilityId.FILE_DELETE ->
        runtime.mobileagent.diagnostics.DiagnosticToolCapability.WORKSPACE_WRITE
    value == "search" -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.SEARCH
    else -> runtime.mobileagent.diagnostics.DiagnosticToolCapability.UNKNOWN
}

private fun Authority.toDiagnosticAuthority(): DiagnosticAuthority = when (this) {
    Authority.NONE -> DiagnosticAuthority.NONE
    Authority.SHIZUKU -> DiagnosticAuthority.SHIZUKU
    Authority.WIRED_ADB -> DiagnosticAuthority.WIRED_ADB
}
