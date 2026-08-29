// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.remote

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val REMOTE_EXECUTOR_PROTOCOL_VERSION: Int = 1

@Serializable
enum class RemoteInvocationStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    UNKNOWN_OUTCOME,
}

@Serializable
enum class RemoteCancelReason {
    USER,
    BUDGET,
    TIMEOUT,
    SHUTDOWN,
}

@Serializable
data class RemoteLimits(
    val timeoutMs: Long,
    val maxOutputBytes: Long,
    val maxToolCalls: Int,
    val maxModelCalls: Int,
    val maxModelTokens: Int,
)

@Serializable
data class RemoteUsage(
    val durationMs: Long,
    val outputBytes: Long,
    val toolCalls: Int,
    val modelCalls: Int,
    val modelTokens: Int,
)

@Serializable
data class RemoteArtifactDescriptor(
    val id: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class RemoteError(
    val code: String,
    val userMessage: String,
    val retryClass: String,
    val stage: String,
    val operationId: String,
    val sanitizedDetails: String = "",
)

@Serializable
data class RemoteCapabilities(
    val protocolVersion: Int,
    val executorId: String,
    val runtimes: List<String>,
    val capabilities: List<String>,
    val maxInputBytes: Long,
    val maxOutputBytes: Long,
    val maxTimeoutMs: Long,
    val supportsCancel: Boolean,
)

@Serializable
data class RemoteInvocationRequest(
    val protocolVersion: Int,
    val invocationId: String,
    val runId: String,
    val grantId: String,
    val executorId: String,
    val skillId: String,
    val skillVersion: String,
    val packageSha256: String,
    val inputSchemaVersion: Int,
    val arguments: JsonObject,
    val approvedCapabilities: List<String>,
    val deadlineAt: String,
    val limits: RemoteLimits,
)

@Serializable
data class RemoteInvocationResult(
    val protocolVersion: Int,
    val invocationId: String,
    val status: RemoteInvocationStatus,
    val result: JsonObject? = null,
    val artifactDescriptors: List<RemoteArtifactDescriptor> = emptyList(),
    val error: RemoteError? = null,
    val startedAt: String,
    val finishedAt: String,
    val usage: RemoteUsage,
)

@Serializable
data class RemoteCancelRequest(
    val protocolVersion: Int,
    val invocationId: String,
    val cancelRequestId: String,
    val reason: RemoteCancelReason,
)

@Serializable
data class RemoteCancelAck(
    val protocolVersion: Int,
    val invocationId: String,
    val cancelRequestId: String,
    val accepted: Boolean,
    val terminalStatus: RemoteInvocationStatus? = null,
)

/** User-owned transport boundary.  It deliberately has no package upload API. */
interface RemoteExecutorTransport {
    suspend fun capabilities(): RemoteCapabilities

    suspend fun invoke(request: RemoteInvocationRequest): RemoteInvocationResult

    suspend fun cancel(request: RemoteCancelRequest): RemoteCancelAck?
}

class RemoteProtocolException(message: String) : IllegalArgumentException(message)

/**
 * Strict validation for the versioned remote executor port.  This is not the
 * MCP wire schema; it is the local contract used by a user-configured remote
 * skill executor.
 */
object RemoteDtoValidator {
    private val sha256 = Regex("[0-9a-f]{64}")
    private val control = Regex(".*[\\u0000-\\u001F\\u007F].*")

    fun validateCapabilities(value: RemoteCapabilities): RemoteCapabilities {
        requireVersion(value.protocolVersion)
        bounded(value.executorId, "executorId")
        list(value.runtimes, "runtimes")
        list(value.capabilities, "capabilities")
        positive(value.maxInputBytes, "maxInputBytes")
        positive(value.maxOutputBytes, "maxOutputBytes")
        positive(value.maxTimeoutMs, "maxTimeoutMs")
        return value
    }

    fun validateRequest(value: RemoteInvocationRequest): RemoteInvocationRequest {
        requireVersion(value.protocolVersion)
        bounded(value.invocationId, "invocationId")
        bounded(value.runId, "runId")
        bounded(value.grantId, "grantId")
        bounded(value.executorId, "executorId")
        bounded(value.skillId, "skillId")
        bounded(value.skillVersion, "skillVersion")
        if (!sha256.matches(value.packageSha256)) throw invalid("packageSha256 must be lowercase SHA-256 hex")
        if (value.inputSchemaVersion <= 0) throw invalid("inputSchemaVersion must be positive")
        list(value.approvedCapabilities, "approvedCapabilities")
        parseInstant(value.deadlineAt, "deadlineAt")
        validateLimits(value.limits)
        return value
    }

    fun validateResult(value: RemoteInvocationResult): RemoteInvocationResult {
        requireVersion(value.protocolVersion)
        bounded(value.invocationId, "invocationId")
        parseInstant(value.startedAt, "startedAt")
        val finished = parseInstant(value.finishedAt, "finishedAt")
        val started = parseInstant(value.startedAt, "startedAt")
        if (finished.isBefore(started)) throw invalid("finishedAt precedes startedAt")
        validateUsage(value.usage)
        value.artifactDescriptors.forEach(::validateArtifact)
        if (value.result != null && value.artifactDescriptors.isNotEmpty()) {
            throw invalid("result and artifactDescriptors are mutually exclusive")
        }
        when (value.status) {
            RemoteInvocationStatus.SUCCEEDED -> {
                if (value.error != null) throw invalid("SUCCEEDED result cannot contain error")
                if (value.result == null && value.artifactDescriptors.isEmpty()) {
                    throw invalid("SUCCEEDED result must contain result or artifactDescriptors")
                }
            }
            RemoteInvocationStatus.FAILED -> {
                if (value.error == null) throw invalid("FAILED result must contain error")
                if (value.result != null || value.artifactDescriptors.isNotEmpty()) {
                    throw invalid("FAILED result cannot contain output")
                }
            }
            RemoteInvocationStatus.UNKNOWN_OUTCOME -> {
                if (value.error == null) throw invalid("UNKNOWN_OUTCOME must contain error")
                if (value.result != null || value.artifactDescriptors.isNotEmpty()) {
                    throw invalid("UNKNOWN_OUTCOME cannot contain output")
                }
            }
            RemoteInvocationStatus.CANCELLED,
            RemoteInvocationStatus.TIMED_OUT,
            -> if (value.result != null || value.artifactDescriptors.isNotEmpty()) {
                throw invalid("cancelled or timed out result cannot contain output")
            }
        }
        value.error?.let(::validateError)
        return value
    }

    fun validateCancel(value: RemoteCancelRequest): RemoteCancelRequest {
        requireVersion(value.protocolVersion)
        bounded(value.invocationId, "invocationId")
        bounded(value.cancelRequestId, "cancelRequestId")
        return value
    }

    fun validateCancelAck(value: RemoteCancelAck): RemoteCancelAck {
        requireVersion(value.protocolVersion)
        bounded(value.invocationId, "invocationId")
        bounded(value.cancelRequestId, "cancelRequestId")
        if (!value.accepted && value.terminalStatus != null &&
            value.terminalStatus != RemoteInvocationStatus.UNKNOWN_OUTCOME
        ) {
            throw invalid("rejected cancellation cannot report a terminal success/failure")
        }
        return value
    }

    private fun validateLimits(value: RemoteLimits) {
        positive(value.timeoutMs, "limits.timeoutMs")
        positive(value.maxOutputBytes, "limits.maxOutputBytes")
        positive(value.maxToolCalls, "limits.maxToolCalls")
        positive(value.maxModelCalls, "limits.maxModelCalls")
        positive(value.maxModelTokens, "limits.maxModelTokens")
    }

    private fun validateUsage(value: RemoteUsage) {
        nonNegative(value.durationMs, "usage.durationMs")
        nonNegative(value.outputBytes, "usage.outputBytes")
        nonNegative(value.toolCalls, "usage.toolCalls")
        nonNegative(value.modelCalls, "usage.modelCalls")
        nonNegative(value.modelTokens, "usage.modelTokens")
    }

    private fun validateArtifact(value: RemoteArtifactDescriptor) {
        bounded(value.id, "artifact.id")
        bounded(value.mediaType, "artifact.mediaType")
        nonNegative(value.size, "artifact.size")
        if (!sha256.matches(value.sha256)) throw invalid("artifact.sha256 must be lowercase SHA-256 hex")
    }

    private fun validateError(value: RemoteError) {
        bounded(value.code, "error.code")
        bounded(value.userMessage, "error.userMessage")
        bounded(value.retryClass, "error.retryClass")
        bounded(value.stage, "error.stage")
        bounded(value.operationId, "error.operationId")
        bounded(value.sanitizedDetails, "error.sanitizedDetails", allowBlank = true)
    }

    private fun list(values: List<String>, field: String) {
        if (values.any { it.isBlank() || it.length > 128 || control.matches(it) }) {
            throw invalid("$field contains an invalid value")
        }
        if (values.toSet().size != values.size) throw invalid("$field contains duplicates")
    }

    private fun bounded(value: String, field: String, allowBlank: Boolean = false) {
        if ((!allowBlank && value.isBlank()) || value.length > 256 || control.matches(value)) {
            throw invalid("$field is invalid")
        }
    }

    private fun positive(value: Long, field: String) {
        if (value <= 0) throw invalid("$field must be positive")
    }

    private fun positive(value: Int, field: String) {
        if (value <= 0) throw invalid("$field must be positive")
    }

    private fun nonNegative(value: Long, field: String) {
        if (value < 0) throw invalid("$field must not be negative")
    }

    private fun nonNegative(value: Int, field: String) {
        if (value < 0) throw invalid("$field must not be negative")
    }

    private fun parseInstant(value: String, field: String): Instant {
        if (!value.endsWith("Z")) throw invalid("$field must be an ISO-8601 UTC timestamp")
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            throw invalid("$field must be an ISO-8601 UTC timestamp")
        }
    }

    private fun requireVersion(version: Int) {
        if (version != REMOTE_EXECUTOR_PROTOCOL_VERSION) {
            throw invalid("unsupported remote executor protocolVersion $version")
        }
    }

    private fun invalid(message: String) = RemoteProtocolException(message)
}

