// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.util.UUID
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.skills.tooling.ApprovalBinding
import runtime.mobileagent.skills.tooling.ApprovalLifecycleEvent
import runtime.mobileagent.skills.tooling.ApprovalLifecycleSink
import runtime.mobileagent.skills.tooling.ApprovalLifecycleTransition
import runtime.mobileagent.skills.tooling.ApprovalStaleReason
import runtime.mobileagent.skills.tooling.ToolErrorCode

data class PendingApproval(
    val approvalId: String,
    /** Runtime-generated identity; never a model callId. */
    val requestId: String,
    /** Correlation only; it is not used as a map key. */
    val modelCallId: String,
    val binding: ApprovalBinding,
    val capability: CapabilityId,
    val workspaceId: String? = null,
    val pathScope: String? = null,
    val grantRevision: Long = 0L,
    val policyVersion: Long = 0L,
    val taskId: String? = null,
    val sessionId: String? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

data class ApprovalGrant(
    val approvalId: String,
    val requestId: String,
    val modelCallId: String,
    val binding: ApprovalBinding,
    val capability: CapabilityId,
    val workspaceId: String? = null,
    val pathScope: String? = null,
    val grantRevision: Long = 0L,
    val policyVersion: Long = 0L,
    val lifetime: GrantLifetime,
    val taskId: String? = null,
    val sessionId: String? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long? = null,
)

sealed interface ApprovalDecision {
    data class Approved(val grant: ApprovalGrant) : ApprovalDecision
    data class Required(val pending: PendingApproval) : ApprovalDecision
    data class Rejected(val code: ToolErrorCode, val staleReasons: Set<ApprovalStaleReason> = emptySet()) : ApprovalDecision
}

data class ApprovalRequest(
    val requiresUserAction: Boolean,
    val pending: PendingApproval? = null,
    val grant: ApprovalGrant? = null,
    val reasonCode: ToolErrorCode? = null,
)

/** Extra metadata needed to match a persistent capability-level approval rule. */
data class ApprovalScope(
    val capability: CapabilityId,
    val workspaceId: String? = null,
    val pathScope: String? = null,
    val grantRevision: Long = 0L,
    val policyVersion: Long = 0L,
    val taskId: String? = null,
    val sessionId: String? = null,
) {
    init { require(grantRevision >= 0 && policyVersion >= 0) }
}

/**
 * Replay-resistant approval lifecycle.  Pending and task/session grants live
 * only in process memory.  Durable PERSISTENT rules are capability-level
 * records with explicit scope and current grant/policy revisions; they never
 * arise by dropping request/call fields from a command binding.
 */
class ApprovalEngine(
    private val clock: ToolingClock = SYSTEM_TOOLING_CLOCK,
    private val pendingTtlMs: Long = DEFAULT_PENDING_TTL_MS,
    /** Kept for source compatibility; pending command approvals never use it. */
    private val persistentStore: ApprovalStateStore? = null,
    private val lifecycleSink: ApprovalLifecycleSink? = null,
) {
    /** Convenience overload for callers that do not use persistent capability storage. */
    constructor(
        clock: ToolingClock,
        pendingTtlMs: Long,
        lifecycleSink: ApprovalLifecycleSink,
    ) : this(clock, pendingTtlMs, null, lifecycleSink)

    private val lock = Any()
    private val pending = linkedMapOf<String, PendingApproval>()
    private val approved = linkedMapOf<String, ApprovalGrant>()
    private val consumed = hashSetOf<String>()
    private val lifecycleTransitions = hashSetOf<LifecycleKey>()

    init { require(pendingTtlMs in 1..MAX_PENDING_TTL_MS) }

    /** Preferred API: caller supplies Runtime's internal request id. */
    fun request(
        requestId: String,
        modelCallId: String,
        binding: ApprovalBinding,
        scope: ApprovalScope,
        suggestedLifetime: GrantLifetime = GrantLifetime.ONCE,
    ): ApprovalRequest = synchronized(lock) {
        pruneLocked()
        require(requestId.isNotBlank()) { "request id is blank" }
        require(binding.requestId == requestId) { "approval binding must use internal request id" }
        val existing = pending[requestId]
        if (existing != null) {
            return@synchronized if (sameBinding(existing.binding, binding) && existing.scope() == scope) {
                ApprovalRequest(true, pending = existing, reasonCode = ToolErrorCode.APPROVAL_REQUIRED)
            } else {
                // A changed command or security snapshot invalidates the old
                // approval.  A subsequent request receives a fresh id and a
                // fresh TTL instead of being allowed to reuse stale state.
                pending.remove(requestId)
                emitLocked(
                    existing,
                    ApprovalLifecycleTransition.INVALIDATED,
                    ToolErrorCode.SNAPSHOT_STALE,
                )
                createPendingLocked(requestId, modelCallId, binding, scope)
            }
        }

        // GrantLifetime is a capability-grant lifetime.  It is deliberately
        // not a persistence hint for this process-local command approval.
        when (suggestedLifetime) {
            GrantLifetime.ONCE,
            GrantLifetime.TASK,
            GrantLifetime.SESSION,
            GrantLifetime.PERSISTENT -> Unit
        }
        createPendingLocked(requestId, modelCallId, binding, scope)
    }

    /** Convenience for direct callers where the binding carries internal id. */
    fun request(
        binding: ApprovalBinding,
        scope: ApprovalScope = ApprovalScope(CapabilityId(CapabilityId.SHELL_EXECUTE), policyVersion = 0L),
        suggestedLifetime: GrantLifetime = GrantLifetime.ONCE,
    ): ApprovalRequest = request(
        requestId = binding.requestId ?: throw IllegalArgumentException("internal request id is required"),
        modelCallId = binding.callId,
        binding = binding,
        scope = scope,
        suggestedLifetime = suggestedLifetime,
    )

    fun approve(
        requestId: String,
        expectedBinding: ApprovalBinding,
        scope: ApprovalScope,
        lifetime: GrantLifetime = GrantLifetime.ONCE,
    ): ApprovalDecision = synchronized(lock) {
        pruneLocked()
        val requested = pending[requestId]
            ?: return@synchronized ApprovalDecision.Rejected(ToolErrorCode.TIMEOUT)
        if (lifetime == GrantLifetime.PERSISTENT) {
            // Persistent capability grants are issued by the capability/grant
            // service, never by this command-approval state machine.
            return@synchronized ApprovalDecision.Rejected(ToolErrorCode.INVALID_REQUEST)
        }
        if (!sameBinding(requested.binding, expectedBinding) || requested.scope() != scope) {
            pending.remove(requestId)
            emitLocked(requested, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.SNAPSHOT_STALE)
            return@synchronized ApprovalDecision.Rejected(
                ToolErrorCode.SNAPSHOT_STALE,
                expectedBinding.staleReasons(requested.binding),
            )
        }
        pending.remove(requestId)
        val now = clock.nowMillis()
        val grant = ApprovalGrant(
            approvalId = requested.approvalId,
            requestId = requestId,
            modelCallId = requested.modelCallId,
            binding = requested.binding,
            capability = scope.capability,
            workspaceId = scope.workspaceId,
            pathScope = scope.pathScope,
            grantRevision = scope.grantRevision,
            policyVersion = scope.policyVersion,
            lifetime = lifetime,
            taskId = scope.taskId,
            sessionId = scope.sessionId,
            createdAtMs = now,
            expiresAtMs = if (lifetime == GrantLifetime.ONCE) deadline(now, ONCE_GRANT_TTL_MS) else null,
        )
        approved[grant.approvalId] = grant
        emitLocked(grant, ApprovalLifecycleTransition.APPROVED)
        ApprovalDecision.Approved(grant)
    }

    fun approve(
        requestId: String,
        lifetime: GrantLifetime = GrantLifetime.ONCE,
        expectedBinding: ApprovalBinding? = null,
    ): ApprovalDecision = synchronized(lock) {
        val requested = pending[requestId]
            ?: return@synchronized ApprovalDecision.Rejected(ToolErrorCode.TIMEOUT)
        val scope = requested.scope()
        approve(requestId, expectedBinding ?: requested.binding, scope, lifetime)
    }

    fun reject(requestId: String): ApprovalDecision = synchronized(lock) {
        pruneLocked()
        val requested = pending.remove(requestId)
        if (requested != null) emitLocked(requested, ApprovalLifecycleTransition.DENIED, ToolErrorCode.APPROVAL_DENIED)
        ApprovalDecision.Rejected(ToolErrorCode.APPROVAL_DENIED)
    }

    /** Explicitly expire a pending command approval without waiting for a read. */
    fun expire(requestId: String): ApprovalDecision = synchronized(lock) {
        pruneLocked()
        val requested = pending.remove(requestId)
            ?: return@synchronized ApprovalDecision.Rejected(ToolErrorCode.TIMEOUT)
        emitLocked(requested, ApprovalLifecycleTransition.EXPIRED, ToolErrorCode.TIMEOUT)
        ApprovalDecision.Rejected(ToolErrorCode.TIMEOUT)
    }

    /** Consume only after every current binding and revision is revalidated. */
    fun consume(
        grant: ApprovalGrant,
        currentBinding: ApprovalBinding,
        currentScope: ApprovalScope = grant.scope(),
        currentGrantRevision: Long = currentScope.grantRevision,
        currentPolicyVersion: Long = currentScope.policyVersion,
    ): ApprovalDecision = synchronized(lock) {
        val beforePrune = approved[grant.approvalId]
        if (beforePrune != null && expired(beforePrune.expiresAtMs)) {
            approved.remove(grant.approvalId)
            emitLocked(beforePrune, ApprovalLifecycleTransition.EXPIRED, ToolErrorCode.TIMEOUT)
            return@synchronized ApprovalDecision.Rejected(ToolErrorCode.TIMEOUT)
        }
        pruneLocked()
        if (grant.requestId in consumed) return@synchronized ApprovalDecision.Rejected(ToolErrorCode.CALL_ID_REPLAY)
        val issued = approved[grant.approvalId]
        if (issued == null || issued != grant) {
            return@synchronized ApprovalDecision.Rejected(ToolErrorCode.CALL_ID_REPLAY)
        }
        if (!sameBinding(grant.binding, currentBinding) || grant.scope() != currentScope) {
            approved.remove(grant.approvalId)
            emitLocked(grant, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.SNAPSHOT_STALE)
            return@synchronized ApprovalDecision.Rejected(
                ToolErrorCode.SNAPSHOT_STALE,
                grant.binding.staleReasons(currentBinding),
            )
        }
        if (grant.grantRevision != currentGrantRevision || grant.policyVersion != currentPolicyVersion) {
            approved.remove(grant.approvalId)
            emitLocked(grant, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.SNAPSHOT_STALE)
            return@synchronized ApprovalDecision.Rejected(ToolErrorCode.SNAPSHOT_STALE)
        }
        if (grant.lifetime == GrantLifetime.ONCE && expired(grant.expiresAtMs)) {
            approved.remove(grant.approvalId)
            emitLocked(grant, ApprovalLifecycleTransition.EXPIRED, ToolErrorCode.TIMEOUT)
            return@synchronized ApprovalDecision.Rejected(ToolErrorCode.TIMEOUT)
        }
        if (grant.lifetime == GrantLifetime.ONCE) {
            consumed += grant.requestId
            approved.remove(grant.approvalId)
        }
        emitLocked(grant, ApprovalLifecycleTransition.CONSUMED)
        ApprovalDecision.Approved(grant)
    }

    fun validate(approvalId: String, binding: ApprovalBinding, scope: ApprovalScope? = null): Boolean = synchronized(lock) {
        pruneLocked()
        val grant = approved[approvalId] ?: return@synchronized false
        sameBinding(grant.binding, binding) && (scope == null || grant.scope() == scope)
    }

    fun pending(requestId: String): PendingApproval? = synchronized(lock) {
        pruneLocked()
        pending[requestId]
    }

    fun pending(): List<PendingApproval> = synchronized(lock) {
        pruneLocked()
        pending.values.toList()
    }

    fun clearTask(taskId: String) = synchronized(lock) {
        val pendingRemoved = pending.values.filter { it.taskId == taskId }
        pendingRemoved.forEach { pending.remove(it.requestId) }
        pendingRemoved.forEach { emitLocked(it, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.INVALID_REQUEST) }
        val grantsRemoved = approved.values.filter { it.lifetime == GrantLifetime.TASK && it.taskId == taskId }
        grantsRemoved.forEach { approved.remove(it.approvalId) }
        grantsRemoved.forEach { emitLocked(it, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.INVALID_REQUEST) }
    }

    fun clearSession(sessionId: String) = synchronized(lock) {
        val pendingRemoved = pending.values.filter { it.sessionId == sessionId }
        pendingRemoved.forEach { pending.remove(it.requestId) }
        pendingRemoved.forEach { emitLocked(it, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.INVALID_REQUEST) }
        val grantsRemoved = approved.values.filter { it.lifetime == GrantLifetime.SESSION && it.sessionId == sessionId }
        grantsRemoved.forEach { approved.remove(it.approvalId) }
        grantsRemoved.forEach { emitLocked(it, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.INVALID_REQUEST) }
    }

    fun clearAllPending() = synchronized(lock) {
        val removed = pending.values.toList()
        pending.clear()
        removed.forEach { emitLocked(it, ApprovalLifecycleTransition.INVALIDATED, ToolErrorCode.INVALID_REQUEST) }
    }

    private fun pruneLocked() {
        val now = clock.nowMillis()
        val expiredPending = pending.values.filter { it.expiresAtMs <= now }
        expiredPending.forEach { pending.remove(it.requestId) }
        expiredPending.forEach { emitLocked(it, ApprovalLifecycleTransition.EXPIRED, ToolErrorCode.TIMEOUT) }
        val expiredGrants = approved.values.filter { it.expiresAtMs != null && it.expiresAtMs <= now }
        expiredGrants.forEach { approved.remove(it.approvalId) }
        expiredGrants.forEach { emitLocked(it, ApprovalLifecycleTransition.EXPIRED, ToolErrorCode.TIMEOUT) }
    }

    private fun expired(at: Long?): Boolean = at != null && at <= clock.nowMillis()

    private fun sameBinding(left: ApprovalBinding, right: ApprovalBinding): Boolean =
        left.matches(right) && left.staleReasons(right).isEmpty()

    private fun PendingApproval.scope(): ApprovalScope = ApprovalScope(
        capability = capability,
        workspaceId = workspaceId,
        pathScope = pathScope,
        grantRevision = grantRevision,
        policyVersion = policyVersion,
        taskId = taskId,
        sessionId = sessionId,
    )

    private fun ApprovalGrant.scope(): ApprovalScope = ApprovalScope(
        capability = capability,
        workspaceId = workspaceId,
        pathScope = pathScope,
        grantRevision = grantRevision,
        policyVersion = policyVersion,
        taskId = taskId,
        sessionId = sessionId,
    )

    private fun createPendingLocked(
        requestId: String,
        modelCallId: String,
        binding: ApprovalBinding,
        scope: ApprovalScope,
    ): ApprovalRequest {
        val now = clock.nowMillis()
        val next = PendingApproval(
            approvalId = UUID.randomUUID().toString(),
            requestId = requestId,
            modelCallId = modelCallId,
            binding = binding,
            capability = scope.capability,
            workspaceId = scope.workspaceId,
            pathScope = scope.pathScope,
            grantRevision = scope.grantRevision,
            policyVersion = scope.policyVersion,
            taskId = scope.taskId,
            sessionId = scope.sessionId,
            createdAtMs = now,
            expiresAtMs = deadline(now, pendingTtlMs),
        )
        pending[requestId] = next
        // REQUESTED is not an unexplained transition: callers need an explicit,
        // stable reason in diagnostics so a timed-out approval can be separated
        // from missing grants or a provider outage without logging command data.
        emitLocked(next, ApprovalLifecycleTransition.REQUESTED, ToolErrorCode.APPROVAL_REQUIRED)
        return ApprovalRequest(true, pending = next, reasonCode = ToolErrorCode.APPROVAL_REQUIRED)
    }

    private fun emitLocked(
        pendingApproval: PendingApproval,
        transition: ApprovalLifecycleTransition,
        reasonCode: ToolErrorCode? = null,
    ) = emitLocked(
        approvalId = pendingApproval.approvalId,
        requestId = pendingApproval.requestId,
        binding = pendingApproval.binding,
        capability = pendingApproval.capability,
        authority = pendingApproval.binding.selectedAuthority,
        dangerousMode = pendingApproval.binding.dangerousMode,
        grantLifetime = null,
        grantRevision = pendingApproval.grantRevision,
        policyVersion = pendingApproval.policyVersion,
        transition = transition,
        timestampMs = clock.nowMillis(),
        reasonCode = reasonCode,
    )

    private fun emitLocked(
        grant: ApprovalGrant,
        transition: ApprovalLifecycleTransition,
        reasonCode: ToolErrorCode? = null,
    ) = emitLocked(
        approvalId = grant.approvalId,
        requestId = grant.requestId,
        binding = grant.binding,
        capability = grant.capability,
        authority = grant.binding.selectedAuthority,
        dangerousMode = grant.binding.dangerousMode,
        grantLifetime = grant.lifetime,
        grantRevision = grant.grantRevision,
        policyVersion = grant.policyVersion,
        transition = transition,
        timestampMs = clock.nowMillis(),
        reasonCode = reasonCode,
    )

    private fun emitLocked(
        approvalId: String,
        requestId: String,
        binding: ApprovalBinding,
        capability: CapabilityId,
        authority: Authority,
        dangerousMode: DangerousMode,
        grantLifetime: GrantLifetime?,
        grantRevision: Long,
        policyVersion: Long,
        transition: ApprovalLifecycleTransition,
        timestampMs: Long,
        reasonCode: ToolErrorCode?,
    ) {
        if (!lifecycleTransitions.add(LifecycleKey(approvalId, transition))) return
        val sink = lifecycleSink ?: return
        runCatching {
            sink.record(
                ApprovalLifecycleEvent(
                    approvalId = approvalId,
                    requestId = requestId,
                    agentId = binding.agentId,
                    skillId = binding.skillId,
                    sessionIdentity = binding.sessionIdentity,
                    transition = transition,
                    bindingSha256 = binding.digest,
                    capability = capability,
                    authority = authority,
                    dangerousMode = dangerousMode,
                    grantLifetime = grantLifetime,
                    grantRevision = grantRevision,
                    policyVersion = policyVersion,
                    timestampMs = timestampMs,
                    reasonCode = reasonCode,
                ),
            )
        }
    }

    private fun deadline(now: Long, ttlMs: Long): Long =
        if (Long.MAX_VALUE - now < ttlMs) Long.MAX_VALUE else now + ttlMs

    private data class LifecycleKey(
        val approvalId: String,
        val transition: ApprovalLifecycleTransition,
    )

    companion object {
        const val DEFAULT_PENDING_TTL_MS = 60_000L
        const val ONCE_GRANT_TTL_MS = 60_000L
        const val MAX_PENDING_TTL_MS = 5L * 60L * 1000L
    }
}
