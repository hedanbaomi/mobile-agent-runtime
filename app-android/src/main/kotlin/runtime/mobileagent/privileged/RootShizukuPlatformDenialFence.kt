// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAuthorityRecord
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootHostControlGate
import runtime.mobileagent.shizuku.ShizukuAuthorityState
import runtime.mobileagent.shizuku.ShizukuBridgePolicy

/**
 * Fences a live `SHIZUKU_ROOT` session when the platform explicitly denies it (permission revoked,
 * binder gone, or the server is no longer UID 0).
 *
 * Extracted from [RootRuntimeCoordinator] to stay inside the conditional-token budget. The whole
 * decision runs under the coordinator's monitor so it races neither a replacement attach nor
 * `close()`:
 * 1. capture the current record and re-check close/consent/degraded;
 * 2. invalidate that authority's acquisition generations (no late completion may attach);
 * 3. revoke the host gate first (old approvals can no longer submit);
 * 4. apply the DEGRADED decision with the captured record revision/epoch;
 * 5. retire the session (map removal inline; the revoke RPC runs on the retire thread).
 */
internal class RootShizukuPlatformDenialFence(
    private val lock: Any,
    private val isClosed: () -> Boolean,
    private val record: () -> RootAuthorityRecord,
    private val invalidateAcquisitions: () -> Unit,
    private val hostGate: () -> RootHostControlGate?,
    private val markDegraded: (revision: Long, identityEpoch: Long) -> Unit,
    private val detach: (reason: String) -> Unit,
) {
    /**
     * True only for an explicit platform denial. `ready=false` with SERVER_UID_UNTRUSTED or a null
     * `userServiceUid` describes the *shell* bridge of a UID0 server and is not a denial.
     */
    fun isDenied(state: ShizukuAuthorityState?): Boolean {
        if (state == null) return false
        return !state.binderAlive || !state.permissionGranted ||
            (state.serverUid != null && state.serverUid != 0)
    }

    /** Denial only applies to the Shizuku authority, never to ROOT_DIRECT. */
    fun deniesDispatch(authority: Authority, state: ShizukuAuthorityState?): Boolean =
        authority == Authority.SHIZUKU_ROOT && isDenied(state)

    fun fenceIfDenied(state: ShizukuAuthorityState?): Boolean =
        isDenied(state) && fence()

    /** Explicit denial from the live SDK facts (unknown/timeout is represented as not-authorizing). */
    fun deniesFacts(facts: RootShizukuLiveFacts?): Boolean {
        if (facts == null) return false
        return RootShizukuBridgePolicy.evaluateRootShizukuServer(
            selectedAuthority = Authority.SHIZUKU_ROOT,
            binderAlive = facts.binderAlive,
            preV11 = facts.preV11,
            permissionGranted = facts.permissionGranted,
            serverUid = facts.serverUid,
            serverVersion = facts.serverVersion,
        ) is RootBridgeGateDecision.Denied
    }

    /** True only for facts that the real policy authorizes; null/unknown never authorizes. */
    fun authorizes(facts: RootShizukuLiveFacts?): Boolean =
        facts != null && !deniesFacts(facts)

    /** Explicit state from RuntimeIntegration uses the same real fields/policy. */
    fun stateFacts(state: ShizukuAuthorityState): RootShizukuLiveFacts = RootShizukuLiveFacts(
        binderAlive = state.binderAlive,
        preV11 = (state.apiVersion ?: 0) < ShizukuBridgePolicy.MIN_SERVER_VERSION,
        permissionGranted = state.permissionGranted,
        serverUid = state.serverUid,
        serverVersion = state.apiVersion,
    )

    /** Latest bounded observation; a timeout is recorded as a non-authorizing fact. */
    @Volatile
    private var lastFacts: RootShizukuLiveFacts? = null

    fun recordFacts(facts: RootShizukuLiveFacts?) {
        lastFacts = facts ?: UNKNOWN_FACTS
    }

    /** True only when an observation exists and the real policy denies it. */
    fun lastDenies(): Boolean = lastFacts?.let { deniesFacts(it) } == true

    /** Records an explicit state and fences when the real policy denies it. */
    fun applyExplicitState(state: ShizukuAuthorityState?): Boolean = synchronized(lock) {
        if (state == null) return false
        val facts = stateFacts(state)
        if (!deniesFacts(facts)) {
            recordFacts(facts)
            return false
        }
        val fenced = fence()
        recordFacts(facts)
        fenced
    }

    /**
     * Applies one live sample under the coordinator lock after re-verifying the captured session
     * identity and record revision/epoch (F7 fencing): a slow observation can never retire a
     * replacement session, and an old generation is ignored.
     */
    fun applyObserved(
        capturedTransport: Any?,
        capturedIdentity: PrivilegedSessionIdentity?,
        recordRevision: Long,
        recordEpoch: Long,
        facts: RootShizukuLiveFacts?,
        closed: () -> Boolean,
        currentTransport: () -> Any?,
        currentIdentity: () -> PrivilegedSessionIdentity?,
        currentStamp: () -> Pair<Long, Long>?,
    ): Boolean = synchronized(lock) {
        if (closed()) return false
        // Same guard as the Direct watcher: the captured session object/transport must still be the
        // live one, so a slow observation can never retire a replacement session.
        if (capturedTransport == null || currentTransport() !== capturedTransport) return false
        if (capturedIdentity == null || currentIdentity() != capturedIdentity) return false
        val stamp = currentStamp() ?: return false
        if (stamp.first != recordRevision || stamp.second != recordEpoch) return false
        // Unknown/timeout is recorded and fenced like a denial: a stale allow must not survive and
        // the captured stamp/approval must not dispatch.
        val recorded = facts ?: UNKNOWN_FACTS
        if (!deniesFacts(recorded)) {
            lastFacts = recorded
            return false
        }
        val fenced = fence()
        lastFacts = recorded
        fenced
    }

    /** True when a persisted consent/degraded state requires a detach. */
    fun needsDetach(record: RootAuthorityRecord): Boolean = !record.consent.enabled || record.degraded

    private companion object {
        val UNKNOWN_FACTS = RootShizukuLiveFacts(
            binderAlive = false,
            preV11 = false,
            permissionGranted = false,
            serverUid = null,
            serverVersion = null,
        )
    }

    fun fence(): Boolean = synchronized(lock) {
        if (isClosed()) return false
        val current = record()
        if (!current.consent.enabled || current.degraded) return false
        invalidateAcquisitions()
        hostGate()?.markRevoked()
        markDegraded(current.revision, current.identityEpoch)
        detach("platform_permission_revoked")
        true
    }
}
