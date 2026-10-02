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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ProviderConnectionErrorCode

/**
 * Terminal guards for both OpenAI-compatible protocols.
 *
 * An end marker is not a success.  A response with no visible content is
 * unusable, a reasoning-only answer is diagnosable, and function-call
 * arguments the provider never confirmed must never be released as a tool
 * call.  The `[DONE]` transport marker and `response.completed` must decide
 * alike, and a provider that omits `[DONE]` must not change how a length stop
 * is labelled.  Every case drives the real adapter/parser through MockEngine;
 * nothing here re-implements production logic.
 */
class StreamingTerminalGuardTest {
    @Test
    fun responsesErrorFramesKeepUsageWithoutReleasingOutputOrCredentials() = runTest {
        val billing = """{"input_tokens":8,"output_tokens":40,"output_tokens_details":{"reasoning_tokens":5}}"""
        val failure = """{"message":"provider rejected token"}"""
        val sequences = listOf(
            responsesJson("""{"status":"failed","error":$failure,"usage":$billing}"""),
            responsesEvents("""data: {"type":"response.failed","response":{"status":"failed","error":$failure,"usage":$billing}}"""),
            responsesEvents("""data: {"type":"error","message":"provider rejected token","usage":$billing}"""),
        )
        sequences.forEachIndexed { index, events ->
            assertEquals(ModelEvent.Usage(8, 40, 5, 8, 40), events.filterIsInstance<ModelEvent.Usage>().single(), "error frame $index")
            val terminal = events.filterIsInstance<ModelEvent.Failed>().single()
            assertTrue(!terminal.sanitizedMessage.contains("token"), "error frame $index leaked the synthetic credential")
            assertEquals(terminal, events.last())
            assertTrue(events.none { it is ModelEvent.ToolCallDelta || it is ModelEvent.TextDelta || it == ModelEvent.Completed })
        }
    }

