// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.util.Collections
import kotlinx.coroutines.CancellationException
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolHandler
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolRegistration
import runtime.mobileagent.skills.tooling.ToolRegistry
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.skills.tooling.ToolRunSnapshot

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
) {
    private val providers: List<NamedExecutor> = listOfNotNull(
        web?.let { NamedExecutor("web", it) },
        mcp?.let { NamedExecutor("mcp", it) },
        python?.let { NamedExecutor("python", it) },
        memory?.let { NamedExecutor("memory", it) },
        workspace?.let { NamedExecutor("workspace", it) },
        shell?.let { NamedExecutor("shell", it) },
    )

    /** Combined legacy boundary for the current runtime until it consumes the shared registry. */
    val executor: ToolExecutor by lazy {
        CompositeToolExecutor(providers.map { it.executor })
    }

    /**
     * Frozen model-facing registrations. An empty list is a valid run state:
     * it means the current Agent/snapshot has no effective optional tools, not
     * that factory construction failed.
     */
    private val registrations: List<ToolRegistration> by lazy {
        providers.flatMap { provider ->
            provider.executor.specs.mapNotNull { legacy ->
                val capability = legacy.capability.takeIf { it.isNotBlank() }?.let { CapabilityId(it) }
                ToolRegistration(
                    spec = ToolSpec(
                        name = legacy.name,
                        description = legacy.description,
                        inputSchema = legacy.parametersJson,
                        capability = capability,
                        sideEffect = legacy.sideEffect,
                    ),
                    ownerId = provider.ownerId,
                )
            }
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
        val handlers = providers.associate { provider ->
            provider.ownerId to ToolHandler { invocation -> provider.invoke(invocation) }
        }
        ToolRegistry(registrations, handlers)
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

    /** Resolve a pending legacy approval through the executor that accepted the call. */
    suspend fun reject(callId: String): ToolResult = executor.reject(callId)

    /** Expire a pending legacy approval through the executor that accepted the call. */
    suspend fun expire(callId: String): ToolResult = executor.expire(callId)

    suspend fun invoke(invocation: ToolInvocation): ToolExecution = registry.dispatch(invocation)

    /**
     * Cancellation is intentionally a separate typed seam: only the shell
     * executor owns an active one-shot request, and it routes cancellation to
     * the already-selected authority without provider fallback.
     */
    suspend fun cancel(requestId: String): Boolean = shell?.cancel(requestId) == true

    private data class NamedExecutor(
        val ownerId: String,
        val executor: ToolExecutor,
    ) {
        suspend fun invoke(invocation: ToolInvocation): ToolExecution {
            when (val typed = executor) {
                is UnifiedWorkspaceToolExecutor -> return typed.invoke(invocation)
                is ShellToolExecutor -> return typed.invoke(invocation)
            }
            val call = ToolCall(invocation.callId, invocation.name, invocation.argumentsJson)
            val result = executor.invoke(call)
            return when (result) {
                is ToolResult.Value -> ToolExecution.Value(result.json)
                is ToolResult.Denied -> ToolExecution.Failed(
                    runtime.mobileagent.skills.tooling.ToolError(
                        runtime.mobileagent.skills.tooling.ToolErrorCode.CAPABILITY_DENIED,
                    ),
                )
                is ToolResult.Invalid -> ToolExecution.Failed(
                    runtime.mobileagent.skills.tooling.ToolError(
                        runtime.mobileagent.skills.tooling.ToolErrorCode.INVALID_REQUEST,
                    ),
                )
                is ToolResult.UnknownOutcome -> ToolExecution.Unknown()
                ToolResult.NeedsApproval -> ToolExecution.Failed(
                    runtime.mobileagent.skills.tooling.ToolError(
                        runtime.mobileagent.skills.tooling.ToolErrorCode.APPROVAL_REQUIRED,
                    ),
                )
            }
        }
    }
}

/** Safe counts only; names, schemas, paths and provider details are deliberately absent. */
data class ToolExposureSummary(
    val totalTools: Int,
    val ownerToolCounts: Map<String, Int>,
) {
    val hasExposedTools: Boolean get() = totalTools > 0
}

private class CompositeToolExecutor(executors: List<ToolExecutor>) : ToolExecutor {
    private val lock = Any()
    private val owners = executors.flatMap { executor -> executor.specs.map { it.name to executor } }
        .also { values -> require(values.map { it.first }.distinct().size == values.size) { "Duplicate tool name" } }
        .toMap()
    /**
     * Legacy approval APIs carry only the model call id, not the tool name.
     * Remember the owner at invocation time so approval settlement cannot
     * probe every provider (or invoke a provider's default settlement).
     */
    private val callsById = mutableMapOf<String, LegacyCallBinding>()

    override val specs: List<LegacyToolSpec> = Collections.unmodifiableList(
        owners.keys.mapNotNull { name -> owners[name]?.specs?.firstOrNull { it.name == name } }.toList(),
    )

    override suspend fun invoke(call: ToolCall): ToolResult {
        val owner = owners[call.name] ?: return ToolResult.Invalid("Unknown tool")
        val binding: LegacyCallBinding
        synchronized(lock) {
            val previous = callsById[call.callId]
            if (previous != null) {
                if (previous.owner !== owner || previous.call != call) {
                    return ToolResult.Invalid("Tool call ID was already used by another tool")
                }
                return when (previous.state) {
                    LegacyCallState.IN_FLIGHT -> ToolResult.UnknownOutcome("Tool call is already executing")
                    LegacyCallState.PENDING -> ToolResult.NeedsApproval
                    LegacyCallState.SETTLING -> ToolResult.UnknownOutcome("Tool call approval settlement is in progress")
                    LegacyCallState.SETTLED -> previous.result ?: ToolResult.Invalid("Tool call is already settled")
                }
            }
            binding = LegacyCallBinding(owner, call)
            callsById[call.callId] = binding
        }
        return owner.invoke(call).also { result ->
            synchronized(lock) {
                binding.result = result
                binding.state = if (result == ToolResult.NeedsApproval) {
                    LegacyCallState.PENDING
                } else {
                    LegacyCallState.SETTLED
                }
            }
        }
    }

    override suspend fun approve(callId: String): ToolResult {
        return settle(callId) { owner -> owner.approve(callId) }
    }

    override suspend fun reject(callId: String): ToolResult {
        return settle(callId) { owner -> owner.reject(callId) }
    }

    override suspend fun expire(callId: String): ToolResult {
        return settle(callId) { owner -> owner.expire(callId) }
    }

    private suspend fun settle(
        callId: String,
        operation: suspend (ToolExecutor) -> ToolResult,
    ): ToolResult {
        val binding = synchronized(lock) {
            callsById[callId]?.takeIf { it.state == LegacyCallState.PENDING }?.also {
                it.state = LegacyCallState.SETTLING
            }
        } ?: return ToolResult.Invalid("No pending approval")
        return try {
            operation(binding.owner).also { result -> completeSettlement(binding, result) }
        } catch (cancelled: CancellationException) {
            completeSettlement(binding, ToolResult.UnknownOutcome("Approval settlement was cancelled"))
            throw cancelled
        } catch (_: Throwable) {
            val result = ToolResult.UnknownOutcome("Approval settlement outcome is unknown")
            completeSettlement(binding, result)
            result
        }
    }

    private fun completeSettlement(binding: LegacyCallBinding, result: ToolResult) {
        synchronized(lock) {
            if (binding.state == LegacyCallState.SETTLING) {
                binding.state = LegacyCallState.SETTLED
                binding.result = result
            }
        }
    }

    private data class LegacyCallBinding(
        val owner: ToolExecutor,
        val call: ToolCall,
        var state: LegacyCallState = LegacyCallState.IN_FLIGHT,
        var result: ToolResult? = null,
    )

    private enum class LegacyCallState { IN_FLIGHT, PENDING, SETTLING, SETTLED }
}
