// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.CoroutineScope
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
) {
    private data class ControlBinding(val call: ToolCall, val runtimeId: String = InternalRequestIds.new())
    private val controlIds = ConcurrentHashMap<String, ControlBinding>()
    private val lifecycleIds = ConcurrentHashMap<String, String>()
    fun ownsTool(name: String): Boolean = name in RunControlTools.ALL
    fun runtimeInvocationId(modelCallId: String): String? = controlIds[modelCallId]?.runtimeId
    private fun lifecycleId(callId: String): String = lifecycleIds[callId] ?: callId
    private fun controlCall(call: ToolCall): ToolCall = if (ownsTool(call.name))
        call.copy(callId = controlIds[call.callId]?.runtimeId ?: call.callId) else call
    private val exposed = core.specs.map { it.name }.toSet()
    private val reads = READ_TOOLS intersect exposed
    private val batch = ReadBatchToolExecutor(core, liveContext, core::authorizeReplay, reserveCalls,
        eligibleTools = reads, deferredResultAuthorized = core::authorizeDeferredResult)
    private val jobs = ManagedJobToolExecutor(batch, scope, liveContext, batch::authorizeReplay,
        reserveCalls = reserveCalls,
        eligibleTools = (reads + ShellToolExecutor.SHELL_EXEC) intersect exposed,
        deferredResultAuthorized = batch::authorizeDeferredResult,
        // A stopped read changed nothing; only shell jobs keep an unknown outcome.
        readOnlyTools = reads)

    val hasUnknownOutcomes: Boolean get() = jobs.hasUnknownOutcomes || batch.hasUnknownOutcomes
    val executor: ToolExecutor = object : ToolExecutor {
        override val specs = jobs.specs
        override suspend fun invoke(call: ToolCall): ToolResult {
            if (ownsTool(call.name)) {
                val binding = controlIds.computeIfAbsent(call.callId) { ControlBinding(call) }
                if (binding.call != call) return ToolResult.Invalid("Tool call ID was already used for a different request")
            }
            val routed = controlCall(call)
            lifecycleIds[call.callId] = routed.callId
            return guarded { jobs.invoke(routed) }
        }
        override suspend fun approve(callId: String): ToolResult = guarded { jobs.approve(lifecycleId(callId)) }
        override suspend fun reject(callId: String): ToolResult = jobs.reject(lifecycleId(callId))
        override suspend fun expire(callId: String): ToolResult = jobs.expire(lifecycleId(callId))
        override suspend fun authorizeReplay(call: ToolCall): Boolean = !hasUnknownOutcomes && jobs.authorizeReplay(controlCall(call))
        override suspend fun authorizeDeferredResult(call: ToolCall): Boolean = !hasUnknownOutcomes && jobs.authorizeDeferredResult(controlCall(call))
        override suspend fun close() {
            try { closeChildren() } finally { try { core.close() } finally { controlIds.clear(); lifecycleIds.clear() } }
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
