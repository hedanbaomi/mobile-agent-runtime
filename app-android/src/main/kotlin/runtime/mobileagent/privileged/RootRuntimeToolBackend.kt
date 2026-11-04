// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import runtime.mobileagent.data.RootToolExposureGate
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.*
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.AgentWorkspaceView
import runtime.mobileagent.tooling.OperationScopeView

/** Run-owned adapter. Only an explicit approval of the original durable invocation may dispatch. */
class RootRuntimeToolBackend(
    private val db: SqlConnection,
    private val coordinator: RootRuntimeCoordinator,
    private val context: ToolExecutionContext,
    private val execution: RootExecutionStamp?,
) : RootToolBackend {
    private val stateLock = Any()
    private val grantLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val pending = linkedMapOf<String, RootToolInvocation>()
    private val cancelled = linkedSetOf<String>()
    private var cancelSaturated = false
    private val cancelRoute = execution?.let(coordinator::cancellationRoute)
    private val exposureGate = RootToolExposureGate(db)
    internal var afterPendingTaken: suspend () -> Unit = {}

    private fun markCancelledLocked(runtimeInvocationId: String) {
        if (cancelled.size < RootControlLedger.MAX_SESSION_REQUESTS) {
            cancelled.add(runtimeInvocationId)
        }
        if (cancelled.size >= RootControlLedger.MAX_SESSION_REQUESTS) {
            cancelSaturated = true
        }
    }

    private fun isCancelledLocked(runtimeInvocationId: String): Boolean =
        cancelSaturated || cancelled.size >= RootControlLedger.MAX_SESSION_REQUESTS || runtimeInvocationId in cancelled

    override fun exposedTools(): List<RootToolExposure> {
        val frozen = execution ?: return emptyList()
        if (!live(frozen)) return emptyList()
        return exposureGate.evaluateExposure(
            context.snapshotId, context.snapshotGrantBindings.map { it.grantId }, frozen,
            context.taskIdentity, context.sessionIdentity,
        ).visibleTools.map {
            RootToolExposure(it.toolName, it.capability, it.workspaceId, it.grantId, it.pathScope, it.writableWorkspace)
        }
    }

    override fun workspaceViews(): List<AgentWorkspaceView> = exposedTools().groupBy { it.workspaceId }
        .mapNotNull { (id, bindings) ->
            val workspace = WorkspaceRepository(db).get(id)?.takeIf { it.enabled } ?: return@mapNotNull null
            val operations = bindings.map { it.toolName }.distinct().sorted()
            AgentWorkspaceView(
                id = id, displayName = workspace.displayName, enabled = true,
                workspaceScope = if (workspace.scope == WorkspaceScope.FULL_DEVICE_FILES) "full_device_files" else "selected_directory",
                readable = bindings.any { !RootApprovalTools.requiresWritableWorkspace(it.capability) },
                writable = bindings.any { RootApprovalTools.requiresWritableWorkspace(it.capability) },
                // Root does not promise workspace-wide conditional replacement on mixed ownership paths.
                atomicReplace = false, createOnlyOperations = emptyList(), authorizedOperations = operations,
                expectedVersionOperations = operations.filter { it == "file_set_metadata" },
                conditionalExpectedVersionOperations = operations.filter { it in setOf("file_write_text", "file_apply_patch") },
                operationConditions = operations.filter { it in setOf("file_write_text", "file_apply_patch", "file_move") }.associateWith {
                    when (it) {
                        "file_apply_patch" -> "requires_uid0_exclusive_parent_chain; app_private_paths_return_WORKSPACE_VERSION_UNSUPPORTED"
                        "file_move" -> "no_replace_and_all_directory_moves_require_kernel_atomic_no_replace_rename; emulated_storage_may_reject_these_moves; replace_existing_true_supports_regular_file_entry_replace_only; no_non_atomic_fallback"
                        else -> "expected_version_requires_uid0_exclusive_parent_chain; emulated_storage_uses_non_atomic_fd_write_and_filesystem_default_create_permissions; app_private_paths_allow_unconditional_replace_only"
                    }
                },
                patchFormats = if ("file_apply_patch" in operations) listOf("unified_diff", "search_replace") else emptyList(),
                operationScopes = bindings.groupBy { it.toolName }.mapValues { (_, scopes) ->
                    OperationScopeView(scopes.any { it.pathScope.isNullOrEmpty() }, scopes.mapNotNull { it.pathScope?.takeIf(String::isNotEmpty) }.distinct().sorted())
                },
            )
        }.sortedBy { it.id }

    override fun acceptsDiscoveryContext(snapshotId: String, agentId: String): Boolean =
        snapshotId == context.snapshotId && agentId == context.agentId && execution?.let(::live) == true

    override suspend fun invoke(invocation: RootToolInvocation): ToolResult {
        if (!matchesContext(invocation) || !currentlyExposed(invocation)) return denied()
        val previous = synchronized(stateLock) {
            if (isCancelledLocked(invocation.runtimeInvocationId)) return denied()
            pending.putIfAbsent(invocation.runtimeInvocationId, invocation)
        }
        if (previous != null && previous.request.canonicalSha256 != invocation.request.canonicalSha256) {
            return ToolResult.Denied(ToolErrorCode.CALL_ID_REPLAY.name)
        }
        // ChatViewModel persists the invocation after this event. Never manufacture its owner here.
        return ToolResult.NeedsApproval
    }

    override suspend fun approve(runtimeInvocationId: String): ToolResult {
        val invocation = synchronized(stateLock) {
            if (isCancelledLocked(runtimeInvocationId)) return denied()
            pending.remove(runtimeInvocationId)
        } ?: return denied()
        afterPendingTaken()
        return runInterruptible(Dispatchers.IO) {
            if (synchronized(stateLock) { isCancelledLocked(runtimeInvocationId) }) return@runInterruptible denied()
            val frozen = execution ?: return@runInterruptible denied()
            val grantLock = grantLocks.computeIfAbsent(invocation.grantId) { Any() }
            val response = synchronized(grantLock) {
                if (synchronized(stateLock) { isCancelledLocked(runtimeInvocationId) }) return@synchronized null
                if (!matchesContext(invocation) || !currentlyExposed(invocation)) return@synchronized null
                val ownerCallId = RootChildInvocationSupport.resolveOrMaterializeOwnerCallId(
                    db = db,
                    context = context,
                    runtimeInvocationId = runtimeInvocationId,
                    invocation = invocation,
                ) ?: return@synchronized null
                val approved = coordinator.approveFileInvocation(
                    authority = frozen.identity.authority,
                    runId = context.taskIdentity,
                    invocationId = runtimeInvocationId,
                    callId = ownerCallId,
                    snapshotId = context.snapshotId,
                    grantId = invocation.grantId,
                    typedArgs = invocation.request.typed,
                    configSnapshotHash = context.configSnapshotHash,
                    sessionIdentity = context.sessionIdentity,
                ) ?: return@synchronized null
                if (approved.request.execution != frozen) return@synchronized null
                val payload = (invocation.request.payload as? RootToolPayload.Text)?.text?.toByteArray(Charsets.UTF_8)
                    ?: ByteArray(0)
                try {
                    coordinator.executeFileInvocation(
                        authority = frozen.identity.authority,
                        invocationId = runtimeInvocationId,
                        args = invocation.request.typed,
                        payload = payload,
                        deferDisclosure = runtimeInvocationId.startsWith("rchild."),
                    )
                } finally {
                    payload.fill(0)
                }
            }
            if (synchronized(stateLock) { isCancelledLocked(runtimeInvocationId) }) {
                runCatching { coordinator.withholdDeferredResult(frozen.identity.authority, runtimeInvocationId) }
                ToolResult.UnknownOutcome("Root invocation cancelled; result withheld")
            } else response?.let(::project) ?: denied()
        }
    }

    override suspend fun reject(runtimeInvocationId: String): ToolResult {
        synchronized(stateLock) { pending.remove(runtimeInvocationId); markCancelledLocked(runtimeInvocationId) }
        return ToolResult.Denied(ToolErrorCode.APPROVAL_DENIED.name)
    }

    override suspend fun expire(runtimeInvocationId: String): ToolResult {
        synchronized(stateLock) { pending.remove(runtimeInvocationId); markCancelledLocked(runtimeInvocationId) }
        return ToolResult.Denied(ToolErrorCode.TIMEOUT.name)
    }

    override suspend fun authorizeDisclosure(invocation: RootToolInvocation, disclosure: ResultDisclosure): Boolean {
        if (!matchesContext(invocation)) return false
        val frozen = execution ?: return false
        if (synchronized(stateLock) { isCancelledLocked(invocation.runtimeInvocationId) } || !live(frozen)) return false
        return runInterruptible(Dispatchers.IO) {
            coordinator.authorizeResultDisclosure(frozen.identity.authority, invocation.runtimeInvocationId, disclosure)
        }
    }

    override suspend fun precheckDisclosure(invocation: RootToolInvocation): Boolean {
        if (!matchesContext(invocation)) return false
        val frozen = execution ?: return false
        if (synchronized(stateLock) { isCancelledLocked(invocation.runtimeInvocationId) } || !live(frozen)) return false
        return runInterruptible(Dispatchers.IO) {
            coordinator.precheckResultDisclosure(frozen.identity.authority, invocation.runtimeInvocationId)
        }
    }

    override suspend fun withholdDisclosure(invocation: RootToolInvocation) {
        val frozen = execution ?: return
        runInterruptible(Dispatchers.IO) {
            coordinator.withholdDeferredResult(frozen.identity.authority, invocation.runtimeInvocationId)
        }
    }

    override suspend fun cancel(runtimeInvocationId: String): Boolean {
        val wasPending = synchronized(stateLock) {
            markCancelledLocked(runtimeInvocationId)
            pending.remove(runtimeInvocationId) != null
        }
        if (wasPending) return true
        val confirmed = cancelRoute?.invoke(runtimeInvocationId) == true
        execution?.takeIf { runtimeInvocationId.startsWith("rchild.") }?.let {
            runCatching { coordinator.withholdDeferredResult(it.identity.authority, runtimeInvocationId) }
        }
        return confirmed
    }

    private fun matchesContext(invocation: RootToolInvocation): Boolean =
        context.taskIdentity.isNotBlank() && context.sessionIdentity.isNotBlank() &&
            (invocation.snapshotId == null || invocation.snapshotId == context.snapshotId) &&
            (invocation.agentId == null || invocation.agentId == context.agentId)

    private fun live(frozen: RootExecutionStamp): Boolean =
        coordinator.currentExecutionStamp(frozen.identity.authority) == frozen

    private fun currentlyExposed(invocation: RootToolInvocation): Boolean = exposedTools().any {
        it.toolName == invocation.toolName && it.capability == invocation.capability &&
            it.workspaceId == invocation.workspaceId && it.grantId == invocation.grantId && it.pathScope == invocation.pathScope
    }

    private fun project(response: RootServiceExecuteResponse): ToolResult {
        if (response.requiresDegradedEpoch || response.terminalOutcome == RootServiceTerminalOutcome.UNKNOWN_OUTCOME) {
            return ToolResult.UnknownOutcome("Root operation outcome is unknown; user acknowledgement is required")
        }
        if (response.terminalOutcome == RootServiceTerminalOutcome.CANCELLED) {
            return ToolResult.Failure(ToolError(ToolErrorCode.SHELL_CANCELLED, "Root invocation was cancelled; output withheld"))
        }
        // A settled nonzero command exit is an observed shell result, not a transport failure.
        // Only disclose a complete command result; pre-execution failures and cancellation
        // keep their typed failure/unknown semantics below.
        response.shellOutcome?.takeIf {
            response.errorReason == null && it.exitCode != null && !it.timedOut && !it.cancelled &&
                !it.requiresDegradedEpoch && it.terminalOutcomeName == response.terminalOutcome?.name &&
                response.terminalOutcome in setOf(RootServiceTerminalOutcome.SUCCEEDED, RootServiceTerminalOutcome.FAILED)
        }?.let { shell ->
            return ToolResult.Value(buildJsonObject {
                put("status", shell.statusName)
                put("success", shell.exitCode == 0)
                shell.exitCode?.let { put("exit_code", it) }
                put("stdout", shell.stdout); put("stderr", shell.stderr)
                put("stdout_truncated", shell.stdoutTruncated); put("stderr_truncated", shell.stderrTruncated)
                put("timed_out", shell.timedOut); put("cancelled", shell.cancelled)
            }.toString())
        }
        if (response.terminalOutcome != RootServiceTerminalOutcome.SUCCEEDED) {
            return ToolResult.Failure(RootBridgeErrorCode.fromExecuteFailure(response))
        }
        response.fileResultJson?.let { return ToolResult.Value(it) }
        return ToolResult.Failure(ToolError(ToolErrorCode.OPERATION_UNAVAILABLE))
    }

    private fun denied(): ToolResult = ToolResult.Denied(ToolErrorCode.AUTHORITY_NOT_GRANTED.name)
}
