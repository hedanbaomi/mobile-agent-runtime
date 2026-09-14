// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelDiagnosticEvent
import runtime.mobileagent.provider.ModelDiagnosticSink
import runtime.mobileagent.provider.ModelDiagnosticStage
import runtime.mobileagent.provider.ModelDispatchStatus
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest

class ModelTransportDiagnosticsTest {
    @Test
    fun chatCompletionReportsDispatchResponseFinishUsageAndOptInContent() = runBlocking {
        val diagnostics = mutableListOf<ModelDiagnosticEvent>()
        val sink = capturingSink(diagnostics)
        val adapter = OpenAiCompatibleAdapter(
            HttpClient(MockEngine {
                respond(
                    """{"choices":[{"message":{"content":"{\"ok\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":7}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            "https://example.invalid/v1",
        )

        val events = adapter.stream(request(sink), "top-secret".toCharArray()).toList()

        assertTrue(events.last() is ModelEvent.Completed)
        val terminal = diagnostics.last { it.stage == ModelDiagnosticStage.TERMINAL }
        assertEquals(ModelDispatchStatus.RESPONSE_RECEIVED, terminal.dispatchStatus)
        assertEquals(200, terminal.httpStatus)
        assertEquals("stop", terminal.finishReason)
        assertEquals(11, terminal.inputTokens)
        assertEquals(7, terminal.outputTokens)
        assertTrue(diagnostics.any { it.contentKind == "request.json" && it.content?.contains("hello") == true })
        assertTrue(diagnostics.any { it.contentKind == "response.json" && it.content?.contains("ok") == true })
        assertTrue(diagnostics.filter { it.content != null }.none { it.content!!.contains("top-secret") })
    }

    @Test
    fun knownHttpAndMalformedCompleteResponseRemainKnownFailures() = runBlocking {
        suspend fun invoke(status: HttpStatusCode, body: String): Pair<List<ModelEvent>, List<ModelDiagnosticEvent>> {
            val diagnostics = mutableListOf<ModelDiagnosticEvent>()
            val adapter = OpenAiCompatibleAdapter(
                HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }),
                "https://example.invalid/v1",
            )
            return adapter.stream(request(capturingSink(diagnostics)), "key".toCharArray()).toList() to diagnostics
        }

        val (limited, limitedDiagnostics) = invoke(HttpStatusCode.TooManyRequests, "{}")
        assertEquals(ModelEvent.Failed("RATE_LIMITED"), limited.last())
        assertEquals(429, limitedDiagnostics.last().httpStatus)
        assertEquals(ModelDispatchStatus.RESPONSE_RECEIVED, limitedDiagnostics.last().dispatchStatus)

        val (malformed, malformedDiagnostics) = invoke(HttpStatusCode.OK, "{bad-json")
        assertEquals(ModelEvent.Failed("INVALID_RESPONSE"), malformed.last())
        assertEquals("INVALID_RESPONSE", malformedDiagnostics.last().errorCode)
        assertEquals(ModelDispatchStatus.RESPONSE_RECEIVED, malformedDiagnostics.last().dispatchStatus)
    }

    @Test
    fun postDispatchIoCutIsUnknownAndDiagnosticSinkFailureIsIsolated() = runBlocking {
        val diagnostics = mutableListOf<ModelDiagnosticEvent>()
        val adapter = OpenAiCompatibleAdapter(
            HttpClient(MockEngine { throw IOException("cut") }),
            "https://example.invalid/v1",
        )
        val events = adapter.stream(request(capturingSink(diagnostics)), "key".toCharArray()).toList()
        assertEquals(ModelEvent.Failed("UNKNOWN_OUTCOME"), events.last())
        assertEquals(ModelDispatchStatus.UNKNOWN_AFTER_DISPATCH, diagnostics.last().dispatchStatus)
        assertEquals(IOException::class.java.name, diagnostics.last().exceptionClass)

        val throwing = ModelDiagnosticSink { throw IllegalStateException("diagnostics down") }
        val success = OpenAiCompatibleAdapter(
            HttpClient(MockEngine {
                respond(
                    """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            "https://example.invalid/v1",
        ).stream(request(throwing), "key".toCharArray()).toList()
        assertTrue(success.last() is ModelEvent.Completed)
    }

    @Test
    fun responsesReportsUsageAndRemovesPrivateContinuationFromContent() = runBlocking {
        val diagnostics = mutableListOf<ModelDiagnosticEvent>()
        val adapter = OpenAiResponsesAdapter(
            HttpClient(MockEngine {
                respond(
                    """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]},{"type":"reasoning","encrypted_content":"opaque-private"}],"usage":{"input_tokens":4,"output_tokens":2}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            "https://example.invalid/v1",
        )

        val events = adapter.stream(request(capturingSink(diagnostics)), "key".toCharArray()).toList()

        assertTrue(events.last() is ModelEvent.Completed)
        val terminal = diagnostics.last()
        assertEquals("completed", terminal.finishReason)
        assertEquals(4, terminal.inputTokens)
        assertEquals(2, terminal.outputTokens)
        val response = diagnostics.first { it.contentKind == "response.json" }.content
        assertTrue(response?.contains("ok") == true)
        assertFalse(response.orEmpty().contains("opaque-private"))
        assertFalse(response.orEmpty().contains("encrypted_content"))
    }

    @Test
    fun emptyCredentialNeverDispatchesOrCapturesContent() = runBlocking {
        val diagnostics = mutableListOf<ModelDiagnosticEvent>()
        val adapter = OpenAiCompatibleAdapter(HttpClient(MockEngine { error("must not dispatch") }), "https://example.invalid/v1")

        val events = adapter.stream(request(capturingSink(diagnostics)), CharArray(0)).toList()

        assertEquals(listOf(ModelEvent.Failed("SECRET_UNAVAILABLE")), events)
        assertEquals(ModelDispatchStatus.NOT_DISPATCHED, diagnostics.last().dispatchStatus)
        assertEquals("SECRET_UNAVAILABLE", diagnostics.last().errorCode)
        assertNull(diagnostics.last().content)
    }

    @Test
    fun diagnosticJsonRedactsEscapedSecretsStripsPrivateFieldsAndOmitsMalformedContent() = runBlocking {
        val secret = "quote\"slash\\snow雪"
        val encodedSecret = JsonPrimitive(secret).toString()
        val diagnostics = mutableListOf<ModelDiagnosticEvent>()
        val body = """{"choices":[{"message":{"content":$encodedSecret,"nested":{"encrypted_content":"opaque-private"}},"finish_reason":"stop"}]}"""
        OpenAiCompatibleAdapter(
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            "https://example.invalid/v1",
        ).stream(request(capturingSink(diagnostics)), secret.toCharArray()).toList()
        val captured = diagnostics.first { it.contentKind == "response.json" }.content.orEmpty()
        assertFalse(captured.contains("opaque-private"))
        assertFalse(captured.contains("encrypted_content"))
        assertFalse(captured.contains("quote"))
        assertTrue(captured.contains("***"))

        val malformedDiagnostics = mutableListOf<ModelDiagnosticEvent>()
        OpenAiCompatibleAdapter(
            HttpClient(MockEngine { respond("{bad", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            "https://example.invalid/v1",
        ).stream(request(capturingSink(malformedDiagnostics)), secret.toCharArray()).toList()
        assertTrue(malformedDiagnostics.none { it.contentKind == "response.json" && it.content != null })
        assertTrue(malformedDiagnostics.any { it.responseBytes == 4L && it.errorCode == "INVALID_RESPONSE" })
    }

    @Test
    fun finalDispatchGateDenialAndExceptionNeverReachEitherHttpEngine() = runBlocking {
        listOf<(HttpClient) -> runtime.mobileagent.provider.ModelAdapter>(
            { OpenAiCompatibleAdapter(it, "https://example.invalid/v1") },
            { OpenAiResponsesAdapter(it, "https://example.invalid/v1") },
        ).forEach { create ->
            var calls = 0
            val http = HttpClient(MockEngine { calls++; error("must not dispatch") })
            val deniedDiagnostics = mutableListOf<ModelDiagnosticEvent>()
            val denied = create(http).stream(
                request(capturingSink(deniedDiagnostics)).copy(beforeDispatch = { false }),
                "key".toCharArray(),
            ).toList()
            assertEquals(ModelEvent.Failed("REQUEST_CANCELLED"), denied.single())
            assertEquals(0, calls)
            assertEquals(ModelDispatchStatus.NOT_DISPATCHED, deniedDiagnostics.last().dispatchStatus)

            val throwingDiagnostics = mutableListOf<ModelDiagnosticEvent>()
            val throwing = create(http).stream(
                request(capturingSink(throwingDiagnostics)).copy(beforeDispatch = { error("gate unavailable") }),
                "key".toCharArray(),
            ).toList()
            assertEquals(ModelEvent.Failed("INVALID_CONFIG"), throwing.single())
            assertEquals(0, calls)
            assertEquals(IllegalStateException::class.java.name, throwingDiagnostics.last().exceptionClass)
            assertEquals(ModelDispatchStatus.NOT_DISPATCHED, throwingDiagnostics.last().dispatchStatus)
        }
    }

    @Test
    fun sseDiagnosticContentIsDecodedSanitizedAndMalformedFramesAreOmitted() = runBlocking {
        val secret = "quoted\"secret\\雪"
        val encoded = JsonPrimitive(secret).toString()
        val diagnostics = mutableListOf<ModelDiagnosticEvent>()
        val body = "data: {bad\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":$encoded},\"encrypted_content\":\"opaque\"}]}\n\n" +
            "data: [DONE]\n\n"
        OpenAiCompatibleAdapter(
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }),
            "https://example.invalid/v1",
        ).stream(request(capturingSink(diagnostics)).copy(stream = true), secret.toCharArray()).toList()

        val frames = diagnostics.filter { it.contentKind == "response.sse.event" }
        assertTrue(frames.any { it.content == null && it.originalContentChars == 10L })
        assertTrue(frames.any { it.content == "[DONE]" })
        assertTrue(frames.filter { it.content != null }.none { it.content!!.contains(secret) || it.content!!.contains("opaque") })
        assertTrue(frames.any { it.content?.contains("captured-after-stream-redaction") == true })
        val safeEvents = diagnostics.filter { it.contentKind == "response.sse.model_event" }
        assertTrue(safeEvents.any { it.content?.contains("***") == true })
        assertTrue(safeEvents.none { it.content.orEmpty().contains(secret) })
    }

    private fun request(sink: ModelDiagnosticSink) = ModelRequest(
        modelId = "vision-model",
        messages = listOf(ChatMessage("user", "hello")),
        stream = false,
        diagnostics = sink,
    )

    private fun capturingSink(events: MutableList<ModelDiagnosticEvent>) = object : ModelDiagnosticSink {
        override val captureContent = true
        override fun record(event: ModelDiagnosticEvent) {
            events += event
        }
    }
}
