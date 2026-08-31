// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime

/** Typed state transitions for the process-local command approval lifecycle. */
enum class ApprovalLifecycleTransition {
    REQUESTED,
    APPROVED,
    DENIED,
    EXPIRED,
    INVALIDATED,
    CONSUMED,
}

/**
 * Redacted approval lifecycle record.  This is intentionally a summary-only
 * contract: identity, hashes, enums, revisions, timestamps, and a stable
 * error code are allowed; command/cwd/output text is not represented.
 */
data class ApprovalLifecycleEvent(
    val approvalId: String,
    val requestId: String,
    val agentId: String,
    val skillId: String? = null,
    /** Opaque session reference; it is never a command cwd or output field. */
    val sessionIdentity: String? = null,
    val transition: ApprovalLifecycleTransition,
    val bindingSha256: String,
    val capability: CapabilityId,
    val authority: Authority,
    val dangerousMode: DangerousMode,
    val grantLifetime: GrantLifetime? = null,
    val grantRevision: Long = 0L,
    val policyVersion: Long = 0L,
    val timestampMs: Long,
    val reasonCode: ToolErrorCode? = null,
) {
    init {
        require(approvalId.isNotBlank() && approvalId.length <= 256)
        require(requestId.isNotBlank() && requestId.length <= 256)
        require(agentId.matches(SAFE_ID))
        require(skillId == null || skillId.matches(SAFE_ID))
        require(sessionIdentity == null || sessionIdentity.matches(SAFE_ID))
        require(bindingSha256.matches(SHA256_HEX))
        require(grantRevision >= 0L && policyVersion >= 0L)
        require(timestampMs >= 0L)
    }

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]{0,255}")
    }
}

/**
 * Lifecycle sinks are synchronous by design so the approval engine can emit
 * a transition atomically with its in-memory state change.  Implementations
 * must not block on unbounded I/O; returning false reports that the event was
 * not durably accepted while the engine still keeps the transition one-shot.
 */
fun interface ApprovalLifecycleSink {
    fun record(event: ApprovalLifecycleEvent): Boolean
}

/** Audit phase for the shell dispatch lifecycle. */
enum class ShellAuditPhase {
    STARTED,
    COMPLETED,
}

/**
 * Redacted audit data.  The contract stores hashes and counters, never the
 * command, cwd, stdout, stderr, secret, URI, or host path itself.
 */
data class ShellAuditEvent(
    val phase: ShellAuditPhase,
    val requestId: String,
    val callId: String,
    val agentId: String,
    val skillId: String?,
    val authority: Authority?,
    val dangerousMode: DangerousMode,
    val commandSha256: String,
    val cwdSha256: String?,
    val exitCode: Int? = null,
    val status: ShellExecutionStatus? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val outputBytes: Long = 0,
    val durationMs: Long = 0,
    /** Approval identity used to correlate STARTED with the terminal event. */
    val approvalId: String? = null,
) {
    init {
        require(requestId.isNotBlank())
        require(callId.isNotBlank())
        require(agentId.length <= 256)
        require(skillId == null || skillId.length <= 256)
        require(approvalId == null || (approvalId.isNotBlank() && approvalId.length <= 256))
        require(commandSha256.matches(SHA256_HEX))
        require(cwdSha256 == null || cwdSha256.matches(SHA256_HEX))
        require(outputBytes >= 0)
        require(durationMs >= 0)
    }

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** The audit sink is the last gate before a high-privilege dispatch. */
interface ShellAuditSink {
    suspend fun recordStarted(event: ShellAuditEvent): Boolean

    suspend fun recordCompleted(event: ShellAuditEvent): Boolean
}

/**
 * Completion audit failure opens a process-local degraded fuse.  A future
 * high-privilege dispatch must fail closed until the owner explicitly resets
 * the fuse after repairing its audit sink.
 */
class AuditDegradedFuse {
    @Volatile
    var isOpen: Boolean = false
        private set

    fun trip() {
        isOpen = true
    }

    fun reset() {
        isOpen = false
    }
}

/**
 * Adds the mandatory STARTED audit gate and completion-failure fuse to any
 * selected shell backend.  Backend selection remains outside this wrapper.
 */
class AuditedShellExecutor(
    private val delegate: ShellExecutor,
    private val audit: ShellAuditSink,
    private val fuse: AuditDegradedFuse = AuditDegradedFuse(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Maps the private transport request to Runtime's internal request id. */
    private val requestIdProvider: (ShellExecRequest) -> String = { request -> request.requestId },
    /** Resolves the approval used for this dispatch, if one exists. */
    private val approvalIdProvider: (ShellExecRequest) -> String? = { null },
) : ShellExecutor {
    val degradedFuse: AuditDegradedFuse
        get() = fuse

    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
        if (fuse.isOpen) return ShellExecResult(
            status = ShellExecutionStatus.FAILED,
            authority = request.selectedAuthority,
            requestId = request.requestId,
            error = ToolError(ToolErrorCode.AUDIT_FUSE_OPEN),
        )
        val commandHash = sha256Hex(request.command)
        val cwdHash = request.cwd?.let { cwd -> normalizeCwd(cwd)?.let(::sha256Hex) }
        val auditRequestId = requestIdProvider(request)
        val approvalId = approvalIdProvider(request)
        val started = ShellAuditEvent(
            phase = ShellAuditPhase.STARTED,
            requestId = auditRequestId,
            callId = request.callId,
            agentId = request.agentId,
            skillId = request.skillId,
            authority = request.selectedAuthority,
            dangerousMode = request.dangerousMode,
            commandSha256 = commandHash,
            cwdSha256 = cwdHash,
            approvalId = approvalId,
        )
        val startedOk = runCatching { audit.recordStarted(started) }.getOrDefault(false)
        if (!startedOk) return ShellExecResult(
            status = ShellExecutionStatus.FAILED,
            authority = request.selectedAuthority,
            requestId = request.requestId,
            error = ToolError(ToolErrorCode.AUDIT_UNAVAILABLE),
        )

        val startedAt = nowMs()
        // Once STARTED is acknowledged, cancellation/transport failure cannot
        // prove whether the provider ran the command.  Preserve that fact as
        // UNKNOWN_OUTCOME and still emit the completion audit event.
        val result = runCatching { delegate.execute(request) }.getOrElse {
            ShellExecResult.unknownOutcome(request)
        }
        val completed = ShellAuditEvent(
            phase = ShellAuditPhase.COMPLETED,
            requestId = auditRequestId,
            callId = request.callId,
            agentId = request.agentId,
            skillId = request.skillId,
            authority = request.selectedAuthority,
            dangerousMode = request.dangerousMode,
            commandSha256 = commandHash,
            cwdSha256 = cwdHash,
            exitCode = result.exitCode,
            status = result.status,
            timedOut = result.timedOut,
            cancelled = result.cancelled,
            outputBytes = result.outputBytes,
            durationMs = (nowMs() - startedAt).coerceAtLeast(0),
            approvalId = approvalId,
        )
        val completedOk = runCatching { audit.recordCompleted(completed) }.getOrDefault(false)
        if (!completedOk) {
            fuse.trip()
            // STARTED was acknowledged and the provider may have applied a
            // side effect.  Do not expose the provider's apparent success (or
            // a retryable ordinary failure) when terminal audit is missing.
            return ShellExecResult.unknownOutcome(request, (nowMs() - startedAt).coerceAtLeast(0))
        }
        return result
    }

    override suspend fun cancel(requestId: String): Boolean = delegate.cancel(requestId)
}
