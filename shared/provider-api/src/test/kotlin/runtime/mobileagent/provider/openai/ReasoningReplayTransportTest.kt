// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.provider.*

class ReasoningReplayTransportTest {
    @Test fun whitespaceReasoningChunksArePreservedExactly() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(listOf("alpha", " ", "beta", "\n").joinToString("") { piece ->
                "data: " + buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("reasoning_content", piece) } }) } } + "\n\n"
            } + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val events = OpenAiCompatibleAdapter(client, "https://example.invalid/v1").stream(ModelRequest("model", listOf(ChatMessage("user", "question"))), "test-token".toCharArray()).toList()
        assertEquals("alpha beta\n", events.filterIsInstance<ModelEvent.ReasoningDelta>().joinToString("") { it.text })
        client.close()
    }
    private fun request() = ModelRequest("model", listOf(
        ChatMessage("user", "question"),
        ChatMessage("assistant", "previous answer", reasoningContent = "prior declared reasoning"),
        ChatMessage("assistant", toolCalls = listOf(AssistantToolCall("call", "search", "{}")), reasoningContent = "current declared reasoning"),
        ChatMessage("tool", "{}", toolCallId = "call"),
    ), tools = listOf(mapOf("name" to "search", "parameters" to "{\"type\":\"object\"}")))

    @Test fun replayUsesSeparateWireFieldAndNeverEntersPreviewOrCapturedRequest() = runBlocking {
        var wire = ""
        val events = mutableListOf<ModelDiagnosticEvent>()
        val client = HttpClient(MockEngine { incoming ->
            wire = (incoming.body as TextContent).text
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val adapter = OpenAiCompatibleAdapter(client, "https://example.invalid/v1")
        val request = request().copy(diagnostics = object : ModelDiagnosticSink {
            override val captureContent = true
            override fun record(event: ModelDiagnosticEvent) { events += event }
        })
        adapter.stream(request, "test-token".toCharArray()).toList()
        val assistants = Json.parseToJsonElement(wire).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals(listOf("prior declared reasoning", "current declared reasoning"), assistants.map { it["reasoning_content"]!!.jsonPrimitive.content })
        assertEquals("previous answer", assistants.first()["content"]!!.jsonPrimitive.content)
        assertFalse(adapter.previewRequest(request).contains("declared reasoning"))
        assertFalse(events.joinToString().contains("declared reasoning"))
        assertFalse(adapter.estimateInput(request).toString().contains("declared reasoning"))
        client.close()
    }

    @Test fun onlyChatToolRequestsCountOrSendObservedReasoning() = runBlocking {
        ApiFormat.entries.forEach { format ->
            var wire = ""
            val client = HttpClient(MockEngine { incoming ->
                wire = (incoming.body as TextContent).text
                respond(if (format == ApiFormat.OPENAI_COMPATIBLE) "data: [DONE]\n\n" else "data: {\"type\":\"response.completed\",\"response\":{}}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            })
            val adapter = OpenAiAdapterFactory.create(format, client, "https://example.invalid/v1")
            val full = request()
            val dropped = full.copy(messages = full.messages.map { it.copy(reasoningContent = null) })
            val delta = adapter.estimateInput(full).units - adapter.estimateInput(dropped).units
            adapter.stream(full, "test-token".toCharArray()).toList()
            if (format == ApiFormat.OPENAI_COMPATIBLE) {
                assertTrue(delta >= "prior declared reasoningcurrent declared reasoning".length)
                assertTrue(wire.contains("reasoning_content"))
                adapter.stream(dropped, "test-token".toCharArray()).toList()
                assertFalse(wire.contains("reasoning_content"), "Never infer from answer text")
                adapter.stream(full.copy(tools = emptyList()), "test-token".toCharArray()).toList()
                assertFalse(wire.contains("reasoning_content"), "No tool replay needed for summary/plain chat")
            } else {
                assertEquals(0L, delta)
                assertFalse(wire.contains("declared reasoning"))
                assertFalse(wire.contains("reasoning_content"))
            }
            client.close()
        }
    }
}
