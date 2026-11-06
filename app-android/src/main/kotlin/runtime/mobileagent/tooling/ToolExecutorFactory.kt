// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import runtime.mobileagent.privileged.RootToolArguments
import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.privileged.RootToolExecutor
import runtime.mobileagent.tooling.ModelToolArguments.WorkspaceIdSelection
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.authorizeDisclosure
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolHandler
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolRegistration
import runtime.mobileagent.skills.tooling.ToolRegistry
import runtime.mobileagent.skills.tooling.ToolRunSnapshot
import runtime.mobileagent.skills.tooling.ToolSpec

/**
 * Composition seam for the existing web/MCP/Python executors and the new
 * workspace/shell ports.  It deliberately has no ChatViewModel dependency.
 * Tool snapshots are materialized once, so a provider connection change cannot
 * silently replace a run's schema or route.
 */
class ToolExecutorFactory(
    private val web: ToolExecutor? = null,
    private val mcp: ToolExecutor? = null,
    private val python: ToolExecutor? = null,
    private val workspace: UnifiedWorkspaceToolExecutor? = null,
    private val shell: ShellToolExecutor? = null,
    /**
     * Runtime-owned memory adapter.  This remains an optional legacy executor
     * because the canonical memory implementation is created by the runtime
     * for the frozen run context.  Keeping it after the existing parameters
     * preserves source compatibility for current callers.
     */
    private val memory: ToolExecutor? = null,
    /**
     * Root shares file names with INTERNAL/SAF workspaces through exact frozen-ID routing.
     * Root shell replaces the ordinary shell; unavailable Root operations never fall back.
     */
    private val root: ToolExecutor? = null,
    /** Frozen Root bindings, including IDs whose Root service is currently unavailable. */
    rootWorkspaceIds: Set<String> = emptySet(),
    /** Initial registry schema belongs to this immutable Runtime/DB snapshot. */
    private val boundSnapshotId: String? = null,
) {
    private val rootWorkspaceIds: Set<String> = rootWorkspaceIds.toSet()
    private val providers: List<NamedExecutor> = listOfNotNull(
        web?.let { NamedExecutor("web", it) },
        mcp?.let { NamedExecutor("mcp", it) },
        python?.let { NamedExecutor("python", it) },
        memory?.let { NamedExecutor("memory", it) },
        // Shared file names have one composite lifecycle and an exact workspace route.
        root?.let { NamedExecutor("root", it) },
        workspace?.let { NamedExecutor("workspace", it) },
        if (root == null && rootWorkspaceIds.isEmpty()) shell?.let { NamedExecutor("shell", it) } else null,
    )

    /** Combined legacy boundary for the current runtime until it consumes the shared registry. */
    val executor: ToolExecutor by lazy {
        CompositeToolExecutor(providers, root, workspace,
            rootWorkspaceIds + (root as? RootToolExecutor)?.workspaceIds.orEmpty())
    }

    /**
     * Frozen model-facing registrations. An empty list is a valid run state:
     * it means the current Agent/snapshot has no effective optional tools, not
     * that factory construction failed.
     */
    private val registrations: List<ToolRegistration> by lazy {
        executor.specs.map { legacy ->
                val capability = legacy.capability.takeIf { it.isNotBlank() }?.let { CapabilityId(it) }
                ToolRegistration(
                    spec = ToolSpec(
                        name = legacy.name,
                        description = legacy.description,
                        inputSchema = legacy.parametersJson,
                        capability = capability,
                        sideEffect = legacy.sideEffect,
                        schemaVersion = legacy.schemaVersion,
                    ),
                    ownerId = providers.filter { provider -> provider.executor.specs.any { it.name == legacy.name } }
                        .singleOrNull()?.ownerId ?: "workspace",
                )
        }.also { values ->
            require(values.map { it.spec.name }.distinct().size == values.size) {
                "Tool name is owned by more than one executor"
            }
        }
    }

    /** Shared skills-api registry with owner routing hidden from model schemas. */
    val registry: ToolRegistry by lazy {
        require(registrations.isNotEmpty()) {
            "Cannot create a ToolRegistry for an empty effective tool set"
        }
        require(registrations.map { it.spec.name }.distinct().size == registrations.size) {
            "Tool name is owned by more than one executor"
        }
        val handlers = registrations.map { it.ownerId }.distinct().associateWith {
            ToolHandler { invocation -> (executor as TypedToolExecutor).invoke(invocation) }
        }
        ToolRegistry(registrations, handlers, initialSnapshotId = boundSnapshotId,
            completedResultAuthorization = { invocation ->
                executor.authorizeReplay(ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson))
            })
    }

    val toolingSpecs: List<ToolSpec> by lazy {
        Collections.unmodifiableList(registrations.map { it.spec }.toList())
    }

    val exposureSummary: ToolExposureSummary by lazy {
        ToolExposureSummary(
            totalTools = registrations.size,
            ownerToolCounts = Collections.unmodifiableMap(
                registrations.groupingBy { registration -> registration.ownerId }.eachCount(),
            ),
        )
    }

    fun createLegacyExecutor(): ToolExecutor = executor
    fun createToolRegistry(): ToolRegistry = registry
    fun createToolRegistryOrNull(): ToolRegistry? = if (registrations.isEmpty()) null else registry
    fun beginRun(): ToolRunSnapshot = registry.beginRun()

    suspend fun invoke(call: ToolCall): ToolResult = executor.invoke(call)

    suspend fun approve(callId: String): ToolResult = executor.approve(callId)

    /**
     * Disclosure check for a cached result owned by the composite.  This only
     * routes to [CompositeToolExecutor.authorizeReplay]; it never dispatches.
     */
    suspend fun authorizeReplay(call: ToolCall): Boolean = executor.authorizeReplay(call)
    /** Resolve a pending legacy approval through the executor that accepted the call. */
    suspend fun reject(callId: String): ToolResult = executor.reject(callId)

    /** Expire a pending legacy approval through the executor that accepted the call. */
    suspend fun expire(callId: String): ToolResult = executor.expire(callId)

    suspend fun invoke(invocation: ToolInvocation): ToolExecution = registry.dispatch(invocation)

    /**
     * Cancellation is intentionally a separate typed seam with a single owner:
     * a Root run routes cancellation only to its Root executor, otherwise only
     * the shell executor owns an active one-shot request.  There is no provider
     * fallback or broadcast.
     */
    suspend fun cancel(requestId: String): Boolean = when {
        root != null -> (root as? TypedToolExecutor)?.cancel(requestId) == true
        else -> shell?.cancel(requestId) == true
    }
}

