// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/**
 * Two-stage cancellation and remote drain lifecycle for Root executions (`W1`, `ROOT06/07/08/16`):
 * - [CANCEL_RECORDED] / [INTERRUPT_SENT]: cancellation intent is recorded and signals were sent,
 *   but remote worker threads, stream/PFD leases, retained FDs, or child processes may still be active.
 * - [QUIESCENT_CONFIRMED]: all remote worker threads, stream/PFD leases, retained FDs, and
 *   supervised child processes for the target scope are confirmed drained.
 */
@Serializable
enum class RootRemoteCancelStage {
    NONE,
    CANCEL_RECORDED,
    INTERRUPT_SENT,
    QUIESCENT_CONFIRMED,
}

@Serializable
data class RootQuiescenceQueryRequest(
    val schemaVersion: Int = CURRENT_VERSION,
    val targetAuthority: Authority,
    val targetServiceInstanceId: String,
    val targetSessionId: String,
    val targetIdentityEpoch: Long,
    val invocationId: String? = null,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

@Serializable
data class RootRemoteQuiescenceSnapshot(
    val schemaVersion: Int = RootQuiescenceQueryRequest.CURRENT_VERSION,
    val accepted: Boolean,
    val authority: Authority? = null,
    val serviceInstanceId: String? = null,
    val sessionId: String? = null,
    val identityEpoch: Long = 0L,
    val servicePid: Int = -1,
    val serviceStartTime: Long = -1L,
    val activeWorkerCount: Int = 0,
    val activeStreamLeaseCount: Int = 0,
    val activeRetainedFdCount: Int = 0,
    val activeShellProcessCount: Int = 0,
    val unconfirmedShellProcessCount: Int = 0,
    val activeRequestIds: Set<String> = emptySet(),
    val unconfirmedRequestIds: Set<String> = emptySet(),
    val cancelStage: RootRemoteCancelStage = RootRemoteCancelStage.NONE,
    val quiescent: Boolean = false,
) {
    fun matchesIdentity(expected: PrivilegedSessionIdentity): Boolean =
        setOf(
            accepted,
            schemaVersion == RootQuiescenceQueryRequest.CURRENT_VERSION,
            authority == expected.authority,
            serviceInstanceId == expected.serviceInstanceId,
            sessionId == expected.sessionId,
            identityEpoch == expected.identityEpoch,
        ).all { it }

    fun hasZeroActiveResources(): Boolean =
        listOf(
            activeWorkerCount,
            activeStreamLeaseCount,
            activeRetainedFdCount,
            activeShellProcessCount,
            unconfirmedShellProcessCount,
            activeRequestIds.size,
            unconfirmedRequestIds.size,
        ).all { it == 0 }

    fun provesSessionQuiescent(expected: PrivilegedSessionIdentity): Boolean =
        listOf(
            matchesIdentity(expected),
            quiescent,
            cancelStage != RootRemoteCancelStage.CANCEL_RECORDED,
            cancelStage != RootRemoteCancelStage.INTERRUPT_SENT,
            hasZeroActiveResources(),
        ).all { it }

    fun provesInvocationQuiescent(
        expected: PrivilegedSessionIdentity,
        requestId: String,
        invocationId: String,
    ): Boolean =
        listOf(
            matchesIdentity(expected),
            quiescent,
            requestId !in activeRequestIds,
            invocationId !in activeRequestIds,
            requestId !in unconfirmedRequestIds,
            invocationId !in unconfirmedRequestIds,
            activeWorkerCount == 0,
            activeStreamLeaseCount == 0,
            activeRetainedFdCount == 0,
            activeShellProcessCount == 0,
            unconfirmedShellProcessCount == 0,
        ).all { it }
}
