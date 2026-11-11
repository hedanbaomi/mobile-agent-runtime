// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.diagnostics.DiagnosticApprovalState
import runtime.mobileagent.diagnostics.DiagnosticToolCapability
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor

/** Trusted executor-call approval; presentation events cannot supply authorization arguments. */
internal class ChatToolApprovalRequest(
    private val container: AppContainer,
    private val ui: ChatExecutionUi,
    private val owner: ChatRunOwner,
    private val resources: ChatRunSession,
    private val runId: String,
    private val snapshot: AgentSnapshot,
    private val toolExecutor: ToolExecutor,
    private val threadWorkspaceId: String?,
    private val secret: CharArray?,
    private val progress: ChatRunEventState,
    private val invocations: MutableMap<String, ToolInvocation>,
    private val invocationId: (String, String) -> String?,
    private val approvals: ChatRunApprovals,
) : ChatExecutionUi by ui {
    private val conversationId get() = owner.conversationId
    val onApprove: suspend (ToolCall) -> Boolean = approvalDecision@{ call ->
        if (!withContext(Dispatchers.Main) { canProjectRun(owner) }) return@approvalDecision false
        // onApprove carries the original executor call. Runtime events are redacted
        // presentation only and must never supply the durable authorization binding.
        val invocation = withContext(Dispatchers.IO) {
            persistChatApprovalInvocation(container.runs, runId,
                invocationId(call.callId, call.name), call,
                secret?.let { listOf(String(it)) }.orEmpty())
        }
        withContext(Dispatchers.Main) { invocations[invocation.invocationId] = invocation }
        // RuntimeIntegration.snapshot() is the canonical, UI-safe
        // projection but reads repositories/content permissions;
        // collect the two enum labels off the Main dispatcher.
        val authoritySnapshot = try {
            container.runtimeIntegration.snapshot()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        val selectedAuthority = authoritySnapshot?.selectedAuthority?.name ?: "NONE"
        val dangerousMode = authoritySnapshot?.dangerousMode?.name ?: "UNKNOWN"
        val capability = approvals.diagnosticToolCapability(call.name, toolExecutor.specs)
        // Approval follows the workspace this exact call names. A mixed Root+SAF Run
        // must not make a SAF write inherit Root confirmation, and a Root write must
        // never inherit the ordinary skip switch. read_batch carries its workspace
        // ids in child arguments, even though the outer spec has no workspace capability.
        val workspaceScoped = capability == DiagnosticToolCapability.WORKSPACE_READ ||
            capability == DiagnosticToolCapability.WORKSPACE_WRITE ||
            AgentToolConfirmation.invocationWorkspaceIds(call.name, call.argumentsJson).isNotEmpty()
        val invocationAuthority = if (workspaceScoped) {
            AgentToolConfirmation.invocationWorkspaceAuthority(
                call.name, call.argumentsJson, threadWorkspaceId,
            ) { workspaceId -> container.runtimeIntegration.approvalWorkspaceAuthority(workspaceId) }
        } else {
            approvals.diagnosticToolAuthority(call.name, toolExecutor.specs, threadWorkspaceId, selectedAuthority)
        }
        val approvalAudit = ChatRunApprovals.PendingApprovalAudit(
            runId = runId,
            callId = call.callId,
            invocationId = invocationId(call.callId, call.name),
            agentId = snapshot.agentId,
            sessionRef = conversationId,
            capability = capability,
            authority = invocationAuthority,
        )
        approvals.rememberPendingApproval(approvalAudit)
        approvals.recordToolApprovalState(approvalAudit, DiagnosticApprovalState.REQUESTED, "permission")
        if (!withContext(Dispatchers.Main) { canProjectRun(owner) }) return@approvalDecision false
        val liveAgent = container.agents.get(snapshot.agentId)
        val snapshotSettings = snapshot.permissionSettingsJson
        val liveAgentSettings = liveAgent?.permissionSettingsJson
        val rootRun = runtime.mobileagent.data.RootRunManifest.hasRootStamp(progress.record.manifestJson)
        val runScopedRoot = rootRun &&
            AgentToolConfirmation.isRunScopedRootTool(call.name, call.argumentsJson)
        // Root read-only calls have their own opt-in (`skipRootReadConfirmations`, frozen
        // snapshot + live Agent); the ordinary global switch can never authorize them.
        // Every Root write, Shell and background job stays in the explicit branch.
        val autoApproved = AgentToolConfirmation.autoApprovesInvocation(
            toolName = call.name,
            argumentsJson = call.argumentsJson,
            selectedAuthority = selectedAuthority,
            workspaceAuthority = invocationAuthority,
            fallbackWorkspaceId = threadWorkspaceId,
            rootRun = rootRun,
            runScopedRoot = runScopedRoot,
            rootReadConfirmed = AgentToolConfirmation.allowsRootRead(snapshotSettings, liveAgentSettings),
            ordinarySkip = AgentToolConfirmation.allows(snapshotSettings, liveAgentSettings),
            authorityForWorkspace = { workspaceId ->
                container.runtimeIntegration.approvalWorkspaceAuthority(workspaceId)
            },
        )
        if (autoApproved) {
            approvals.markPendingApprovalApproved(approvalAudit)
            approvals.recordToolApprovalState(approvalAudit, DiagnosticApprovalState.APPROVED, "agent_auto_skip")
            progress.approvedToolInFlight = true
            progress.approvedToolCallId = call.callId
            progress.toolWaitingApproval = false
            true
        } else {
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            if (!canProjectRun(owner)) {
                deferred.complete(false)
                return@withContext
            }
            resources.approval = deferred
            resources.approvalCallId = call.callId
            publishRunState(owner, state.value.copy(
                pendingTool = approvals.approvalUi(
                    call = call,
                    specs = toolExecutor.specs,
                    secret = secret,
                    selectedAuthority = selectedAuthority,
                    dangerousMode = dangerousMode,
                ),
            ))
        }
        deferred.await().also {
            progress.approvedToolInFlight = it
            progress.approvedToolCallId = call.callId.takeIf { _ -> it }
            progress.toolWaitingApproval = false
        }
        }
    }
}
