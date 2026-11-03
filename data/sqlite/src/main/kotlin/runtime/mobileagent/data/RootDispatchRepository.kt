// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import runtime.mobileagent.domain.*

/**
 * Local transactional reservations. The App's single Root session controller must
 * hold its policy/transport lock and prove live OS identity before actual submission
 * and result forwarding. This repository performs no IPC and cannot prove either.
 */
class RootDispatchRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    internal val controllerId = UUID.randomUUID().toString()
    private val permits = linkedMapOf<String,RootDispatchPermit>()
    private val releasedPermits = linkedMapOf<String,RootDispatchPermit>()
    private val tickets = linkedMapOf<String,RootAttemptTicket>()
    private val ledger = RootDispatchLedger(db)
    private val guard = RootDispatchGuard(db,clock)
    private val terminalizer = RootDispatchTerminalizer(db,clock)

    private val cancelledInvocations = mutableSetOf<Pair<RootExecutionStamp, String>>()
    private val saturatedExecutions = mutableSetOf<RootExecutionStamp>()
    private val retiredSessions = linkedSetOf<String>()
    private val remoteActiveRequests = linkedSetOf<String>()

    @Synchronized
    fun forgetSession(sessionId: String) {
        permits.values.filter {
            it.claim.approval.request.execution.identity.sessionId == sessionId
        }.toList().forEach { permit ->
            val row = current(permit)
            if (row.stage != RootDispatchStage.TERMINAL) {
                finish(permit, if (row.stage == RootDispatchStage.CLAIMED)
                    RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT else RootDispatchOutcome.UNKNOWN_OUTCOME)
            }
            if (permit.requestId !in remoteActiveRequests) {
                closePermit(permit, disclosed = false)
            }
        }
        cancelledInvocations.removeAll { (execution, _) -> execution.identity.sessionId == sessionId }
        saturatedExecutions.removeAll { execution -> execution.identity.sessionId == sessionId }
        releasedPermits.entries.removeAll { (_, p) -> p.claim.approval.request.execution.identity.sessionId == sessionId }
        retiredSessions.remove(sessionId)
        if (retiredSessions.size >= RootControlLedger.MAX_SESSION_REQUESTS) {
            retiredSessions.remove(retiredSessions.first())
        }
        retiredSessions.add(sessionId)
    }

    /** Tombstone bookkeeping only applies to executions of live (non-retired) sessions. */
    private fun isEligibleForTombstone(execution: RootExecutionStamp): Boolean =
        execution.identity.sessionId !in retiredSessions

    /**
     * True when the invocation needs the controlled-shell Root consent to resolve its stamp.
     *
     * Recovered verbatim in behaviour after the complexity extraction: the pre-refactor body
     * inspected `permits` + `releasedPermits` only (reviewer before-copy L64-69); durable receipts
     * are intentionally not consulted here.
     */
    fun requiresShellForInvocation(authority: Authority, invocationId: String): Boolean =
        RootDispatchLookups.requiresShell(
            permits = permits.values + releasedPermits.values,
            authority = authority,
            invocationId = invocationId,
        )

    @Synchronized
    fun executionForInvocation(authority: Authority, invocationId: String): RootExecutionStamp? =
        RootDispatchLookups.executionForInvocation(
            permits = permits.values + releasedPermits.values,
            authority = authority,
            invocationId = invocationId,
        )

    /** No RPC under this monitor. Settle owned attempts now, not on a possibly absent reply. */
    @Synchronized
    fun cancelUnconfirmedInvocation(execution: RootExecutionStamp, invocationId: String) {
        if (isEligibleForTombstone(execution)) {
            cancelledInvocations.removeAll { (stamp, _) -> stamp != execution && !isEligibleForTombstone(stamp) }
            saturatedExecutions.removeAll { stamp -> stamp != execution && !isEligibleForTombstone(stamp) }
            val countForExecution = cancelledInvocations.count { (stamp, _) -> stamp == execution }
            if (countForExecution < RootControlLedger.MAX_SESSION_REQUESTS &&
                (execution to invocationId) !in cancelledInvocations
            ) {
                if (cancelledInvocations.size >= RootControlLedger.MAX_SESSION_REQUESTS) {
                    val victim = cancelledInvocations.firstOrNull { (stamp, _) -> stamp != execution }
                        ?: cancelledInvocations.first()
                    cancelledInvocations.remove(victim)
                }
                cancelledInvocations += execution to invocationId
            }
            if (countForExecution + 1 >= RootControlLedger.MAX_SESSION_REQUESTS) {
                if (saturatedExecutions.size >= RootControlLedger.MAX_SESSION_REQUESTS &&
                    execution !in saturatedExecutions
                ) {
                    saturatedExecutions.remove(saturatedExecutions.first())
                }
                saturatedExecutions += execution
            }
        }
        permits.values.filter {
            it.claim.approval.request.execution == execution &&
                it.claim.approval.request.invocationId == invocationId
        }.toList().forEach { permit ->
            val row = current(permit)
            if (row.stage != RootDispatchStage.TERMINAL) {
                finish(permit, if (row.stage == RootDispatchStage.CLAIMED)
                    RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT else RootDispatchOutcome.UNKNOWN_OUTCOME)
            }
            if (permit.requestId !in remoteActiveRequests) {
                closePermit(permit, disclosed = false)
            }
        }
        releasedPermits.entries.removeAll { (_, p) ->
            p.claim.approval.request.execution == execution && p.claim.approval.request.invocationId == invocationId
        }
    }

    @Synchronized
    fun claim(approvalId: String, request: RootApprovalRequest): RootDispatchPermit {
        require(
            request.execution.identity.sessionId !in retiredSessions &&
                request.execution !in saturatedExecutions &&
                (request.execution to request.invocationId) !in cancelledInvocations,
        )
        require(permits.size < RootControlLedger.MAX_SESSION_REQUESTS)
        RootReceiptRecoveryCoordinator(db,clock).requireNoOrphanInvocations()
        val permit = db.transaction {
            val binding = requireNotNull(RootApprovalRepository(db,clock).getCurrent(approvalId,request))
            require(ledger.get(binding.approval.requestId) == null)
            val snapshot = requireNotNull(RootSnapshotGrantRepository(db,clock).revalidate(
                request.snapshotId,request.grantId,request.execution,request.taskIdentity,request.sessionIdentity))
            val before = snapshot.grant
            val at = clock()
            val consumed = ApprovalRepository(db,clock).consume(approvalId,binding.approval)
            require(consumed.decision == ApprovalDecision.CONSUMED && consumed.consumedAt != null)
            val once = before.lifetime == GrantLifetime.ONCE
            val after = if (once) requireNotNull(CapabilityGrantRepository(db,clock).consumeOnce(
                before.grantId,before.revision,request.taskIdentity,request.sessionIdentity,at)) else before
            val c = RootDispatchClaim(binding,controllerId,RootReceiptDigests.grant(before),RootReceiptDigests.grant(after),
                after.revision,once,after.consumedAt,requireNotNull(consumed.consumedAt),at)
            val result = RootDispatchPermit(c,snapshot,after)
            val receipt = RootDispatchReceipt(c)
            ledger.insert(receipt)
            db.execute("UPDATE runs SET state=?,updated_at=? WHERE run_id=? AND state IN(?,?,?)",
                listOf(RunStatus.TOOL_EXECUTING.name,at,request.runId,RunStatus.WAITING_TOOL_APPROVAL.name,RunStatus.TOOL_EXECUTING.name,"RUNNING"))
            guard.moveInvocation(c,"PENDING","ROOT_DISPATCH_CLAIMED")
            guard.validate(result,receipt,"ROOT_DISPATCH_CLAIMED")
            result
        }
        permits[permit.requestId] = permit
        return permit
    }

    @Synchronized
    fun beginAttempt(permit: RootDispatchPermit): RootAttemptTicket {
        requireOwned(permit)
        require(tickets[permit.requestId] == null)
        db.transaction {
            val current = current(permit)
            require(current.stage == RootDispatchStage.CLAIMED)
            guard.validate(permit,current,"ROOT_DISPATCH_CLAIMED")
            val next = current.copy(stage=RootDispatchStage.ATTEMPTED,revision=current.revision+1)
            ledger.update(current,next)
            guard.moveInvocation(current.claim,"ROOT_DISPATCH_CLAIMED","ROOT_DISPATCH_ATTEMPTED")
            guard.validate(permit,next,"ROOT_DISPATCH_ATTEMPTED")
        }
        return RootAttemptTicket(permit).also { tickets[permit.requestId] = it }
    }

    /** Redeem once immediately before the adapter sends, under its Root session coordination lock. */
    @Synchronized
    fun takeAttempt(ticket: RootAttemptTicket, normalizedArgumentsJson: String): RootTransportInvocation {
        val permit = ticket.permit
        requireOwned(permit)
        require(tickets[permit.requestId] === ticket && !ticket.used)
        require(normalizedArgumentsJson.toByteArray(Charsets.UTF_8).size in 1..65_536)
        require(Json.parseToJsonElement(normalizedArgumentsJson) is JsonObject)
        require(RootReceiptDigests.text(normalizedArgumentsJson) == permit.claim.approval.request.normalizedArgumentsSha256)
        db.transaction {
            val row = current(permit)
            require(row.stage == RootDispatchStage.ATTEMPTED)
            guard.validate(permit,row,"ROOT_DISPATCH_ATTEMPTED")
        }
        ticket.used = true
        return RootTransportInvocation(permit.claim,permit.snapshot,normalizedArgumentsJson)
    }

    /** An authenticated adapter owns the ACCEPTED observation; it is not permission to start work. */
    @Synchronized
    fun acknowledge(permit: RootDispatchPermit): RootDispatchReceipt = db.transaction {
        requireOwned(permit)
        require(tickets[permit.requestId]?.used == true)
        val row = current(permit)
        require(row.stage == RootDispatchStage.ATTEMPTED)
        val next = row.copy(stage=RootDispatchStage.ACKNOWLEDGED,revision=row.revision+1)
        ledger.update(row,next)
        guard.moveInvocation(row.claim,"ROOT_DISPATCH_ATTEMPTED","ROOT_DISPATCH_ACKNOWLEDGED")
        next
    }

    /** Actual peer/outcome proof belongs to the adapter; known facts remain auditable after revocation. */
    @Synchronized
    fun finish(permit: RootDispatchPermit, outcome: RootDispatchOutcome, resultSha256: String? = null): RootDispatchReceipt =
        db.transaction {
            requireOwned(permit)
            if (outcome in setOf(RootDispatchOutcome.SUCCEEDED,RootDispatchOutcome.FAILED,
                    RootDispatchOutcome.REJECTED_BEFORE_ACCEPTED)) require(tickets[permit.requestId]?.used == true)
            terminalizer.finish(current(permit),outcome,resultSha256)
        }

    /** Local gate only. The caller must retain its coordination lock through actual forwarding. */
    @Synchronized
    fun authorizeDisclosure(permit: RootDispatchPermit): Boolean {
        requireOwned(permit)
        val allowed = db.transaction {
            val row = current(permit)
            if (row.stage != RootDispatchStage.TERMINAL || row.outcome !in
                setOf(RootDispatchOutcome.SUCCEEDED,RootDispatchOutcome.FAILED)) return@transaction false
            runCatching { guard.validate(permit,row,"ROOT_DISPATCH_RESULT_PENDING") }.isSuccess
        }
        permit.disclosureChecked = allowed
        return allowed
    }

    @Synchronized
    fun activePermitRequestIds(): Set<String> =
        (permits.keys + remoteActiveRequests).toSet()

    @Synchronized
    fun hasActivePermitsForAuthority(authority: Authority): Boolean =
        permits.values.any { it.claim.approval.request.execution.identity.authority == authority } ||
            remoteActiveRequests.any { reqId ->
                ledger.get(reqId)?.claim?.approval?.request?.execution?.identity?.authority == authority
            }

    /**
     * Durable counterpart of [hasActivePermitsForAuthority]: receipts of this authority that were
     * never closed still record unconfirmed remote work after a controller rebuild.
     */
    fun hasUndrainedReceiptsForAuthority(authority: Authority): Boolean =
        RootDispatchLookups.hasUndrainedReceipts(ledger.list(), authority)

    /** Persisted session identities whose remote drain was never confirmed for this authority. */
    fun undrainedSessionIdentities(authority: Authority): Set<PrivilegedSessionIdentity> =
        RootDispatchLookups.undrainedSessionIdentities(ledger.list(), authority)


    @Synchronized
    fun redeemForWireAttempt(permit: RootDispatchPermit, normalizedArgumentsJson: String): RootTransportInvocation? {
        if (permit.closed || permits[permit.requestId] !== permit) return null
        val row = ledger.get(permit.requestId) ?: return null
        if (row.closed || row.stage != RootDispatchStage.CLAIMED) return null
        val ticket = beginAttempt(permit)
        val transportInv = takeAttempt(ticket, normalizedArgumentsJson)
        markRemoteAttemptActive(permit)
        return transportInv
    }

    @Synchronized
    fun abortClaimBeforeAttempt(permit: RootDispatchPermit) {
        if (permit.closed || permits[permit.requestId] !== permit) return
        val row = ledger.get(permit.requestId) ?: return
        if (row.stage == RootDispatchStage.CLAIMED) {
            finish(permit, RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT)
        }
        if (permit.requestId !in remoteActiveRequests && !permit.closed) {
            val latest = ledger.get(permit.requestId)
            if (latest != null && latest.stage == RootDispatchStage.TERMINAL && !latest.closed) {
                closePermit(permit, disclosed = false)
            }
        }
    }

    @Synchronized
    fun markRemoteAttemptActive(permit: RootDispatchPermit) {
        requireOwned(permit)
        remoteActiveRequests += permit.requestId
    }

    @Synchronized
    fun markRemoteWorkerDrainedKeepPermitOpen(requestId: String) {
        remoteActiveRequests.remove(requestId)
    }

    @Synchronized
    fun precheckDeferredInvocation(execution: RootExecutionStamp, invocationId: String): Boolean {
        if ((execution to invocationId) in cancelledInvocations || execution in saturatedExecutions) return false
        findPermit(permits, execution, invocationId)?.let { return authorizeDisclosure(it) }
        return checkReleasedPermit(execution, invocationId)
    }

    @Synchronized
    fun discloseDeferredInvocation(execution: RootExecutionStamp, invocationId: String): Boolean {
        if ((execution to invocationId) in cancelledInvocations || execution in saturatedExecutions) return false
        findPermit(permits, execution, invocationId)?.let { permit ->
            val allowed = authorizeDisclosure(permit)
            closePermit(permit, disclosed = allowed)
            if (allowed) releasedPermits[permit.requestId] = permit
            return allowed
        }
        return checkReleasedPermit(execution, invocationId)
    }

    @Synchronized
    fun authorizeReplayInvocation(execution: RootExecutionStamp, invocationId: String): Boolean =
        checkReleasedPermit(execution, invocationId)

    @Synchronized
    fun withholdDeferredInvocation(execution: RootExecutionStamp, invocationId: String) {
        cancelUnconfirmedInvocation(execution, invocationId)
    }

    private fun findPermit(
        source: Map<String, RootDispatchPermit>,
        execution: RootExecutionStamp,
        invocationId: String,
    ): RootDispatchPermit? = source.values.singleOrNull {
        it.claim.approval.request.execution == execution && it.claim.approval.request.invocationId == invocationId
    }

    private fun checkReleasedPermit(execution: RootExecutionStamp, invocationId: String): Boolean {
        if ((execution to invocationId) in cancelledInvocations || execution in saturatedExecutions) return false
        val permit = findPermit(releasedPermits, execution, invocationId) ?: return false
        val row = db.transaction { ledger.get(permit.requestId) } ?: return false
        val ok = db.transaction { guard.validateReleasedReceipt(permit, row) }
        if (!ok) releasedPermits.remove(permit.requestId)
        return ok
    }

    @Synchronized
    fun markRemoteAttemptQuiescent(requestId: String) {
        remoteActiveRequests.remove(requestId)
        permits[requestId]?.let { permit ->
            val row = ledger.get(requestId)
            if (row != null && row.stage == RootDispatchStage.TERMINAL && !row.closed) {
                closePermit(permit, disclosed = false)
            }
        }
    }

    @Synchronized
    fun confirmRemoteSessionQuiescent(
        expectedIdentity: PrivilegedSessionIdentity,
        snapshot: RootRemoteQuiescenceSnapshot,
    ): Boolean {
        if (!snapshot.provesSessionQuiescent(expectedIdentity)) return false
        permits.values.filter {
            it.claim.approval.request.execution.identity == expectedIdentity
        }.toList().forEach { permit ->
            remoteActiveRequests.remove(permit.requestId)
            val row = ledger.get(permit.requestId) ?: return@forEach
            // Another controller sharing this durable DB may already have settled the receipt:
            // the durable close is idempotent, but this controller still has to release its own
            // in-memory permit so it no longer counts as an active permit.
            if (row.closed) {
                closePermit(permit, disclosed = false)
                return@forEach
            }
            if (row.stage != RootDispatchStage.TERMINAL) {
                finish(permit, if (row.stage == RootDispatchStage.CLAIMED)
                    RootDispatchOutcome.CANCELLED_BEFORE_ATTEMPT else RootDispatchOutcome.UNKNOWN_OUTCOME)
            }
            closePermit(permit, disclosed = false)
        }
        return true
    }

    /** Close streams in the adapter before this call. Terminal alone does not release a permit. */
    @Synchronized
    fun closePermit(permit: RootDispatchPermit, disclosed: Boolean = false): RootDispatchReceipt {
        requireOwned(permit)
        val result = db.transaction {
            val row = requireNotNull(ledger.get(permit.requestId))
            require(row.claim == permit.claim)
            if (disclosed) {
                require(!row.closed)
                require(permit.disclosureChecked)
                guard.validate(permit,row,"ROOT_DISPATCH_RESULT_PENDING")
            }
            terminalizer.close(row,disclosed)
        }
        permit.closed = true
        remoteActiveRequests.remove(permit.requestId)
        tickets.remove(permit.requestId)
        permits.remove(permit.requestId)
        return result
    }

    /** Historical facts only; there is deliberately no resume/getPermit API. */
    fun getReceipt(requestId: String): RootDispatchReceipt? = db.transaction { ledger.get(requestId) }

    @Synchronized
    fun canRotate(sessionId: String, openContentStreams: Int): Boolean {
        require(openContentStreams >= 0)
        return openContentStreams == 0 && permits.values.none { it.claim.approval.request.execution.identity.sessionId == sessionId } &&
            ledger.list().none { it.claim.approval.request.execution.identity.sessionId == sessionId &&
                (it.stage != RootDispatchStage.TERMINAL || !it.closed) }
    }

    private fun requireOwned(permit: RootDispatchPermit) {
        require(!permit.closed && permits[permit.requestId] === permit && permit.claim.controllerId == controllerId) {
            "Root permit is not owned by this live controller"
        }
    }

    private fun current(permit: RootDispatchPermit): RootDispatchReceipt =
        requireNotNull(ledger.get(permit.requestId)).also { require(it.claim == permit.claim && !it.closed) }
}