    @Test
    fun responsesUnknownStatusKeepsUsageBeforeParsingUnconfirmedOutput() = runTest {
        val unconfirmedOutputs = listOf(
            """[{"type":"function_call","call_id":"","name":"calculator","arguments":"{}"}]""",
            """[{"type":"function_call","call_id":"call_1","name":"calculator","arguments":"{\"value\":\"token\"}"}]""",
            """[{"type":"message","content":[{"type":"output_text","text":{"unexpected":"shape"}}]}]""",
        )
        for (status in listOf("cancelled", "queued", "unrecognized")) {
            unconfirmedOutputs.forEachIndexed { index, output ->
                val events = responsesJson(
                    """{"status":"$status","output":$output,"usage":{"input_tokens":8,"output_tokens":40,"output_tokens_details":{"reasoning_tokens":5}}}""",
                )
                assertEquals(
                    listOf(ModelEvent.Usage(8, 40, 5, 8, 40), ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name)),
                    events,
                    "Unknown status $status, unconfirmed output shape $index",
                )
            }
        }
    }

    private fun sseEngine(vararg frames: String) = MockEngine {
        respond(frames.joinToString("\n\n"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    private suspend fun responsesEvents(vararg frames: String): List<ModelEvent> =
        OpenAiResponsesAdapter(HttpClient(sseEngine(*frames)), "https://example.invalid/v1")
            .stream(ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()

    private suspend fun responsesJson(body: String): List<ModelEvent> {
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1")
            .stream(ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()
    }

    private suspend fun chatEvents(vararg frames: String): List<ModelEvent> =
        OpenAiCompatibleAdapter(HttpClient(sseEngine(*frames)), "https://example.invalid/v1")
            .stream(ModelRequest("demo", listOf(ChatMessage("user", "hi"))), "token".toCharArray())
            .toList()

    private fun lastFailure(events: List<ModelEvent>): String =
        (events.last { it is ModelEvent.Failed } as ModelEvent.Failed).sanitizedMessage

    /** `[DONE]` with nothing visible is not a successful empty answer. */
    @Test
    fun responsesDoneWithNoVisibleContentIsInvalidResponse() = runTest {
        val events = responsesEvents(
            """data: {"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}""",
            "data: [DONE]",
        )
        assertEquals(ProviderConnectionErrorCode.INVALID_RESPONSE.name, lastFailure(events))
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
    }

    /** `[DONE]` after reasoning only keeps the diagnosable reasoning terminal. */
    @Test
    fun responsesDoneWithOnlyReasoningIsReasoningOnly() = runTest {
        val events = responsesEvents(
            """data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"thinking"}""",
            "data: [DONE]",
        )
        assertEquals(ErrorCode.REASONING_ONLY.name, lastFailure(events))
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
    }

    /**
     * A function call the provider streamed arguments for but never confirmed
     * with an `arguments.done`/`output_item.done` terminal must not become a
     * tool call, and must not leave the run looking successful either.
     */
    @Test
    fun responsesDoneDoesNotReleaseUnconfirmedToolArguments() = runTest {
        val events = responsesEvents(
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","call_id":"call_1","output_index":0,"delta":"{\"code\":\"print(1)\"}"}""",
            "data: [DONE]",
        )
        assertTrue(events.none { it is ModelEvent.ToolCallDelta }, events.toString())
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
        assertEquals(ProviderConnectionErrorCode.INVALID_RESPONSE.name, lastFailure(events))
    }

    /**
     * The same holds for a call that was only announced: already-produced answer
     * text must not be reported as a successful completion that quietly drops the
     * unconfirmed call.  The text that did arrive stays readable.
     */
    @Test
    fun responsesDoneDoesNotIgnoreAnnouncedButUnconfirmedFunctionCall() = runTest {
        val events = responsesEvents(
            """data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"visible answer"}""",
            """data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"weather","arguments":""}}""",
            "data: [DONE]",
        )
        assertEquals("visible answer", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ModelEvent.ToolCallDelta }, events.toString())
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
        assertEquals(ProviderConnectionErrorCode.INVALID_RESPONSE.name, lastFailure(events))
    }

    /** A provider-confirmed tool call still ends in a normal completion. */
    @Test
    fun responsesDoneKeepsConfirmedToolCallAsSuccess() = runTest {
        val events = responsesEvents(
            """data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"calculator","arguments":"{\"expression\":\"1+1\"}"}}""",
            "data: [DONE]",
        )
        val call = events.filterIsInstance<ModelEvent.ToolCallDelta>().single()
        assertEquals("call_1", call.callId)
        assertEquals("calculator", call.name)
        assertEquals("""{"expression":"1+1"}""", call.argumentsJson)
        assertEquals(ModelEvent.Completed, events.last())
        assertTrue(events.none { it is ModelEvent.Failed }, events.toString())
    }

    /** Text and refusals remain visible content, so `[DONE]` stays a completion. */
    @Test
    fun responsesDoneKeepsTextAndRefusalAsSuccess() = runTest {
        val text = responsesEvents(
            """data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"partial answer"}""",
            "data: [DONE]",
        )
        assertEquals("partial answer", text.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(ModelEvent.Completed, text.last())

        val refusal = responsesEvents(
            """data: {"type":"response.refusal.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"cannot help"}""",
            "data: [DONE]",
        )
        assertEquals("cannot help", refusal.filterIsInstance<ModelEvent.RefusalDelta>().joinToString("") { it.text })
        assertEquals(ModelEvent.Completed, refusal.last())
    }

    /**
     * The same length-stop evidence must be classified alike whether or not the
     * provider sent the transport end marker, and the usage must survive both.
     */
    @Test
    fun chatLengthStopIsClassifiedAlikeWithAndWithoutDoneMarker() = runTest {
        val frame =
            """data: {"choices":[{"delta":{"reasoning_content":"thinking"},"finish_reason":"length"}],"usage":{"prompt_tokens":9,"completion_tokens":100,"completion_tokens_details":{"reasoning_tokens":100}}}"""
        val withDone = chatEvents(frame, "data: [DONE]")
        val withoutDone = chatEvents(frame)

        assertEquals(ErrorCode.REASONING_EXHAUSTED.name, lastFailure(withDone))
        assertEquals(ErrorCode.REASONING_EXHAUSTED.name, lastFailure(withoutDone))
        assertEquals(withDone, withoutDone)
        assertEquals(100, withoutDone.filterIsInstance<ModelEvent.Usage>().single().reasoningTokens)
        assertEquals(1, withoutDone.filterIsInstance<ModelEvent.Usage>().size)
        assertTrue(withoutDone.none { it == ModelEvent.Completed }, withoutDone.toString())
    }

    /** A length stop that did produce answer text is still output truncation. */
    @Test
    fun chatLengthStopWithVisibleTextWithoutDoneMarkerIsOutputTruncated() = runTest {
        val events = chatEvents(
            """data: {"choices":[{"delta":{"content":"partial answer"},"finish_reason":"length"}]}""",
        )
        assertTrue(events.any { it is ModelEvent.TextDelta }, events.toString())
        assertEquals(ErrorCode.OUTPUT_TRUNCATED.name, lastFailure(events))
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
    }

    /**
     * A length stop with no output at all and no reported reasoning is an
     * unusable result, not a truncation of real content -- and it stays that way
     * whether or not the provider sent the transport end marker, with the same
     * usage settled exactly once.
     */
    @Test
    fun chatLengthStopWithNoOutputAtAllIsInvalidResponseWithAndWithoutDoneMarker() = runTest {
        val frame =
            """data: {"choices":[{"delta":{},"finish_reason":"length"}],"usage":{"prompt_tokens":9,"completion_tokens":32,"completion_tokens_details":{"reasoning_tokens":0}}}"""
        val withDone = chatEvents(frame, "data: [DONE]")
        val withoutDone = chatEvents(frame)

        assertEquals(ProviderConnectionErrorCode.INVALID_RESPONSE.name, lastFailure(withDone))
        assertEquals(ProviderConnectionErrorCode.INVALID_RESPONSE.name, lastFailure(withoutDone))
        assertEquals(withDone, withoutDone)
        val usage = withoutDone.filterIsInstance<ModelEvent.Usage>().single()
        assertEquals(9, usage.inputTokens)
        assertEquals(32, usage.outputTokens)
        assertEquals(0, usage.reasoningTokens)
        assertTrue(withoutDone.none { it == ModelEvent.Completed }, withoutDone.toString())
    }

    /**
     * A non-stream response the provider never declared complete is not an
     * answer: only its already-verified usage is kept, and no body text or tool
     * call from the unverified payload is released.
     */
    @Test
    fun responsesJsonUnknownStatusKeepsOnlyVerifiedUsage() = runTest {
        val body =
            """{"id":"resp_1","status":"cancelled","output":[{"type":"message","content":[{"type":"output_text","text":"SYNTHETIC_UNVERIFIED_BODY"}]}],"usage":{"input_tokens":8,"output_tokens":40,"output_tokens_details":{"reasoning_tokens":5}}}"""
        val events = responsesJson(body)

        val usage = events.filterIsInstance<ModelEvent.Usage>().single()
        assertEquals(8, usage.inputTokens)
        assertEquals(40, usage.outputTokens)
        assertEquals(5, usage.reasoningTokens)
        assertTrue(events.none { it is ModelEvent.TextDelta }, events.toString())
        assertTrue(events.none { it is ModelEvent.ToolCallDelta }, events.toString())
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
        assertEquals(ErrorCode.UNKNOWN_OUTCOME.name, lastFailure(events))
    }

    /**
     * A structurally perfect function call inside a response the provider never
     * declared complete is still not released: only the verified usage survives
     * the unknown status.
     */
    @Test
    fun responsesJsonUnknownStatusDoesNotReleaseLegalFunctionCall() = runTest {
        val body =
            """{"id":"resp_2","status":"in_progress","output":[{"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{\"city\":\"Paris\"}"}],"usage":{"input_tokens":5,"output_tokens":7}}"""
        val events = responsesJson(body)

        val usage = events.filterIsInstance<ModelEvent.Usage>().single()
        assertEquals(5, usage.inputTokens)
        assertEquals(7, usage.outputTokens)
        assertNull(usage.reasoningTokens)
        assertTrue(events.none { it is ModelEvent.ToolCallDelta }, events.toString())
        assertTrue(events.none { it is ModelEvent.TextDelta }, events.toString())
        assertTrue(events.none { it == ModelEvent.Completed }, events.toString())
        assertEquals(ErrorCode.UNKNOWN_OUTCOME.name, lastFailure(events))
    }
}
