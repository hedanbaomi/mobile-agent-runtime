// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.provider.openai.OpenAiAdapterFactory
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/** Proves both OpenAI wire protocols enter the same runtime-owned workspace tool loop. */
class OpenAiWorkspaceToolLoopTest {
    @Test
    fun visualSearchAndWorkspaceResultsStayContiguousOnBothProtocols() = runBlocking {
        listOf(4 to null, 7 to null, 64 to null, 65 to null, 7 to 4).forEach { (imageCount, explicitBudget) ->
          ApiFormat.entries.forEach { format ->
            val bodies = mutableListOf<JsonObject>()
            val engine = MockEngine { request ->
                bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                if (bodies.size > 1) {
                    val input = bodies.last()[if (format == ApiFormat.OPENAI_COMPATIBLE) "messages" else "input"]!!.jsonArray
                    val callsAt = input.indexOfFirst { it.jsonObject["tool_calls"] != null || it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
                    val toolResults = input.withIndex().filter { (_, value) -> value.jsonObject["role"]?.jsonPrimitive?.content == "tool" || value.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }
                    assertEquals(2, toolResults.size)
                    val resultIds = toolResults.map { (_, value) -> value.jsonObject[
                        if (format == ApiFormat.OPENAI_COMPATIBLE) "tool_call_id" else "call_id"
                    ]?.jsonPrimitive?.content }
                    assertEquals(listOf("search-call", "workspace-call"), resultIds)
                    val declaredIds = if (format == ApiFormat.OPENAI_COMPATIBLE) {
                        input[callsAt].jsonObject.getValue("tool_calls").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                    } else input.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
                        .map { it.jsonObject.getValue("call_id").jsonPrimitive.content }
                    assertEquals(declaredIds, resultIds)
                    val imagesAt = input.indexOfFirst { value ->
                        (value.jsonObject["content"] as? JsonArray)?.any { part ->
                            part.jsonObject["type"]?.jsonPrimitive?.content in setOf("image_url", "input_image")
                        } == true
                    }
                    assertTrue(imagesAt > toolResults.last().index, "Images must follow ALL tool results: $input")
                    assertTrue(toolResults.first().index > callsAt)
                    val images = input.flatMap { (it.jsonObject["content"] as? JsonArray).orEmpty() }
                        .filter { it.jsonObject["type"]?.jsonPrimitive?.content in setOf("image_url", "input_image") }
                    assertEquals(imageCount, images.size, "$format must transmit every original")
                }
                val response = if (bodies.size == 1) {
                    if (format == ApiFormat.OPENAI_COMPATIBLE) {
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"search-call\",\"type\":\"function\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"workspace-call\",\"type\":\"function\",\"function\":{\"name\":\"workspace_list\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n"
                    } else {
                        listOf("search-call" to "search", "workspace-call" to "workspace_list").joinToString("") { (id, name) ->
                            "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_$id\",\"call_id\":\"$id\",\"name\":\"$name\",\"arguments\":\"\"}}\n\n" +
                                "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_$id\",\"call_id\":\"$id\",\"name\":\"$name\",\"arguments\":\"{}\"}\n\n"
                        } + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n"
                    }
                } else if (format == ApiFormat.OPENAI_COMPATIBLE) {
                    "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n"
                } else "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"
                respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
            }
            val executor = object : ToolExecutor {
                override val specs = listOf("search", "workspace_list").map { ToolSpec(it, it, "{\"type\":\"object\"}", "", false) }
                override suspend fun invoke(call: ToolCall) = ToolResult.Value("{}")
                override suspend fun approve(callId: String): ToolResult = error("unused")
            }
            val run = AgentRun("visual-${format.name}", "snapshot", "conversation")
            val baseRequest = AgentRuntimeRequest(run, EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "search"), "model", "test-token".toCharArray(), true,
                    executor = executor, toolImages = { call, _ -> if (call.name == "search") (1..imageCount).map { InlineImage("image/png", "aW1hZ2U=", "asset-$it") } else emptyList() })
            val request = explicitBudget?.let { baseRequest.copy(maxImagesPerRequest = it) } ?: baseRequest
            val events = AgentRuntime(OpenAiAdapterFactory.create(format, HttpClient(engine), "https://example.invalid/v1"))
                .run(request).toList()
            val fits = imageCount <= request.maxImagesPerRequest
            assertEquals(if (fits) RunState.COMPLETED else RunState.BUDGET_EXHAUSTED, run.state, "$format: $events")
            assertEquals(if (fits) 2 else 1, bodies.size, "Over-budget originals must never reach HTTP")
            if (imageCount > request.maxImagesPerRun) {
                assertTrue(events.none { it is RuntimeEvent.ToolImagesAttached })
                return@forEach
            }
            val attached = events.filterIsInstance<RuntimeEvent.ToolImagesAttached>().single()
            assertEquals("search-call", attached.callId)
            assertEquals((1..imageCount).map { "asset-$it" }, attached.assets.map { it.assetId })
            val lastResult = events.indexOfLast { it is RuntimeEvent.ToolResultProduced }
            assertTrue(events.indexOfFirst { it is RuntimeEvent.ToolImagesAttached } > lastResult, "Durable transcript must use the same ordering")
          }
        }
    }

