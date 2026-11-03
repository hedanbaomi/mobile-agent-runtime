// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RootServiceExecutionStage { ACCEPTED, RUNNING, TERMINAL }

@Serializable
enum class RootServiceTerminalOutcome { SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME }

@Serializable
enum class RootDuplicateStatus { ALREADY_ACCEPTED, ALREADY_RUNNING, ALREADY_COMPLETED }

@Serializable
enum class RootServiceSessionState { ACTIVE, REVOKED, SEQUENCE_FAULTED, CLOSED }

@Serializable
enum class RootSequenceRejection { REPLAY_OR_REGRESSION, GAP_SESSION_CLOSED, SESSION_CLOSED }

@Serializable
enum class RootNonAcceptanceReason {
    CAPACITY_EXHAUSTED,
    SESSION_REVOKED,
    WORKSPACE_HANDLE_INVALID,
    PARAMETER_OR_SCOPE_REJECTED,
    INVOCATION_CANCELLED,
}

/**
 * Status-only projection of a registered Root execution request.
 * Duplicate submissions and status observations return this metadata without
 * re-executing the command or re-emitting content bytes/file descriptors.
 */
@Serializable
data class RootControlStatusSnapshot(
    val requestId: String,
    val envelopeSha256: String,
    val acceptedSequence: Long,
    val stage: RootServiceExecutionStage,
    val terminalOutcome: RootServiceTerminalOutcome? = null,
    val resultDigestSha256: String? = null,
) {
    init {
        require(RootPathGuards.isSafeId(requestId) && RootPathGuards.isSha256(envelopeSha256))
        require(acceptedSequence > 0L)
        require((stage == RootServiceExecutionStage.TERMINAL) == (terminalOutcome != null))
        require(
            resultDigestSha256 == null ||
                (stage == RootServiceExecutionStage.TERMINAL && RootPathGuards.isSha256(resultDigestSha256)),
        )
    }

    val duplicateStatus: RootDuplicateStatus
        get() = when (stage) {
            RootServiceExecutionStage.ACCEPTED -> RootDuplicateStatus.ALREADY_ACCEPTED
            RootServiceExecutionStage.RUNNING -> RootDuplicateStatus.ALREADY_RUNNING
            RootServiceExecutionStage.TERMINAL -> RootDuplicateStatus.ALREADY_COMPLETED
        }
}

/**
 * Signed/bound service receipt proving that [requestId] with [envelopeSha256]
 * was rejected at the scheduling gate before ever registering ACCEPTED.
 * Never issued for already-accepted IDs, replay digest mismatches, untrusted
 * callers, or mismatched sessions.
 */
@Serializable
data class RootNonAcceptanceProof(
    val authority: Authority,
    val serviceInstanceId: String,
    val sessionId: String,
    val identityEpoch: Long,
    val requestId: String,
    val envelopeSha256: String,
    val controlSequence: Long,
    val reason: RootNonAcceptanceReason,
) {
    init {
        require(authority.isRootAuthority && identityEpoch > 0L && controlSequence > 0L)
        require(RootPathGuards.isOpaqueId(serviceInstanceId) && RootPathGuards.isOpaqueId(sessionId))
        require(RootPathGuards.isSafeId(requestId) && RootPathGuards.isSha256(envelopeSha256))
    }

    companion object {
        internal fun create(
            identity: PrivilegedSessionIdentity,
            requestId: String,
            envelopeSha256: String,
            controlSequence: Long,
            reason: RootNonAcceptanceReason,
        ): RootNonAcceptanceProof = RootNonAcceptanceProof(
            authority = identity.authority,
            serviceInstanceId = identity.serviceInstanceId,
            sessionId = identity.sessionId,
            identityEpoch = identity.identityEpoch,
            requestId = requestId,
            envelopeSha256 = envelopeSha256,
            controlSequence = controlSequence,
            reason = reason,
        )
    }
}

object RootNonAcceptanceVerifier {
    fun verify(
        proof: RootNonAcceptanceProof,
        expectedEnvelope: RootControlEnvelope,
        liveIdentity: PrivilegedSessionIdentity?,
    ): Boolean {
        val expectedIdentity = expectedEnvelope.execution.identity
        if (liveIdentity != expectedIdentity) return false
        return proof.authority == expectedIdentity.authority &&
            proof.serviceInstanceId == expectedIdentity.serviceInstanceId &&
            proof.sessionId == expectedIdentity.sessionId &&
            proof.identityEpoch == expectedIdentity.identityEpoch &&
            proof.requestId == expectedEnvelope.requestId &&
            proof.envelopeSha256 == expectedEnvelope.canonicalSha256()
    }

