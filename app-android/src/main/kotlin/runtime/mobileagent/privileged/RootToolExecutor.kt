// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.Collections
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.tooling.AgentWorkspaceView
import runtime.mobileagent.tooling.ManagedToolExecutor
import runtime.mobileagent.tooling.WorkspaceDiscovery

/**
 * Runtime-side Root tool seam.  Every operation is keyed by the runtime
 * invocation id and revalidates against the live DB/service facts; this
 * executor never creates a grant, Run, invocation or approval, and never
 * searches approvals by matching arguments.
 */
interface RootToolBackend {
    /** Live, path-free discovery derived from the same Root grants as [exposedTools]. */
    fun workspaceViews(): List<AgentWorkspaceView> = emptyList()
    fun acceptsDiscoveryContext(snapshotId: String, agentId: String): Boolean = false
    /**
     * Live exposure frozen for this run: only tools that are actually
     * implemented and currently backed by an unrevoked grant.  Returning a
     * tool here is not an approval; [invoke] must revalidate it.
     */
    fun exposedTools(): List<RootToolExposure>

    /**
     * Dispatch exactly [invocation].  A pending human decision is
     * [ToolResult.NeedsApproval]; a definite refusal is Denied/Invalid/Failure;
     * anything whose external effect cannot be proven must be UnknownOutcome.
     */
    suspend fun invoke(invocation: RootToolInvocation): ToolResult

    /** Settle the pending approval with this exact runtime id; the DB owns the original model id. */
    suspend fun approve(runtimeInvocationId: String): ToolResult

    /** Explicitly deny a pending approval for this exact runtime id. */
    suspend fun reject(runtimeInvocationId: String): ToolResult

    /** Expire a pending approval for this exact runtime id. */
    suspend fun expire(runtimeInvocationId: String): ToolResult

    /**
     * Revalidate disclosure of an already-settled result.  This must not
     * dispatch the tool again and must not widen to a same-argument search.
     */
    suspend fun authorizeDisclosure(invocation: RootToolInvocation, disclosure: ResultDisclosure): Boolean

    /** Non-consuming preflight check before releasing a multi-child batch envelope. */
    suspend fun precheckDisclosure(invocation: RootToolInvocation): Boolean = true

    /** Explicitly withhold a deferred result that was never disclosed to the model. */
    suspend fun withholdDisclosure(invocation: RootToolInvocation) {}

    /** Cancel one pending/in-flight runtime invocation; true only when confirmed. */
    suspend fun cancel(runtimeInvocationId: String): Boolean
}

/**
 * Provider-neutral projection of a granted Root tool binding.  It intentionally
 * carries no absolute device path: the model-visible workspace id plus the
 * relative path scope are enough for the runtime, and errors never echo either
 * the grant locator or a device path.
 */
