/*
 * SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package runtime.mobileagent.skills.tooling

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode

/** One-shot shell limits; adapters may only reduce these values. */
data class ShellLimits(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(timeoutMs > 0)
        require(maxOutputBytes > 0)
    }

    fun clamped(
        maxTimeoutMs: Long = MAX_TIMEOUT_MS,
        maxOutputBytes: Long = MAX_OUTPUT_BYTES,
    ): ShellLimits = ShellLimits(
        timeoutMs = timeoutMs.coerceIn(1, maxTimeoutMs),
        maxOutputBytes = this.maxOutputBytes.coerceIn(1, maxOutputBytes),
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        /** Raw output is kept well below the 1 MiB serialized ToolResult budget. */
        const val DEFAULT_MAX_OUTPUT_BYTES = 64L * 1024L
        const val MAX_TIMEOUT_MS = 5L * 60L * 1000L
        const val MAX_OUTPUT_BYTES = 128L * 1024L
        const val MAX_SERIALIZED_RESULT_BYTES = 1L * 1024L * 1024L
    }
}

/** Trusted Runtime-only skill context; normal model shell requests carry none. */
class TrustedSkillInvocation internal constructor(
    val skillId: String,
    val skillRevision: Long,
) {
    init {
        require(skillId.isNotBlank())
        require(skillRevision > 0)
    }
}

/**
 * Runtime-owned request envelope.  The internal constructor prevents a model
 * JSON decoder in another module from supplying requestId, authority, policy,
 * or the trusted skill marker.  Use [fromRuntime] for normal shell dispatch;
 * Runtime creates the internal UUID and associates the model callId only.
 */