    fun requireValid(
        proof: RootNonAcceptanceProof,
        expectedEnvelope: RootControlEnvelope,
        liveIdentity: PrivilegedSessionIdentity?,
    ) {
        require(verify(proof, expectedEnvelope, liveIdentity)) {
            "Root non-acceptance proof does not match the authenticated session or envelope digest"
        }
    }
}

/** Single-use process-owned execution token issued only when a new requestId is accepted. */
class RootExecutionTicket internal constructor(
    internal val owner: RootControlLedger,
    val requestId: String,
    val envelopeSha256: String,
    val acceptedSequence: Long,
) {
    internal var started: Boolean = false
    internal var completed: Boolean = false
    override fun toString(): String =
        "RootExecutionTicket(acceptedSequence=$acceptedSequence, started=$started, completed=$completed)"
}

/**
 * Per-session in-memory control plane ledger.
 *
 * Enforces:
 * - Strict monotonic `controlSequence` starting at 1 (`lastAcceptedSequence + 1`),
 *   where duplicate/regressed sequences are rejected without side effects and any
 *   sequence gap permanently faults and closes the session.
 * - At most 256 cumulative unique `requestId` registrations per session with zero
 *   eviction (even after requests reach TERMINAL).
 * - Atomic registration of `(requestId, envelopeSha256, ACCEPTED)` before scheduling
 *   any side effect; duplicate `requestId` with different digest returns
 *   `ReplayBindingMismatch`, and with identical digest returns status only.
 * - Single scheduling gate ordering `submit` and `revoke`.
 *
 * This ledger does not replace live OS/Binder caller verification, package/signature
 * validation, or Host SQLite reservation gates.
 */
class RootControlLedger(val identity: PrivilegedSessionIdentity) {
    init {
        require(identity.authority.isRootAuthority && identity.privilegeTier == PrivilegeTier.ROOT) {
            "RootControlLedger requires a Root session identity"
        }
        require(identity.protocolVersion == PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION) {
            "RootControlLedger requires protocol version 4"
        }
    }

    private class Entry(
        val requestId: String,
        val envelopeSha256: String,
        val envelope: RootControlEnvelope,
        val acceptedSequence: Long,
        var stage: RootServiceExecutionStage,
        var terminalOutcome: RootServiceTerminalOutcome? = null,
        var resultDigestSha256: String? = null,
        var ticket: RootExecutionTicket? = null,
        var cancelRequested: Boolean = false,
    ) {
        fun snapshot(): RootControlStatusSnapshot = RootControlStatusSnapshot(
            requestId = requestId,
            envelopeSha256 = envelopeSha256,
            acceptedSequence = acceptedSequence,
            stage = stage,
            terminalOutcome = terminalOutcome,
            resultDigestSha256 = resultDigestSha256,
        )
    }

    private val entries = linkedMapOf<String, Entry>()
    private val cancelledInvocations = linkedSetOf<String>()
    private var cancellationCapacityExhausted = false

    /** Session-lifetime tombstones are never evicted to admit another invocation. */
    @Synchronized
    internal fun cancelInvocationAtGate(invocationId: String): Boolean {
        if (!RootPathGuards.isSafeId(invocationId)) return false
        val accepted = entries.values.filter { it.envelope.invocationId == invocationId }
        if (accepted.isEmpty() && invocationId !in cancelledInvocations && cancelledInvocations.size >= MAX_SESSION_REQUESTS) {
            cancellationCapacityExhausted = true
            return false
        }
        if (accepted.isEmpty()) cancelledInvocations += invocationId
        accepted.forEach { it.cancelRequested = true }
        accepted.filter { it.stage == RootServiceExecutionStage.ACCEPTED }
            .forEach {
                it.stage = RootServiceExecutionStage.TERMINAL
                it.terminalOutcome = RootServiceTerminalOutcome.CANCELLED
                it.ticket?.completed = true
            }
        return true
    }

    @Synchronized
    fun requestIdsForInvocation(invocationId: String): List<String> =
        entries.values.filter { it.envelope.invocationId == invocationId }.map { it.requestId }
    private var lastSequence: Long = 0L
    private var state: RootServiceSessionState = RootServiceSessionState.ACTIVE

    @Synchronized
    fun lastAcceptedSequence(): Long = lastSequence

