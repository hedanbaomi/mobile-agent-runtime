// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.DiffPart
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode

class RuntimeEventsTest {
    @Test
    fun providerReasoningIsForwardedOnlyAsItsOwnRuntimeEvent() = runBlocking {
        val adapter = RecordingAdapter(
            listOf(listOf(ModelEvent.ReasoningDelta("provider thinking"), ModelEvent.TextDelta("answer"), ModelEvent.Completed)),
        )
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                AgentRun("reasoning-run", "s", "c"),
                EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                "model",
                charArrayOf(),
                toolsEnabled = false,
            ),
        ).toList()
        val modelEvents = events.filterIsInstance<RuntimeEvent.ModelEvent>().map { it.event }
        assertTrue(modelEvents.contains(ModelEvent.ReasoningDelta("provider thinking")))
        assertTrue(modelEvents.contains(ModelEvent.TextDelta("answer")))
    }

    @Test
    fun messagePartProjectionIsClosedAndDiffRequiresExplicitEnvelope() {
        val error = ModelEvent.Failed("PROVIDER_UNAUTHORIZED: response body must not be persisted")
            .toMessagePartOrNull() as ErrorPart
        assertEquals(MessageErrorCode.PROVIDER_UNAUTHORIZED, error.code)
        assertEquals("服务商拒绝了请求，请检查授权。", error.message)
        assertTrue(!error.message.contains("response"))

        val explicitDiff = RuntimeEvent.ToolResultProduced(
            callId = "call",
            name = "file.apply_patch",
            status = "VALUE",
            resultSummary = "changed",
            resultJson = "{\"type\":\"diff\",\"summary\":\"updated Main.kt\",\"patch_preview\":\"@@ -1 +1 @@\",\"changed_files\":1}",
        ).toDiffPartOrNull()
        assertEquals(DiffPart("updated Main.kt", "@@ -1 +1 @@", 1), explicitDiff)

        val ordinary = RuntimeEvent.ToolResultProduced("call", "file.write", "VALUE", "diff --git a/a b/a")
        assertNull(ordinary.toDiffPartOrNull())
        val unsafe = RuntimeEvent.ToolResultProduced(
            "call", "file.apply_patch", "VALUE", "diff",
            "{\"kind\":\"diff\",\"summary\":\"updated\",\"patch\":\"C:\\\\private\\\\Main.kt\"}",
        )
        assertNull(unsafe.toDiffPartOrNull())
    }

    @Test
    fun structuredRunUsesGenericExecutorAndOptInPreview() = runBlocking {
        val adapter = RecordingAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("call-1", "external", "{\"x\":1}"), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val executor = FakeExecutor()
        val run = AgentRun("run-1", "snapshot-1", "conversation-1")
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                run = run,
                prompt = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                modelId = "model-1",
                secret = "secret".toCharArray(),
                toolsEnabled = true,
                parameters = ParameterLayers(agentOverrides = mapOf("temperature" to JsonPrimitive(0.4))),
                operationId = "op-1",
                emitRequestPreview = true,
                executor = executor,
            ),
        ).toList()

        val prepared = events.filterIsInstance<RuntimeEvent.RequestPrepared>().first()
        assertEquals("op-1", prepared.operationId)
        assertEquals(listOf("temperature"), prepared.parameterKeys)
        assertEquals("preview:model-1", prepared.requestPreview)
        assertTrue(prepared.toolNames.contains("external"))
        assertTrue(events.any { it is RuntimeEvent.ToolCallObserved })
        assertTrue(events.any { it is RuntimeEvent.ToolResultProduced && it.status == "VALUE" })
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals(1, executor.invocations)
        assertEquals("external", adapter.requests.first().tools.single()["name"])
    }

    @Test
    fun previewIsNullByDefaultAndOldRunStillFiltersStructuredEvents() = runBlocking {
        val adapter = RecordingAdapter(listOf(listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed)))
        val runtime = AgentRuntime(adapter)
        val structured = runtime.run(
            AgentRuntimeRequest(
                AgentRun("run-2", "s", "c"),
                EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                "model",
                "secret".toCharArray(),
                toolsEnabled = false,
            ),
        ).toList()
        assertNull(structured.filterIsInstance<RuntimeEvent.RequestPrepared>().single().requestPreview)

        val legacyAdapter = RecordingAdapter(listOf(listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed)))
        val legacyRuntime = AgentRuntime(legacyAdapter)
        val legacy = legacyRuntime.run(
            AgentRun("run-3", "s", "c"),
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
            "model",
            "secret".toCharArray(),
            toolsEnabled = false,
        ).toList()
        assertTrue(legacy.any { it is ModelEvent.TextDelta && it.text == "ok" })
        assertTrue(legacy.last() == ModelEvent.Completed)
    }

    @Test
    fun typedHistoryPreservesToolAndImageFields() {
        val typed = ChatMessage(
            role = "assistant",
            text = "",
            toolCalls = listOf(runtime.mobileagent.provider.AssistantToolCall("t", "external", "{}")),
        )
        val prompt = EffectivePrompt(
            runtimeContract = "contract",
            userSystemPrompt = "",
            skillInstructions = emptyList(),
            retrieved = emptyList(),
            history = listOf("assistant" to "flattened"),
            currentUser = "question",
            typedHistory = listOf(typed),
        )
        val historyMessage = prompt.asMessages().first { it.role == "assistant" }
        assertEquals("t", historyMessage.toolCalls.single().id)
        assertEquals("", historyMessage.text)
    }

    @Test
    fun toolResultKeepsCompleteRedactedJsonAndReturnsImagesAsUserMessage() = runBlocking {
        val fullResult = "{\"evidence\":\"${"x".repeat(1400)}\"}"
        val adapter = RecordingAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("call-image", "external", "{\"x\":1}"), ModelEvent.Completed),
                listOf(ModelEvent.Completed),
            ),
        )
        val executor = FakeExecutor(fullResult)
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                run = AgentRun("run-image", "s", "c"),
                prompt = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                modelId = "model",
                secret = "secret".toCharArray(),
                toolsEnabled = true,
                executor = executor,
                toolImages = { _, _ ->
                    listOf(InlineImage("image/png", "base64-image", "asset-1"))
                },
            ),
        ).toList()

        val result = events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
        assertEquals(fullResult, result.resultJson)
        assertTrue(result.resultSummary.length < result.resultJson.length)
        val imageMessage = adapter.requests.last().messages.single { it.images.isNotEmpty() }
        assertEquals("user", imageMessage.role)
        assertEquals("asset-1", imageMessage.images.single().assetId)
    }

    @Test
    fun typedToolFailureReachesTheModelAndUiAsAPathFreeActionableEnvelope() = runBlocking {
        val adapter = RecordingAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("call-large", "external", "{}"), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("handled"), ModelEvent.Completed),
            ),
        )
        val executor = object : ToolExecutor {
            override val specs = FakeExecutor().specs
            override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Failure(
                ToolError(ToolErrorCode.FILE_TOO_LARGE, message = "C:\\private\\huge.bin"),
            )
            override suspend fun approve(callId: String): ToolResult = error("unused")
        }

        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                run = AgentRun("run-large", "s", "c"),
                prompt = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                modelId = "model",
                secret = charArrayOf(),
                toolsEnabled = true,
                executor = executor,
            ),
        ).toList()

        val result = events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
        assertEquals("FAILED", result.status)
        assertTrue(result.resultJson.contains("\"code\":\"FILE_TOO_LARGE\""))
        assertTrue(result.resultJson.contains("too large to read as text"))
        assertTrue(!result.resultJson.contains("private"))
        assertEquals("文件太大，无法作为文本读取。", toolResultUserMessage(result.resultJson))
        assertEquals(
            "该工作区条目类型不受支持，未打开该条目。",
            toolResultUserMessage("{\"error\":{\"code\":\"UNSUPPORTED_ENTRY\"}}"),
        )
        assertEquals(
            "所选工作区后端暂不支持该操作。",
            toolResultUserMessage("{\"error\":{\"code\":\"OPERATION_UNAVAILABLE\"}}"),
        )
        val continuation = adapter.requests.last().messages.single { it.toolCallId == "call-large" }
        assertTrue(continuation.text.contains("FILE_TOO_LARGE"))
        assertTrue(!continuation.text.contains("private"))
    }

    @Test
    fun invalidToolArgumentsNeverReachGenericExecutor() = runBlocking {
        val adapter = RecordingAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("bad", "strict", "{\"wrong\":true}"), ModelEvent.Completed),
            ),
        )
        val executor = StrictExecutor()
        val run = AgentRun("run-invalid", "s", "c")
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                run = run,
                prompt = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                modelId = "model",
                secret = charArrayOf(),
                toolsEnabled = true,
                executor = executor,
            ),
        ).toList()

        assertEquals(0, executor.invocations)
        assertTrue(events.any { it is RuntimeEvent.ModelEvent && it.event is ModelEvent.Failed })
        assertEquals(RunState.FAILED, run.state)
    }

    @Test
    fun unknownToolOutcomeStopsBeforeAnotherModelRound() = runBlocking {
        val adapter = RecordingAdapter(listOf(listOf(ModelEvent.ToolCallDelta("uncertain", "external", "{}"), ModelEvent.Completed)))
        val executor = object : ToolExecutor {
            override val specs = FakeExecutor().specs
            override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.UnknownOutcome("connection lost after send")
            override suspend fun approve(callId: String): ToolResult = error("unused")
        }
        val run = AgentRun("unknown-run", "s", "c")
        val events = AgentRuntime(adapter).run(AgentRuntimeRequest(run,
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"), "model", charArrayOf(), true,
            executor = executor)).toList()
        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, adapter.requests.size)
        val unknown = events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
        assertEquals("UNKNOWN_OUTCOME", unknown.status)
        assertTrue(unknown.resultJson.contains("\"automaticReplayAllowed\":false"))
        assertEquals(RunState.UNKNOWN_OUTCOME, events.filterIsInstance<RuntimeEvent.RunFinished>().single().state)
    }

    @Test
    fun unknownModelOutcomeIsNotAnOrdinaryRetryableFailure() = runBlocking {
        val adapter = RecordingAdapter(listOf(listOf(ModelEvent.Failed("UNKNOWN_OUTCOME"))))
        val run = AgentRun("unknown-model", "s", "c")
        AgentRuntime(adapter).run(AgentRuntimeRequest(run,
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"), "model", charArrayOf(), false)).toList()
        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, adapter.requests.size)
    }

    private class FakeExecutor(private val resultJson: String = "{\"ok\":true}") : ToolExecutor {
        override val specs = listOf(ToolSpec("external", "external tool", "{\"type\":\"object\"}", "external", false))
        var invocations = 0
        override suspend fun invoke(call: ToolCall): ToolResult {
            invocations += 1
            return ToolResult.Value(resultJson)
        }

        override suspend fun approve(callId: String): ToolResult = ToolResult.Value("{}")
    }

    private class RecordingAdapter(private val scripts: List<List<ModelEvent>>) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        private var index = 0

        override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): CapabilityReport = error("unused")

        override fun previewRequest(request: ModelRequest): String = "preview:${request.modelId}"

        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            requests += request
            scripts.getOrElse(index++) { listOf(ModelEvent.Completed) }.forEach { emit(it) }
        }

        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
    }

    private class StrictExecutor : ToolExecutor {
        override val specs = listOf(
            ToolSpec(
                "strict",
                "strict tool",
                "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}}",
                "strict",
                false,
            ),
        )
        var invocations = 0
        override suspend fun invoke(call: ToolCall): ToolResult {
            invocations += 1
            return ToolResult.Value("{}")
        }

        override suspend fun approve(callId: String): ToolResult = ToolResult.Value("{}")
    }
}