    @Test fun sixtyFourOriginalsUseEightSerialWireRequestsOnBothProtocols() = runBlocking {
        ApiFormat.entries.forEach { format ->
            val payloads = mutableListOf<JsonObject>()
            val engine = MockEngine { request ->
                payloads += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val response = if (format == ApiFormat.OPENAI_COMPATIBLE) {
                    "data: {\"choices\":[{\"delta\":{\"content\":\"notes\"}}]}\n\ndata: [DONE]\n\n"
                } else "data: {\"type\":\"response.output_text.delta\",\"delta\":\"notes\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"
                respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
            }
            val originals = (1..64).map { InlineImage("image/png",
                java.util.Base64.getEncoder().encodeToString(("original-" + it).toByteArray()), "asset-" + it) }
            val run = AgentRun("batch", "s", "c", budget = RunBudget(maxModelRounds = 32))
            val events = AgentRuntime(OpenAiAdapterFactory.create(format, HttpClient(engine), "https://example.invalid/v1"))
                .run(AgentRuntimeRequest(run,
                    EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = originals),
                    "fixture", "synthetic-only".toCharArray(), false, batchAllImages = true)).toList()
            assertEquals(RunState.COMPLETED, run.state, events.toString())
            assertEquals(9, payloads.size)
            val wireImages = payloads.map { payload ->
                payload.getValue(if (format == ApiFormat.OPENAI_COMPATIBLE) "messages" else "input").jsonArray
                    .flatMap { (it.jsonObject["content"] as? JsonArray).orEmpty() }
                    .mapNotNull { part ->
                        if (format == ApiFormat.OPENAI_COMPATIBLE)
                            part.jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                        else if (part.jsonObject["type"]?.jsonPrimitive?.content == "input_image")
                            part.jsonObject["image_url"]?.jsonPrimitive?.content else null
                    }
            }
            assertEquals(List(8) { 8 } + listOf(0), wireImages.map { it.size })
            assertEquals(originals.map { "data:image/png;base64," + it.base64 }, wireImages.flatten())
            assertTrue(payloads.last().toString().contains("asset-64"))
            assertEquals(8, events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
            assertEquals(9, run.modelRounds)
        }
    }