    @Synchronized
    fun sessionState(): RootServiceSessionState = state

    @Synchronized
    fun acceptedCount(): Int = entries.size

    @Synchronized
    fun inFlightRequestIds(): List<String> =
        entries.values.filter { it.stage != RootServiceExecutionStage.TERMINAL }.map { it.requestId }

    @Synchronized
    fun observe(requestId: String): RootControlStatusSnapshot? = entries[requestId]?.snapshot()

    @Synchronized
    fun canRotate(openContentStreams: Int): Boolean {
        require(openContentStreams >= 0)
        return state == RootServiceSessionState.ACTIVE &&
            openContentStreams == 0 &&
            entries.values.all { it.stage == RootServiceExecutionStage.TERMINAL }
    }

    @Synchronized
    fun beginExecution(ticket: RootExecutionTicket): Boolean {
        val entry = requireOwnedTicket(ticket)
        require(!ticket.started) { "Root execution ticket has already been started" }
        ticket.started = true
        if (state != RootServiceSessionState.ACTIVE || entry.stage == RootServiceExecutionStage.TERMINAL) {
            entry.stage = RootServiceExecutionStage.TERMINAL
            if (entry.terminalOutcome == null) {
                entry.terminalOutcome = RootServiceTerminalOutcome.CANCELLED
            }
            ticket.completed = true
            return false
        }
        entry.stage = RootServiceExecutionStage.RUNNING
        return true
    }

    @Synchronized
    fun completeExecution(
        ticket: RootExecutionTicket,
        outcome: RootServiceTerminalOutcome,
        resultDigestSha256: String? = null,
    ): RootControlStatusSnapshot {
        val entry = requireOwnedTicket(ticket)
        require(!ticket.completed && entry.stage != RootServiceExecutionStage.TERMINAL) {
            "Root execution ticket is already terminal"
        }
        if (!ticket.started) {
            require(outcome != RootServiceTerminalOutcome.SUCCEEDED) {
                "Unstarted Root execution ticket cannot succeed"
            }
            ticket.started = true
        }
        require(resultDigestSha256 == null || RootPathGuards.isSha256(resultDigestSha256))
        entry.stage = RootServiceExecutionStage.TERMINAL
        entry.terminalOutcome = outcome
        entry.resultDigestSha256 = resultDigestSha256
        ticket.completed = true
        return entry.snapshot()
    }

    @Synchronized
    internal fun submitAtGate(
        controlSequence: Long,
        rawEnvelopeJson: String,
        workspaceHandleValidator: (RootControlEnvelope) -> Boolean,
        parameterPolicyValidator: (RootControlEnvelope) -> Boolean,
        onGapFault: (List<String>) -> Unit,
    ): RootControlSubmitResult {
        if (state == RootServiceSessionState.SEQUENCE_FAULTED || state == RootServiceSessionState.CLOSED) {
            return RootControlSubmitResult.SequenceRejected(RootSequenceRejection.SESSION_CLOSED, lastSequence, state)
        }
        val decoded = runCatching { RootControlEnvelope.decodeAndCanonicalize(rawEnvelopeJson) }
        val preDecoded = decoded.getOrNull()
        if (preDecoded != null && preDecoded.execution.identity != identity) {
            return RootControlSubmitResult.IdentityMismatch
        }
        val seqRejection = advanceSequenceOrReject(controlSequence, onGapFault)
        if (seqRejection != null) {
            return RootControlSubmitResult.SequenceRejected(seqRejection, lastSequence, state)
        }
        val envelope = preDecoded ?: return RootControlSubmitResult.InvalidEnvelope(
            controlSequence,
            decoded.exceptionOrNull()?.message ?: "Invalid envelope",
        )
        val digest = envelope.canonicalSha256()
        val existing = entries[envelope.requestId]
        if (existing != null) {
            return if (existing.envelopeSha256 == digest) {
                RootControlSubmitResult.DuplicateSameDigest(existing.snapshot(), controlSequence)
            } else {
                RootControlSubmitResult.ReplayBindingMismatch(envelope.requestId, existing.stage, controlSequence)
            }
        }
        val rejectionReason = preAcceptanceRejection(envelope, workspaceHandleValidator, parameterPolicyValidator)
        if (rejectionReason != null) {
            return RootControlSubmitResult.RejectedBeforeAccepted(
                RootNonAcceptanceProof.create(identity, envelope.requestId, digest, controlSequence, rejectionReason),
            )
        }
        val ticket = RootExecutionTicket(this, envelope.requestId, digest, controlSequence)
        val entry = Entry(
            requestId = envelope.requestId,
            envelopeSha256 = digest,
            envelope = envelope,
            acceptedSequence = controlSequence,
            stage = RootServiceExecutionStage.ACCEPTED,
            ticket = ticket,
        )
        entries[envelope.requestId] = entry
        return RootControlSubmitResult.Accepted(entry.snapshot(), ticket, envelope)
    }

