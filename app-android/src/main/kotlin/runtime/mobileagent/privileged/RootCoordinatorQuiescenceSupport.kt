// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.concurrent.ConcurrentHashMap
import runtime.mobileagent.data.RootAuthorityRepository
import runtime.mobileagent.data.RootDispatchRepository
import runtime.mobileagent.data.RootPendingDrainLoad
import runtime.mobileagent.data.RootPendingDrainRepository
import runtime.mobileagent.data.RootReceiptRecoveryCoordinator
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootHostControlGate
import runtime.mobileagent.domain.RootQuiescenceQueryRequest
import runtime.mobileagent.domain.RootRemoteQuiescenceSnapshot
import runtime.mobileagent.domain.RootServiceLifecycleCoordinator
import runtime.mobileagent.domain.RootServiceSession

internal data class ManagedRootSessionState(
    val session: RootServiceSession,
    val lifecycle: RootServiceLifecycleCoordinator,
    val caller: RootCallerObservation,
    val transport: RootChannelTransport,
    val hostControlGate: RootHostControlGate,
)

internal object RootCoordinatorQuiescenceSupport {
    private val verifiedQuiescenceSnapshots =
        ConcurrentHashMap<Authority, Pair<PrivilegedSessionIdentity, RootRemoteQuiescenceSnapshot>>()

    fun clearVerifiedSnapshot(authority: Authority) {
        verifiedQuiescenceSnapshots.remove(authority)
    }

    fun querySessionQuiescence(
        transport: RootChannelTransport,
        identity: PrivilegedSessionIdentity,
        invocationId: String? = null,
    ): RootRemoteQuiescenceSnapshot? = runCatching {
        transport.queryQuiescence(
            RootQuiescenceQueryRequest(
                targetAuthority = identity.authority,
                targetServiceInstanceId = identity.serviceInstanceId,
                targetSessionId = identity.sessionId,
                targetIdentityEpoch = identity.identityEpoch,
                invocationId = invocationId,
            ),
        )
    }.getOrNull()

    fun isInvocationQuiescent(
        transport: RootChannelTransport,
        identity: PrivilegedSessionIdentity,
        requestId: String,
        invocationId: String,
    ): Boolean {
        val snapshot = querySessionQuiescence(transport, identity, invocationId) ?: return false
        return snapshot.provesInvocationQuiescent(identity, requestId, invocationId)
    }

    fun acknowledgeIfStillActive(
        dispatchRepository: RootDispatchRepository,
        permit: runtime.mobileagent.data.RootDispatchPermit,
    ): Boolean = synchronized(dispatchRepository) {
        val receipt = dispatchRepository.getReceipt(permit.requestId)
        if (receipt == null || receipt.closed || receipt.stage == runtime.mobileagent.domain.RootDispatchStage.TERMINAL) {
            return@synchronized false
        }
        dispatchRepository.acknowledge(permit)
        true
    }

