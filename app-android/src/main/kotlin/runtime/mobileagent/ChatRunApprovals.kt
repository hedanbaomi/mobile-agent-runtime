// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.diagnostics.DiagnosticApprovalState
import runtime.mobileagent.diagnostics.DiagnosticAuthority
import runtime.mobileagent.diagnostics.DiagnosticToolCapability
import runtime.mobileagent.diagnostics.ToolApprovalStateRecord
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.tooling.HighRiskDetector

/** Durable approval settlement and process-local callback ownership; never restores an approval. */
internal class ChatRunApprovals(private val application: Application, private val container: AppContainer) {
    @Suppress("UNCHECKED_CAST")
    private fun <T : Application> getApplication(): T = application as T
    /**
     * A pending approval is deliberately kept only in process memory.  The run repository stores
     * the fact that a prompt was waiting, but never enough state to restore, approve, or replay it.
     * The map is shared by shell-scoped ChatViewModels so a second VM in this process cannot mistake
     * an active approval for a post-restart orphan while the first VM is still streaming.
     */
    internal data class PendingApprovalAudit(
        val runId: String,
        val callId: String,
        /** Runtime-owned durable invocation key; callId remains model correlation only. */
        val invocationId: String? = null,
        val agentId: String,
        val sessionRef: String,
        val capability: DiagnosticToolCapability,
        val authority: DiagnosticAuthority,
    )

    /**
     * Terminalize every durable invocation still waiting for approval.  Approval callbacks are
     * process-local, so this is also the last-resort guard for cancellation/TTL races where the
     * runtime cannot deliver a terminal tool event to this collector.  The result is deliberately
     * a small status object: command arguments and working directories never enter diagnostics.
     */
    fun terminalizePendingApproval(
        runId: String,
        outcome: ChatApprovalOutcome,
        pending: PendingApprovalAudit? = null,
    ): ToolInvocation? {
        val waiting = waitingInvocations(runId, pending)
        if (waiting.isEmpty()) return null
        val now = Utc.nowIso()
        var first: ToolInvocation? = null
        waiting.forEach { invocation ->
            val closed = invocation.copy(
                permissionDecision = outcome.permissionDecision,
                state = outcome.invocationState,
                errorCode = outcome.errorCode,
                resultJson = outcome.resultJson,
                updatedAt = now,
            )
            container.runs.updateInvocation(closed)
            if (first == null) first = closed
        }
        return first
    }

    fun waitingInvocations(runId: String, pending: PendingApprovalAudit?) =
        container.runs.invocations(runId).filter { invocation ->
            invocation.isAwaitingApproval() && when {
                pending == null -> true
                pending.invocationId != null -> invocation.invocationId == pending.invocationId
                else -> invocation.callId == pending.callId
            }
        }

    fun ToolInvocation.isAwaitingApproval() = state == "WAITING_APPROVAL" || state == "PENDING"

    fun markPendingApprovalApproved(pending: PendingApprovalAudit) {
        val now = Utc.nowIso()
        waitingInvocations(pending.runId, pending).forEach { invocation ->
            container.runs.updateInvocation(invocation.copy(permissionDecision = "APPROVED", updatedAt = now))
        }
    }

    suspend fun settleActiveApproval(resources: ChatRunSession, pending: PendingApprovalAudit, outcome: ChatApprovalOutcome) {
        val id = pending.invocationId ?: pending.callId
        withContext(NonCancellable) {
            runCatching {
                resources.executor?.let { executor ->
                    if (outcome == ChatApprovalOutcome.EXPIRED) executor.expire(id) else executor.reject(id)
                }
            }
        }
    }

    suspend fun settleInterruptedApproval(resources: ChatRunSession, pending: PendingApprovalAudit, outcome: ChatApprovalOutcome) {
        settleActiveApproval(resources, pending, outcome)
        if (terminalizePendingApproval(pending.runId, outcome, pending) != null) {
            recordToolApprovalState(pending, outcome.auditState, outcome.reasonCode)
        }
    }