    @Synchronized
    internal fun revokeAtGate(
        controlSequence: Long,
        reason: String,
        onRevokeOrFault: (List<String>) -> Unit,
    ): RootControlRevokeResult {
        val seqRejection = advanceSequenceOrReject(controlSequence, onRevokeOrFault)
        if (seqRejection != null) {
            return RootControlRevokeResult.SequenceRejected(seqRejection, lastSequence, state)
        }
        state = RootServiceSessionState.REVOKED
        val inFlight = cancelUnstartedAndCollectInFlight()
        onRevokeOrFault(inFlight)
        return RootControlRevokeResult.Revoked(controlSequence, inFlight, reason)
    }

    @Synchronized
    internal fun forceClose(onCloseInFlight: (List<String>) -> Unit): List<String> {
        if (state == RootServiceSessionState.ACTIVE || state == RootServiceSessionState.REVOKED) {
            state = RootServiceSessionState.CLOSED
        }
        val inFlight = cancelUnstartedAndCollectInFlight()
        onCloseInFlight(inFlight)
        return inFlight
    }

    private fun advanceSequenceOrReject(
        controlSequence: Long,
        onGapFault: (List<String>) -> Unit,
    ): RootSequenceRejection? {
        if (state == RootServiceSessionState.SEQUENCE_FAULTED || state == RootServiceSessionState.CLOSED) {
            return RootSequenceRejection.SESSION_CLOSED
        }
        if (controlSequence <= 0L || controlSequence <= lastSequence) {
            return RootSequenceRejection.REPLAY_OR_REGRESSION
        }
        val expected = Math.addExact(lastSequence, 1L)
        if (controlSequence > expected) {
            state = RootServiceSessionState.SEQUENCE_FAULTED
            onGapFault(cancelUnstartedAndCollectInFlight())
            return RootSequenceRejection.GAP_SESSION_CLOSED
        }
        lastSequence = controlSequence
        return null
    }

    private fun cancelUnstartedAndCollectInFlight(): List<String> {
        val activeIds = inFlightRequestIds()
        entries.values.forEach { entry ->
            if (entry.stage == RootServiceExecutionStage.ACCEPTED) {
                entry.stage = RootServiceExecutionStage.TERMINAL
                entry.terminalOutcome = RootServiceTerminalOutcome.CANCELLED
                entry.ticket?.completed = true
            }
        }
        return activeIds
    }

    private fun preAcceptanceRejection(
        envelope: RootControlEnvelope,
        workspaceHandleValidator: (RootControlEnvelope) -> Boolean,
        parameterPolicyValidator: (RootControlEnvelope) -> Boolean,
    ): RootNonAcceptanceReason? = when {
        state == RootServiceSessionState.REVOKED -> RootNonAcceptanceReason.SESSION_REVOKED
        envelope.invocationId in cancelledInvocations || entries.values.any {
            it.envelope.invocationId == envelope.invocationId && it.cancelRequested
        } -> RootNonAcceptanceReason.INVOCATION_CANCELLED
        cancellationCapacityExhausted -> RootNonAcceptanceReason.CAPACITY_EXHAUSTED
        !workspaceHandleValidator(envelope) -> RootNonAcceptanceReason.WORKSPACE_HANDLE_INVALID
        !parameterPolicyValidator(envelope) -> RootNonAcceptanceReason.PARAMETER_OR_SCOPE_REJECTED
        entries.size >= MAX_SESSION_REQUESTS -> RootNonAcceptanceReason.CAPACITY_EXHAUSTED
        else -> null
    }

    private fun requireOwnedTicket(ticket: RootExecutionTicket): Entry {
        require(ticket.owner === this) { "Root execution ticket belongs to another session ledger" }
        val entry = requireNotNull(entries[ticket.requestId]) { "Root execution request is unknown" }
        require(entry.ticket === ticket && entry.envelopeSha256 == ticket.envelopeSha256) {
            "Root execution ticket does not match ledger entry"
        }
        return entry
    }

    companion object {
        const val MAX_SESSION_REQUESTS = 256
    }
}