class ShellExecRequest internal constructor(
    val callId: String,
    val command: String,
    val cwd: String? = null,
    val timeoutMs: Long = ShellLimits.DEFAULT_TIMEOUT_MS,
    val maxOutputBytes: Long = ShellLimits.DEFAULT_MAX_OUTPUT_BYTES,
    val requestId: String,
    val agentId: String,
    val snapshotId: String,
    val skillId: String? = null,
    val skillRevision: Long? = null,
    val selectedAuthority: Authority,
    val dangerousMode: DangerousMode = DangerousMode.DISABLED,
    val toolSchemaVersion: Int = 1,
    val policyVersion: Long,
    val configSnapshotHash: String,
    val sessionIdentity: String,
    /** Only Runtime's trusted skill factory may set this context. */
    val trustedSkillEnvelope: Boolean = skillId != null,
) {
    init {
        require(callId.isNotBlank())
        require(command.isNotBlank())
        require(command.length <= MAX_COMMAND_LENGTH)
        require(!command.contains('\u0000'))
        require(cwd == null || (cwd.isNotBlank() && cwd.length <= MAX_CWD_LENGTH && !cwd.contains('\u0000')))
        require(requestId.isNotBlank())
        require(agentId.isNotBlank())
        require(snapshotId.isNotBlank())
        require(skillId == null == (skillRevision == null)) { "skill id and revision must be paired" }
        require(skillId == null || trustedSkillEnvelope) {
            "skillId requires a Runtime trusted invocation envelope"
        }
        require(selectedAuthority != Authority.NONE)
        require(toolSchemaVersion > 0)
        require(policyVersion >= 0)
        require(configSnapshotHash.isNotBlank())
        require(sessionIdentity.isNotBlank())
    }

    val limits: ShellLimits
        get() = ShellLimits(timeoutMs, maxOutputBytes)

    /** Naming alias used by Runtime dispatch code; never the model call id. */
    val invocationId: String
        get() = requestId

    fun clamped(
        maxTimeoutMs: Long = ShellLimits.MAX_TIMEOUT_MS,
        maxOutputBytes: Long = ShellLimits.MAX_OUTPUT_BYTES,
    ): ShellExecRequest {
        val limits = this.limits.clamped(maxTimeoutMs, maxOutputBytes)
        return internalCopy(timeoutMs = limits.timeoutMs, maxOutputBytes = limits.maxOutputBytes)
    }

    /** Never include command, cwd, or credentials in logs produced by toString. */
    override fun toString(): String =
        "ShellExecRequest(callId=$callId, requestId=$requestId, agentId=$agentId, snapshotId=$snapshotId, skillId=$skillId, authority=$selectedAuthority)"

    private fun internalCopy(timeoutMs: Long = this.timeoutMs, maxOutputBytes: Long = this.maxOutputBytes): ShellExecRequest =
        ShellExecRequest(
            callId = callId,
            command = command,
            cwd = cwd,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes,
            requestId = requestId,
            agentId = agentId,
            snapshotId = snapshotId,
            skillId = skillId,
            skillRevision = skillRevision,
            selectedAuthority = selectedAuthority,
            dangerousMode = dangerousMode,
            toolSchemaVersion = toolSchemaVersion,
            policyVersion = policyVersion,
            configSnapshotHash = configSnapshotHash,
            sessionIdentity = sessionIdentity,
            trustedSkillEnvelope = trustedSkillEnvelope,
        )

    companion object {
        const val MAX_COMMAND_LENGTH = 256 * 1024
        const val MAX_CWD_LENGTH = 4096

        /** Runtime-owned construction point for a fresh internal UUID. */
        fun fromRuntime(
            callId: String,
            command: String,
            cwd: String? = null,
            limits: ShellLimits = ShellLimits(),
            agentId: String,
            snapshotId: String,
            selectedAuthority: Authority,
            dangerousMode: DangerousMode,
            policyVersion: Long,
            configSnapshotHash: String,
            sessionIdentity: String,
            skill: TrustedSkillInvocation? = null,
            toolSchemaVersion: Int = 1,
        ): ShellExecRequest = ShellExecRequest(
            callId = callId,
            command = command,
            cwd = cwd,
            timeoutMs = limits.clamped().timeoutMs,
            maxOutputBytes = limits.clamped().maxOutputBytes,
            requestId = InternalRequestIds.new(),
            agentId = agentId,
            snapshotId = snapshotId,
            skillId = skill?.skillId,
            skillRevision = skill?.skillRevision,
            selectedAuthority = selectedAuthority,
            dangerousMode = dangerousMode,
            toolSchemaVersion = toolSchemaVersion,
            policyVersion = policyVersion,
            configSnapshotHash = configSnapshotHash,
            sessionIdentity = sessionIdentity,
            trustedSkillEnvelope = skill != null,
        )

        /** Compatibility seam for an adapter still carrying a textual domain policy version. */
        fun fromRuntime(
            callId: String,
            command: String,
            cwd: String? = null,
            limits: ShellLimits = ShellLimits(),
            agentId: String,
            snapshotId: String,
            selectedAuthority: Authority,
            dangerousMode: DangerousMode,
            skillId: String? = null,
            toolSchemaVersion: Int = 1,
            policyVersion: String,
            configSnapshotHash: String,
            sessionIdentity: String,
            trustedSkillEnvelope: Boolean = false,
            skillRevision: Long? = null,
        ): ShellExecRequest {
            require(skillId == null == (skillRevision == null)) {
                "skill id and revision must be paired"
            }
            require(skillId == null || trustedSkillEnvelope) {
                "skillId requires a Runtime trusted invocation envelope"
            }
            require(!trustedSkillEnvelope || (skillId != null && skillRevision != null && skillRevision > 0)) {
                "trusted skill envelope requires a positive skill id and revision"
            }
            require(skillRevision == null || skillRevision > 0) {
                "skill revision must be positive"
            }
            val skill = skillId?.let { TrustedSkillInvocation(it, skillRevision!!) }
            return fromRuntime(
                callId = callId,
                command = command,
                cwd = cwd,
                limits = limits,
                agentId = agentId,
                snapshotId = snapshotId,
                selectedAuthority = selectedAuthority,
                dangerousMode = dangerousMode,
                policyVersion = policyVersion.toLongOrNull() ?: throw IllegalArgumentException("policy version is invalid"),
                configSnapshotHash = configSnapshotHash,
                sessionIdentity = sessionIdentity,
                skill = skill,
                toolSchemaVersion = toolSchemaVersion,
            )
        }

        fun fromTrustedSkill(
            callId: String,
            command: String,
            skill: TrustedSkillInvocation,
            cwd: String? = null,
            limits: ShellLimits = ShellLimits(),
            agentId: String,
            snapshotId: String,
            selectedAuthority: Authority,
            dangerousMode: DangerousMode,
            policyVersion: Long,
            configSnapshotHash: String,
            sessionIdentity: String,
            toolSchemaVersion: Int = 1,
        ): ShellExecRequest = fromRuntime(
            callId = callId,
            command = command,
            cwd = cwd,
            limits = limits,
            agentId = agentId,
            snapshotId = snapshotId,
            selectedAuthority = selectedAuthority,
            dangerousMode = dangerousMode,
            policyVersion = policyVersion,
            configSnapshotHash = configSnapshotHash,
            sessionIdentity = sessionIdentity,
            skill = skill,
            toolSchemaVersion = toolSchemaVersion,
        )
    }
}

