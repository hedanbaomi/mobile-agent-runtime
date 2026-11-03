// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.CoroutineScope
import runtime.mobileagent.privileged.RootChildInvocationSupport
import runtime.mobileagent.privileged.RootToolArguments
import runtime.mobileagent.skills.BuiltinTools
import runtime.mobileagent.skills.ManagedJobToolExecutor
import runtime.mobileagent.skills.ReadBatchToolExecutor
import runtime.mobileagent.skills.RunControlTools
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.InternalRequestIds
import runtime.mobileagent.tooling.ShellToolExecutor
import runtime.mobileagent.tooling.UnifiedWorkspaceToolExecutor
import java.util.concurrent.ConcurrentHashMap

/** Explicit eligible tools only: screen, HTTP, MCP, Python and mutations cannot enter a read batch. */
internal class RunProductivityTools(
    private val core: ToolExecutor,
    scope: CoroutineScope,
    liveContext: suspend () -> Boolean,
    reserveCalls: (Int) -> Int,
    excludedChildTools: Set<String> = emptySet(),
    approvalRequiredChildTools: Set<String> = emptySet(),
) {
    private class ControlBinding(
        val call: ToolCall,
        val runtimeId: String = InternalRequestIds.new(),
        @Volatile var needsApproval: Boolean = false,
        @Volatile var inFlight: Boolean = false,
        @Volatile var resultPendingObservation: Boolean = false,
        @Volatile var resultObserved: Boolean = false,
    )
    private val controlIds = ConcurrentHashMap<String, ControlBinding>()
    private val lifecycleIds = ConcurrentHashMap<String, String>()
    fun ownsTool(name: String): Boolean = name in RunControlTools.ALL
    fun runtimeInvocationId(modelCallId: String): String? {
        val binding = controlIds[modelCallId] ?: return null
        if (binding.needsApproval || binding.inFlight) return binding.runtimeId
        if (!binding.resultPendingObservation || binding.resultObserved) return null
        binding.resultPendingObservation = false
        binding.resultObserved = true
        return binding.runtimeId
    }
    private fun bindingFor(callId: String): ControlBinding? =
        controlIds[callId] ?: controlIds.values.firstOrNull { it.runtimeId == callId }
    private fun lifecycleId(callId: String): String = lifecycleIds[callId] ?: callId
    private fun controlCall(call: ToolCall): ToolCall = if (ownsTool(call.name))
        call.copy(callId = controlIds[call.callId]?.runtimeId ?: call.callId) else call
    private val exposed = core.specs.map { it.name }.toSet() - excludedChildTools
    private val reads = READ_TOOLS intersect exposed
    private val rootReads = core.specs
        .filter { spec -> RootToolArguments.spec(spec.name)?.inputSchema == spec.parametersJson }
        .map { it.name }
        .toSet() intersect reads
    private val batch = ReadBatchToolExecutor(
        delegate = core,
        liveAuthorized = liveContext,
        replayAuthorized = core::authorizeReplay,
        reserveCalls = reserveCalls,
        eligibleTools = reads,
        deferredResultAuthorized = core::authorizeDeferredResult,
        childCallId = { outer, index, _ -> RootChildInvocationSupport.mintBatchChildCallId(outer, index) },
        approvalRequiredTools = (approvalRequiredChildTools intersect reads) + rootReads,
    )
    private val jobs = ManagedJobToolExecutor(
        delegate = batch,
        scope = scope,
        liveAuthorized = liveContext,
        replayAuthorized = batch::authorizeReplay,
        reserveCalls = reserveCalls,
        eligibleTools = (reads + ShellToolExecutor.SHELL_EXEC) intersect exposed,
        childCallId = { outer, _ -> RootChildInvocationSupport.mintJobChildCallId(outer) },
        deferredResultAuthorized = batch::authorizeDeferredResult,
        // A stopped read changed nothing; only shell jobs keep an unknown outcome.
        readOnlyTools = reads,
    )

    val hasUnknownOutcomes: Boolean get() = jobs.hasUnknownOutcomes || batch.hasUnknownOutcomes
    val executor: ToolExecutor = object : ToolExecutor {
        override val specs = jobs.specs
        override suspend fun invoke(call: ToolCall): ToolResult {
            if (RootChildInvocationSupport.isReservedChildCallId(call.callId)) {
                return ToolResult.Invalid("Reserved runtime child call ID")
            }
            val binding = if (ownsTool(call.name)) {
                controlIds.computeIfAbsent(call.callId) { ControlBinding(call) }.also {
                    if (it.call != call) return ToolResult.Invalid("Tool call ID was already used for a different request")
                    it.inFlight = true
                    it.resultPendingObservation = false
                }
            } else null
            val routed = controlCall(call)
            lifecycleIds[call.callId] = routed.callId
            return try {
                guarded { jobs.invoke(routed) }.also { result ->
                    if (binding != null) {
                        binding.needsApproval = result is ToolResult.NeedsApproval
                        binding.resultPendingObservation = !binding.resultObserved && result !is ToolResult.NeedsApproval
                    }
                }
            } finally {
                binding?.inFlight = false
            }
        }
        override suspend fun approve(callId: String): ToolResult = settleLifecycle(callId) { guarded { jobs.approve(it) } }
        override suspend fun reject(callId: String): ToolResult = settleLifecycle(callId) { jobs.reject(it) }
        override suspend fun expire(callId: String): ToolResult = settleLifecycle(callId) { jobs.expire(it) }
        override suspend fun authorizeReplay(call: ToolCall): Boolean = !hasUnknownOutcomes && jobs.authorizeReplay(controlCall(call))
        override suspend fun authorizeDeferredResult(call: ToolCall): Boolean = !hasUnknownOutcomes && jobs.authorizeDeferredResult(controlCall(call))
        override suspend fun precheckDeferredResult(call: ToolCall): Boolean = !hasUnknownOutcomes && jobs.precheckDeferredResult(controlCall(call))
        override suspend fun withholdDeferredResult(call: ToolCall) = jobs.withholdDeferredResult(controlCall(call))
        override suspend fun close() {
            try { closeChildren() } finally { try { core.close() } finally { controlIds.clear(); lifecycleIds.clear() } }
        }
    }

    private suspend fun settleLifecycle(callId: String, action: suspend (String) -> ToolResult): ToolResult {
        val binding = bindingFor(callId)
        binding?.inFlight = true
        return try {
            action(lifecycleId(callId)).also {
                if (binding != null) {
                    binding.needsApproval = false
                    binding.resultPendingObservation = !binding.resultObserved
                }
            }
        } finally {
            binding?.inFlight = false
        }
    }

    suspend fun closeChildren() {
        try { jobs.close() } finally { batch.close() }
    }

    private suspend fun guarded(action: suspend () -> ToolResult): ToolResult {
        if (hasUnknownOutcomes) return unknown()
        val result = action()
        return if (hasUnknownOutcomes) unknown() else result
    }

    private fun unknown(): ToolResult = ToolResult.UnknownOutcome("A dispatched child tool has an unknown outcome; stop and do not retry automatically")

    private companion object {
        /** Side-effect-free tools that may run in a batch or a read-only job. */
        val READ_TOOLS = setOf(
            BuiltinTools.calculator.name,
            UnifiedWorkspaceToolExecutor.FILE_LIST,
            UnifiedWorkspaceToolExecutor.FILE_STAT,
            UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
            UnifiedWorkspaceToolExecutor.FILE_GLOB,
            UnifiedWorkspaceToolExecutor.FILE_GREP,
        )
    }
}
