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
            // Legacy executors use ToolCall.callId as their execution/cache key.
            // The typed boundary must pass the runtime ID, never model correlation.
            val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
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
                is ToolResult.Failure -> ToolExecution.Failed(result.error)
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
        var newBinding: LegacyCallBinding? = null
        var settledReplay: SettledReplay? = null
        synchronized(lock) {
            val previous = callsById[call.callId]
            if (previous != null) {
                if (previous.owner !== owner || previous.call != call) {
                    return ToolResult.Invalid("Tool call ID was already used by another tool")
                }
                when (previous.state) {
                    LegacyCallState.IN_FLIGHT -> return ToolResult.UnknownOutcome("Tool call is already executing")
                    LegacyCallState.PENDING -> return ToolResult.NeedsApproval
                    LegacyCallState.SETTLING -> return ToolResult.UnknownOutcome("Tool call approval settlement is in progress")
                    LegacyCallState.UNKNOWN_OUTCOME -> return previous.result ?: unknownReplay()
                    LegacyCallState.SETTLED -> {
                        val cached = previous.result
                            ?: return ToolResult.Invalid("Tool call is already settled")
                        settledReplay = SettledReplay(previous, cached)
                    }
                    LegacyCallState.REPLAY_DENIED -> return previous.result ?: replayDenied()
                }
            } else {
                val created = LegacyCallBinding(owner, call)
                newBinding = created
                callsById[call.callId] = created
            }
        }
        settledReplay?.let { return replay(call, it) }
        val binding = checkNotNull(newBinding)
        return owner.invoke(call).also { result ->
            synchronized(lock) {
                binding.result = if (result is ToolResult.UnknownOutcome) unknownReplay() else result
                binding.state = if (result is ToolResult.UnknownOutcome) {
                    LegacyCallState.UNKNOWN_OUTCOME
                } else if (result == ToolResult.NeedsApproval) {
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

    /**
     * Forward cached-result disclosure to the child executor that actually ran
     * the call.  The composite never discloses from its own default: the
     * binding must be settled for exactly this call, the tool must still
     * route to the same owner, and the child revalidates live authorization.
     * A revoked child, an unknown call id, a reused call id with different
     * arguments, or a child failure therefore denies without dispatch and
     * without leaking the cached payload.  This never re-invokes the tool and
     * never returns the cached result itself (b07 follow-up finding A).
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean {
        val settled = synchronized(lock) {
            val binding = callsById[call.callId] ?: return@synchronized null
            if (binding.state != LegacyCallState.SETTLED) return@synchronized null
            if (binding.call != call) return@synchronized null
            if (owners[call.name] !== binding.owner) return@synchronized null
            val cached = binding.result ?: return@synchronized null
            SettledReplay(binding, cached)
        } ?: return false
        return authorizeReplay(call, settled)
    }

    /**
     * Revalidate a settled result outside [lock], then verify that the exact
     * cached object is still the one being disclosed.  A denied or failed
     * revalidation replaces the payload with a terminal tombstone, so a later
     * authorization change cannot resurrect the old result.
     */
    private suspend fun replay(call: ToolCall, settled: SettledReplay): ToolResult {
        if (!authorizeReplay(call, settled)) return replayDenied()
        return synchronized(lock) {
            if (settled.binding.state == LegacyCallState.SETTLED &&
                settled.binding.call == call &&
                owners[call.name] === settled.binding.owner &&
                settled.binding.result === settled.cached
            ) {
                settled.cached
            } else {
                replayDenied()
            }
        }
    }

    private suspend fun authorizeReplay(call: ToolCall, settled: SettledReplay): Boolean {
        val allowed = try {
            settled.binding.owner.authorizeReplay(call)
        } catch (_: Exception) {
            false
        }
        if (!allowed) {
            synchronized(lock) {
                if (settled.binding.state == LegacyCallState.SETTLED &&
                    settled.binding.result === settled.cached
                ) {
                    settled.binding.state = LegacyCallState.REPLAY_DENIED
                    settled.binding.result = replayDenied()
                }
            }
            return false
        }
        return synchronized(lock) {
            settled.binding.state == LegacyCallState.SETTLED &&
                settled.binding.call == call &&
                owners[call.name] === settled.binding.owner &&
                settled.binding.result === settled.cached
        }
    }

    private fun replayDenied(): ToolResult = ToolResult.Denied(REPLAY_DENIED_REASON)

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
                if (result is ToolResult.UnknownOutcome) {
                    binding.state = LegacyCallState.UNKNOWN_OUTCOME
                    binding.result = unknownReplay()
                } else {
                    binding.state = LegacyCallState.SETTLED
                    binding.result = result
                }
            }
        }
    }

    private data class LegacyCallBinding(
        val owner: ToolExecutor,
        val call: ToolCall,
        var state: LegacyCallState = LegacyCallState.IN_FLIGHT,
        var result: ToolResult? = null,
    )

    private data class SettledReplay(
        val binding: LegacyCallBinding,
        val cached: ToolResult,
    )

    private enum class LegacyCallState {
        IN_FLIGHT,
        PENDING,
        SETTLING,
        SETTLED,
        UNKNOWN_OUTCOME,
        REPLAY_DENIED,
    }

    private companion object {
        const val REPLAY_DENIED_REASON = "Tool authorization changed; cached tool output is unavailable"
        const val UNKNOWN_REPLAY_REASON = "Tool call outcome is unknown; it cannot be replayed"

        fun unknownReplay(): ToolResult = ToolResult.UnknownOutcome(UNKNOWN_REPLAY_REASON)
    }
}
