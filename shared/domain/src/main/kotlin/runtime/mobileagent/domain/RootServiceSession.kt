// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/**
 * Live OS/Binder caller facts observed at the service boundary on every RPC.
 * Never populate this from model arguments or unverified client JSON.
 */
data class RootCallerObservation(
    val callingUid: Int,
    val observedServiceUid: Int,
    val packageName: String,
    val androidUserId: Int,
    val signingSha256: String,
    val binderAlive: Boolean = true,
) {
    fun matches(identity: PrivilegedSessionIdentity): Boolean =
        binderAlive &&
            observedServiceUid == PrivilegeTier.ROOT.uid &&
            callingUid == identity.appUid &&
            androidUserId == identity.androidUserId &&
            callingUid / 100_000 == identity.androidUserId &&
            packageName == identity.packageName &&
            signingSha256 == identity.signingSha256
}

sealed interface RootControlSubmitResult {
    data class Accepted(
        val status: RootControlStatusSnapshot,
        val ticket: RootExecutionTicket,
        val envelope: RootControlEnvelope,
    ) : RootControlSubmitResult

    data class DuplicateSameDigest(
        val status: RootControlStatusSnapshot,
        val controlSequence: Long,
    ) : RootControlSubmitResult

    data class ReplayBindingMismatch(
        val requestId: String,
        val existingStage: RootServiceExecutionStage,
        val controlSequence: Long,
    ) : RootControlSubmitResult

    data class RejectedBeforeAccepted(
        val proof: RootNonAcceptanceProof,
    ) : RootControlSubmitResult

    data class SequenceRejected(
        val rejection: RootSequenceRejection,
        val lastAcceptedSequence: Long,
        val sessionState: RootServiceSessionState,
    ) : RootControlSubmitResult

    data class InvalidEnvelope(
        val controlSequence: Long,
        val reason: String,
    ) : RootControlSubmitResult

    data object UntrustedCaller : RootControlSubmitResult
    data object IdentityMismatch : RootControlSubmitResult
}

sealed interface RootControlRevokeResult {
    data class Revoked(
        val controlSequence: Long,
        val inFlightRequestIds: List<String>,
        val reason: String,
    ) : RootControlRevokeResult

    data class SequenceRejected(
        val rejection: RootSequenceRejection,
        val lastAcceptedSequence: Long,
        val sessionState: RootServiceSessionState,
    ) : RootControlRevokeResult

    data object UntrustedCaller : RootControlRevokeResult
    data object IdentityMismatch : RootControlRevokeResult
}

sealed interface RootControlObserveResult {
    data class Found(val status: RootControlStatusSnapshot) : RootControlObserveResult
    data class NotFound(val requestId: String) : RootControlObserveResult
    data object UntrustedCaller : RootControlObserveResult
    data object IdentityMismatch : RootControlObserveResult
}

class RootContentStreamLease internal constructor(
    private val session: RootServiceSession,
    val requestId: String,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    internal fun closeInternal(): Boolean {
        if (closed) return false
        closed = true
        runCatching { onClose() }
        return true
    }

    override fun close() {
        session.releaseContentStream(this)
    }
}

/**
 * Controlled Root service session gate.
 *
 * Binds one authenticated [PrivilegedSessionIdentity] (`serviceInstanceId`, `sessionId`,
 * `identityEpoch`) to a single [RootControlLedger], workspace session handle table, and
 * active content stream registry. Every control-plane entrypoint verifies live OS caller
 * identity and target session identity before entering the scheduling gate.
 */