typealias ShellRequest = ShellExecRequest

enum class ShellExecutionStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN_OUTCOME,
}

/** Structured one-shot result; raw output is bounded before construction. */
data class ShellExecResult(
    val status: ShellExecutionStatus,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = status == ShellExecutionStatus.TIMED_OUT,
    val cancelled: Boolean = status == ShellExecutionStatus.CANCELLED,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val authority: Authority? = null,
    val durationMs: Long = 0,
    val requestId: String? = null,
    val error: ToolError? = null,
) {
    init {
        require(durationMs >= 0)
        require(!(timedOut && status != ShellExecutionStatus.TIMED_OUT && status != ShellExecutionStatus.UNKNOWN_OUTCOME))
        require(!(cancelled && status != ShellExecutionStatus.CANCELLED && status != ShellExecutionStatus.UNKNOWN_OUTCOME))
        require(outputBytes <= ShellLimits.MAX_OUTPUT_BYTES) { "Combined shell output exceeds the result budget" }
        require(estimatedSerializedBytes() < ShellLimits.MAX_SERIALIZED_RESULT_BYTES) {
            "Serialized shell result exceeds the AgentRuntime result budget"
        }
        if (status == ShellExecutionStatus.UNKNOWN_OUTCOME) {
            require(error?.code == ToolErrorCode.UNKNOWN_OUTCOME || error == null)
        }
    }

    val success: Boolean
        get() = status == ShellExecutionStatus.SUCCEEDED && exitCode == 0

    val outputBytes: Long
        get() = utf8Bytes(stdout) + utf8Bytes(stderr)

    /** Conservative bound accounting for JSON escaping and fixed envelope fields. */
    fun estimatedSerializedBytes(): Long = 4096L + escapedUpperBound(stdout) + escapedUpperBound(stderr)

    /**
     * Shell results are never safe for an automatic replay.
     *
     * A timeout, cancellation, or failure may have happened after the
     * backend performed a side effect, and UNKNOWN_OUTCOME explicitly means
     * that the runtime cannot establish what happened.  An operator may make
     * a new, separately authorized invocation, but the runtime must not
     * replay this request from its result.
     */
    val automaticReplayAllowed: Boolean
        get() = false

    /** Do not leak stdout/stderr (which may contain paths or secrets) in logs. */
    override fun toString(): String =
        "ShellExecResult(status=$status, exitCode=$exitCode, outputBytes=$outputBytes, authority=$authority, requestId=$requestId, error=${error?.code})"

    companion object {
        fun succeeded(request: ShellExecRequest, exitCode: Int, stdout: String, stderr: String, durationMs: Long): ShellExecResult =
            bounded(request, ShellExecutionStatus.SUCCEEDED, exitCode, stdout, stderr, durationMs)

        fun failed(request: ShellExecRequest, message: ToolErrorCode = ToolErrorCode.SHELL_EXECUTION_FAILED): ShellExecResult =
            ShellExecResult(
                status = ShellExecutionStatus.FAILED,
                authority = request.selectedAuthority,
                requestId = request.requestId,
                error = ToolError(message),
            )

        fun timedOut(request: ShellExecRequest, durationMs: Long): ShellExecResult = ShellExecResult(
            status = ShellExecutionStatus.TIMED_OUT,
            timedOut = true,
            authority = request.selectedAuthority,
            durationMs = durationMs,
            requestId = request.requestId,
            error = ToolError(ToolErrorCode.SHELL_TIMED_OUT),
        )

        fun cancelled(request: ShellExecRequest, durationMs: Long): ShellExecResult = ShellExecResult(
            status = ShellExecutionStatus.CANCELLED,
            cancelled = true,
            authority = request.selectedAuthority,
            durationMs = durationMs,
            requestId = request.requestId,
            error = ToolError(ToolErrorCode.SHELL_CANCELLED),
        )

        fun unknownOutcome(request: ShellExecRequest, durationMs: Long = 0): ShellExecResult = ShellExecResult(
            status = ShellExecutionStatus.UNKNOWN_OUTCOME,
            authority = request.selectedAuthority,
            durationMs = durationMs,
            requestId = request.requestId,
            error = ToolError.unknownOutcome(),
        )

        private fun bounded(
            request: ShellExecRequest,
            status: ShellExecutionStatus,
            exitCode: Int?,
            stdout: String,
            stderr: String,
            durationMs: Long,
        ): ShellExecResult {
            val limitedOut = ShellOutputLimiter.limit(stdout, request.maxOutputBytes)
            val outputRemaining = request.maxOutputBytes - limitedOut.bytes
            val limitedErr = if (outputRemaining > 0) {
                ShellOutputLimiter.limit(stderr, outputRemaining)
            } else {
                LimitedShellOutput("", stderr.isNotEmpty(), 0)
            }
            return ShellExecResult(
                status = status,
                exitCode = exitCode,
                stdout = limitedOut.text,
                stderr = limitedErr.text,
                stdoutTruncated = limitedOut.truncated,
                stderrTruncated = limitedErr.truncated,
                authority = request.selectedAuthority,
                durationMs = durationMs,
                requestId = request.requestId,
                error = if (limitedOut.truncated || limitedErr.truncated) ToolError(ToolErrorCode.SHELL_OUTPUT_TRUNCATED) else null,
            )
        }

        private fun utf8Bytes(value: String): Long = value.toByteArray(StandardCharsets.UTF_8).size.toLong()

        private fun escapedUpperBound(value: String): Long = value.fold(0L) { total, char ->
            total + when (char) {
                '\\', '"' -> 2L
                '\b', '\t', '\n', '\u000C', '\r' -> 2L
                in '\u0000'..'\u001F' -> 6L
                else -> if (char.code > 0x7F) 6L else 1L
            }
        }
    }
}

