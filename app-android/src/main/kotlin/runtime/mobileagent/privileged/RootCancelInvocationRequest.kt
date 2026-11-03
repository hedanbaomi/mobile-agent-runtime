// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlLedger
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootServiceExecutionStage
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootServiceTerminalOutcome

/** A task owns its worker until detach; signal and detach share this short, task-local gate. */
internal class WorkerOwnership(private var thread: Thread?) {
    @Synchronized fun interrupt() { thread?.interrupt() }
    @Synchronized fun detach() { thread = null }
}

/** Versioned session-bound cancel intent. RPC true confirms non-start/known stop, never intent alone. */
@Serializable
data class RootCancelInvocationRequest(
    val schemaVersion: Int = 1,
    val targetServiceInstanceId: String,
    val targetSessionId: String,
    val targetIdentityEpoch: Long,
    val invocationId: String,
) {
    companion object {
        internal fun decode(raw: String): RootCancelInvocationRequest? =
            runCatching { RootPrivilegedServiceEngine.json.decodeFromString<RootCancelInvocationRequest>(raw) }
                .getOrNull()?.takeIf { it.schemaVersion == 1 }

        internal fun cancelMatchedSessionInvocation(
            request: RootCancelInvocationRequest,
            session: RootServiceSession,
            activeSession: RootServiceSession?,
            verifiedCaller: RootCallerObservation?,
            observedCallingUid: Int,
            observedServiceUid: Int,
            cancelledRequestIds: MutableSet<String>,
        ): List<String>? {
            val caller = verifiedCaller ?: return null
            if (activeSession !== session || observedCallingUid != caller.callingUid) return null
            val liveCaller = caller.copy(observedServiceUid = observedServiceUid)
            if (!liveCaller.matches(session.identity)) return null
            val accepted = session.cancelInvocation(
                liveCaller,
                request.targetServiceInstanceId,
                request.targetSessionId,
                request.targetIdentityEpoch,
                request.invocationId,
            )
            if (!accepted) return null
            val requests = session.ledger.requestIdsForInvocation(request.invocationId)
            cancelledRequestIds.addAll(requests)
            return requests
        }

        internal fun isSettledConfirmedCancellation(
            ids: List<String>,
            ledger: RootControlLedger,
            inFlightThreads: Map<String, WorkerOwnership>,
        ): Boolean = ids.all { id ->
            val status = ledger.observe(id)
            status?.stage == RootServiceExecutionStage.TERMINAL &&
                status.terminalOutcome != RootServiceTerminalOutcome.UNKNOWN_OUTCOME &&
                !inFlightThreads.containsKey(id)
        }

        internal fun dispatchCancellationRoute(
            activeIdentity: PrivilegedSessionIdentity?,
            transport: RootChannelTransport?,
            execution: RootExecutionStamp,
            onUnconfirmed: (String) -> Unit,
        ): (String) -> Boolean {
            val matchedTransport = transport.takeIf { activeIdentity == execution.identity }
            val identity = execution.identity
            return { invocationId ->
                val confirmed = runCatching {
                    matchedTransport?.cancelInvocation(
                        RootCancelInvocationRequest(
                            targetServiceInstanceId = identity.serviceInstanceId,
                            targetSessionId = identity.sessionId,
                            targetIdentityEpoch = identity.identityEpoch,
                            invocationId = invocationId,
                        ),
                    ) == true
                }.getOrDefault(false)
                if (!confirmed) onUnconfirmed(invocationId)
                confirmed
            }
        }
    }
}
