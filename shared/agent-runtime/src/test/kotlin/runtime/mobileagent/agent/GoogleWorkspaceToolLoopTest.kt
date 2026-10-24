// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.openai.OpenAiAdapterFactory
import runtime.mobileagent.skills.*

/** The production runtime must carry signed Google content through its own tool boundary. */
class GoogleWorkspaceToolLoopTest {
    @Test fun emptyAndReasoningOnlyCompletionsUseDurableFailureLabels() = runBlocking {
        for ((parts, expected) in listOf(
            "[]" to "INVALID_RESPONSE",
            """[{"text":"brief thought summary","thought":true}]""" to "REASONING_ONLY",
        )) {
            val body = """{"candidates":[{"content":{"role":"model","parts":$parts},"finishReason":"STOP"}]}"""
            HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }).use { http ->
                val events = OpenAiAdapterFactory.create(ApiFormat.GOOGLE_AI_STUDIO, http, "https://example.invalid/v1beta")
                    .stream(runtime.mobileagent.provider.ModelRequest("gemini-fixture", listOf(runtime.mobileagent.provider.ChatMessage("user", "hello"))),
                        "synthetic".toCharArray()).toList()
                val code = (events.last() as ModelEvent.Failed).sanitizedMessage
                assertEquals(expected, code)
                assertFalse(events.any { it is ModelEvent.Completed })
                assertNotEquals(runtime.mobileagent.domain.MessageErrorCode.INTERNAL, toSafeErrorPart(code).code)
            }
        }
    }

    @Test fun providerFailuresUseDurableRuntimeLabels() = runBlocking {
        for ((status, expected) in listOf(
            401 to "PROVIDER_UNAUTHORIZED", 403 to "PROVIDER_UNAUTHORIZED", 404 to "CONFIG_INVALID",
            408 to "TIMEOUT", 429 to "RATE_LIMITED", 503 to "PROVIDER_REJECTED",
        )) {
            HttpClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status)) }).use { http ->
                val events = OpenAiAdapterFactory.create(ApiFormat.GOOGLE_AI_STUDIO, http, "https://example.invalid/v1beta")
                    .stream(runtime.mobileagent.provider.ModelRequest("gemini-fixture", listOf(runtime.mobileagent.provider.ChatMessage("user", "hello"))),
                        "synthetic".toCharArray()).toList()
                val code = (events.last() as ModelEvent.Failed).sanitizedMessage
                assertEquals(expected, code)
                assertNotEquals(runtime.mobileagent.domain.MessageErrorCode.INTERNAL, toSafeErrorPart(code).code)
            }
        }
        HttpClient(MockEngine { error("No credentials must not dispatch") }).use { http ->
            val events = OpenAiAdapterFactory.create(ApiFormat.GOOGLE_AI_STUDIO, http, "https://example.invalid/v1beta")
                .stream(runtime.mobileagent.provider.ModelRequest("gemini-fixture", listOf(runtime.mobileagent.provider.ChatMessage("user", "hello"))), charArrayOf()).toList()
            assertEquals("SECRET_UNAVAILABLE", (events.last() as ModelEvent.Failed).sanitizedMessage)
        }
    }

    @Test fun nativeFormatsContinueSignedWorkspaceCallsWithoutExposingPrivateData() = runBlocking {
        for ((format, base) in listOf(
            ApiFormat.GOOGLE_AI_STUDIO to "https://example.invalid/v1beta",
            ApiFormat.GOOGLE_VERTEX_AI to "https://example.invalid/v1/projects/test-project/locations/global/publishers/google",
            ApiFormat.GOOGLE_VERTEX_EXPRESS to "https://example.invalid/v1/publishers/google",
        )) {
            val requests = mutableListOf<JsonObject>()
            val engine = MockEngine { req ->
                requests += Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                assertTrue(req.url.encodedPath.endsWith("/models/gemini-fixture:streamGenerateContent"))
                assertEquals("sse", req.url.parameters["alt"])
                assertEquals(if (format == ApiFormat.GOOGLE_VERTEX_AI) "Bearer synthetic-credential" else null, req.headers["Authorization"])
                assertEquals(if (format != ApiFormat.GOOGLE_VERTEX_AI) "synthetic-credential" else null, req.headers["x-goog-api-key"])
                val json = if (requests.size == 1) {
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"Inspecting files.","thoughtSignature":"synthetic-private-signature"},{"functionCall":{"id":"google-workspace-call","name":"workspace_list","args":{}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"thoughtsTokenCount":3}}"""
                } else {
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"workspace complete"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":4}}"""
                }
                respond("data: $json\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            var invoked = 0
            val executor = object : ToolExecutor {
                override val specs = listOf(ToolSpec("workspace_list", "List authorized metadata",
                    """{"type":"object","properties":{}}""", "workspace.enumerate", sideEffect = false))
                override suspend fun invoke(call: ToolCall): ToolResult {
                    invoked++
                    return ToolResult.Value("""{"entries":[{"path":"file.txt"}]}""")
                }
                override suspend fun approve(callId: String): ToolResult = error("No approval expected")
            }
            HttpClient(engine).use { http ->
                val run = AgentRun("google-run-${format.name}", "session", "conversation")
                val events = AgentRuntime(OpenAiAdapterFactory.create(format, http, base)).run(
                    AgentRuntimeRequest(run, EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "list files"),
                        "gemini-fixture", "synthetic-credential".toCharArray(), toolsEnabled = true,
                        executor = executor, emitRequestPreview = true),
                ).toList()
                assertEquals(RunState.COMPLETED, run.state, "$format: $events")
                assertEquals(1, invoked)
                assertEquals(2, requests.size)
                val parts = requests.last().getValue("contents").jsonArray.flatMap { it.jsonObject.getValue("parts").jsonArray }
                assertEquals(1, parts.count { it.jsonObject["thoughtSignature"]?.jsonPrimitive?.content == "synthetic-private-signature" })
                val response = parts.single { it.jsonObject.containsKey("functionResponse") }.jsonObject.getValue("functionResponse").jsonObject
                assertEquals("google-workspace-call", response.getValue("id").jsonPrimitive.content)
                assertEquals("workspace_list", response.getValue("name").jsonPrimitive.content)
                assertTrue(response.toString().contains("file.txt"))
                assertTrue(events.filterIsInstance<RuntimeEvent.ModelEvent>().none { it.event is ModelEvent.ProviderContinuation })
                assertFalse(events.toString().contains("synthetic-private-signature"))
                assertFalse(events.toString().contains("thoughtSignature"))
            }
        }
    }
}
