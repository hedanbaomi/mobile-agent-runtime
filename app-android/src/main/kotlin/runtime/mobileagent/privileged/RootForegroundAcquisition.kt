// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.domain.RootAuthorityRecord

/**
 * Foreground Root acquisition: consent check, generation capture, the RPC, and the fenced
 * publish/abort choreography.
 *
 * Extracted from [RootRuntimeCoordinator] for the conditional-token ratchet; the ordering is
 * unchanged: no coordinator lock is held across the connector RPC, and the publish is validated
 * against the captured generation, the close flag and the live consent.
 */
internal class RootForegroundAcquisition(
    private val connector: RootSessionConnector,
    private val currentRecord: () -> RootAuthorityRecord,
    private val hasActiveSession: () -> Boolean,
    private val isReady: () -> Boolean,
    private val advanceEpoch: (Long) -> RootAuthorityRecord?,
    private val nextGeneration: () -> Long?,
    private val currentGeneration: () -> Long?,
    private val isClosed: () -> Boolean,
    private val completeAndAttach: (Long, RootAcquiredSession) -> Boolean,
    private val verifyAuthorization: () -> Boolean,
) {
    fun request(
        authority: Authority,
        appInstanceId: String,
        shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?,
    ): Boolean {
        require(authority.isRootAuthority)
        val current = currentRecord()
        if (!current.consent.enabled || current.degraded) return false
        // An existing ready session is the answer: never launch a second `su`.
        if (hasActiveSession()) return isReady()
        val record = advanceEpoch(current.revision) ?: return false
        val generation = nextGeneration() ?: return false
        val acquired = connector.tryAcquireSession(
            authority = authority,
            identityEpoch = record.identityEpoch,
            appInstanceId = appInstanceId,
            shizukuState = shizukuState,
        ) ?: return false
        val attached = runCatching { publish(generation, acquired) }.getOrDefault(false)
        if (!attached) {
            runCatching { connector.abortAcquisition(authority, acquired.attemptToken) }
            runCatching { acquired.transport?.close() }
            return false
        }
        if (authority == Authority.ROOT_DIRECT && !verifyAuthorization()) {
            runCatching { connector.abortAcquisition(authority, acquired.attemptToken) }
            runCatching { acquired.transport?.close() }
            return false
        }
        return true
    }

    private fun publish(
        generation: Long,
        acquired: RootAcquiredSession,
    ): Boolean {
        val stillCurrent = !isClosed() &&
            currentGeneration() == generation &&
            currentRecord().consent.enabled
        if (!stillCurrent) return false
        // Ownership transfer + attach happen exactly once, inside the coordinator's short lock.
        return completeAndAttach(generation, acquired)
    }
}

/** The recovery proof must come from the exact newly acquired, still-live Root session. */
internal fun RootAcquiredSession.matchesRecoveryCandidate(
    authority: Authority, nextEpoch: Long, appInstanceId: String,
): Boolean = RootCoordinatorReadiness.matchesRootCaller(authority, identity, caller) &&
    identity.identityEpoch == nextEpoch && identity.appInstanceId == appInstanceId && transport?.isAlive() == true