enum class RemoteInvocationState {
    CREATED,
    SENT,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    UNKNOWN_OUTCOME,
}

/**
 * Versioned remote executor client.  It has no default endpoint and never
 * uploads package bytes, secrets, or knowledge-base contents.
 */
class RemoteSkillExecutor(
    private val transport: RemoteExecutorTransport,
    private val now: () -> Instant = { Instant.now() },
    /** Host-owned install/hash verifier; fail closed when no verifier is supplied. */
    private val packageAllowed: (RemoteInvocationRequest) -> Boolean = { false },
) {
    private val lock = Any()
    private val states = linkedMapOf<String, RemoteInvocationState>()
    private val requests = linkedMapOf<String, RemoteInvocationRequest>()
    private val cancelAcks = linkedMapOf<String, RemoteCancelAck>()
    private var cachedCapabilities: RemoteCapabilities? = null
    private var invocationCount = 0

    suspend fun capabilities(forceRefresh: Boolean = false): RemoteCapabilities {
        synchronized(lock) {
            if (!forceRefresh) cachedCapabilities?.let { return it }
        }
        val result = RemoteDtoValidator.validateCapabilities(transport.capabilities())
        synchronized(lock) { cachedCapabilities = result }
        return result
    }

    suspend fun invokeRemote(request: RemoteInvocationRequest): RemoteInvocationResult {
        RemoteDtoValidator.validateRequest(request)
        val caps = capabilities()
        if (request.executorId != caps.executorId) throw RemoteProtocolException("executorId does not match configured executor")
        if (!packageAllowed(request)) throw RemoteProtocolException("remote package is not installed or hash is not approved")
        if (request.approvedCapabilities.any { it !in caps.capabilities }) {
            throw RemoteProtocolException("approved capability is not declared by the executor")
        }
        if (request.arguments.toString().toByteArray(Charsets.UTF_8).size > caps.maxInputBytes) {
            throw RemoteProtocolException("arguments exceed executor maxInputBytes")
        }
        val nowInstant = now()
        val deadline = Instant.parse(request.deadlineAt)
        if (!deadline.isAfter(nowInstant)) throw RemoteProtocolException("remote invocation deadline has expired")
        if (request.limits.maxOutputBytes > caps.maxOutputBytes || request.limits.timeoutMs > caps.maxTimeoutMs) {
            throw RemoteProtocolException("remote invocation limits exceed executor capability")
        }
        synchronized(lock) {
            if (states.containsKey(request.invocationId)) {
                throw RemoteProtocolException("invocationId was already used")
            }
            states[request.invocationId] = RemoteInvocationState.SENT
            requests[request.invocationId] = request
            invocationCount += 1
        }
        return try {
            synchronized(lock) { states[request.invocationId] = RemoteInvocationState.RUNNING }
            val response = transport.invoke(request)
            val validated = runCatching {
                RemoteDtoValidator.validateResult(response).also {
                    if (it.invocationId != request.invocationId) {
                        throw RemoteProtocolException("remote result invocationId does not match request")
                    }
                    val resultBytes = (it.result?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
                        it.artifactDescriptors.toString().toByteArray(Charsets.UTF_8).size
                    if (resultBytes > request.limits.maxOutputBytes) {
                        throw RemoteProtocolException("remote result exceeds maxOutputBytes")
                    }
                }
            }.getOrElse { error ->
                unknownResult(request, "remote result is invalid: ${error.message}")
            }
            synchronized(lock) { states[request.invocationId] = validated.status.toState() }
            validated
        } catch (e: CancellationException) {
            synchronized(lock) { states[request.invocationId] = RemoteInvocationState.UNKNOWN_OUTCOME }
            throw e
        } catch (e: Exception) {
            val unknown = unknownResult(request, "remote invocation outcome is unknown: ${safeMessage(e)}")
            synchronized(lock) { states[request.invocationId] = RemoteInvocationState.UNKNOWN_OUTCOME }
            unknown
        }
    }

    /** Contract name from the remote executor DTO table. */
    suspend fun invoke(request: RemoteInvocationRequest): RemoteInvocationResult = invokeRemote(request)

    suspend fun cancel(request: RemoteCancelRequest): RemoteCancelAck {
        RemoteDtoValidator.validateCancel(request)
        synchronized(lock) {
            cancelAcks[request.cancelRequestId]?.let {
                if (it.invocationId != request.invocationId) {
                    throw RemoteProtocolException("cancelRequestId was already used for another invocation")
                }
                return it
            }
        }
        val current = synchronized(lock) { states[request.invocationId] }
        if (current == null) {
            return rememberCancel(
                request,
                RemoteCancelAck(
                    REMOTE_EXECUTOR_PROTOCOL_VERSION,
                    request.invocationId,
                    request.cancelRequestId,
                    accepted = false,
                    terminalStatus = RemoteInvocationStatus.UNKNOWN_OUTCOME,
                ),
            )
        }
        if (current in TERMINAL_STATES) {
            return rememberCancel(
                request,
                RemoteCancelAck(
                    REMOTE_EXECUTOR_PROTOCOL_VERSION,
                    request.invocationId,
                    request.cancelRequestId,
                    accepted = true,
                    terminalStatus = current.toStatus(),
                ),
            )
        }
        val caps = capabilities()
        if (!caps.supportsCancel) {
            synchronized(lock) { states[request.invocationId] = RemoteInvocationState.UNKNOWN_OUTCOME }
            return rememberCancel(
                request,
                RemoteCancelAck(
                    REMOTE_EXECUTOR_PROTOCOL_VERSION,
                    request.invocationId,
                    request.cancelRequestId,
                    accepted = false,
                    terminalStatus = RemoteInvocationStatus.UNKNOWN_OUTCOME,
                ),
            )
        }
        val ack = try {
            transport.cancel(request)
        } catch (_: CancellationException) {
            throw CancellationException("remote cancellation cancelled")
        } catch (_: Exception) {
            null
        }
        val validated = ack?.let {
            runCatching { RemoteDtoValidator.validateCancelAck(it) }.getOrNull()
                ?.takeIf { value ->
                    value.invocationId == request.invocationId && value.cancelRequestId == request.cancelRequestId
                }
        }
        val result = validated ?: RemoteCancelAck(
            REMOTE_EXECUTOR_PROTOCOL_VERSION,
            request.invocationId,
            request.cancelRequestId,
            accepted = false,
            terminalStatus = RemoteInvocationStatus.UNKNOWN_OUTCOME,
        )
        synchronized(lock) {
            states[request.invocationId] = when {
                result.terminalStatus != null && result.terminalStatus != RemoteInvocationStatus.UNKNOWN_OUTCOME -> result.terminalStatus.toState()
                else -> RemoteInvocationState.UNKNOWN_OUTCOME
            }
        }
        // A successful HTTP delivery of notifications/cancelled is not proof
        // that the remote computation stopped.  Preserve UNKNOWN_OUTCOME when
        // the executor does not return a terminal status.
        return rememberCancel(request, result)
    }

    fun state(invocationId: String): RemoteInvocationState? = synchronized(lock) { states[invocationId] }

    fun sentInvocationCount(): Int = synchronized(lock) { invocationCount }

    private fun rememberCancel(request: RemoteCancelRequest, ack: RemoteCancelAck): RemoteCancelAck {
        synchronized(lock) { cancelAcks[request.cancelRequestId] = ack }
        return ack
    }

    private fun unknownResult(request: RemoteInvocationRequest, message: String): RemoteInvocationResult {
        val instant = now().toString()
        return RemoteInvocationResult(
            protocolVersion = REMOTE_EXECUTOR_PROTOCOL_VERSION,
            invocationId = request.invocationId,
            status = RemoteInvocationStatus.UNKNOWN_OUTCOME,
            error = RemoteError(
                code = "UNKNOWN_OUTCOME",
                userMessage = "Remote executor outcome is unknown",
                retryClass = "NEVER",
                stage = "remote-executor",
                operationId = request.invocationId,
                sanitizedDetails = message.take(512),
            ),
            startedAt = instant,
            finishedAt = instant,
            usage = RemoteUsage(0, 0, 0, 0, 0),
        )
    }

    private fun safeMessage(error: Throwable): String = error.message?.take(256) ?: "transport failure"

    private fun RemoteInvocationStatus.toState(): RemoteInvocationState = when (this) {
        RemoteInvocationStatus.SUCCEEDED -> RemoteInvocationState.SUCCEEDED
        RemoteInvocationStatus.FAILED -> RemoteInvocationState.FAILED
        RemoteInvocationStatus.CANCELLED -> RemoteInvocationState.CANCELLED
        RemoteInvocationStatus.TIMED_OUT -> RemoteInvocationState.TIMED_OUT
        RemoteInvocationStatus.UNKNOWN_OUTCOME -> RemoteInvocationState.UNKNOWN_OUTCOME
    }

    private fun RemoteInvocationState.toStatus(): RemoteInvocationStatus = when (this) {
        RemoteInvocationState.SUCCEEDED -> RemoteInvocationStatus.SUCCEEDED
        RemoteInvocationState.FAILED -> RemoteInvocationStatus.FAILED
        RemoteInvocationState.CANCELLED -> RemoteInvocationStatus.CANCELLED
        RemoteInvocationState.TIMED_OUT -> RemoteInvocationStatus.TIMED_OUT
        RemoteInvocationState.UNKNOWN_OUTCOME -> RemoteInvocationStatus.UNKNOWN_OUTCOME
        RemoteInvocationState.CREATED,
        RemoteInvocationState.SENT,
        RemoteInvocationState.RUNNING,
        -> RemoteInvocationStatus.UNKNOWN_OUTCOME
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            RemoteInvocationState.SUCCEEDED,
            RemoteInvocationState.FAILED,
            RemoteInvocationState.CANCELLED,
            RemoteInvocationState.TIMED_OUT,
            RemoteInvocationState.UNKNOWN_OUTCOME,
        )
    }
}