    @Test
    fun compatibleReplaysDeclaredReasoningWithToolCalls() = runBlocking {
        var rounds = 0
        val engine = MockEngine { request ->
            rounds++
            if (rounds == 2) {
                val messages = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["messages"]!!.jsonArray
                val assistant = messages.single { it.jsonObject["tool_calls"] != null }.jsonObject
                assertEquals("provider reasoning", assistant["reasoning_content"]?.jsonPrimitive?.content)
                assertEquals(JsonNull, assistant["content"], "Reasoning must stay separate from answer text")
            }
            val response = if (rounds == 1) {
                listOf("provider", " ", "reasoning").joinToString("") { piece ->
                    "data: " + buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("reasoning_content", piece) } }) } } + "\n\n"
                } +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"workspace-call\",\"function\":{\"name\":\"workspace_list\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n"
            } else "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n"
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
        }
        val run = AgentRun("reasoning-compatible", "snapshot", "conversation")
        AgentRuntime(OpenAiAdapterFactory.create(ApiFormat.OPENAI_COMPATIBLE, HttpClient(engine), "https://example.invalid/v1")).run(
            AgentRuntimeRequest(run, EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"), "model", "test-token".toCharArray(), true, executor = RecordingWorkspaceExecutor()),
        ).toList()
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals(2, rounds)
    }

    @Test
    fun compatibleAndResponsesBothContinueAfterWorkspaceMetadataListing() = runBlocking {
        ApiFormat.entries.forEach { format ->
            val bodies = mutableListOf<String>()
            val paths = mutableListOf<String>()
            val engine = MockEngine { request ->
                paths += request.url.encodedPath
                bodies += (request.body as? TextContent)?.text ?: "<${request.body.javaClass.name}>"
                val round = bodies.size
                val response = when (format) {
                    ApiFormat.OPENAI_COMPATIBLE -> if (round == 1) {
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"workspace-call\",\"type\":\"function\",\"function\":{\"name\":\"workspace_list\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n"
                    } else {
                        "data: {\"choices\":[{\"delta\":{\"content\":\"workspace complete\"}}]}\n\ndata: [DONE]\n\n"
                    }
                    ApiFormat.OPENAI_RESPONSES -> if (round == 1) {
                        "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"workspace-call\",\"name\":\"workspace_list\",\"arguments\":\"\"}}\n\n" +
                            "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"call_id\":\"workspace-call\",\"name\":\"workspace_list\",\"arguments\":\"{}\"}\n\n" +
                            "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                    } else {
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"workspace complete\"}\n\n" +
                            "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                    }
                }
                respond(
                    response,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            }
            val executor = RecordingWorkspaceExecutor()
            val adapter = OpenAiAdapterFactory.create(format, HttpClient(engine), "https://example.invalid/v1")
            val run = AgentRun("run-${format.name}", "session", "conversation")

            val events = AgentRuntime(adapter).run(
                AgentRuntimeRequest(
                    run = run,
                    prompt = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "list workspace"),
                    modelId = "model",
                    secret = "test-token".toCharArray(),
                    toolsEnabled = true,
                    executor = executor,
                ),
            ).toList()

            assertEquals(RunState.COMPLETED, run.state, "${format.name}: $events; paths=$paths; bodies=$bodies")
            assertEquals(1, executor.invocations, format.name)
            assertEquals(2, bodies.size, format.name)
            assertEquals(
                "workspace complete",
                events.filterIsInstance<RuntimeEvent.ModelEvent>()
                    .mapNotNull { it.event as? ModelEvent.TextDelta }
                    .joinToString("") { it.text },
            )
            when (format) {
                ApiFormat.OPENAI_COMPATIBLE -> {
                    assertEquals(listOf("/v1/chat/completions", "/v1/chat/completions"), paths)
                    assertTrue(bodies.last().contains("\"tool_call_id\":\"workspace-call\""))
                }
                ApiFormat.OPENAI_RESPONSES -> {
                    assertEquals(listOf("/v1/responses", "/v1/responses"), paths)
                    assertTrue(bodies.last().contains("function_call_output"))
                    assertTrue(bodies.last().contains("\"call_id\":\"workspace-call\""))
                }
            }
            assertTrue(bodies.last().contains("large.bin"), format.name)
        }
    }

    @Test
    fun responsesReasoningContinuationSurvivesTheToolLoopIntoRoundTwo() = runBlocking {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as? TextContent)?.text ?: "<${request.body.javaClass.name}>"
            val response = if (bodies.size == 1) {
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_loop\",\"encrypted_content\":\"loop-secret\"}}\n\n" +
                    "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"workspace-call\",\"name\":\"workspace_list\",\"arguments\":\"\"}}\n\n" +
                    "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"call_id\":\"workspace-call\",\"name\":\"workspace_list\",\"arguments\":\"{}\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
            } else {
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"workspace complete\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
            }
            respond(
                response,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val executor = RecordingWorkspaceExecutor()
        val adapter = OpenAiAdapterFactory.create(ApiFormat.OPENAI_RESPONSES, HttpClient(engine), "https://example.invalid/v1")
        val run = AgentRun("run-responses-continuation", "session", "conversation")

        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                run = run,
                prompt = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "list workspace"),
                modelId = "model",
                secret = "test-token".toCharArray(),
                toolsEnabled = true,
                executor = executor,
            ),
        ).toList()

        assertEquals(RunState.COMPLETED, run.state, "$events; bodies=$bodies")
        assertEquals(2, bodies.size)
        val roundTwo = bodies.last()
        // The encrypted reasoning item is replayed verbatim next to the tool result...
        assertTrue(roundTwo.contains("\"type\":\"reasoning\""))
        assertTrue(roundTwo.contains("\"encrypted_content\":\"loop-secret\""))
        assertTrue(roundTwo.contains("function_call_output"))
        assertTrue(roundTwo.contains("\"call_id\":\"workspace-call\""))
        // ...while the provider-private payload never leaks into visible text.
        val visibleText = events.filterIsInstance<RuntimeEvent.ModelEvent>()
            .mapNotNull { it.event as? ModelEvent.TextDelta }
            .joinToString("") { it.text }
        assertEquals("workspace complete", visibleText)
        assertTrue(events.filterIsInstance<RuntimeEvent.ModelEvent>().none { it.event is ModelEvent.ProviderContinuation })
    }

    private class RecordingWorkspaceExecutor : ToolExecutor {
        override val specs = listOf(
            ToolSpec(
                name = "workspace_list",
                description = "List authorized workspace metadata",
                parametersJson = "{\"type\":\"object\",\"additionalProperties\":false}",
                capability = "workspace.enumerate",
                sideEffect = false,
            ),
        )
        var invocations: Int = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            invocations += 1
            assertEquals("workspace_list", call.name)
            return ToolResult.Value("{\"entries\":[{\"path\":\"large.bin\",\"type\":\"file\",\"bytes\":1234567890}]}")
        }

        override suspend fun approve(callId: String): ToolResult = error("approval is not expected")
    }
}
