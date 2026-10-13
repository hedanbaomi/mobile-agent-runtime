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
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest

/**
 * SSE framing (F06): the transport is a sequence of *events* terminated by a
 * blank line, not a sequence of independently parsed `data:` lines.  One legal
 * event may carry several `data:` fields whose values are joined with LF before
 * the protocol sees them.
 *
 * Every case drives the real adapters through MockEngine, so nothing here
 * re-implements production parsing.  The framing rules asserted here follow the
 * WHATWG event-stream interpretation: `data` values join with LF, CRLF/LF/CR are
 * all line terminators, a leading BOM is ignored, comments and unknown fields
 * are ignored, `data` without a colon is an empty-valued data field, and an
 * event that the stream never terminates with a blank line is discarded rather
 * than shipped as a success.
 */
class SseEventFramingTest {
    private fun sseEngine(body: String) = MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    private suspend fun chatEvents(body: String): List<ModelEvent> =
        OpenAiCompatibleAdapter(HttpClient(sseEngine(body)), "https://example.invalid/v1")
            .stream(ModelRequest("demo", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()

    private suspend fun responsesEvents(body: String): List<ModelEvent> =
        OpenAiResponsesAdapter(HttpClient(sseEngine(body)), "https://example.invalid/v1")
            .stream(ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()

    private fun textOf(events: List<ModelEvent>): String =
        events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }

    /** A two-line `data:` payload is one event, and merging happens before JSON. */
    @Test
    fun chatCompletionsMergesMultiLineDataEvent() = runTest {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":\n")
            append("data: {\"content\":\"hello\"}}]}\n")
            append("\n")
            append("data: [DONE]\n\n")
        }
        val events = chatEvents(body)
        assertEquals("hello", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
    }

    /** Responses frames are split by providers too; the event vocabulary is unchanged. */
    @Test
    fun responsesMergesMultiLineDataEvent() = runTest {
        val body = buildString {
            append("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\n")
            append("data: \"content_index\":0,\"delta\":\"split answer\"}\n")
            append("\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
        }
        val events = responsesEvents(body)
        assertEquals("split answer", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
    }

    /** CRLF is a line terminator everywhere it appears, including inside a split event. */
    @Test
    fun chatMergesMultiLineDataEventWithCrlfTerminators() = runTest {
        val body = "data: {\"choices\":[{\"delta\":\r\n" +
            "data: {\"content\":\"crlf\"}}]}\r\n" +
            "\r\n" +
            "data: [DONE]\r\n\r\n"
        val events = chatEvents(body)
        assertEquals("crlf", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
    }

    /** A lone CR is also a line terminator, not payload. */
    @Test
    fun chatMergesMultiLineDataEventWithBareCrTerminators() = runTest {
        val body = "data: {\"choices\":[{\"delta\":\r" +
            "data: {\"content\":\"cr\"}}]}\r" +
            "\r" +
            "data: [DONE]\r\r"
        val events = chatEvents(body)
        assertEquals("cr", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
    }

    /** A BOM belongs to the encoding, not to the first field name. */
    @Test
    fun byteOrderMarkAtStreamStartIsIgnored() = runTest {
        val body = "\uFEFFdata: {\"choices\":[{\"delta\":{\"content\":\"bom\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val events = chatEvents(body)
        assertEquals("bom", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
    }

    /**
     * Comments and fields this protocol does not consume (`event:`, `id:`,
     * `retry:`) never become model input and never end the event early.
     */
    @Test
    fun commentsAndUnknownFieldsDoNotDisturbTheEvent() = runTest {
        val body = buildString {
            append(": keep-alive\n")
            append("event: message\n")
            append("id: 1\n")
            append("retry: 100\n")
            append("data: {\"choices\":[{\"delta\":\n")
            append("data: {\"content\":\"framed\"}}]}\n")
            append("\n")
            append(": trailing comment\n")
            append("data: [DONE]\n\n")
        }
        val events = chatEvents(body)
        assertEquals("framed", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
    }

    /** `data` with no colon is a data field whose value is the empty string. */
    @Test
    fun dataFieldWithoutColonDoesNotSwallowTheNextEvent() = runTest {
        val body = buildString {
            append("data\n")
            append("\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"after\"}}]}\n")
            append("\n")
            append("data: [DONE]\n\n")
        }
        val events = chatEvents(body)
        assertEquals("after", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
    }

    /** A plain single-line event keeps working exactly as before. */
    @Test
    fun singleLineEventsStillDecode() = runTest {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"plain\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val events = chatEvents(body)
        assertEquals("plain", textOf(events))
        assertEquals(ModelEvent.Completed, events.last())
    }

    /**
     * EOF without a delimiting blank line: the event the stream never finished
     * is discarded, exactly as the event-stream interpretation requires.  It must
     * not reach the user as text, and it must not be reported as a success.
     */
    @Test
    fun unterminatedFinalEventIsDiscardedAndNeverASuccess() = runTest {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"unfinished\"}}]}\n"
        val events = chatEvents(body)
        assertEquals("", textOf(events))
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
        assertEquals(
            runtime.mobileagent.provider.ProviderConnectionErrorCode.INVALID_RESPONSE.name,
            (events.last() as ModelEvent.Failed).sanitizedMessage,
        )
    }

    /**
     * Frames separated by a single LF are one unterminated event, not four
     * events: a provider that never delimits its stream is not silently accepted
     * line by line, and its unterminated payload never becomes a success.
     */
    @Test
    fun unterminatedNonConformantFramingIsNotAcceptedLineByLine() = runTest {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"two\"}}]}\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n" +
            "data: [DONE]\n"
        val events = chatEvents(body)
        assertEquals("", textOf(events))
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
        assertTrue(events.any { it is ModelEvent.Failed }, events.toString())
    }
}
