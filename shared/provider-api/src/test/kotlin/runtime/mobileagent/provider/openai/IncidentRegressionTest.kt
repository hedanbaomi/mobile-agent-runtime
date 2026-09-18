// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ProviderHttpResponseException

/**
 * Formal regressions for the incident review chain (C1-C4 and the two adjacent
 * P2 items).  Every case drives the real adapter/parser through a MockEngine or
 * the real redactor entry point; nothing here re-implements production logic.
 */
class IncidentRegressionTest {
    private fun sseEngine(vararg frames: String) = MockEngine {
        respond(frames.joinToString("\n\n"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    private suspend fun responsesEvents(vararg frames: String): List<ModelEvent> =
        OpenAiResponsesAdapter(HttpClient(sseEngine(*frames)), "https://example.invalid/v1")
            .stream(ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()

    private suspend fun chatEvents(vararg frames: String): List<ModelEvent> =
        OpenAiCompatibleAdapter(HttpClient(sseEngine(*frames)), "https://example.invalid/v1")
            .stream(ModelRequest("demo", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()

    private suspend fun chatHttp(status: HttpStatusCode, body: String): List<ModelEvent> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
            .stream(ModelRequest("demo", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()
    }

    private fun lastFailure(events: List<ModelEvent>): String =
        (events.last { it is ModelEvent.Failed } as ModelEvent.Failed).sanitizedMessage

    /** C1: one terminal frame must settle usage exactly once. */
    @Test
    fun incompleteTerminalEmitsUsageExactlyOnce() = runTest {
        val events = responsesEvents(
            """data: {"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"input_tokens":1326,"output_tokens":10240,"output_tokens_details":{"reasoning_tokens":10240}}}}""",
            "data: [DONE]",
        )
        val usages = events.filterIsInstance<ModelEvent.Usage>()
        assertEquals(1, usages.size, events.toString())
        assertEquals(1326, usages.single().inputTokens)
        assertEquals(10240, usages.single().outputTokens)
        assertEquals(10240, usages.single().reasoningTokens)
        assertEquals(ErrorCode.REASONING_EXHAUSTED.name, lastFailure(events))
    }

    /** C2: all three protocol paths map the same reasoning evidence alike. */
    @Test
    fun allReasoningStopIsReasoningExhaustedOnEveryProtocol() = runTest {
        val chat = chatEvents(
            """data: {"choices":[{"delta":{"reasoning_content":"thinking"},"finish_reason":"length"}],"usage":{"prompt_tokens":9,"completion_tokens":100,"completion_tokens_details":{"reasoning_tokens":100}}}""",
            "data: [DONE]",
        )
        val responses = responsesEvents(
            """data: {"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"input_tokens":9,"output_tokens":100,"output_tokens_details":{"reasoning_tokens":100}}}}""",
            "data: [DONE]",
        )
        assertEquals(ErrorCode.REASONING_EXHAUSTED.name, lastFailure(chat))
        assertEquals(ErrorCode.REASONING_EXHAUSTED.name, lastFailure(responses))
    }

    /** C2: unknown/zero reasoning with no answer is an unusable result everywhere. */
    @Test
    fun unknownOrZeroReasoningStopIsUnusableEverywhere() = runTest {
        val chatZero = chatEvents(
            """data: {"choices":[{"delta":{"content":""},"finish_reason":"length"}],"usage":{"prompt_tokens":9,"completion_tokens":32,"completion_tokens_details":{"reasoning_tokens":0}}}""",
            "data: [DONE]",
        )
        val responsesUnknown = responsesEvents(
            """data: {"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"input_tokens":9,"output_tokens":32}}}""",
            "data: [DONE]",
        )
        assertEquals("INVALID_RESPONSE", lastFailure(chatZero))
        assertEquals("INVALID_RESPONSE", lastFailure(responsesUnknown))
    }

    /** P2-B: a done-only answer is visible content, so a later stop is truncation. */
    @Test
    fun doneOnlyTextThenIncompleteIsOutputTruncation() = runTest {
        val events = responsesEvents(
            """data: {"type": "response.output_text.done", "item_id": "synthetic-item", "output_index": 0, "content_index": 0, "text": "SYNTHETIC_PARTIAL_ANSWER"}""",
            """data: {"type": "response.incomplete", "response": {"incomplete_details": {"reason": "max_output_tokens"}, "usage": {"input_tokens": 8, "output_tokens": 100, "output_tokens_details": {"reasoning_tokens": 0}}}}""",
            "data: [DONE]",
        )
        assertEquals("SYNTHETIC_PARTIAL_ANSWER", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(ErrorCode.OUTPUT_TRUNCATED.name, lastFailure(events))
        assertTrue(events.none { it == ModelEvent.Completed })
    }

    /** C4: an ordinary 5xx response stays a canonical unknown outcome. */
    @Test
    fun ordinaryServerErrorIsCanonicalUnknownOutcome() = runTest {
        val events = chatHttp(HttpStatusCode.BadGateway, """{"error":{"message":"synthetic service unavailable"}}""")
        assertEquals(ErrorCode.UNKNOWN_OUTCOME.name, lastFailure(events))
    }

    /** P2-A: the HTTP-engine interceptor path obeys the same 5xx policy. */
    @Test
    fun interceptorServerErrorIsCanonicalUnknownOutcome() = runTest {
        val engine = MockEngine { throw ProviderHttpResponseException(503) }
        val events = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
            .stream(ModelRequest("demo", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()
        assertEquals(ErrorCode.UNKNOWN_OUTCOME.name, lastFailure(events))
    }

    /** C4: an explicit input-window rejection is not a generic rejection. */
    @Test
    fun explicitInputWindowRejectionIsInputOverflow() = runTest {
        val events = chatHttp(
            HttpStatusCode.BadRequest,
            """{"error":{"message":"This model's maximum context length is 8192 tokens"}}""",
        )
        assertEquals(ErrorCode.INPUT_OVERFLOW.name, lastFailure(events))
    }

    /** C4: an unrelated 4xx stays a decided rejection, not an overflow guess. */
    @Test
    fun unrelatedClientErrorStaysRejection() = runTest {
        val code = lastFailure(chatHttp(HttpStatusCode.BadRequest, """{"error":{"message":"unsupported parameter"}}"""))
        assertTrue(code != ErrorCode.INPUT_OVERFLOW.name, code)
        assertTrue(code != ErrorCode.UNKNOWN_OUTCOME.name, code)
    }

    /** C3: interleaved channels keep their own withheld suffix and stay intact. */
    @Test
    fun interleavedChannelsKeepContentComplete() {
        val redactor = StreamingSecretRedactor(listOf("not-a-real-credential-123456"))
        val answer = buildString {
            append(redactor.accept("Do not", StreamingSecretRedactor.Channel.TEXT))
            redactor.accept("Checking.", StreamingSecretRedactor.Channel.REASONING)
            append(redactor.accept(" proceed.", StreamingSecretRedactor.Channel.TEXT))
            redactor.finish().forEach { (channel, tail) ->
                if (channel == StreamingSecretRedactor.Channel.TEXT) append(tail)
            }
        }
        assertEquals("Do not proceed.", answer)
    }

    /** C3: the same-channel credential prefix is still withheld and redacted. */
    @Test
    fun sameChannelCredentialAcrossDeltasIsRedacted() {
        val redactor = StreamingSecretRedactor(listOf("not-a-real-credential-123456"))
        assertEquals("***", redactor.accept("not-a-real-credential-123456", StreamingSecretRedactor.Channel.TEXT))
    }

    /** C3: a reasoning tail is never emitted as the answer. */
    @Test
    fun reasoningTailIsNotEmittedAsAnswer() {
        val redactor = StreamingSecretRedactor(listOf("and_NOT_A_REAL_CREDENTIAL_0123456789"))
        val reasoning = redactor.accept("Synthetic provider reasoning and", StreamingSecretRedactor.Channel.REASONING)
        assertEquals(listOf(StreamingSecretRedactor.Channel.REASONING to "and"), redactor.finish())
        assertEquals("Synthetic provider reasoning ", reasoning)
    }
}