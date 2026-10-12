// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentContextPolicy
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.provider.*
import runtime.mobileagent.skills.*

class DirectImageDeliveryTest {
    private val original = InlineImage("image/png", "", "upload", 5, "hash")
    private fun request(run: AgentRun = AgentRun("run", "snapshot", "conversation")) = AgentRuntimeRequest(
        run, EffectivePrompt("contract", "system", emptyList(), emptyList(), emptyList(), "read the image", listOf(original)),
        "fixture", charArrayOf(), false, batchAllImages = true, directImageAssetIds = setOf("upload"),
        imageLoader = { it.copy(base64 = "aW1hZ2U=") },
    )

    @Test fun toolsAndTheirFollowupReceiveTheSameOriginalWithoutAnAnalysisRequest() = runBlocking {
        var calls = 0
        val executor = object : ToolExecutor {
            override val specs = listOf(ToolSpec("lookup", "lookup", "{\"type\":\"object\"}", "", false))
            override suspend fun invoke(call: ToolCall): ToolResult { calls++; return ToolResult.Value("{}") }
            override suspend fun approve(callId: String): ToolResult = error("unused")
        }
        val adapter = RecordingAdapter { _ ->
            if (requests.size == 1) emit(ModelEvent.ToolCallDelta("call", "lookup", "{}"))
            else emit(ModelEvent.TextDelta("answer"))
            emit(ModelEvent.Completed)
        }
        var verified = 0
        val input = request().copy(toolsEnabled = true, executor = executor,
            beforeImageRequest = { assertEquals("aW1hZ2U=", it.single().base64); verified++ })
        val events = AgentRuntime(adapter).run(input).toList()
        assertEquals(RunState.COMPLETED, input.run.state)
        assertEquals(1, calls)
        assertEquals(2, adapter.requests.size)
        assertEquals(2, verified)
        adapter.requests.forEach {
            assertEquals("aW1hZ2U=", it.messages.flatMap { m -> m.images }.single().base64)
            assertEquals(1, it.tools.size)
        }
        assertEquals("call", adapter.requests.last().messages.last().toolCallId)
        assertTrue(events.none { it is RuntimeEvent.VisualBatchStarted })
    }

    @Test fun knowledgeBatchesExcludeDirectUploadAndMainRequestKeepsIt() = runBlocking {
        val refs = (1..9).map { original.copy(assetId = "kb-$it") }
        val adapter = RecordingAdapter()
        val input = request().let { it.copy(prompt = it.prompt.copy(typedHistory = listOf(ChatMessage("user", images = refs)))) }
        val events = AgentRuntime(adapter).run(input).toList()
        assertEquals(RunState.COMPLETED, input.run.state)
        assertEquals(listOf(8, 1, 1), adapter.requests.map { it.messages.sumOf { m -> m.images.size } })
        adapter.requests.dropLast(1).forEach { assertTrue(it.messages.flatMap { m -> m.images }.all { i -> i.assetId!!.startsWith("kb-") }) }
        assertEquals("upload", adapter.requests.last().messages.flatMap { it.images }.single().assetId)
        assertEquals(2, events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
    }

    @Test fun revocationAfterMainPreviewPreventsAnyProviderDispatch() = runBlocking {
        var allowed = true
        val adapter = RecordingAdapter()
        val input = request().copy(beforeImageRequest = { require(allowed) { "PERMISSION_DENIED: source revoked" } })
        AgentRuntime(adapter).run(input).collect { if (it is RuntimeEvent.RequestPrepared) allowed = false }
        assertTrue(adapter.requests.isEmpty())
        assertEquals(RunState.FAILED, input.run.state)
    }

    @Test fun changedBytesAndInputOverflowStopBeforeMainDispatch() = runBlocking {
        val adapter = RecordingAdapter()
        val changed = request().copy(imageLoader = { it.copy(base64 = "aW1hZ2U=", sha256 = "changed") })
        AgentRuntime(adapter).run(changed).toList()
        assertEquals(RunState.FAILED, changed.run.state)
        val overflow = request().copy(maxInputBudgetUnits = 1)
        AgentRuntime(adapter).run(overflow).toList()
        assertEquals(RunState.BUDGET_EXHAUSTED, overflow.run.state)
        assertTrue(adapter.requests.isEmpty())
    }

    @Test fun directImageAndTotalLimitsRejectBeforeLoadingOrAnalysis() = runBlocking {
        val refs = listOf(original, original.copy(assetId = "upload-2"))
        for ((total, group) in listOf(64 to 1, 1 to 8)) {
            val delivery = VisualBatchDelivery(total, group, true, "goal", refs.mapNotNull { it.assetId }.toSet())
            try {
                delivery.prepare(ModelRequest("fixture", listOf(ChatMessage("user", images = refs))),
                    { error("must not load") }) { _, _, _ -> error("must not analyze") }
                fail<Unit>("expected image budget failure")
            } catch (_: VisualDeliveryBudgetExceeded) { }
        }
    }

    @Test fun lazyOriginalSurvivesHistoryCompactionInTheAnswerRequest() = runBlocking {
        val history = (1..6).flatMap { listOf(ChatMessage("user", "question $it"), ChatMessage("assistant", "answer $it")) }
        val sources = history.indices.map { ContextSource("history-$it", "turn-${it / 2}") }
        val adapter = RecordingAdapter { req ->
            if (req.messages.any { it.text.contains("Summarize the supplied conversation data") }) {
                assertTrue(req.messages.all { it.images.isEmpty() })
                emit(ModelEvent.TextDelta("{\"goals\":[\"question\"],\"constraints\":[],\"decisions\":[],\"pending\":[],\"results\":[\"history\"]}"))
            } else {
                assertEquals("aW1hZ2U=", req.messages.flatMap { it.images }.single().base64)
                emit(ModelEvent.TextDelta("answer"))
            }
            emit(ModelEvent.Completed)
        }
        val input = request().let { it.copy(prompt = it.prompt.copy(typedHistory = history), maxInputBudgetUnits = 64_000,
            context = RuntimeContext(AgentContextPolicy(modelAwareCompaction = false, maxHistoryMessages = 4, keepRecentTurns = 1),
                sources, "current", "model", "authorization")) }
        AgentRuntime(adapter).run(input).toList()
        assertEquals(RunState.COMPLETED, input.run.state)
        assertTrue(input.run.compactionRequests > 0)
        assertEquals("aW1hZ2U=", adapter.requests.last().messages.flatMap { it.images }.single().base64)
    }

    private class RecordingAdapter(
        val reply: suspend RecordingAdapter.(ModelRequest) -> Unit = { emit(ModelEvent.TextDelta("notes")); emit(ModelEvent.Completed) },
    ) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        private lateinit var collector: FlowCollector<ModelEvent>
        suspend fun emit(event: ModelEvent) = collector.emit(event)
        override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            requests += request; collector = this
            reply(request)
        }
    }
}
