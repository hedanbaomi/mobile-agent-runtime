// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.*

/** Synthetic gateway contract fixtures; no user documents, credentials or paid calls. */
class GatewayKnowledgeContinuationTest {
    private fun request() = ModelRequest(
        "deepseek/deepseekv4.1flash",
        listOf(
            ChatMessage("user", "Compare the cited documents and page figures."),
            ChatMessage("assistant", toolCalls = listOf(AssistantToolCall("search", "knowledge_search", "{}")), reasoningContent = "Search relevant sources."),
            ChatMessage("tool", buildJsonObject { put("hits", JsonArray((1..10).map { buildJsonObject {
                put("text", "Synthetic source passage $it."); put("citationId", "synthetic-citation-$it")
            } })) }.toString(), toolCallId = "search"),
            ChatMessage("user", "Tool visual evidence: search", images = listOf(InlineImage("image/png", "AQID"))),
        ),
        tools = listOf(mapOf("name" to "knowledge_search", "description" to "Search", "parameters" to "{}")),
    )

    private suspend fun invoke(body: String, sse: Boolean): List<ModelEvent> {
        val http = HttpClient(MockEngine { outgoing ->
            val payload = Json.parseToJsonElement((outgoing.body as TextContent).text).jsonObject
            assertEquals("deepseek/deepseekv4.1flash", payload["model"]!!.jsonPrimitive.content)
            val messages = payload["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", "assistant", "tool", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            assertEquals("Search relevant sources.", messages[1]["reasoning_content"]!!.jsonPrimitive.content)
            val hits = Json.parseToJsonElement(messages[2]["content"]!!.jsonPrimitive.content).jsonObject["hits"]!!.jsonArray
            assertEquals(10, hits.size)
            assertEquals("synthetic-citation-10", hits.last().jsonObject["citationId"]!!.jsonPrimitive.content)
            assertTrue(messages[3]["content"]!!.jsonArray.any { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" })
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, if (sse) "text/event-stream" else "application/json"))
        })
        return try { OpenAiCompatibleAdapter(http, "https://gateway.invalid/provider/v1").stream(request(), "synthetic-key".toCharArray()).toList() }
        finally { http.close() }
    }

    @Test fun citedKnowledgeAndImageContinuationAcceptsJsonAndFinalUsageSse() = runTest {
        val json = """{"choices":[{"message":{"content":"Source comparison [synthetic-citation-1]."},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":10}}"""
        val sse = """data: {"choices":[{"delta":{"reasoning_content":"Check cited evidence."}}]}

data: {"choices":[{"delta":{"content":"Source comparison [synthetic-citation-1]."},"finish_reason":"stop"}]}

data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10}}

data: [DONE]

"""
        for ((body, streaming) in listOf(json to false, sse to true)) {
            val events = invoke(body, streaming)
            assertEquals(ModelEvent.Completed, events.last())
            assertEquals("Source comparison [synthetic-citation-1].", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(events.none { it is ModelEvent.Failed })
            assertEquals(100, events.filterIsInstance<ModelEvent.Usage>().single().inputTokens)
        }
    }

    @Test fun continuedToolCallCompletesAfterFinalUsageChunk() = runTest {
        val events = invoke("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"next-search","function":{"name":"knowledge_search","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10}}

data: [DONE]

""", true)
        assertEquals(ModelEvent.ToolCallDelta("next-search", "knowledge_search", "{}"), events.filterIsInstance<ModelEvent.ToolCallDelta>().single())
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test fun incompleteTransportAndEmptyCompleteResultReproduceInvalidResponse() = runTest {
        val incomplete = """data: {"choices":[{"delta":{"content":"Partial comparison"}}]}

"""
        for ((body, streaming) in listOf(incomplete to true, """{"choices":[{"message":{"content":null},"finish_reason":"stop"}]}""" to false)) {
            val events = invoke(body, streaming)
            assertEquals(ModelEvent.Failed("INVALID_RESPONSE"), events.last())
            assertTrue(events.none { it == ModelEvent.Completed })
        }
    }
}
