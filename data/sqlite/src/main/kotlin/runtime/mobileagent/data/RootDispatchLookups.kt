// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootDispatchReceipt
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootExecutionStamp

/**
 * Read-only ledger lookups extracted from [RootDispatchRepository] for the conditional-token
 * ratchet. They never mutate state and keep the same "durable receipt is the evidence" semantics.
 */
internal object RootDispatchLookups {
    /**
     * True when the invocation is a controlled-shell request, i.e. resolving its execution stamp
     * needs the dedicated Root shell consent.
     *
     * Pure extraction of the pre-refactor body: it inspects the live and released permits only
     * (the in-memory view of this controller). It deliberately does **not** consult durable
     * receipts, so post-restart disclosure gating is unchanged by this move.
     */
    fun requiresShell(
        permits: Collection<RootDispatchPermit>,
        authority: Authority,
        invocationId: String,
    ): Boolean {
        fun matches(permit: RootDispatchPermit): Boolean {
            val request = permit.claim.approval.request
            return request.execution.identity.authority == authority &&
                request.invocationId == invocationId &&
                permit.claim.approval.capability.value == CapabilityId.SHELL_EXECUTE
        }
        return permits.any(::matches)
    }

    fun executionForInvocation(
        permits: Collection<RootDispatchPermit>,
        authority: Authority,
        invocationId: String,
    ): RootExecutionStamp? = permits
        .map { it.claim.approval.request }
        .filter { it.execution.identity.authority == authority && it.invocationId == invocationId }
        .minByOrNull { it.execution.identity.identityEpoch }
        ?.execution

    fun hasUndrainedReceipts(receipts: List<RootDispatchReceipt>, authority: Authority): Boolean =
        receipts.any {
            it.claim.approval.request.execution.identity.authority == authority && !it.closed
        }

    fun undrainedSessionIdentities(
        receipts: List<RootDispatchReceipt>,
        authority: Authority,
    ): Set<PrivilegedSessionIdentity> = receipts.filter {
        it.claim.approval.request.execution.identity.authority == authority && !it.closed
    }.mapTo(linkedSetOf()) { it.claim.approval.request.execution.identity }
}