    fun detachAndRetireSession(
        authority: Authority,
        reason: String,
        forceCloseImmediately: Boolean,
        activeSessions: ConcurrentHashMap<Authority, ManagedRootSessionState>,
        retiringSessions: ConcurrentHashMap<Authority, ManagedRootSessionState>,
        dispatchBridge: RootCoordinatorDispatchBridge,
        dispatchRepository: RootDispatchRepository,
    ) {
        val removed = activeSessions.remove(authority)
        if (removed == null) {
            if (forceCloseImmediately) {
                retiringSessions.remove(authority)?.let { retiring ->
                    runCatching { retiring.transport.close() }
                }
            }
            return
        }
        val identity = removed.session.identity
        removed.hostControlGate.markRevoked()
        dispatchBridge.forgetSession(identity.sessionId)
        dispatchRepository.forgetSession(identity.sessionId)
        removed.lifecycle.initiateForcedShutdown(reason, 0L)
        retiringSessions[authority] = removed
        if (forceCloseImmediately) {
            retiringSessions.remove(authority, removed)
            runCatching {
                val revokeSeq = removed.hostControlGate.revokeUnderLock { it }
                removed.transport.revokeAndShutdown(revokeSeq, reason)
            }
            runCatching { removed.transport.close() }
            return
        }
        Thread({
            runCatching {
                val revokeSeq = removed.hostControlGate.revokeUnderLock { it }
                removed.transport.revokeAndShutdown(revokeSeq, reason)
            }
            for (attempt in 0 until 10) {
                val snapshot = querySessionQuiescence(removed.transport, identity)
                if (snapshot != null && dispatchRepository.confirmRemoteSessionQuiescent(identity, snapshot)) {
                    verifiedQuiescenceSnapshots[authority] = identity to snapshot
                    if (retiringSessions.remove(authority, removed)) {
                        runCatching { removed.transport.close() }
                    }
                    break
                }
                try {
                    Thread.sleep(25L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "mar-root-retire-${authority.name}").apply {
            isDaemon = true
            start()
        }
    }

    fun pollAndSettleRemoteQuiescence(
        authority: Authority,
        activeSessions: ConcurrentHashMap<Authority, ManagedRootSessionState>,
        retiringSessions: ConcurrentHashMap<Authority, ManagedRootSessionState>,
        dispatchRepository: RootDispatchRepository,
    ): Pair<PrivilegedSessionIdentity, RootRemoteQuiescenceSnapshot>? {
        val target = activeSessions[authority] ?: retiringSessions[authority] ?: return null
        val identity = target.session.identity
        val snapshot = querySessionQuiescence(target.transport, identity) ?: return null
        if (!dispatchRepository.confirmRemoteSessionQuiescent(identity, snapshot)) return null
        verifiedQuiescenceSnapshots[authority] = identity to snapshot
        return identity to snapshot
    }

    fun acknowledgeDegradedRecovery(
        db: SqlConnection,
        authority: Authority,
        userConfirmed: Boolean,
        activeSessions: ConcurrentHashMap<Authority, ManagedRootSessionState>,
        retiringSessions: ConcurrentHashMap<Authority, ManagedRootSessionState>,
        dispatchBridge: RootCoordinatorDispatchBridge,
        dispatchRepository: RootDispatchRepository,
        authorityRepository: RootAuthorityRepository,
    ): RootAuthorityRecord? {
        if (!userConfirmed) return null
        val current = authorityRepository.get(authority)
        if (!current.degraded) return current
        val drains = RootPendingDrainRepository(db)
        val pendingLoad = drains.load(authority)
        // A damaged obligation is never read as "nothing pending".
        if (pendingLoad is RootPendingDrainLoad.Invalid) return null
        val pending = (pendingLoad as? RootPendingDrainLoad.Present)?.state
        val settledPair = if (pending != null) {
            // The persisted obligation survives controller rebuild: a missing in-memory session is
            // never evidence. Only a proof bound to the recorded session may clear it.
            val liveIdentity = (activeSessions[authority] ?: retiringSessions[authority])?.session?.identity
            if (liveIdentity != null && pending.covers(liveIdentity)) {
                val settled = pollAndSettleRemoteQuiescence(
                    authority,
                    activeSessions,
                    retiringSessions,
                    dispatchRepository,
                ) ?: return null
                if (!pending.provenBy(settled.first, settled.second)) return null
                settled
            } else {
                // The retire thread of this same process may already have proven the session and
                // removed it from both maps; that cached proof is still bound to the identity.
                val cached = verifiedQuiescenceSnapshots[authority] ?: return null
                if (!pending.provenBy(cached.first, cached.second)) return null
                cached
            }
        } else {
            val activeTarget = activeSessions[authority]
            val retiringTarget = retiringSessions[authority]
            when {
                activeTarget != null ->
                    pollAndSettleRemoteQuiescence(authority, activeSessions, retiringSessions, dispatchRepository)
                        ?: return null
                retiringTarget != null -> {
                    val identity = retiringTarget.session.identity
                    val snapshot = querySessionQuiescence(retiringTarget.transport, identity)
                    if (snapshot != null) {
                        if (!dispatchRepository.confirmRemoteSessionQuiescent(identity, snapshot)) return null
                        verifiedQuiescenceSnapshots[authority] = identity to snapshot
                        identity to snapshot
                    } else {
                        verifiedQuiescenceSnapshots[authority]
                    }
                }
                else -> verifiedQuiescenceSnapshots[authority]
            }
        }
        if (dispatchRepository.hasActivePermitsForAuthority(authority)) return null
        // Durable receipts must be settled by the same proof; a still-open receipt means the
        // remote (or the ledger that records it) has not drained yet.
        if (dispatchRepository.hasUndrainedReceiptsForAuthority(authority)) return null
        val recovered = runCatching {
            RootReceiptRecoveryCoordinator(db).rotateEpochAndAcknowledgeRecovery(
                authority = authority,
                userConfirmed = true,
                expectedSessionIdentity = settledPair?.first,
                quiescenceSnapshot = settledPair?.second,
            )
        }.getOrNull() ?: return null
        verifiedQuiescenceSnapshots.remove(authority)
        detachAndRetireSession(
            authority = authority,
            reason = "recovery_acknowledged",
            forceCloseImmediately = true,
            activeSessions = activeSessions,
            retiringSessions = retiringSessions,
            dispatchBridge = dispatchBridge,
            dispatchRepository = dispatchRepository,
        )
        return recovered
    }
}
