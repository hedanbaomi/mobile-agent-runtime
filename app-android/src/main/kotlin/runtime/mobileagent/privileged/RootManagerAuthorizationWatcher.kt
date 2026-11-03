// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.concurrent.atomic.AtomicBoolean
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootAuthorityRecord

/**
 * Live manager-authorization observer for [Authority.ROOT_DIRECT].
 *
 * A manager revocation does not kill the running UID0 child, so the coordinator asks the *service*
 * (already authorized, non-interactive, read-only) for the manager's persistent policy. No new
 * `su` request is issued from the app.
 *
 * F7 fencing lives here: the session identity/transport/record are captured before the RPC and a
 * result is applied only through [apply], which re-validates that the very same session is still
 * active and the coordinator is not closing.
 */
internal class RootManagerAuthorizationWatcher(
    private val intervalMs: () -> Long,
    private val currentSession: () -> ManagedRootSessionState?,
    private val currentRecord: () -> RootAuthorityRecord?,
    private val onObservation: (String) -> Unit,
    private val isClosed: () -> Boolean,
    /**
     * Applies one observation. The coordinator runs this inside its own short lock, so the
     * identity/transport/record/closed validation and the decision write are one atomic step; the
     * RPC above stays outside the lock.
     */
    private val applyObservation: (
        PrivilegedSessionIdentity,
        RootChannelTransport,
        RootAuthorityRecord,
        RootManagerAuthorization,
        String,
    ) -> Boolean,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val worker = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(intervalMs().coerceAtLeast(100L))
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                observeOnce()
            }
        }, "mar-root-manager-observer").apply { isDaemon = true }
        thread = worker
        worker.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    /**
     * One bounded observation of the current session. Returns true only for a bound GRANTED.
     */
    fun observeOnce(): Boolean {
        val state = currentSession() ?: return false
        val identity = state.session.identity
        val transport = state.transport
        val record = currentRecord() ?: return false
        val requestJson = runCatching {
            RootPrivilegedServiceEngine.json.encodeToString(
                RootManagerAuthorizationRequest.serializer(),
                RootManagerAuthorizationRequest(
                    serviceInstanceId = identity.serviceInstanceId,
                    sessionId = identity.sessionId,
                    identityEpoch = identity.identityEpoch,
                ),
            )
        }.getOrNull() ?: return false
        val raw = runCatching { transport.queryManagerAuthorization(requestJson) }.getOrNull()
        if (raw == null) {
            onObservation("unavailable")
            return apply(identity, transport, record, RootManagerAuthorization.UNKNOWN, "unavailable")
        }
        val response = runCatching {
            RootPrivilegedServiceEngine.json.decodeFromString<RootManagerAuthorizationResponse>(raw)
        }.getOrNull()
        if (response == null ||
            response.serviceInstanceId != identity.serviceInstanceId ||
            response.sessionId != identity.sessionId ||
            response.identityEpoch != identity.identityEpoch
        ) {
            onObservation("identity_mismatch")
            return apply(identity, transport, record, RootManagerAuthorization.UNKNOWN, "identity_mismatch")
        }
        onObservation("${response.result}:${response.reason}")
        val decision = runCatching { RootManagerAuthorization.valueOf(response.result) }
            .getOrDefault(RootManagerAuthorization.UNKNOWN)
        return apply(identity, transport, record, decision, response.reason)
    }

    /** Applies one bound observation through the coordinator's atomic fence. */
    private fun apply(
        identity: PrivilegedSessionIdentity,
        transport: RootChannelTransport,
        record: RootAuthorityRecord,
        decision: RootManagerAuthorization,
        reason: String,
    ): Boolean {
        if (isClosed()) return false
        return applyObservation(identity, transport, record, decision, reason)
    }
}
