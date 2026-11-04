// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.*

/** Lifecycle facts/audit only. A terminal outcome does not authorize disclosure. */
internal class RootDispatchTerminalizer(private val db: SqlConnection, private val clock: () -> String) {
    private val ledger = RootDispatchLedger(db)
    private val guard = RootDispatchGuard(db,clock)

    fun finish(current: RootDispatchReceipt, outcome: RootDispatchOutcome, resultSha256: String?, remoteQuiescent: Boolean = false): RootDispatchReceipt {
        val identity = current.claim.approval.request.execution.identity
        val drains = RootPendingDrainRepository(db, clock)
        if (remoteQuiescent && (outcome == RootDispatchOutcome.UNKNOWN_OUTCOME || current.outcome == RootDispatchOutcome.UNKNOWN_OUTCOME)) {
            drains.markRequestQuiescent(identity, current.claim.requestId)
        }
        val drainProven = remoteQuiescent || current.claim.requestId in drains.provenRequests(identity.authority)
        if (current.stage == RootDispatchStage.TERMINAL) {
            require(current.outcome == outcome && current.resultSha256 == resultSha256)
            requireAudit(current)
            return current
        }
        require(allowed(current.stage,outcome))
        guard.requireConsumedApproval(current.claim)
        val c = current.claim
        val b = c.approval
        val auditId = "root-terminal-${RootReceiptDigests.text(c.requestId).take(32)}"
        val at = clock()
        val detail = ToolAuditDetail.builder(auditId,c.requestId,b.approval.agentId,b.capability,outcome.name,at)
            .authority(b.approval.selectedAuthority).workspaceHash(b.workspaceId).approval(b.approval.approvalId)
            .dangerousMode(b.approval.dangerousMode).policyVersion(b.request.execution.policyVersion)
            .commandHash(b.approval.commandHash).cwdHash(b.approval.cwdHash).build()
        RootAuditRepository(db).append(detail,b)
        if (outcome == RootDispatchOutcome.UNKNOWN_OUTCOME && !drainProven) {
            val roots = RootAuthorityRepository(db,clock)
            val state = roots.get(b.approval.selectedAuthority)
            check(roots.markDegraded(b.approval.selectedAuthority,state.revision,b.request.execution.identity.identityEpoch) != null)
            // Durable drain obligation: a rebuilt controller or a later process must not read the
            // absent in-memory session maps as "nothing was running".
            RootPendingDrainRepository(db,clock).record(
                identity = b.request.execution.identity,
                requestId = c.requestId,
                degradedEpoch = b.request.execution.identity.identityEpoch,
            )
        }
        val next = current.copy(stage=RootDispatchStage.TERMINAL,revision=current.revision+1,
            outcome=outcome,terminalAuditId=auditId,finishedAt=at,resultSha256=resultSha256)
        ledger.update(current,next)
        guard.moveInvocation(c,invocationStage(current.stage),"ROOT_DISPATCH_RESULT_PENDING")
        requireAudit(next)
        return next
    }

    fun close(current: RootDispatchReceipt, disclosed: Boolean): RootDispatchReceipt {
        require(current.stage == RootDispatchStage.TERMINAL)
        requireAudit(current)
        val target = if (disclosed) RootDisclosureState.RELEASED else RootDisclosureState.WITHHELD
        if (current.closed) { require(current.disclosure == target); return current }
        val next = current.copy(revision=current.revision+1,closed=true,disclosure=target)
        ledger.update(current,next)
        // Disclosed FAILED is an observed nonzero result and keeps its FAILED invocation state;
        // only the withheld form becomes COMPLETED_WITHHELD. REJECTED_BEFORE_ACCEPTED never ran,
        // so it stays FAILED even when its (empty) result is withheld.
        val invocation = current.outcome?.let { invocationState(it,disclosed) } ?: "FAILED"
        guard.moveInvocation(current.claim,"ROOT_DISPATCH_RESULT_PENDING",invocation)
        return next
    }

    fun requireAudit(receipt: RootDispatchReceipt) {
        val id = requireNotNull(receipt.terminalAuditId)
        val b = receipt.claim.approval
        require(RootAuditRepository(db).get(id) == RootAuditBinding(id,b))
        val detail = requireNotNull(AuditRepository(db).getDetail(id))
        val event = requireNotNull(AuditRepository(db).get(id))
        require(detail.requestId == receipt.claim.requestId && detail.approvalId == b.approval.approvalId &&
            detail.authority == b.approval.selectedAuthority && detail.result == receipt.outcome?.name &&
            event.runId == b.request.runId && detail.createdAt == receipt.finishedAt)
    }

    private fun allowed(stage: RootDispatchStage, outcome: RootDispatchOutcome): Boolean = when (stage) {
        RootDispatchStage.CLAIMED -> outcome == RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT
        RootDispatchStage.ATTEMPTED -> outcome != RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT
        RootDispatchStage.ACKNOWLEDGED -> outcome in setOf(RootDispatchOutcome.SUCCEEDED,
            RootDispatchOutcome.FAILED,RootDispatchOutcome.UNKNOWN_OUTCOME)
        RootDispatchStage.TERMINAL -> false
    }

    companion object {
        fun invocationStage(stage: RootDispatchStage): String = when(stage) {
            RootDispatchStage.CLAIMED -> "ROOT_DISPATCH_CLAIMED"
            RootDispatchStage.ATTEMPTED -> "ROOT_DISPATCH_ATTEMPTED"
            RootDispatchStage.ACKNOWLEDGED -> "ROOT_DISPATCH_ACKNOWLEDGED"
            RootDispatchStage.TERMINAL -> "ROOT_DISPATCH_RESULT_PENDING"
        }

        /**
         * Invocation state written for a receipt outcome. The deferred-disclosure guard uses the
         * same mapping for a released receipt, so a disclosed FAILED result can be revalidated
         * for its paged continuation without widening WITHHELD receipts.
         */
        fun invocationState(outcome: RootDispatchOutcome, disclosed: Boolean): String = when (outcome) {
            RootDispatchOutcome.SUCCEEDED -> if (disclosed) "SUCCEEDED" else "COMPLETED_WITHHELD"
            RootDispatchOutcome.FAILED -> if (disclosed) "FAILED" else "COMPLETED_WITHHELD"
            RootDispatchOutcome.UNKNOWN_OUTCOME -> "UNKNOWN_OUTCOME"
            RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT -> "CANCELLED"
            RootDispatchOutcome.REJECTED_BEFORE_ACCEPTED -> "FAILED"
        }
    }
}