data class RootToolExposure(
    val toolName: String,
    val capability: CapabilityId,
    val workspaceId: String,
    val grantId: String,
    val pathScope: String? = null,
    val writableWorkspace: Boolean = true,
) {
    init {
        require(toolName.matches(TOOL_NAME))
        require(workspaceId.matches(SAFE_ID) && grantId.matches(SAFE_ID))
    }

    private companion object {
        val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

/**
 * Exact invocation facts handed to [RootToolBackend].
 *
 * The legacy [ToolExecutor] boundary only carries [runtimeInvocationId]
 * (`RunTools.ownerCall()` already replaced the model call id); the typed
 * boundary additionally retains the model correlation id.  `modelCallId` is
 * never an execution, approval or replay key, and is never substituted for the
 * runtime id.  `toString()` stays path-free so it can be logged.
 */
sealed interface RootManagedInvocation : runtime.mobileagent.tooling.ManagedToolInvocation

class RootToolInvocation internal constructor(
    override val runtimeInvocationId: String,
    override val modelCallId: String?,
    val snapshotId: String?,
    val agentId: String?,
    override val toolName: String,
    val capability: CapabilityId,
    val workspaceId: String,
    val grantId: String,
    val pathScope: String?,
    val request: RootToolRequest,
) : RootManagedInvocation {
    override val identity: String get() = "$toolName|$workspaceId|${request.canonicalSha256}"
    override val contextIdentity: String? get() = snapshotId?.let { "$it|$agentId" }

    init {
        require(runtimeInvocationId.isNotBlank() && runtimeInvocationId.length <= 128)
        require(modelCallId == null || (modelCallId.isNotBlank() && modelCallId.length <= 512))
        require(request.toolName == toolName && request.capability == capability)
        require(request.workspaceId == workspaceId)
        require(grantId.isNotBlank())
    }

    /** Canonical typed JSON omitted text/patch content and device paths. */
    val normalizedArgumentsJson: String get() = request.canonicalJson
    val normalizedArgumentsSha256: String get() = request.canonicalSha256

    /** Bounded payload for write/patch only; never printed by this object. */
    val payload: RootToolPayload get() = request.payload

    override fun toString(): String = "RootToolInvocation(tool=$toolName)"
}

/** Root wire/admission adapter; the invocation state machine is shared with ordinary providers. */
class RootToolExecutor private constructor(
    private val surface: Surface,
    private val backend: RootToolBackend,
) : ManagedToolExecutor<RootManagedInvocation>(surface.specs) {
    constructor(frozenSpecs: List<ToolSpec>, backend: RootToolBackend) : this(surface(frozenSpecs, backend), backend)

    val rejectedToolNames: Set<String> = surface.rejected
    val workspaceIds: Set<String> = surface.exposures.values.flatten().mapTo(linkedSetOf()) { it.workspaceId }

    override fun normalizationFailure(call: ToolCall): ToolResult =
        when (val outcome = RootToolArguments.normalizeResult(call.name, call.argumentsJson)) {
            // A malformed request stays an invalid request; it must not be turned into an
            // unavailable capability or an approval prompt.
            RootArgumentNormalization.Invalid -> ToolResult.Invalid("Invalid Root tool arguments")
            // These are well-formed conditional requests that this backend cannot honour.
            // Refuse before approval is offered so the model never sees "approve, then reject".
            RootArgumentNormalization.UnsupportedVersionCondition -> ToolResult.Failure(ToolError(
                ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED,
                message = "Root does not support expected_version for this operation",
                details = mapOf("reason" to "unsupported_expected_version_condition"),
            ))
            RootArgumentNormalization.UnsupportedPatchFormat -> ToolResult.Failure(ToolError(
                ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED,
                message = "Root does not support this patch format",
                details = mapOf("reason" to "unsupported_patch_format"),
            ))
            // Arguments are valid but this exact workspace/tool is not currently exposed.
            // Keep the existing unavailable semantics instead of re-labelling it invalid.
            is RootArgumentNormalization.Success -> ToolResult.Failure(ToolError(ToolErrorCode.OPERATION_UNAVAILABLE))
        }

    override fun normalize(call: ToolCall, typed: ToolInvocation?): RootManagedInvocation? {
        if (call.name == WorkspaceDiscovery.NAME) {
            if (!WorkspaceDiscovery.validArguments(call.argumentsJson)) return null
            return DiscoveryInvocation(call.callId, typed?.callId, typed?.snapshotId, typed?.agentId)
        }
        val request = RootToolArguments.normalize(call.name, call.argumentsJson) ?: return null
        val exposure = surface.exposures[request.toolName]?.filter {
            it.workspaceId == request.workspaceId && it.capability == request.capability
        }?.singleOrNull() ?: return null
        return RootToolInvocation(call.callId, typed?.callId, typed?.snapshotId, typed?.agentId,
            request.toolName, request.capability, request.workspaceId, exposure.grantId, exposure.pathScope, request)
    }

    override suspend fun execute(invocation: RootManagedInvocation): ToolResult = when (invocation) {
        is RootToolInvocation -> backend.invoke(invocation)
        is DiscoveryInvocation -> {
            if (!discoveryContextValid(invocation)) ToolResult.Denied("SNAPSHOT_STALE")
            else liveViews().let { views ->
                invocation.views = views
                WorkspaceDiscovery.invoke(ToolCall(invocation.runtimeInvocationId, WorkspaceDiscovery.NAME, "{}"), views)
            }
        }
    }
    override suspend fun approveInvocation(invocation: RootManagedInvocation) = backend.approve(invocation.runtimeInvocationId)
    override suspend fun rejectInvocation(invocation: RootManagedInvocation) = backend.reject(invocation.runtimeInvocationId)
    override suspend fun expireInvocation(invocation: RootManagedInvocation) = backend.expire(invocation.runtimeInvocationId)
    override suspend fun authorizeInvocation(invocation: RootManagedInvocation, disclosure: ResultDisclosure): Boolean = when (invocation) {
        is RootToolInvocation -> backend.authorizeDisclosure(invocation, disclosure)
        is DiscoveryInvocation -> discoveryContextValid(invocation) && invocation.views.isNotEmpty() && liveViews() == invocation.views
    }
    override suspend fun precheckInvocation(invocation: RootManagedInvocation): Boolean = when (invocation) {
        is RootToolInvocation -> backend.precheckDisclosure(invocation)
        is DiscoveryInvocation -> authorizeInvocation(invocation, ResultDisclosure.DEFERRED)
    }
    override suspend fun withholdInvocation(invocation: RootManagedInvocation) {
        if (invocation is RootToolInvocation) backend.withholdDisclosure(invocation)
    }
    override suspend fun cancelInvocation(invocation: RootManagedInvocation): Boolean =
        if (invocation is DiscoveryInvocation) true else backend.cancel(invocation.runtimeInvocationId)
    override fun allowsDeferred(invocation: RootManagedInvocation): Boolean = invocation is DiscoveryInvocation ||
        (invocation is RootToolInvocation && (!RootApprovalTools.requiresWritableWorkspace(invocation.capability) ||
            invocation.capability == CapabilityId(CapabilityId.SHELL_EXECUTE)))

    private fun discoveryContextValid(invocation: DiscoveryInvocation): Boolean =
        invocation.snapshotId == null || backend.acceptsDiscoveryContext(invocation.snapshotId, requireNotNull(invocation.agentId))

    private fun liveViews(): List<AgentWorkspaceView> = backend.workspaceViews().mapNotNull { view ->
            val current = backend.exposedTools()
            val operations = view.authorizedOperations.filter { tool ->
                surface.exposures[tool]?.any { it.workspaceId == view.id && it in current } == true
            }
            if (operations.isEmpty()) null else view.copy(
                authorizedOperations = operations,
                readable = operations.any { RootApprovalTools.capability(it)?.let { cap -> !RootApprovalTools.requiresWritableWorkspace(cap) } == true },
                writable = operations.any { RootApprovalTools.capability(it)?.let(RootApprovalTools::requiresWritableWorkspace) == true },
                expectedVersionOperations = view.expectedVersionOperations.intersect(operations.toSet()).toList(),
                conditionalExpectedVersionOperations = view.conditionalExpectedVersionOperations.intersect(operations.toSet()).toList(),
                operationScopes = view.operationScopes.filterKeys { it in operations },
                operationConditions = view.operationConditions.filterKeys { it in operations },
                patchFormats = view.patchFormats.takeIf { "file_apply_patch" in operations }.orEmpty(),
            )
        }.sortedBy { it.id }

    private class DiscoveryInvocation(
        override val runtimeInvocationId: String,
        override val modelCallId: String?,
        val snapshotId: String?,
        val agentId: String?,
    ) : RootManagedInvocation {
        override val toolName = WorkspaceDiscovery.NAME
        override val identity = WorkspaceDiscovery.NAME
        override val contextIdentity: String? get() = snapshotId?.let { "$it|$agentId" }
        var views: List<AgentWorkspaceView> = emptyList()
    }

    private data class Surface(val specs: List<ToolSpec>, val exposures: Map<String, List<RootToolExposure>>, val rejected: Set<String>)

    private companion object {
        fun surface(frozenSpecs: List<ToolSpec>, backend: RootToolBackend): Surface {
            require(frozenSpecs.map { it.name }.distinct().size == frozenSpecs.size) { "Duplicate Root tool specification" }
            val exposures = backend.exposedTools()
                .filter { it.toolName in RootToolArguments.supportedToolNames && RootApprovalTools.capability(it.toolName) == it.capability }
                .filter { !RootApprovalTools.requiresWritableWorkspace(it.capability) || it.writableWorkspace }
                .groupBy { it.toolName }
            val accepted = frozenSpecs.filter { spec ->
                RootToolArguments.spec(spec.name)?.let { catalog ->
                    spec.capability == catalog.capability && spec.inputSchema == catalog.inputSchema &&
                        spec.sideEffect == catalog.sideEffect && spec.schemaVersion == catalog.schemaVersion
                } == true && exposures.containsKey(spec.name)
            }
            val specs = accepted + if (accepted.isNotEmpty() && backend.workspaceViews().isNotEmpty()) listOf(WorkspaceDiscovery.spec) else emptyList()
            return Surface(specs, exposures, Collections.unmodifiableSet(frozenSpecs.map { it.name }.filter { name -> specs.none { it.name == name } }.toSet()))
        }
    }
}
