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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ProviderConnectionErrorCode
import runtime.mobileagent.provider.ProviderHttpResponseException

/**
 * Formal regressions for the incident review chain (C1-C4 and the two adjacent
 * P2 items).  Every case drives the real adapter/parser through a MockEngine or
 * the real redactor entry point; nothing here re-implements production logic.
 */
class IncidentRegressionTest {
    private fun sseEngine(vararg frames: String) = MockEngine {
        // Every event, including the last one, is terminated by a blank line:
        // an event the stream never delimits is discarded, not dispatched.
        respond(frames.joinToString("\n\n") + "\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
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

    /**
     * R2: a ~60KB SSE answer whose tail tool call carries unusable arguments —
     * the observed shape is an accumulated `function.arguments` string that is
     * not parseable JSON, typically a truncated tail — is forwarded so the
     * runtime rejects it and feeds a bounded INVALID result back for a
     * corrected resend.  Nothing was dispatched, so UNKNOWN_OUTCOME and its
     * forced retry-confirmation dialog would be factually wrong.
     */
    @Test
    fun largeResponseTailToolCallWithUnusableArgumentsIsForwardedForRetry() = runTest {
        val chunk = "x".repeat(140)
        val filler = buildString {
            repeat(300) { append("data: {\"choices\":[{\"delta\":{\"content\":\"$chunk\"}}]}\n\n") }
        }
        val tail =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-tail\",\"function\":{\"name\":\"python_skill\",\"arguments\":\"{\\\"code\\\":\\\"print(1)\"}}]}}]}\n\n"
        val body = filler + tail + "data: [DONE]\n\n"
        assertTrue(body.length > 50_000, "evidence shape expected a ~60KB stream, got ${body.length}")
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val events = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
            .stream(ModelRequest("demo", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()
        assertTrue(events.any { it is ModelEvent.TextDelta }, events.toString())
        val call = events.filterIsInstance<ModelEvent.ToolCallDelta>().single()
        assertEquals("call-tail", call.callId)
        assertEquals("{\"code\":\"print(1)", call.argumentsJson)
        assertEquals(ModelEvent.Completed, events.last())
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
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

    /** Multi-delta stream with secret split across chunk boundary (e.g. "sk-" in chunk 1, rest in chunk 2). */
    @Test
    fun secretSplitAcrossChunkBoundaryIsRedacted() {
        val secret = "sk-proj-abc123456789xyz"
        val redactor = StreamingSecretRedactor(listOf(secret))
        val chunk1 = redactor.accept("Key prefix: sk-", StreamingSecretRedactor.Channel.TEXT)
        assertEquals("Key prefix: ", chunk1)
        val chunk2 = redactor.accept("proj-abc123456789xyz is active", StreamingSecretRedactor.Channel.TEXT)
        assertEquals("*** is active", chunk2)
        val remaining = redactor.finish()
        assertTrue(remaining.isEmpty())
    }

    /** Pattern-based credential split across chunk boundary without pre-declared secret. */
    @Test
    fun patternSecretSplitAcrossChunksIsRedacted() {
        val redactor = StreamingSecretRedactor()
        val c1 = redactor.accept("OpenAI key is sk-", StreamingSecretRedactor.Channel.TEXT)
        assertEquals("OpenAI key is ", c1)
        val c2 = redactor.accept("1234567890abcdef and then proceed", StreamingSecretRedactor.Channel.TEXT)
        assertEquals("*** and then proceed", c2)
        assertTrue(redactor.finish().isEmpty())
    }

    /** Cancellation or error drops unconfirmed prefix safely without leaking to output. */
    @Test
    fun cancellationOrErrorDropsUnconfirmedPrefix() {
        val secret = "sk-secret-confidential-999"
        val redactor = StreamingSecretRedactor(listOf(secret))
        val output = redactor.accept("Confirmed payload. Leaking: sk-", StreamingSecretRedactor.Channel.TEXT)
        assertEquals("Confirmed payload. Leaking: ", output)
        assertEquals(StreamingSecretRedactor.Channel.TEXT, redactor.pendingChannel())
        redactor.discard()
        assertTrue(redactor.finish().isEmpty())
    }

    /** Mixed interleaved channels isolate withheld prefixes independently. */
    @Test
    fun mixedInterleavedChannelsIsolateWithheldPrefixes() {
        val textSecret = "sk-text-secret-1111"
        val reasoningSecret = "sk-reason-secret-2222"
        val refusalSecret = "sk-refusal-secret-3333"
        val redactor = StreamingSecretRedactor(listOf(textSecret, reasoningSecret, refusalSecret))

        // Interleave chunk 1 across all 3 channels
        val t1 = redactor.accept("text prefix: sk-", StreamingSecretRedactor.Channel.TEXT)
        val r1 = redactor.accept("reasoning prefix: sk-", StreamingSecretRedactor.Channel.REASONING)
        val f1 = redactor.accept("refusal prefix: sk-", StreamingSecretRedactor.Channel.REFUSAL)
        assertEquals("text prefix: ", t1)
        assertEquals("reasoning prefix: ", r1)
        assertEquals("refusal prefix: ", f1)

        // Interleave chunk 2 completing secrets
        val t2 = redactor.accept("text-secret-1111 done", StreamingSecretRedactor.Channel.TEXT)
        val r2 = redactor.accept("reason-secret-2222 done", StreamingSecretRedactor.Channel.REASONING)
        val f2 = redactor.accept("refusal-secret-3333 done", StreamingSecretRedactor.Channel.REFUSAL)
        assertEquals("*** done", t2)
        assertEquals("*** done", r2)
        assertEquals("*** done", f2)

        assertTrue(redactor.finish().isEmpty())
    }

    /** 1000-chunk streaming stress test asserting linear O(N) execution time. */
    @Test
    fun streamingBenchmarkStressTestOOfNPerformance() {
        val secret = "sk-benchmark-secret-424242"
        val redactor = StreamingSecretRedactor(listOf(secret))
        val deltas = 1000
        val startNs = System.nanoTime()
        val accumulated = StringBuilder()
        for (i in 0 until deltas) {
            val chunk = when {
                i == 500 -> "sk-"
                i == 501 -> "benchmark-secret-424242"
                else -> "chunk-$i-payload "
            }
            accumulated.append(redactor.accept(chunk, StreamingSecretRedactor.Channel.TEXT))
        }
        redactor.finish().forEach { (_, tail) -> accumulated.append(tail) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        assertTrue(elapsedMs < 200, "1000-delta stream took $elapsedMs ms; expected < 200ms for linear O(N)")
        val result = accumulated.toString()
        assertTrue(result.contains("***"), "Secret must be redacted")
        assertFalse(result.contains(secret), "Raw secret must never appear")
    }

    /** Delimiter split before '=': undeclared api-key split across delta boundary must be redacted. */
    @Test
    fun testPatternApiKeySplitBeforeDelimiter() {
        val redactor = StreamingSecretRedactor()
        val c1 = redactor.accept("Use api-key", StreamingSecretRedactor.Channel.TEXT)
        val c2 = redactor.accept("=my-secret-key-7777 for access", StreamingSecretRedactor.Channel.TEXT)
        val streamingOut = c1 + c2
        assertFalse(streamingOut.contains("my-secret-key-7777"), "Secret must not leak when split at delimiter")
        assertTrue(streamingOut.contains("***"), "Secret must be redacted to ***")
        val out = streamingOut + redactor.finish().joinToString("") { it.second }
        assertEquals("Use api-key=*** for access", out)
        assertTrue(redactor.finish().isEmpty())
    }

    /** Delimiter split before ':': undeclared api_key split across delta boundary must be redacted. */
    @Test
    fun testPatternApiKeySplitBeforeColon() {
        val redactor = StreamingSecretRedactor()
        val c1 = redactor.accept("Header api_key", StreamingSecretRedactor.Channel.TEXT)
        val c2 = redactor.accept(": my-secret-key-6666\n", StreamingSecretRedactor.Channel.TEXT)
        val out = c1 + c2
        assertFalse(out.contains("my-secret-key-6666"), "Secret must not leak when split at colon")
        assertTrue(out.contains("***"), "Secret must be redacted to ***")
        assertEquals("Header api_key=***\n", out)
        assertTrue(redactor.finish().isEmpty())
    }

    @Test
    fun testChallengerR2AdversarialSuite() {
        val testSecrets = listOf("secret123456789", "my-secret-key-7777", "topSecret987")

        // 1. Delimiter split before '=' across variations
        val equalsPrefixes = listOf("api-key", "api_key", "apikey", "x-api-key", "API-KEY", "\"api-key\"", "'api-key'")
        for (prefix in equalsPrefixes) {
            val redactor = StreamingSecretRedactor()
            val c1 = redactor.accept("Key: $prefix", StreamingSecretRedactor.Channel.TEXT)
            val c2 = redactor.accept("=secret123456789 for user", StreamingSecretRedactor.Channel.TEXT)
            val out = c1 + c2
            assertFalse(out.contains("secret123456789"), "Undeclared secret must not leak for prefix '$prefix' split before '='")
            assertTrue(out.contains("***"), "Must contain *** redaction for prefix '$prefix'")
            assertTrue(redactor.finish().isEmpty())
        }

        // 2. Delimiter split before ':' across variations
        val colonPrefixes = listOf("api-key", "api_key", "apikey", "x-api-key", "API_KEY", "\"api_key\"", "'api_key'")
        for (prefix in colonPrefixes) {
            val redactor = StreamingSecretRedactor()
            val c1 = redactor.accept("Auth $prefix", StreamingSecretRedactor.Channel.TEXT)
            val c2 = redactor.accept(": secret123456789\n", StreamingSecretRedactor.Channel.TEXT)
            val out = c1 + c2
            assertFalse(out.contains("secret123456789"), "Undeclared secret must not leak for prefix '$prefix' split before ':'")
            assertTrue(out.contains("***"), "Must contain *** redaction for prefix '$prefix'")
            assertTrue(redactor.finish().isEmpty())
        }

        // 3. Multi-chunk delimiter splits (prefix + delimiter + secret)
        run {
            val redactor = StreamingSecretRedactor()
            val c1 = redactor.accept("Prefix api-key", StreamingSecretRedactor.Channel.TEXT)
            val c2 = redactor.accept("=", StreamingSecretRedactor.Channel.TEXT)
            val c3 = redactor.accept("secret123456789 rest", StreamingSecretRedactor.Channel.TEXT)
            val out = c1 + c2 + c3
            assertFalse(out.contains("secret123456789"), "Secret must not leak when split across 3 chunks (before and after =)")
            assertTrue(out.contains("***"), "Secret must be redacted")
        }
        run {
            val redactor = StreamingSecretRedactor()
            val c1 = redactor.accept("Prefix api-key", StreamingSecretRedactor.Channel.TEXT)
            val c2 = redactor.accept(" = ", StreamingSecretRedactor.Channel.TEXT)
            val c3 = redactor.accept("secret123456789 rest", StreamingSecretRedactor.Channel.TEXT)
            val out = c1 + c2 + c3
            assertFalse(out.contains("secret123456789"), "Secret must not leak when split across 3 chunks with whitespace delimiter")
            assertTrue(out.contains("***"), "Secret must be redacted")
        }
        run {
            val redactor = StreamingSecretRedactor()
            val c1 = redactor.accept("Header api_key", StreamingSecretRedactor.Channel.TEXT)
            val c2 = redactor.accept(":", StreamingSecretRedactor.Channel.TEXT)
            val c3 = redactor.accept(" secret123456789 rest", StreamingSecretRedactor.Channel.TEXT)
            val out = c1 + c2 + c3
            assertFalse(out.contains("secret123456789"), "Secret must not leak when split across 3 chunks (before and after :)")
            assertTrue(out.contains("***"), "Secret must be redacted")
        }
        run {
            val redactor = StreamingSecretRedactor()
            val c1 = redactor.accept("Header api_key", StreamingSecretRedactor.Channel.TEXT)
            val c2 = redactor.accept(": ", StreamingSecretRedactor.Channel.TEXT)
            val c3 = redactor.accept("secret123456789 rest", StreamingSecretRedactor.Channel.TEXT)
            val out = c1 + c2 + c3
            assertFalse(out.contains("secret123456789"), "Secret must not leak when split across 3 chunks (: with space)")
            assertTrue(out.contains("***"), "Secret must be redacted")
        }

        // 4. Verify NO false positives on regular words (single chunk)
        val regularTexts = listOf(
            "This is an api-key discussion.",
            "What is an api_key in computer science?",
            "We should use apikey here.",
            "apiKey is very important.",
            "We need an x-api-key header.",
            "monkey donkey keyboard rapid therapy",
            "This API key is great."
        )
        for (text in regularTexts) {
            val redactor = StreamingSecretRedactor()
            val out = redactor.accept(text, StreamingSecretRedactor.Channel.TEXT)
            val finished = redactor.finish().joinToString("") { it.second }
            val full = out + finished
            assertEquals(text, full, "Regular text must not be redacted or corrupted: '$text'")
            assertFalse(full.contains("***"), "Regular text must not contain '***': '$text'")
        }

        // 5. Verify NO false positives on regular words split across chunk boundaries
        val regularChunkSplits = listOf(
            Pair("This is an api-key", " discussion."),
            Pair("What is an api_key", " in computer science?"),
            Pair("We should use apikey", " here."),
            Pair("apiKey", " is very important."),
            Pair("We need an x-api-key", " header."),
            Pair("Use an api-key", " to access the service.")
        )
        for ((chunk1, chunk2) in regularChunkSplits) {
            val redactor = StreamingSecretRedactor()
            val out1 = redactor.accept(chunk1, StreamingSecretRedactor.Channel.TEXT)
            val out2 = redactor.accept(chunk2, StreamingSecretRedactor.Channel.TEXT)
            val finished = redactor.finish().joinToString("") { it.second }
            val full = out1 + out2 + finished
            val expected = chunk1 + chunk2
            assertEquals(expected, full, "Split regular text must reassemble verbatim: '$expected'")
            assertFalse(full.contains("***"), "Split regular text must not contain '***': '$expected'")
        }

        // 6. Verify stream ending immediately with candidate keyword (finish() flush)
        val endingKeywords = listOf("api-key", "api_key", "apikey", "x-api-key", "API-KEY", "\"api-key\"")
        for (kw in endingKeywords) {
            val redactor = StreamingSecretRedactor()
            val out = redactor.accept("Keyword: $kw", StreamingSecretRedactor.Channel.TEXT)
            val finished = redactor.finish().joinToString("") { it.second }
            val full = out + finished
            assertEquals("Keyword: $kw", full, "Keyword ending stream must flush verbatim without redaction")
            assertFalse(full.contains("***"), "Unmatched keyword must not be redacted on finish()")
        }
    }
}
