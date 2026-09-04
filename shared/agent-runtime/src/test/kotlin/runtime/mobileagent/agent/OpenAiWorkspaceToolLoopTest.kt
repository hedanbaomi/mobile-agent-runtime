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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.openai.OpenAiAdapterFactory
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/** Proves both OpenAI wire protocols enter the same runtime-owned workspace tool loop. */
class OpenAiWorkspaceToolLoopTest {
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
