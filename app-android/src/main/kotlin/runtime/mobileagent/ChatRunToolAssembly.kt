// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.app.Application
import kotlinx.coroutines.*
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.diagnostics.*
import runtime.mobileagent.tooling.ToolExecutionContext

internal data class ChatRunToolAssembly(
    val context: ToolExecutionContext?,
    val runTools: RunTools,
    val productivity: RunProductivityTools,
    val toolingUnavailable: Boolean,
    val noEffectiveTools: Boolean,
)

/** Freezes tool exposure once on IO; execution retains the exact same live-revalidation gates. */
internal suspend fun assembleChatRunTools(
    application: Application,
    container: AppContainer,
    snapshot: AgentSnapshot,
    run: AgentRun,
    skillIds: Set<String>,
    acceptsImages: Boolean,
    degrade: Boolean,
    toolsEnabled: Boolean,
    threadWorkspaceId: String?,
    threadWorkspaceBindingReadFailed: Boolean,
    threadWorkspaceRuntimePort: ThreadWorkspaceRuntimePort?,
    resources: ChatRunSession,
    onToolingStart: () -> Unit,
): ChatRunToolAssembly {
    val conversationId = resources.owner.conversationId
    val webExecutor = withContext(Dispatchers.IO) { webSearchTools(container, snapshot) }
    val mcpExecutor = withContext(Dispatchers.IO) { mcpTools(container, snapshot) }
    // Freeze the run's immutable configuration before tool exposure.  The
    // integration may re-read live grants/connections at invoke/approval,
    // but it must never rebuild the model-visible schema from those facts.
    val configSnapshotHash = RunCoordinator.sha256Hex(snapshot.bindingManifestJson.toByteArray(Charsets.UTF_8))
    // Memory is scoped to the Runtime-created trusted Skill envelope.  A run may
    // include several Skill instructions, but without one unambiguous identity the
    // model must not receive a union of their memory handles.
    val trustedSkillId = skillIds.singleOrNull()
    val trustedSkillRevision = trustedSkillId?.let { skillId ->
        withContext(Dispatchers.IO) {
            val packageHash = container.skills.get(skillId)?.packageHash
            container.skills.grantsFor(skillId)
                .filter { !it.revoked && (packageHash == null || it.packageHash == packageHash) }
                .maxByOrNull { it.revision }
                ?.revision
                ?.toLong()
        }
    }
    val toolingContext = try {
        if (threadWorkspaceBindingReadFailed ||
            threadWorkspaceRuntimePort == null ||
            !threadWorkspaceRuntimePort.available
        ) {
            null
        } else {
            withContext(Dispatchers.IO) {
                threadWorkspaceRuntimePort.createToolExecutionContextForWorkspace(
                    snapshot = snapshot,
                    workspaceId = threadWorkspaceId,
                    modelCallId = run.runId,
                    sessionIdentity = conversationId,
                    configSnapshotHash = configSnapshotHash,
                    taskIdentity = run.runId,
                    skillId = trustedSkillId,
                    skillRevision = trustedSkillRevision,
                    trustedSkillEnvelope = trustedSkillId != null,
                )
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
    var v2ToolingUnavailable = toolingContext == null
    var v2NoEffectiveTools = false
    val modelToolTransportEnabled = toolsEnabled
    fun recordToolExposure(
        exposedToolCount: Int,
        ownerToolCounts: Map<String, Int> = emptyMap(),
        reason: RuntimeToolExposureReason,
    ) {
        val context = toolingContext ?: return
        runCatching {
            val exposureInputs = container.runtimeIntegration.toolExposureDiagnostics(context)
            (application as MobileAgentApp).diagnostics.recordRuntimeToolExposure(
                RuntimeToolExposureRecord(
                    agentId = context.agentId,
                    sessionRef = conversationId,
                    runRef = run.runId,
                    effectiveGrantCount = context.canonicalGrants.size,
                    snapshotBindingCount = context.snapshotGrantBindings.size,
                    exposedToolCount = exposedToolCount,
                    webToolCount = ownerToolCounts["web"] ?: 0,
                    mcpToolCount = ownerToolCounts["mcp"] ?: 0,
                    pythonToolCount = ownerToolCounts["python"] ?: 0,
                    memoryToolCount = ownerToolCounts["memory"] ?: 0,
                    workspaceToolCount = ownerToolCounts["workspace"] ?: 0,
                    shellToolCount = ownerToolCounts["shell"] ?: 0,
                    registeredWorkspaceCount = exposureInputs.registeredWorkspaceCount,
                    grantedWorkspaceCount = exposureInputs.grantedWorkspaceCount,
                    boundWorkspaceCount = exposureInputs.boundWorkspaceCount,
                    registeredGrantedWorkspaceCount = exposureInputs.registeredGrantedWorkspaceCount,
                    selectedAuthority = exposureInputs.selectedAuthority,
                    selectedAuthorityReady = exposureInputs.selectedAuthorityReady,
                    safGrantActive = exposureInputs.safGrantActive,
                    safBackendRegistered = exposureInputs.safBackendRegistered,
                    modelToolTransportEnabled = modelToolTransportEnabled,
                    reason = reason,
                    effectiveAgentWorkspaceCapabilityCount = exposureInputs.effectiveAgentWorkspaceCapabilityCount,
                    effectiveSkillWorkspaceCapabilityCount = exposureInputs.effectiveSkillWorkspaceCapabilityCount,
                    backendReadyWorkspaceCount = exposureInputs.backendReadyWorkspaceCount,
                    backendProbeFailureCount = exposureInputs.backendProbeFailureCount,
                    authorityMismatchWorkspaceCount = exposureInputs.authorityMismatchWorkspaceCount,
                    schemaFrozen = exposureInputs.schemaFrozen,
                    backendProbeState = exposureInputs.backendProbeState,
                    safOperationCapabilityCount = exposureInputs.safOperationCapabilityCount,
                    safProbeState = exposureInputs.safProbeState,
                ),
            )
        }
    }
    fun noteV2ToolingUnavailable(errorCode: RuntimeToolingUnavailableCode) {
        v2ToolingUnavailable = true
        // Keep the diagnostic intentionally typed and non-sensitive:
        // no exception text, URI, serial, path, or model arguments.
        runCatching {
            (application as MobileAgentApp).diagnostics.recordRuntimeToolingUnavailable(
                RuntimeToolingUnavailableRecord(
                    errorCode = errorCode,
                    sessionRef = conversationId,
                    runRef = run.runId,
                ),
            )
        }
    }
    if (toolingContext == null) noteV2ToolingUnavailable(RuntimeToolingUnavailableCode.TOOL_EXECUTION_CONTEXT_UNAVAILABLE)
    onToolingStart()
    // RunTools freezes the model-visible tool set by probing every workspace
    // backend (SAF ContentResolver queries, privileged Binder state) and Skill
    // package; this is the slowest preparation step and must stay off Main.
    val runTools = withContext(Dispatchers.IO) { RunTools(container, application, snapshot, run,
        acceptsImages, degrade,
        baseExecutors = listOf(webExecutor, mcpExecutor),
        runExecutorFactory = { python ->
            toolingContext?.let { frozenContext ->
                try {
                    val factory = container.runtimeIntegration
                        .createToolExecutorFactory(frozenContext, webExecutor, mcpExecutor, python)
                    val catalogToolCount = factory.exposureSummary.totalTools
                    val exposedToolCount = if (modelToolTransportEnabled) catalogToolCount else 0
                    val reason = when {
                        !modelToolTransportEnabled -> RuntimeToolExposureReason.MODEL_TOOL_TRANSPORT_DISABLED
                        exposedToolCount > 0 -> RuntimeToolExposureReason.EXPOSED
                        frozenContext.canonicalGrants.isEmpty() -> RuntimeToolExposureReason.NO_EFFECTIVE_AGENT_GRANTS
                        frozenContext.snapshotGrantBindings.isEmpty() -> RuntimeToolExposureReason.NO_SNAPSHOT_BINDINGS
                        else -> RuntimeToolExposureReason.EMPTY_EFFECTIVE_TOOL_SET
                    }
                    v2NoEffectiveTools = exposedToolCount == 0
                    recordToolExposure(
                        exposedToolCount,
                        factory.exposureSummary.ownerToolCounts.takeIf { modelToolTransportEnabled }.orEmpty(),
                        reason,
                    )
                    factory.createLegacyExecutor()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    noteV2ToolingUnavailable(RuntimeToolingUnavailableCode.TOOL_EXECUTOR_FACTORY_UNAVAILABLE)
                    recordToolExposure(
                        exposedToolCount = 0,
                        reason = RuntimeToolExposureReason.FACTORY_UNAVAILABLE,
                    )
                    null
                }
            }
        },
    ) }
    val productivity = RunProductivityTools(
        core = runTools.executor,
        scope = kotlinx.coroutines.CoroutineScope(currentCoroutineContext() + Dispatchers.IO),
        liveContext = { withContext(Dispatchers.IO) { container.agents.isLiveBinding(snapshot) } },
        reserveCalls = runTools::reserveAdditionalToolCalls,
        rootWorkspaceIds = withContext(Dispatchers.IO) {
            toolingContext?.let(container.runtimeIntegration::frozenRootWorkspaceIds).orEmpty()
        },
    )
    resources.productivity = productivity
    resources.confirmedDeviceAction = runTools::confirmedDeviceActionReceipt
    resources.executor = productivity.executor
    return ChatRunToolAssembly(toolingContext, runTools, productivity, v2ToolingUnavailable, v2NoEffectiveTools)
}