class RootServiceSession(
    val identity: PrivilegedSessionIdentity,
    private val parameterPolicyValidator: (RootControlEnvelope) -> Boolean = { true },
    private val onCancelInFlight: (String) -> Unit = {},
    controlPlane: RootControlPlaneBoundary? = null,
) {
    val ledger = RootControlLedger(identity)
    private val workspaceStore = RootServiceWorkspaceStore(identity, controlPlane)
    private val activeStreams = linkedSetOf<RootContentStreamLease>()

    @Synchronized
    fun configureControlPlane(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        boundary: RootControlPlaneBoundary,
    ): Boolean {
        if (!isAuthorizedActiveCall(caller, targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) return false
        workspaceStore.configureControlPlane(boundary)
        return true
    }

    @Synchronized
    fun stageProvisionalDirectory(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        provisionalHandle: String,
        rootPath: String,
        rootKind: RootWorkspaceRootKind,
        directoryIdentity: RootFdNodeIdentity,
        onCloseFd: () -> Unit = {},
    ): RootConfinementRejection? {
        if (!isAuthorizedActiveCall(caller, targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            runCatching { onCloseFd() }
            return RootConfinementRejection.IDENTITY_MISMATCH
        }
        return workspaceStore.stageProvisional(provisionalHandle, rootPath, rootKind, directoryIdentity, onCloseFd)
    }

    @Synchronized
    fun attachProvisionalWorkspace(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        provisionalHandle: String,
        workspaceSessionHandle: String,
        origin: RootWorkspaceOrigin,
        originSha256: String,
    ): RootWorkspaceAttachResult {
        if (!caller.matches(identity) || !matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            return RootWorkspaceAttachResult.UntrustedCaller
        }
        if (ledger.sessionState() != RootServiceSessionState.ACTIVE) {
            return RootWorkspaceAttachResult.SessionInactive
        }
        return workspaceStore.attachProvisional(provisionalHandle, workspaceSessionHandle, origin, originSha256)
    }

    @Synchronized
    fun confirmStagedWorkspace(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        workspaceId: String,
        workspaceGenerationId: String,
        workspaceSessionHandle: String,
        originSha256: String,
    ): Boolean {
        if (!isAuthorizedActiveCall(caller, targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) return false
        return workspaceStore.confirmStaged(workspaceId, workspaceGenerationId, workspaceSessionHandle, originSha256)
    }

    @Synchronized
    fun releaseStagedWorkspace(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        workspaceSessionHandle: String,
    ): Boolean {
        if (!caller.matches(identity) || !matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            return false
        }
        return workspaceStore.releaseStaged(workspaceSessionHandle)
    }

    /**
     * Releases a provisional handle (user-selected path observed but no origin bound yet) and
     * closes its retained FD now. Used by the three-phase selection RELEASE phase.
     */
    /**
     * Host CHECK: the `(workspaceId, generationId, handle)` triple must already be an active
     * binding in this session. Read-only; it never reopens a path or trusts Host-supplied paths.
     */
    @Synchronized
    fun verifyActiveWorkspaceBinding(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        workspaceId: String,
        workspaceGenerationId: String,
        workspaceSessionHandle: String,
    ): Boolean {
        if (!caller.matches(identity) || !matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            return false
        }
        if (ledger.sessionState() != RootServiceSessionState.ACTIVE) return false
        return workspaceStore.isActive(workspaceId, workspaceGenerationId, workspaceSessionHandle)
    }

    @Synchronized
    fun releaseProvisionalWorkspace(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        provisionalHandle: String,
    ): Boolean {
        if (!caller.matches(identity) || !matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            return false
        }
        if (ledger.sessionState() != RootServiceSessionState.ACTIVE) return false
        return workspaceStore.releaseProvisional(provisionalHandle)
    }

    @Synchronized
    fun reattachWorkspace(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        workspaceSessionHandle: String,
        locatorBytes: ByteArray?,
        origin: RootWorkspaceOrigin,
        originSha256: String,
        reopenedRootPath: String,
        reopenedIdentity: RootFdNodeIdentity,
        onCloseFd: () -> Unit = {},
    ): RootWorkspaceReattachResult {
        if (!caller.matches(identity) || !matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            locatorBytes?.fill(0)
            runCatching { onCloseFd() }
            return RootWorkspaceReattachResult.UntrustedCaller
        }
        if (ledger.sessionState() != RootServiceSessionState.ACTIVE) {
            locatorBytes?.fill(0)
            runCatching { onCloseFd() }
            return RootWorkspaceReattachResult.SessionInactive
        }
        return workspaceStore.reattach(
            workspaceSessionHandle = workspaceSessionHandle,
            locatorBytes = locatorBytes,
            origin = origin,
            originSha256 = originSha256,
            reopenedRootPath = reopenedRootPath,
            reopenedIdentity = reopenedIdentity,
            onCloseFd = onCloseFd,
        )
    }

    @Synchronized
    fun bindWorkspaceHandle(
        caller: RootCallerObservation,
        workspaceId: String,
        workspaceGenerationId: String,
        workspaceSessionHandle: String,
        onCloseFd: () -> Unit = {},
    ): Boolean {
        if (!caller.matches(identity)) return false
        if (ledger.sessionState() != RootServiceSessionState.ACTIVE) return false
        require(RootPathGuards.isSafeId(workspaceId))
        require(RootPathGuards.isOpaqueId(workspaceGenerationId) && RootPathGuards.isOpaqueId(workspaceSessionHandle))
        workspaceStore.bindDirectForTestOrInternal(workspaceId, workspaceGenerationId, workspaceSessionHandle, onCloseFd)
        return true
    }

    @Synchronized
    fun submit(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        controlSequence: Long,
        rawEnvelopeJson: String,
    ): RootControlSubmitResult {
        if (!caller.matches(identity)) return RootControlSubmitResult.UntrustedCaller
        if (!matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            return RootControlSubmitResult.IdentityMismatch
        }
        return ledger.submitAtGate(
            controlSequence = controlSequence,
            rawEnvelopeJson = rawEnvelopeJson,
            workspaceHandleValidator = ::isWorkspaceBound,
            parameterPolicyValidator = parameterPolicyValidator,
            onGapFault = ::teardownActiveWork,
        )
    }

    @Synchronized
    fun cancelInvocation(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        invocationId: String,
    ): Boolean {
        if (!isAuthorizedActiveCall(caller, targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) return false
        return ledger.cancelInvocationAtGate(invocationId)
    }

    @Synchronized
    fun revoke(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        controlSequence: Long,
        reason: String = "host_revoked",
    ): RootControlRevokeResult {
        if (!caller.matches(identity)) return RootControlRevokeResult.UntrustedCaller
        if (!matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            return RootControlRevokeResult.IdentityMismatch
        }
        require(reason.isNotBlank() && reason.length <= 128)
        return ledger.revokeAtGate(
            controlSequence = controlSequence,
            reason = reason,
            onRevokeOrFault = ::teardownActiveWork,
        )
    }

    @Synchronized
    fun observeStatus(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
        requestId: String,
    ): RootControlObserveResult {
        if (!caller.matches(identity)) return RootControlObserveResult.UntrustedCaller
        if (!matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch)) {
            return RootControlObserveResult.IdentityMismatch
        }
        require(RootPathGuards.isSafeId(requestId))
        val snapshot = ledger.observe(requestId)
        return if (snapshot != null) RootControlObserveResult.Found(snapshot) else RootControlObserveResult.NotFound(requestId)
    }

    @Synchronized
    fun openContentStream(ticket: RootExecutionTicket, onClose: () -> Unit = {}): RootContentStreamLease? {
        require(ticket.owner === ledger) { "Ticket belongs to another session" }
        if (ledger.sessionState() != RootServiceSessionState.ACTIVE) return null
        val snapshot = ledger.observe(ticket.requestId) ?: return null
        if (snapshot.stage == RootServiceExecutionStage.TERMINAL) return null
        val lease = RootContentStreamLease(this, ticket.requestId, onClose)
        activeStreams += lease
        return lease
    }

    @Synchronized
    internal fun releaseContentStream(lease: RootContentStreamLease) {
        if (activeStreams.remove(lease)) {
            lease.closeInternal()
        }
    }

    @Synchronized
    fun openContentStreamCount(): Int = activeStreams.size

    @Synchronized
    fun hasActiveWork(): Boolean = ledger.inFlightRequestIds().isNotEmpty() || activeStreams.isNotEmpty()

    @Synchronized
    fun canRotate(): Boolean = ledger.canRotate(activeStreams.size)

    @Synchronized
    fun closeSession(): List<String> = ledger.forceClose(::teardownActiveWork)

    @Synchronized
    fun closeSessionIfIdle(): Boolean {
        if (hasActiveWork()) return false
        closeSession()
        return true
    }

    private fun isAuthorizedActiveCall(
        caller: RootCallerObservation,
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
    ): Boolean =
        caller.matches(identity) &&
            matchesTarget(targetServiceInstanceId, targetSessionId, targetIdentityEpoch) &&
            ledger.sessionState() == RootServiceSessionState.ACTIVE

    private fun matchesTarget(
        targetServiceInstanceId: String,
        targetSessionId: String,
        targetIdentityEpoch: Long,
    ): Boolean =
        targetServiceInstanceId == identity.serviceInstanceId &&
            targetSessionId == identity.sessionId &&
            targetIdentityEpoch == identity.identityEpoch

    private fun isWorkspaceBound(envelope: RootControlEnvelope): Boolean =
        workspaceStore.isActive(
            envelope.workspaceId,
            envelope.workspaceGenerationId,
            envelope.workspaceSessionHandle,
        )

    private fun teardownActiveWork(inFlightRequestIds: List<String>) {
        workspaceStore.closeAll()
        val streams = activeStreams.toList()
        activeStreams.clear()
        streams.forEach { it.closeInternal() }
        inFlightRequestIds.forEach { id -> runCatching { onCancelInFlight(id) } }
    }
}

/**
 * Host-side session coordination lock ordering consent/policy revocation against
 * attempt submission and allocating monotonic `controlSequence` numbers from 1.
 */
class RootHostControlGate(val identity: PrivilegedSessionIdentity) {
    private var nextSequence: Long = 0L
    private var revoked: Boolean = false

    @Synchronized
    fun isRevoked(): Boolean = revoked

    @Synchronized
    fun markRevoked(): Boolean {
        val changed = !revoked
        revoked = true
        return changed
    }

    @Synchronized
    fun lastIssuedSequence(): Long = nextSequence

    private data class Turn(
        val sequence: Long,
        val previous: java.util.concurrent.CompletableFuture<Unit>,
        val completed: java.util.concurrent.CompletableFuture<Unit>,
    )
    private var tail = java.util.concurrent.CompletableFuture.completedFuture(Unit)

    private fun reserve(): Turn {
        nextSequence = Math.addExact(nextSequence, 1L)
        return Turn(nextSequence, tail, java.util.concurrent.CompletableFuture()).also { tail = it.completed }
    }

    // Historical name retained for callers; only reservation owns the monitor, never RPC/wait.
    fun <T> submitUnderLock(send: (controlSequence: Long) -> T): T? {
        val turn = synchronized(this) { if (revoked) return null else reserve() }
        return sendInOrder(turn, send)
    }

    fun <T> revokeUnderLock(sendRevoke: (controlSequence: Long) -> T): T {
        val turn = synchronized(this) { revoked = true; reserve() }
        return sendInOrder(turn, sendRevoke)
    }

    private fun <T> sendInOrder(turn: Turn, send: (Long) -> T): T {
        try {
            // join preserves ordering even if the caller is interrupted. A failed RPC may
            // fault the session but must never permit a later sequence to overtake it.
            turn.previous.join()
            return send(turn.sequence)
        } finally { turn.completed.complete(Unit) }
    }
}
