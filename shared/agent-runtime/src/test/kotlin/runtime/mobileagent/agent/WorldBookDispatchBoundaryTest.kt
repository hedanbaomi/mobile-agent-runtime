// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentContextPolicy
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.provider.*
import runtime.mobileagent.provider.openai.OpenAiCompatibleAdapter
import runtime.mobileagent.provider.openai.OpenAiResponsesAdapter
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

class WorldBookDispatchBoundaryTest {
    @Test fun rejectedAuthorizationInputAndRoundAdmissionNeverCommitAnsweringEffects() = runBlocking {
        for (denied in listOf("authorization", "input", "round")) {
            var commits = 0
            val adapter = RecordingAdapter()
            val run = AgentRun(denied, "s", "c", budget = RunBudget(maxModelRounds = if (denied == "round") 0 else 8))
            AgentRuntime(adapter).run(AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), false,
                maxInputBudgetUnits = if (denied == "input") 1 else 100_000,
                beforeModelRequest = { check(denied != "authorization") { "PERMISSION_DENIED" } },
                beforeAnsweringDispatch = { commits++ })).toList()
            assertEquals(0, adapter.requests.size, denied)
            assertEquals(0, commits, denied)
        }
    }

    @Test fun deadlineDuringDurablePreviewNeverCommitsAnsweringEffects() = runBlocking {
        var now = 0L
        var commits = 0
        val adapter = RecordingAdapter()
        val run = AgentRun("deadline", "s", "c", budget = RunBudget(maxRuntimeMs = 1000))
        AgentRuntime(adapter, clock = { now }).run(AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), false,
            beforeAnsweringDispatch = { commits++ })).collect { event ->
            if (event is RuntimeEvent.RequestPrepared) now = 2000
        }
        assertEquals(0, adapter.requests.size)
        assertEquals(0, commits)
    }

    @Test fun successfulAnswerCommitsAfterPreviewAndImmediatelyBeforeCollection() = runBlocking {
        val order = mutableListOf<String>()
        val adapter = RecordingAdapter { _, _ -> order += "transport"; listOf(ModelEvent.Completed) }
        AgentRuntime(adapter).run(AgentRuntimeRequest(AgentRun("answer", "s", "c"), prompt(), "model", charArrayOf(), false,
            beforeAnsweringDispatch = { order += "commit" })).collect { event ->
            if (event is RuntimeEvent.RequestPrepared) order += "preview"
        }
        assertEquals(listOf("preview", "commit", "transport"), order)
    }

    @Test fun rejectedCommitNeverStartsTheAnsweringTransport() = runBlocking {
        val adapter = RecordingAdapter()
        val run = AgentRun("revoked-after-preview", "s", "c")
        AgentRuntime(adapter).run(AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), false,
            beforeAnsweringDispatch = { error("PERMISSION_DENIED: world book revoked") })).toList()
        assertEquals(0, adapter.requests.size)
        assertEquals(RunState.FAILED, run.state)
    }

    @Test fun bothRealAdaptersValidateSecretsHeadersAndFinalDeadlineBeforeCommittingEffects() = runBlocking {
        for (responses in listOf(false, true)) for (denied in listOf("secret", "header", "deadline")) {
            var commits = 0
            var httpCalls = 0
            var now = 0L
            val http = HttpClient(MockEngine { httpCalls++; error("must not contact provider") })
            try {
                val adapter = if (responses) OpenAiResponsesAdapter(http, "https://example.invalid/v1")
                    else OpenAiCompatibleAdapter(http, "https://example.invalid/v1")
                val diagnostics = object : ModelDiagnosticSink {
                    override fun record(event: ModelDiagnosticEvent) {
                        if (denied == "deadline" && event.stage == ModelDiagnosticStage.REQUEST_READY) now = 2000
                    }
                }
                val run = AgentRun("$responses-$denied", "s", "c", budget = RunBudget(maxRuntimeMs = 1000))
                AgentRuntime(adapter, clock = { now }).run(AgentRuntimeRequest(run, prompt(), "model",
                    if (denied == "secret") charArrayOf() else "synthetic-token".toCharArray(), false,
                    headers = if (denied == "header") mapOf("X-Fixture" to RequestHeaderValue.Plain("bad\nvalue")) else emptyMap(),
                    diagnostics = diagnostics, beforeAnsweringDispatch = { commits++ })).toList()
                assertEquals(0, httpCalls, "$responses/$denied")
                assertEquals(0, commits, "$responses/$denied")
            } finally { http.close() }
        }
    }

    @Test fun visualEvidenceDispatchDoesNotCommitEffectsAndFinalAnswerDoes() = runBlocking {
        var commits = 0
        val adapter = RecordingAdapter { _, index ->
            assertEquals(if (index == 0) 0 else 1, commits)
            listOf(ModelEvent.TextDelta("visible evidence notes"), ModelEvent.Completed)
        }
        val image = InlineImage("image/png", "aW1hZ2U=", "asset", 5, "hash")
        AgentRuntime(adapter).run(AgentRuntimeRequest(AgentRun("visual", "s", "c"), prompt().copy(currentImages = listOf(image)),
            "model", charArrayOf(), false, batchAllImages = true,
            beforeAnsweringDispatch = { commits++ })).toList()
        assertEquals(2, adapter.requests.size)
        assertEquals(1, commits)
    }

    @Test fun summaryOnlyRunDoesNotCommitAnsweringEffects() = runBlocking {
        var commits = 0
        val adapter = RecordingAdapter { request, _ ->
            assertTrue(request.messages.first().text.startsWith("Summarize the supplied conversation data"))
            assertEquals(0, commits)
            listOf(ModelEvent.TextDelta("""{"goals":["goal"],"constraints":["constraint"],"decisions":["decision"],"pending":["pending"],"results":["result"]}"""), ModelEvent.Completed)
        }
        val history = (0..2).flatMap { listOf(ChatMessage("user", "question $it"), ChatMessage("assistant", "answer $it")) }
        val context = RuntimeContext(AgentContextPolicy(modelAwareCompaction = false, maxHistoryMessages = 4, keepRecentTurns = 1),
            history.indices.map { ContextSource("h$it", "turn${it / 2}") }, "current", "model", "auth")
        AgentRuntime(adapter).run(AgentRuntimeRequest(AgentRun("summary", "s", "c"), prompt().copy(typedHistory = history),
            "model", charArrayOf(), false, context = context, maxInputBudgetUnits = 50_000,
            beforeModelRequest = { check(adapter.requests.isEmpty()) { "PERMISSION_DENIED: authorization revoked after summary" } },
            beforeAnsweringDispatch = { commits++ })).toList()
        assertEquals(1, adapter.requests.size)
        assertEquals(0, commits)
    }

    @Test fun toolContinuationRevalidatesDispatchWhileHostPublicationRemainsIdempotent() = runBlocking {
        var dispatchChecks = 0
        var publications = 0
        var published = false
        val adapter = RecordingAdapter { _, index ->
            if (index == 0) listOf(ModelEvent.ToolCallDelta("call", "fixture", "{}"), ModelEvent.Completed)
            else listOf(ModelEvent.Completed)
        }
        val executor = object : ToolExecutor {
            override val specs = listOf(ToolSpec("fixture", "fixture", "{\"type\":\"object\"}", "fixture", false))
            override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Value("{}")
            override suspend fun approve(callId: String): ToolResult = error("unused")
        }
        AgentRuntime(adapter).run(AgentRuntimeRequest(AgentRun("tools", "s", "c"), prompt(), "model", charArrayOf(), true,
            executor = executor, beforeAnsweringDispatch = {
                dispatchChecks++
                if (!published) { published = true; publications++ }
            })).toList()
        assertEquals(2, adapter.requests.size)
        assertEquals(2, dispatchChecks)
        assertEquals(1, publications)
    }

    private fun prompt() = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello")

    private class RecordingAdapter(
        val script: (ModelRequest, Int) -> List<ModelEvent> = { _, _ -> listOf(ModelEvent.Completed) },
    ) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            val allowed = try { request.beforeDispatch() } catch (_: Exception) {
                emit(ModelEvent.Failed("INVALID_CONFIG")); return@flow
            }
            if (!allowed) { emit(ModelEvent.Failed("REQUEST_CANCELLED")); return@flow }
            val index = requests.size
            requests += request
            script(request, index).forEach { emit(it) }
        }
    }
}
