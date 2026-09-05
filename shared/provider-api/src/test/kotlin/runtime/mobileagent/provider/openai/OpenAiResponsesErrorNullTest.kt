// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest

/**
 * Real protocol-shape fixtures for the non-streaming Responses parser.
 *
 * The parser must only enter the failure path when `error` is a non-null
 * object. A legitimate success response may carry an explicit `"error":null`
 * member ([kotlinx.serialization.json.JsonNull], not a missing key); treating
 * any present key as failure misclassifies completed responses as
 * UNKNOWN_OUTCOME and breaks Test Connection / non-stream probes.
 */
class OpenAiResponsesErrorNullTest {
    private fun profile() = ModelProfile(
        id = "model.responses",
        providerId = "provider.responses",
        role = ModelRole.CHAT,
        modelId = "gpt-responses",
        capabilities = setOf("stream", "tools", "image"),
        contextLimit = 4_096,
        outputLimit = 256,
        revision = 1,
    )

    private fun jsonEngine(body: String) = MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }

    private suspend fun nonStreaming(body: String): List<ModelEvent> =
        OpenAiResponsesAdapter(HttpClient(jsonEngine(body)), "https://example.invalid/v1").stream(
            ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi")), stream = false),
            "token".toCharArray(),
        ).toList()

    @Test
    fun completedWithExplicitErrorNullIsSuccess() = runTest {
        val events = nonStreaming(
            "{\"status\":\"completed\",\"error\":null,\"output\":[" +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}",
        )
        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "ok" }, events.toString())
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun completedWithoutErrorKeyIsSuccess() = runTest {
        val events = nonStreaming(
            "{\"status\":\"completed\",\"output\":[" +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}",
        )
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun errorObjectIsFailure() = runTest {
        val events = nonStreaming("{\"status\":\"failed\",\"error\":{\"message\":\"rejected\"}}")
        val failed = events.filterIsInstance<ModelEvent.Failed>().single()
        assertEquals("rejected", failed.sanitizedMessage)
    }

    @Test
    fun incompleteStatusIsTypedFailure() = runTest {
        val events = nonStreaming("{\"status\":\"incomplete\",\"error\":null,\"output\":[]}")
        val failed = events.filterIsInstance<ModelEvent.Failed>().single()
        assertEquals(ErrorCode.UNKNOWN_OUTCOME.name, failed.sanitizedMessage)
    }

    @Test
    fun failedStatusIsTypedFailure() = runTest {
        val events = nonStreaming("{\"status\":\"failed\",\"error\":null,\"output\":[]}")
        val failed = events.filterIsInstance<ModelEvent.Failed>().single()
        assertEquals(ErrorCode.UNKNOWN_OUTCOME.name, failed.sanitizedMessage)
    }

    @Test
    fun refusalOutputIsReadableOutputNotFailure() = runTest {
        val events = nonStreaming(
            "{\"status\":\"completed\",\"error\":null,\"output\":[" +
                "{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no.\"}]}]}",
        )
        assertTrue(events.any { it is ModelEvent.RefusalDelta && it.text == "no." }, events.toString())
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun multipleOutputItemsAllSurface() = runTest {
        val events = nonStreaming(
            "{\"status\":\"completed\",\"error\":null,\"output\":[" +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"first\"}]}," +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"second\"}]}" +
                "],\"unknown_future_field\":{\"nested\":[1,2,3]}}",
        )
        val texts = events.filterIsInstance<ModelEvent.TextDelta>().map { it.text }
        assertEquals(listOf("first", "second"), texts)
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun streamingAndNonStreamingCarryEquivalentSemantics() = runTest {
        val nonStreamed = nonStreaming(
            "{\"status\":\"completed\",\"error\":null,\"output\":[" +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"same\"}]}]}",
        )
        val streamEngine = MockEngine {
            respond(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"same\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val streamed = OpenAiResponsesAdapter(HttpClient(streamEngine), "https://example.invalid/v1").stream(
            ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi")), stream = true),
            "token".toCharArray(),
        ).toList()
        assertEquals(
            nonStreamed.filterIsInstance<ModelEvent.TextDelta>().map { it.text },
            streamed.filterIsInstance<ModelEvent.TextDelta>().map { it.text },
        )
        assertEquals(ModelEvent.Completed, nonStreamed.last())
        assertEquals(ModelEvent.Completed, streamed.last())
    }

    @Test
    fun compatibleNonStreamingWithErrorNullIsSuccess() = runTest {
        val engine = MockEngine {
            respond(
                "{\"error\":null,\"choices\":[{\"message\":{\"content\":\"hi\"}}]}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val events = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1").stream(
            ModelRequest("gpt-chat", listOf(ChatMessage("user", "hi")), stream = false),
            "token".toCharArray(),
        ).toList()
        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "hi" }, events.toString())
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
    }
}
