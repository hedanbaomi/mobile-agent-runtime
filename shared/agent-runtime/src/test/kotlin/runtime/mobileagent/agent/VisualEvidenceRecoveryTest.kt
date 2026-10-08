// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.provider.*

class VisualEvidenceRecoveryTest {
    private val originals = (1..8).map { InlineImage("image/png", "aW1hZ2U=", "source-$it", 5, "hash-$it") }
    private class Adapter(val afterReply: () -> Unit = {}, val reply: (Int, Int) -> List<ModelEvent>) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
        override fun stream(request: ModelRequest, secret: CharArray) = flow {
            requests += request
            reply(request.messages.sumOf { it.images.size }, requests.size).forEach { emit(it) }
            afterReply()
        }
    }
    private fun done(text: String) = listOf(ModelEvent.TextDelta(text), ModelEvent.Usage(100, 50), ModelEvent.Completed)
    private fun oversizedOrDone(n: Int, call: Int) = done(if (n == 8) "FAILED_PARENT_" + "x".repeat(16_001) else if (n > 0) "child-$call evidence" else "answer")
    private fun execute(adapter: Adapter, budget: Int = 32, beforeImage: suspend (List<InlineImage>) -> Unit = {}) = runBlocking {
        val run = AgentRun("run", "s", "c", budget = RunBudget(maxModelRounds = budget))
        val events = AgentRuntime(adapter).run(AgentRuntimeRequest(run,
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = originals),
            "selected-model", charArrayOf(), false, batchAllImages = true, outputTokenLimit = 8192, beforeImageRequest = beforeImage)).toList()
        run to events
    }
    @Test fun completedOversizedEightImageReplyIsReplacedByTwoCompleteChildReceipts() {
        val adapter = Adapter(reply = ::oversizedOrDone)
        val (run, events) = execute(adapter)
        assertEquals(RunState.COMPLETED, run.state, run.stopReason)
        assertEquals(listOf(8, 4, 4, 0), adapter.requests.map { it.messages.sumOf { m -> m.images.size } })
        assertEquals(originals, adapter.requests[1].messages.flatMap { it.images } + adapter.requests[2].messages.flatMap { it.images })
        val receipts = events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>()
        assertEquals(2, receipts.size)
        assertEquals(originals.map { it.assetId }, receipts.flatMap { it.assets }.map { it.assetId })
        val final = adapter.requests.last().messages.joinToString { it.text }
        assertTrue(final.contains("child-2 evidence") && final.contains("child-3 evidence"))
        assertFalse(final.contains("FAILED_PARENT_"))
        assertEquals(4, run.modelRounds)
        assertEquals(4, events.filterIsInstance<RuntimeEvent.ModelEvent>().count { it.event is ModelEvent.Usage })
        assertTrue(adapter.requests.all { it.modelId == "selected-model" && it.outputTokenLimit == 8192 })
    }
    @Test fun invalidParentConsumesBudgetAndStopsBeforeSecondChildWithoutLosingFirstReceipt() {
        val adapter = Adapter(reply = ::oversizedOrDone)
        val (run, events) = execute(adapter, 2)
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals(listOf(8, 4), adapter.requests.map { it.messages.sumOf { m -> m.images.size } })
        assertEquals(1, events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
        assertEquals(2, events.filterIsInstance<RuntimeEvent.ModelEvent>().count { it.event is ModelEvent.Usage })
    }
    @Test fun unknownChildStopsImmediatelyAndPreservesFirstChildReceipt() {
        val adapter = Adapter { n, call -> if (call == 3) listOf(ModelEvent.Failed("UNKNOWN_OUTCOME")) else oversizedOrDone(n, call) }
        val (run, events) = execute(adapter)
        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(listOf(8, 4, 4), adapter.requests.map { it.messages.sumOf { m -> m.images.size } })
        assertEquals(1, events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
    }
    @Test fun unusableSingleImageGetsOnlyOneCompactRetryAfterRecursiveSplit() {
        val adapter = Adapter { _, _ -> done("{}") }
        val (run, events) = execute(adapter)
        assertEquals(RunState.FAILED, run.state)
        assertEquals(listOf(8, 4, 2, 1, 1), adapter.requests.map { it.messages.sumOf { m -> m.images.size } })
        assertTrue(events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().isEmpty())
        assertEquals(5, run.modelRounds)
    }
    @Test fun failuresRefusalsToolCallsAndMissingTerminalNeverEnterCompletedReplyRefinement() {
        val fixtures = listOf(
            listOf(ModelEvent.TextDelta("{}"), ModelEvent.Failed("INVALID_RESPONSE"), ModelEvent.Completed),
            listOf(ModelEvent.TextDelta("{}"), ModelEvent.Failed("UNKNOWN_OUTCOME")),
            listOf(ModelEvent.RefusalDelta("no"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCallDelta("call", "knowledge_search", "{}"), ModelEvent.Completed),
            listOf(ModelEvent.TextDelta("{}")),
        )
        for (reply in fixtures) {
            val adapter = Adapter { _, _ -> reply }
            val (run, events) = execute(adapter)
            assertTrue(run.state in setOf(RunState.FAILED, RunState.UNKNOWN_OUTCOME))
            assertEquals(1, adapter.requests.size)
            assertTrue(events.filterIsInstance<RuntimeEvent.VisualBatchRejected>().isEmpty())
        }
    }
    @Test fun permissionIsRecheckedBeforeEveryRefinedDispatch() {
        val adapter = Adapter(reply = ::oversizedOrDone)
        var checks = 0
        val (run, events) = execute(adapter, beforeImage = {
            if (++checks == 2) error("PERMISSION_DENIED: source revoked")
        })
        assertEquals(RunState.FAILED, run.state)
        assertEquals(1, adapter.requests.size)
        assertEquals(2, checks)
        assertEquals(1, events.filterIsInstance<RuntimeEvent.VisualBatchRejected>().size)
    }
    @Test fun cancellationAfterKnownCompletedRejectionDoesNotBecomeUnknownOutcome() = runBlocking {
        val adapter = Adapter(reply = ::oversizedOrDone)
        val run = AgentRun("run", "s", "c")
        try {
            AgentRuntime(adapter).run(AgentRuntimeRequest(run,
                EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = originals),
                "selected-model", charArrayOf(), false, batchAllImages = true)).collect {
                if (it is RuntimeEvent.VisualBatchRejected) throw CancellationException("user cancelled refinement")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(RunState.CANCELLED, run.state)
        assertFalse(run.stopReason.orEmpty().contains("UNKNOWN"))
        assertEquals(1, adapter.requests.size)
    }

    @Test fun transportExceptionAfterCompletedCannotTriggerRefinement() {
        val adapter = Adapter(afterReply = { throw java.io.IOException("connection lost after terminal") }, reply = { _, _ -> done("{}") })
        val (run, events) = execute(adapter)
        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, adapter.requests.size)
        assertTrue(events.filterIsInstance<RuntimeEvent.VisualBatchRejected>().isEmpty())
    }
    @Test fun fullyRefinedEightOriginalsUseAtMostTwentyThreeAnalysisAttemptsAndEightReceipts() {
        var singletonAttempts = 0
        val adapter = Adapter { n, _ -> done(when {
            n == 0 -> "answer"
            n > 1 -> "{}"
            ++singletonAttempts % 2 == 1 -> "{}"
            else -> "compact source evidence"
        }) }
        val (run, events) = execute(adapter)
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals(24, adapter.requests.size)
        assertEquals(8, events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
        assertEquals(originals.map { it.assetId }, events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().flatMap { it.assets }.map { it.assetId })
        assertEquals(24, run.modelRounds)
        assertEquals(24, adapter.requests.map { it.operationId }.toSet().size)
    }

    @Test fun refinementUsesTheOriginalDeadlineAndRetainsCompletedAttemptUsage() = runBlocking {
        var now = 1_000L
        val adapter = Adapter(afterReply = { now += 200 }, reply = ::oversizedOrDone)
        val run = AgentRun("run", "s", "c", budget = RunBudget(maxRuntimeMs = 150))
        val events = AgentRuntime(adapter, clock = { now }).run(AgentRuntimeRequest(run,
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = originals),
            "selected-model", charArrayOf(), false, batchAllImages = true)).toList()
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals(1, adapter.requests.size)
        assertEquals(1, events.filterIsInstance<RuntimeEvent.ModelEvent>().count { it.event is ModelEvent.Usage })
        assertEquals(1_000L, run.startedAtMs)
    }

}