private data class NamedExecutor(val ownerId: String, val executor: ToolExecutor)

/**
 * Optional typed seam for a provider-owned executor that needs both the model
 * correlation id and the runtime invocation id.
 *
 * A legacy [ToolExecutor] only ever sees one id in [ToolCall.callId]; the
 * runtime passes its own invocation id there, so the model id would be lost.
 * Implementations of this seam receive the shared [ToolInvocation] unchanged
 * and must keep `invocation.requestId` as the sole execution/approval key.
 * `cancel` defaults to false because a provider without an active one-shot
 * request has nothing to cancel.
 */
interface TypedToolExecutor {
    suspend fun invoke(invocation: ToolInvocation): ToolExecution

    suspend fun cancel(requestId: String): Boolean = false
}

/** Safe counts only; names, schemas, paths and provider details are deliberately absent. */
data class ToolExposureSummary(
    val totalTools: Int,
    val ownerToolCounts: Map<String, Int>,
) {
    val hasExposedTools: Boolean get() = totalTools > 0
}

/** Provider composition uses the same lifecycle as controlled Root tools. */
private class CompositeToolExecutor(
    private val providers: List<NamedExecutor>,
    private val root: ToolExecutor?,
    private val workspace: UnifiedWorkspaceToolExecutor?,
    private val rootWorkspaceIds: Set<String>,
) : ManagedToolExecutor<ComposedInvocation>(ModelToolArguments.mergeSpecs(providers.flatMap { it.executor.specs })) {
    private val executors: List<ToolExecutor> = providers.map { it.executor }
    private val ownerNames: Map<ToolExecutor, String> = providers.associate { it.executor to it.ownerId }
    private val owners = executors.flatMap { executor -> executor.specs.map { it.name to executor } }
        .groupBy({ it.first }, { it.second }).also { byName ->
            require(byName.values.all { values -> values.size == 1 ||
                (values.size == 2 && root != null && workspace != null && values.toSet() == setOf(root, workspace)) }) {
                "Tool name is owned by more than one executor"
            }
        }
    /** Only the typed Root executor can distinguish a frozen-but-offline binding from a live one. */
    private val rootRouter: RootToolExecutor? = root as? RootToolExecutor
    private val rootLiveIds: Set<String> = rootRouter?.workspaceIds.orEmpty()
    private val rootExposedTools: Set<String> = rootRouter?.exposedToolNames.orEmpty()
    private val workspaceTools: Set<String> = workspace?.specs?.mapTo(linkedSetOf()) { it.name }.orEmpty()

    private fun workspaceRouted(name: String, candidates: List<ToolExecutor>): Boolean =
        candidates.any { it === root || it === workspace } ||
            (rootWorkspaceIds.isNotEmpty() && name in RootToolArguments.workspaceToolNames)

    override fun normalize(call: ToolCall, typed: ToolInvocation?): ComposedInvocation? {
        val candidates = owners[call.name] ?: return null
        val workspaceRouted = workspaceRouted(call.name, candidates)
        val selected = if (call.name == WorkspaceDiscovery.NAME) {
            if (!WorkspaceDiscovery.validArguments(call.argumentsJson)) return null
            candidates
        } else if (!workspaceRouted) {
            // Provider-only tools (web/MCP/Python/memory) keep their own argument
            // vocabulary; workspace_id is not part of their protocol.
            if (candidates.size != 1) return null
            candidates
        } else {
            val id = when (val selection = ModelToolArguments.workspaceIdSelection(call.argumentsJson)) {
                // A missing or malformed workspace_id is a request error, not an
                // ambiguous route and not an unavailable provider.
                WorkspaceIdSelection.Absent, WorkspaceIdSelection.Malformed -> return null
                is WorkspaceIdSelection.Present -> selection.id
            }
            when {
                // A frozen Root binding keeps the Root route even while its service is
                // offline; an ordinary workspace with the same spelling is never tried.
                id in rootWorkspaceIds -> {
                    if (root == null || root !in candidates) return null
                    if (rootRouter != null && id !in rootLiveIds) return null
                    listOf(root)
                }
                workspace != null && workspace in candidates && id in workspace.workspaceIds -> listOf(workspace)
                else -> return null
            }
        }
        if (selected.isEmpty() || (call.name != WorkspaceDiscovery.NAME && selected.size != 1)) return null
        // One canonical spelling reaches routing, approval and both typed backends.
        val canonical = if (workspaceRouted) {
            ModelToolArguments.canonicalWorkspaceArguments(call.argumentsJson) ?: return null
        } else call.argumentsJson
        val canonicalCall = if (canonical == call.argumentsJson) call else call.copy(argumentsJson = canonical)
        return ComposedInvocation(canonicalCall, typed, selected)
    }

    /**
     * The pre-composition legacy executor answered an unexposed name with
     * `Invalid("Unknown tool")`. A built-in workspace call that explicitly names a frozen Root
     * id must not be flattened when authoritative evidence says the Root side is offline; a
     * live Root whose grant is missing keeps its AUTHORITY_NOT_GRANTED answer, and a non-typed
     * legacy stub invents neither state. This only selects a result: no dispatch, no provider
     * fallback, and no change to the exposed tool set.
     */
    override fun legacyUnknownTool(call: ToolCall): ToolResult {
        if (call.name !in RootToolArguments.workspaceToolNames) return ToolResult.Invalid("Unknown tool")
        val id = (ModelToolArguments.workspaceIdSelection(call.argumentsJson)
            as? WorkspaceIdSelection.Present)?.id ?: return ToolResult.Invalid("Unknown tool")
        if (id !in rootWorkspaceIds) return ToolResult.Invalid("Unknown tool")
        return when {
            root == null -> unavailableRootWorkspace()
            // A non-typed legacy stub has no live/offline evidence; keep the legacy boundary.
            rootRouter == null -> ToolResult.Invalid("Unknown tool")
            id !in rootLiveIds -> unavailableRootWorkspace()
            call.name !in rootExposedTools -> rootCapabilityNotGranted()
            else -> ToolResult.Invalid("Unknown tool")
        }
    }

    override fun legacyNoPendingApproval(callId: String): ToolResult =
        ToolResult.Invalid("No pending approval")

    override fun normalizationFailure(call: ToolCall): ToolResult {
        if (call.name == WorkspaceDiscovery.NAME) return ToolResult.Invalid("INVALID_REQUEST")
        val candidates = owners[call.name] ?: return ToolResult.Invalid("INVALID_REQUEST")
        if (!workspaceRouted(call.name, candidates)) return ToolResult.Invalid("Invalid tool arguments")
        val id = when (val selection = ModelToolArguments.workspaceIdSelection(call.argumentsJson)) {
            WorkspaceIdSelection.Absent, WorkspaceIdSelection.Malformed -> return ToolResult.Invalid("INVALID_REQUEST")
            is WorkspaceIdSelection.Present -> selection.id
        }
        return when {
            id in rootWorkspaceIds -> when {
                root == null -> unavailableRootWorkspace()
                // A non-typed legacy Root stub has no live exposure snapshot; let it
                // answer for itself instead of inventing an offline state.
                rootRouter == null -> ToolResult.Failure(ToolError(ToolErrorCode.OPERATION_UNAVAILABLE))
                id !in rootLiveIds -> unavailableRootWorkspace()
                call.name !in rootExposedTools -> rootCapabilityNotGranted()
                // The frozen Root executor rejected an otherwise valid request.
                else -> ToolResult.Invalid("Invalid Root tool arguments")
            }
            workspace != null && id in workspace.workspaceIds -> when {
                call.name !in workspaceTools -> ToolResult.Failure(ToolError(
                    ToolErrorCode.OPERATION_UNAVAILABLE,
                    message = "Tool is not available for this workspace",
                ))
                else -> ToolResult.Invalid("Invalid tool arguments")
            }
            else -> ToolResult.Failure(ToolError(
                ToolErrorCode.WORKSPACE_NOT_FOUND,
                message = "Workspace is not authorized for this run",
            ))
        }
    }

    override suspend fun execute(invocation: ComposedInvocation): ToolResult {
        invocation.owners.forEach { owner -> invocation.record(owner, invokeOwner(owner, invocation)) }
        return combined(invocation)
    }
    override suspend fun approveInvocation(invocation: ComposedInvocation): ToolResult = settleChildren(invocation) { it.approve(invocation.runtimeInvocationId) }
    override suspend fun rejectInvocation(invocation: ComposedInvocation): ToolResult = settleChildren(invocation) { it.reject(invocation.runtimeInvocationId) }
    override suspend fun expireInvocation(invocation: ComposedInvocation): ToolResult = settleChildren(invocation) { it.expire(invocation.runtimeInvocationId) }
    private suspend fun settleChildren(invocation: ComposedInvocation, settle: suspend (ToolExecutor) -> ToolResult): ToolResult {
        invocation.owners.filter { invocation.results[it] == ToolResult.NeedsApproval }
            .forEach { owner -> invocation.record(owner, settle(owner)) }
        return combined(invocation)
    }
    /**
     * Only owners that actually contributed a [ToolResult.Value] to this exact
     * execution are consulted.  A failed discovery side contributed only an
     * `availability` marker and must not veto the healthy side's cached or
     * deferred disclosure.  Every successful contributor still has to
     * re-authorize, and an empty contributor set denies: a revoked successful
     * side can never be released through the other side's authorization.
     */
    override suspend fun authorizeInvocation(invocation: ComposedInvocation, disclosure: ResultDisclosure): Boolean {
        val contributors = invocation.contributingOwners()
        if (contributors.isEmpty()) return false
        return contributors.all { it.authorizeDisclosure(invocation.call, disclosure) }
    }
    override suspend fun precheckInvocation(invocation: ComposedInvocation): Boolean {
        val contributors = invocation.contributingOwners()
        if (contributors.isEmpty()) return false
        return contributors.all { it.precheckDeferredResult(invocation.call) }
    }
    override suspend fun withholdInvocation(invocation: ComposedInvocation) {
        invocation.owners.forEach { it.withholdDeferredResult(invocation.call) }
    }
    override suspend fun cancelInvocation(invocation: ComposedInvocation): Boolean = invocation.owners.map { owner ->
        when (owner) {
            is TypedToolExecutor -> owner.cancel(invocation.runtimeInvocationId)
            is ShellToolExecutor -> owner.cancel(invocation.runtimeInvocationId)
            else -> false
        }
    }.all { it }
    override suspend fun closeResources() { executors.distinct().forEach { it.close() } }

    private fun combined(invocation: ComposedInvocation): ToolResult {
        val values = invocation.owners.map { invocation.results[it] ?: return ToolResult.UnknownOutcome("Missing child result") }
        values.firstOrNull { it is ToolResult.UnknownOutcome }?.let { return it }
        val failure = values.firstOrNull { it != ToolResult.NeedsApproval && it !is ToolResult.Value }
        if (failure != null) {
            val successes = values.filterIsInstance<ToolResult.Value>()
            // Discovery is the one multi-owner tool.  A healthy side must remain
            // visible with explicit availability instead of being erased by an
            // unrelated offline provider.
            return if (invocation.call.name == WorkspaceDiscovery.NAME && successes.isNotEmpty()) {
                partialDiscovery(invocation, values, successes)
            } else failure
        }
        if (ToolResult.NeedsApproval in values) return ToolResult.NeedsApproval
        return if (values.size == 1) values.single() else WorkspaceDiscovery.combine(values.filterIsInstance<ToolResult.Value>())
    }

    private fun partialDiscovery(
        invocation: ComposedInvocation,
        values: List<ToolResult>,
        successes: List<ToolResult.Value>,
    ): ToolResult = runCatching {
        val entries = successes.flatMap { value ->
            parseWorkspaces(value).getValue("workspaces").jsonArray
        }
        val ids = entries.map { it.jsonObject.getValue("workspace_id").jsonPrimitive.content }
        if (ids.distinct().size != ids.size) return ToolResult.Denied("CAPABILITY_DENIED")
        val availability = buildJsonObject {
            invocation.owners.forEachIndexed { index, owner ->
                putJsonObject(ownerNames[owner] ?: "provider") {
                    val result = values[index]
                    put("available", result is ToolResult.Value)
                    if (result !is ToolResult.Value) put("error", availabilityCode(result))
                }
            }
        }
        ToolResult.Value(buildJsonObject {
            put("success", true)
            put("degraded", true)
            put("workspaces", JsonArray(entries))
            put("availability", availability)
        }.toString())
    }.getOrElse { ToolResult.Invalid("INVALID_REQUEST") }

    private fun parseWorkspaces(value: ToolResult.Value): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(value.json).jsonObject

    private fun availabilityCode(result: ToolResult): String = when (result) {
        is ToolResult.Failure -> result.error.code.name
        is ToolResult.Denied -> result.reason.takeIf { it.matches(SAFE_CODE) } ?: "CAPABILITY_DENIED"
        is ToolResult.Invalid -> result.reason.takeIf { it.matches(SAFE_CODE) } ?: "INVALID_REQUEST"
        is ToolResult.UnknownOutcome -> "UNKNOWN_OUTCOME"
        ToolResult.NeedsApproval -> "APPROVAL_REQUIRED"
        is ToolResult.Value -> "AVAILABLE"
    }

    private suspend fun invokeOwner(owner: ToolExecutor, invocation: ComposedInvocation): ToolResult {
        val typed = invocation.typed ?: return owner.invoke(invocation.call)
        val result = when (owner) {
            is TypedToolExecutor -> owner.invoke(typed)
            is UnifiedWorkspaceToolExecutor -> owner.invoke(typed)
            is ShellToolExecutor -> owner.invoke(typed)
            else -> return owner.invoke(invocation.call)
        }
        return when (result) {
            is ToolExecution.Value -> ToolResult.Value(result.json)
            is ToolExecution.Unknown -> ToolResult.UnknownOutcome("Tool outcome is unknown")
            is ToolExecution.Failed -> if (result.error.code == ToolErrorCode.APPROVAL_REQUIRED)
                ToolResult.NeedsApproval else ToolResult.Failure(result.error)
        }
    }

    private fun unavailableRootWorkspace(): ToolResult = ToolResult.Failure(ToolError(
        ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
        message = "Root workspace is temporarily unavailable",
        retryable = true,
    ))

    private fun rootCapabilityNotGranted(): ToolResult = ToolResult.Failure(ToolError(
        ToolErrorCode.AUTHORITY_NOT_GRANTED,
        message = "Root capability is not granted for this workspace",
    ))

    private companion object {
        val SAFE_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
    }
}

