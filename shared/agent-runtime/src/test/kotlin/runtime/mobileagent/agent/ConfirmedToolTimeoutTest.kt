// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.provider.*
import runtime.mobileagent.skills.*

class ConfirmedToolTimeoutTest {
    private val receipt = ToolResult.Value("""{"status":"performed","action_confirmed":true,"observation":{"status":"timeout"}}""")

    @Test fun outerStallPreservesConfirmedResultsForInvokeAndApprove() = runTest {
        for (approval in listOf(false, true)) {
            val scenario = Scenario(approval)
            val events = scenario.execute { receipt }
            val results = events.filterIsInstance<RuntimeEvent.ToolResultProduced>()
            assertEquals(1, results.size, events.toString())
            val result = results.single()
            assertEquals("VALUE", result.status)
            assertEquals(receipt.json, result.resultJson)
            assertEquals(listOf(ToolCall("t1", "device_act", "{}")), scenario.receiptCalls)
            assertEquals(1, scenario.dispatches)
            assertEquals(2, scenario.modelCalls)
            assertEquals(RunState.COMPLETED, scenario.run.state)
        }
    }

    @Test fun absentOrFailedProofKeepsUnknownAndNeverRedispatches() = runTest {
        for (proof in listOf<((ToolCall) -> ToolResult.Value?)?>(null, { null }, { error("unavailable local receipt") })) {
            val scenario = Scenario(true)
            val events = scenario.execute(proof)
            assertEquals("UNKNOWN_OUTCOME", events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single().status)
            assertEquals(RunState.UNKNOWN_OUTCOME, scenario.run.state)
            assertEquals(1, scenario.dispatches)
            assertEquals(1, scenario.modelCalls)
        }
    }

    @Test fun waitingForApprovalCannotRecoverAnExecutionReceipt() = runTest {
        val scenario = Scenario(true, waitForApproval = true)
        scenario.execute { receipt }
        assertEquals(0, scenario.dispatches)
        assertTrue(scenario.receiptCalls.isEmpty())
        assertEquals(RunState.BUDGET_EXHAUSTED, scenario.run.state)
    }

    @Test fun userCancellationDoesNotConsumeTheTimeoutRecoveryHook() = runTest {
        val scenario = Scenario(true)
        val job = launch { scenario.execute { receipt } }
        withTimeout(1000) { scenario.dispatched.await() }
        job.cancelAndJoin()
        assertEquals(1, scenario.dispatches)
        assertTrue(scenario.receiptCalls.isEmpty())
        assertEquals(RunState.CANCELLED, scenario.run.state)
    }

    @Test fun recoveredResultsStillRespectTheRuntimeOutputLimit() = runTest {
        val scenario = Scenario(true)
        val events = scenario.execute { ToolResult.Value("""{"padding":"${"x".repeat(2 * 1024 * 1024)}"}""") }
        assertTrue(events.filterIsInstance<RuntimeEvent.ToolResultProduced>().isEmpty())
        assertEquals(RunState.FAILED, scenario.run.state)
        assertEquals(1, scenario.dispatches)
        assertEquals(1, scenario.modelCalls)
    }

    private class Scenario(private val approval: Boolean, private val waitForApproval: Boolean = false) {
        val run = AgentRun("timeout", "snapshot", "conversation", budget = RunBudget(maxRuntimeMs = 1000, stallTimeoutMs = 20))
        var dispatches = 0
        var modelCalls = 0
        val receiptCalls = mutableListOf<ToolCall>()
        val dispatched = CompletableDeferred<Unit>()

        suspend fun execute(proof: ((ToolCall) -> ToolResult.Value?)? = null): List<RuntimeEvent> {
            val adapter = object : ModelAdapter {
                override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
                override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
                override fun stream(request: ModelRequest, secret: CharArray) = flow {
                    if (modelCalls++ == 0) emit(ModelEvent.ToolCallDelta("t1", "device_act", "{}"))
                    else emit(ModelEvent.TextDelta("done"))
                    emit(ModelEvent.Completed)
                }
            }
            val executor = object : ToolExecutor {
                override val specs = listOf(ToolSpec("device_act", "fixture", "{\"type\":\"object\"}", "device.interact", true))
                override suspend fun invoke(call: ToolCall): ToolResult = if (approval) ToolResult.NeedsApproval else dispatch()
                override suspend fun approve(callId: String): ToolResult = dispatch()
                private suspend fun dispatch(): ToolResult {
                    dispatches++
                    dispatched.complete(Unit)
                    awaitCancellation()
                }
            }
            val request = AgentRuntimeRequest(run, EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                "model", charArrayOf(), toolsEnabled = true, executor = executor)
            return AgentRuntime(adapter, clock = { 0L }, onApprove = {
                if (waitForApproval) awaitCancellation()
                true
            }).run(if (proof == null) request else request.copy(confirmedToolResultAfterTimeout = { call ->
                receiptCalls += call
                proof(call)
            })).toList()
        }
    }
}