    fun closeApprovalFromUi(pending: PendingApprovalAudit, outcome: ChatApprovalOutcome) {
        terminalizePendingApproval(pending.runId, outcome, pending)
        recordToolApprovalState(pending, outcome.auditState, outcome.reasonCode)
    }

    /**
     * Close only persisted approval waits which have no matching process-local callback.  This is
     * intentionally called before the first reload paints state, so a process restart can neither
     * restore nor replay an approval.  A shell-scoped VM registers the call before persistence and
     * therefore remains protected when another VM happens to reload concurrently.
     */
    fun closeOrphanedToolApprovals(): Int {
        var invalidated = 0
        container.db.query("SELECT run_id FROM runs WHERE state='WAITING_TOOL_APPROVAL'")
            .mapNotNull { container.runs.get(it.string("run_id")) }.forEach { candidate ->
            val closed = synchronized(PENDING_APPROVAL_LOCK) {
                val persisted = container.runs.get(candidate.runId) ?: return@synchronized false
                if (persisted.state != RunStatus.WAITING_TOOL_APPROVAL) return@synchronized false
                val now = Utc.nowIso()
                val allInvocations = container.runs.invocations(persisted.runId)
                val waitingInvocations = allInvocations.filter { it.isAwaitingApproval() }
                val mayHaveDispatched = allInvocations.any {
                    it.permissionDecision == "APPROVED" ||
                        it.state == "SUCCEEDED" || it.state == "UNKNOWN_OUTCOME"
                }
                val activeApproval = processPendingApprovals[persisted.runId]
                if (activeApproval != null &&
                    (waitingInvocations.isEmpty() || waitingInvocations.any { it.callId == activeApproval.callId })
                ) return@synchronized false
                val outcome = if (mayHaveDispatched) ChatApprovalOutcome.UNKNOWN else ChatApprovalOutcome.INVALIDATED
                terminalizePendingApproval(persisted.runId, outcome)
                container.runs.save(
                    persisted.copy(
                        state = if (mayHaveDispatched) RunStatus.UNKNOWN_OUTCOME else RunStatus.CANCELLED,
                        stopReason = if (mayHaveDispatched)
                            "process restarted after tool approval; dispatch outcome unknown"
                        else "approval invalidated after process restart; no tool dispatch occurred",
                        errorCode = outcome.errorCode,
                        finishedAt = now,
                        updatedAt = now,
                    ),
                )
                val snapshotAgentId = runCatching { container.agents.getSnapshot(persisted.snapshotId)?.agentId }.getOrNull()
                waitingInvocations.firstOrNull()?.let { invocation ->
                    recordToolApprovalState(
                        PendingApprovalAudit(
                            runId = persisted.runId,
                            callId = invocation.callId,
                            invocationId = invocation.invocationId,
                            agentId = snapshotAgentId.orEmpty(),
                            sessionRef = persisted.conversationId,
                            capability = diagnosticToolCapability(invocation.name, emptyList()),
                            authority = DiagnosticAuthority.NONE,
                        ),
                        outcome.auditState,
                        if (mayHaveDispatched) outcome.reasonCode else "validation",
                    )
                }
                !mayHaveDispatched
            }
            if (closed) invalidated++
        }
        return invalidated
    }

    /** Returns true only when this call became the process-local approval owner. */
    fun rememberPendingApproval(audit: PendingApprovalAudit): Boolean = synchronized(PENDING_APPROVAL_LOCK) {
        val existing = processPendingApprovals[audit.runId]
        when {
            existing == null -> {
                processPendingApprovals[audit.runId] = audit
                true
            }
            existing.callId == audit.callId -> {
                processPendingApprovals[audit.runId] = audit.copy(
                    invocationId = audit.invocationId ?: existing.invocationId,
                    authority = if (audit.authority != DiagnosticAuthority.NONE) audit.authority else existing.authority,
                )
                false
            }
            else -> false
        }
    }

    fun pendingApprovalFor(runId: String): PendingApprovalAudit? = synchronized(PENDING_APPROVAL_LOCK) {
        processPendingApprovals[runId]
    }