private class ComposedInvocation(val call: ToolCall, val typed: ToolInvocation?, val owners: List<ToolExecutor>) : ManagedToolInvocation {
    val results = linkedMapOf<ToolExecutor, ToolResult>()
    /**
     * The exact owners whose settled result was a Value.  Availability-only or
     * failed owners never enter this set, so they cannot veto disclosure of the
     * healthy side; successful owners are never removed, so a revoked successful
     * side still denies the whole combined result.  Identity semantics keep two
     * distinct provider instances from collapsing into one authorization check.
     */
    private val valueOwners: MutableSet<ToolExecutor> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ToolExecutor, Boolean>())
    override val runtimeInvocationId: String get() = call.callId
    override val modelCallId: String? get() = typed?.callId
    override val toolName: String get() = call.name
    override val identity: String get() = "${call.name}|${call.argumentsJson}"
    override val contextIdentity: String? get() = typed?.let { "${it.snapshotId}|${it.agentId}" }

    fun record(owner: ToolExecutor, result: ToolResult) {
        results[owner] = result
        if (result is ToolResult.Value) valueOwners.add(owner) else valueOwners.remove(owner)
    }

    fun contributingOwners(): List<ToolExecutor> = owners.filter { it in valueOwners }

    override fun toString(): String = "ComposedInvocation(tool=$toolName)"
}
