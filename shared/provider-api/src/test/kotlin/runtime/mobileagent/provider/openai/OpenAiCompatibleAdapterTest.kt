// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class OpenAiSseTest {
    @Test
    fun textDeltaAndDone() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val delta = OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            buf,
        )
        val done = OpenAiSse.eventsFromLine("data: [DONE]", buf)
        assertEquals(listOf(ModelEvent.TextDelta("Hello")), delta)
        assertEquals(listOf(ModelEvent.Completed), done)
    }

    @Test
    fun interleavedToolIndexesDoNotCrossAppend() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val indexToId = mutableMapOf<Int, String>()
        OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-a","function":{"name":"a","arguments":"{\"x\":"}}]}}]}""",
            buf,
            indexToId = indexToId,
        )
        OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call-b","function":{"name":"b","arguments":"{\"y\":1}"}}]}}]}""",
            buf,
            indexToId = indexToId,
        )
        val lastA = OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""",
            buf,
            indexToId = indexToId,
        ).filterIsInstance<ModelEvent.ToolCallDelta>().last()
        assertEquals("call-a", lastA.callId)
        assertEquals("""{"x":1}""", lastA.argumentsJson)
        assertEquals("""{"y":1}""", buf.getValue("call-b").second.toString())
    }

    @Test
    fun commentsAreIgnored() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        assertTrue(OpenAiSse.eventsFromLine(": keep-alive", buf).isEmpty())
    }

    @Test
    fun errorMessageIsRedactedWithProvidedSecret() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val secret = "synthetic-provider-token-12345"
        val events = OpenAiSse.eventsFromLine(
            """data: {"error":{"message":"Invalid credential: $secret"}}""",
            buf,
            listOf(secret),
        )
        val failed = events.single() as ModelEvent.Failed
        assertFalse(failed.sanitizedMessage.contains(secret))
        assertTrue(failed.sanitizedMessage.contains("***"))
    }
}

class OpenAiCompatibleAdapterTest {
    @Test
    fun streamsMockedSseWithoutLeakingSecret() = runTest {
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            assertTrue(auth!!.startsWith("Bearer "))
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "test-secret-token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.TextDelta("Hi"), ModelEvent.Completed), events)
    }

    @Test
    fun unauthorizedIsMapped() = runTest {
        val engine = MockEngine {
            respond("nope", HttpStatusCode.Unauthorized)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "sk-testsecretvalue".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed("PROVIDER_UNAUTHORIZED")), events)
    }

    @Test
    fun incompleteStreamIsNotCompleted() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "test-secret-token".toCharArray(),
        ).toList()
        assertEquals(ModelEvent.TextDelta("partial"), events.first())
        assertEquals(ModelEvent.Failed("UNKNOWN_OUTCOME"), events.last())
        assertTrue(events.none { it is ModelEvent.Completed })
    }

    @Test
    fun errorFrameIsNotFollowedByCompleted() = runTest {
        val secret = "fixture-secret-token"
        val engine = MockEngine {
            respond(
                content = "data: {\"error\":{\"message\":\"fixture rejected $secret\"}}\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            secret.toCharArray(),
        ).toList()
        assertEquals(1, events.size)
        val failed = events.single() as ModelEvent.Failed
        assertFalse(failed.sanitizedMessage.contains(secret))
        assertTrue(events.none { it is ModelEvent.Completed })
    }

    @Test
    fun emptyHttp200IsUnknownOutcome() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "test-secret-token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed("UNKNOWN_OUTCOME")), events)
    }

    @Test
    fun requestBodyEncodesAssistantToolCallsAndImages() = runTest {
        var captured = ""
        val engine = MockEngine { request ->
            captured = when (val body = request.body) {
                is io.ktor.http.content.TextContent -> body.text
                else -> body.toString()
            }
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(
                    ChatMessage(role = "assistant", toolCalls = listOf(
                        runtime.mobileagent.provider.AssistantToolCall("t1", "calculator", """{"expression":"1"}"""),
                    )),
                    ChatMessage(role = "tool", text = "ok", toolCallId = "t1"),
                    ChatMessage(
                        role = "user",
                        text = "see",
                        images = listOf(runtime.mobileagent.provider.InlineImage("image/png", "QQ==", "a1")),
                    ),
                ),
            ),
            "test-secret-token".toCharArray(),
        ).toList()
        assertTrue(captured.contains("\"tool_calls\""))
        assertTrue(captured.contains("\"tool_call_id\":\"t1\""))
        assertTrue(captured.contains("image_url"))
        assertTrue(captured.contains("data:image/png;base64,QQ=="))
    }
}