    fun forgetPendingApproval(runId: String, callId: String? = null) {
        synchronized(PENDING_APPROVAL_LOCK) {
            val existing = processPendingApprovals[runId]
            if (existing != null && (callId == null || existing.callId == callId)) {
                processPendingApprovals.remove(runId)
            }
        }
    }

    fun recordToolApprovalState(
        audit: PendingApprovalAudit,
        state: DiagnosticApprovalState,
        reasonCode: String,
    ) {
        runCatching {
            (getApplication<Application>() as MobileAgentApp).diagnostics.recordToolApprovalState(
                ToolApprovalStateRecord(
                    callId = audit.callId,
                    state = state,
                    agentId = audit.agentId.takeIf { it.isNotBlank() },
                    requestRef = audit.runId,
                    capability = audit.capability,
                    authority = audit.authority,
                    sessionRef = audit.sessionRef,
                    reasonCode = reasonCode,
                ),
            )
        }
    }

    fun diagnosticToolCapability(name: String, specs: List<ToolSpec>): DiagnosticToolCapability =
        chatDiagnosticToolCapability(specs.firstOrNull { it.name == name }?.capability)

    fun diagnosticToolAuthority(
        name: String,
        specs: List<ToolSpec>,
        workspaceId: String?,
        selectedAuthority: String? = null,
    ): DiagnosticAuthority {
        val capability = diagnosticToolCapability(name, specs)
        return when (capability) {
            DiagnosticToolCapability.SHELL_EXECUTE -> runCatching {
                chatDiagnosticAuthority(capability, selectedAuthority ?: container.authorityQueryPort.snapshot().selectedAuthority.name)
            }.getOrDefault(DiagnosticAuthority.NONE)
            DiagnosticToolCapability.WORKSPACE_READ, DiagnosticToolCapability.WORKSPACE_WRITE ->
                runCatching { chatDiagnosticAuthority(capability, "NONE", container.toolApprovalAuthorityPort.diagnosticWorkspaceAuthority(workspaceId)) }
                    .getOrDefault(DiagnosticAuthority.NONE)
            else -> DiagnosticAuthority.NONE
        }
    }

    /**
     * Projects the redacted model call into approval-only UI detail.  The
     * structured fields are never fed back into the prompt or diagnostics;
     * authority and policy are deliberately presented as live-revalidated
     * selections rather than caller-controlled arguments.
     */
    fun approvalUi(
        call: ToolCall,
        specs: List<runtime.mobileagent.skills.ToolSpec>,
        secret: CharArray?,
        selectedAuthority: String,
        dangerousMode: String,
    ): ChatToolApprovalUi {
        val safeArguments = SecretRedactor.redact(call.argumentsJson, secret?.let { listOf(String(it)) }.orEmpty())
        val root = runCatching { Json.parseToJsonElement(safeArguments).jsonObject }.getOrNull()
        val shellArguments = AgentToolConfirmation.shellArguments(call.name, root)
        fun stringValue(vararg keys: String): String? = keys.asSequence()
            .mapNotNull { key -> ((shellArguments ?: root)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .firstOrNull { it.isNotBlank() }
        val command = stringValue("command")
        val cwd = stringValue("cwd")
        val shell = shellArguments != null
        val highRisk = shell && command?.let(HighRiskDetector::isHighRisk) == true
        val description = specs.firstOrNull { it.name == call.name }?.description.orEmpty()
        val summary = buildString {
            if (description.isNotBlank()) append(description)
            if (safeArguments.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(safeArguments)
            }
        }.ifBlank { "需要确认该工具调用。" }
        return ChatToolApprovalUi(
            id = call.callId,
            name = call.name,
            summary = summary,
            externalEffect = true,
            command = command,
            cwd = cwd,
            authority = if (shell) "$selectedAuthority（批准时重新校验；不自动切换）" else null,
            dangerousMode = if (shell) "$dangerousMode（批准时重新校验）" else null,
            highRisk = highRisk,
        )
    }

    private companion object {
        private val PENDING_APPROVAL_LOCK = Any()
        private val processPendingApprovals = linkedMapOf<String, PendingApprovalAudit>()
    }
}