typealias ShellResult = ShellExecResult

data class LimitedShellOutput(val text: String, val truncated: Boolean, val bytes: Long)

/** UTF-8 byte limiter shared by shell adapters and result serialization. */
object ShellOutputLimiter {
    fun limit(text: String, maxBytes: Long): LimitedShellOutput {
        require(maxBytes > 0)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size.toLong() <= maxBytes) return LimitedShellOutput(text, false, bytes.size.toLong())
        var end = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
        val prefix = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(java.nio.ByteBuffer.wrap(bytes, 0, end))
            .toString()
        return LimitedShellOutput(prefix, true, prefix.toByteArray(StandardCharsets.UTF_8).size.toLong())
    }

    fun totalOutputWithinLimit(stdout: String, stderr: String, maxBytes: Long = ShellLimits.MAX_OUTPUT_BYTES): Boolean =
        stdout.toByteArray(StandardCharsets.UTF_8).size.toLong() + stderr.toByteArray(StandardCharsets.UTF_8).size <= maxBytes
}

/** One invocation can be executed once; cancellation is keyed by internal requestId. */
interface ShellExecutor {
    suspend fun execute(request: ShellExecRequest): ShellExecResult

    /** Returns true only when a currently active one-shot was signalled. */
    suspend fun cancel(requestId: String): Boolean
}

/**
 * Optional adapter wrapper that enforces the one-shot request invariant for a
 * backend implementation.  A repeated requestId is rejected without a second
 * provider dispatch; an exception after dispatch is represented as unknown by
 * the caller and is never silently retried.
 */
class OneShotShellExecutor(
    private val delegate: ShellExecutor,
) : ShellExecutor {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
        if (!seen.add(request.requestId)) {
            return ShellExecResult(
                status = ShellExecutionStatus.FAILED,
                authority = request.selectedAuthority,
                requestId = request.requestId,
                error = ToolError(ToolErrorCode.CALL_ID_REPLAY),
            )
        }
        return runCatching { delegate.execute(request) }.getOrElse {
            ShellExecResult.unknownOutcome(request)
        }
    }

    override suspend fun cancel(requestId: String): Boolean = delegate.cancel(requestId)
}

/** Runtime-owned source for non-model request IDs. */
object InternalRequestIds {
    fun new(): String = UUID.randomUUID().toString()
}
