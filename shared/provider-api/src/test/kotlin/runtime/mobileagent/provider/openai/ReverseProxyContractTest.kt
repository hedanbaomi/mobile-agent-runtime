// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.*

/**
 * Synthetic wire examples reconstructed from the pinned gateway contracts in
 * docs/evidence/2026-10-15/proxy-provider-compatibility.md, not live gateway acceptance.
 */
class ReverseProxyContractTest {
    private val base = "http://gateway.example.invalid:38080/proxy/v1"
    private val model = "public/fixture-model"
    private val key = "SYNTHETIC_GATEWAY_KEY_NOT_A_CREDENTIAL"

    @Test
    fun gatewayRoutesPreservePrefixAliasImageAndProxyBearerKey() = runBlocking {
        for (format in ApiFormat.entries) {
            var calls = 0
            HttpClient(MockEngine { request ->
                calls++
                assertEquals("http", request.url.protocol.name)
                assertEquals(38080, request.url.port)
                assertEquals("Bearer $key", request.headers[HttpHeaders.Authorization])
                val path = if (format == ApiFormat.OPENAI_COMPATIBLE) "/chat/completions" else "/responses"
                assertEquals("/proxy/v1$path", request.url.encodedPath)
                val body = (request.body as TextContent).text
                assertEquals(model, Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content)
                assertTrue(body.contains("data:image/png;base64,AA=="))
                respond(jsonAnswer(format), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }).use { http ->
                val result = OpenAiAdapterFactory.create(format, http, base).stream(
                    ModelRequest(model, listOf(ChatMessage("user", "hello", images = listOf(InlineImage("image/png", "AA==")))), stream = false),
                    key.toCharArray(),
                ).toList()
                assertEquals("proxy answer", result.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
                assertEquals(1, result.count { it is ModelEvent.Completed })
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun chatGatewayKeepaliveReasoningToolFragmentsAndUsageOnlyFrameStayDistinct() = runBlocking {
        val body = ": ping\r\n\r\n" + frames(
            """{"choices":[{"delta":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"private reasoning","content":"answer"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_proxy","function":{"name":"weather","arguments":"{\"city\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":4,"total_tokens":15},"proxy_cost":0.0}""",
            """{"choices":[]}""",
            "[DONE]",
        )
        val result = stream(ApiFormat.OPENAI_COMPATIBLE, body)
        assertEquals("answer", result.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("private reasoning", result.filterIsInstance<ModelEvent.ReasoningDelta>().single().text)
        assertEquals(ModelEvent.ToolCallDelta("call_proxy", "weather", """{"city":"Paris"}"""),
            result.filterIsInstance<ModelEvent.ToolCallDelta>().single())
        assertEquals(11, result.filterIsInstance<ModelEvent.Usage>().single().inputTokens)
        assertEquals(4, result.filterIsInstance<ModelEvent.Usage>().single().outputTokens)
        assertEquals(1, result.count { it is ModelEvent.Completed })
    }

    @Test
    fun chatGatewayErrorAfterPartialTextNeverBecomesSuccessOrReleasesUnfinishedTool() = runBlocking {
        val result = stream(ApiFormat.OPENAI_COMPATIBLE, frames(
            """{"choices":[{"delta":{"content":"partial","tool_calls":[{"index":0,"id":"pending","function":{"name":"weather","arguments":"{"}}]}}]}""",
            """{"error":{"message":"proxy failure $key","type":null,"param":null,"code":"500"}}""",
            "[DONE]",
        ))
        assertEquals(1, result.count { it is ModelEvent.Failed })
        assertFalse(result.any { it is ModelEvent.Completed || it is ModelEvent.ToolCallDelta })
        assertFalse(result.toString().contains(key))
    }

    @Test
    fun responsesGatewayNamedEventsAndDoneOnlyToolArgumentsAreAcceptedOnce() = runBlocking {
        val body = ": ping\n\n" +
            "event: response.created\n" + frames("""{"type":"response.created","response":{"id":"resp_proxy","status":"in_progress"}}""") +
            "event: response.output_text.delta\n" + frames("""{"type":"response.output_text.delta","delta":"answer","output_index":0,"content_index":0}""") +
            frames(
                """{"type":"response.output_item.added","item":{"id":"fc_proxy","type":"function_call","call_id":"call_proxy","name":"weather","arguments":""}}""",
                """{"type":"response.function_call_arguments.done","item_id":"fc_proxy","arguments":"{\"city\":\"Paris\"}"}""",
                """{"type":"response.output_item.done","item":{"id":"fc_proxy","type":"function_call","call_id":"call_proxy","name":"weather","arguments":"{\"city\":\"Paris\"}"}}""",
                """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":11,"output_tokens":4,"total_tokens":15}}}""",
                "[DONE]",
            )
        val result = stream(ApiFormat.OPENAI_RESPONSES, body)
        assertEquals("answer", result.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, result.filterIsInstance<ModelEvent.ToolCallDelta>().size)
        assertEquals("""{"city":"Paris"}""", result.filterIsInstance<ModelEvent.ToolCallDelta>().single().argumentsJson)
        assertEquals(11, result.filterIsInstance<ModelEvent.Usage>().single().inputTokens)
        assertEquals(1, result.count { it is ModelEvent.Completed })
    }

    @Test
    fun responsesGatewayFailureAndIncompleteEventsCannotBeHiddenByDone() = runBlocking {
        for (terminal in listOf(
            """{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"proxy failure $key"}}}""",
            """{"type":"error","code":"server_error","message":"proxy failure $key","sequence_number":2}""",
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":11,"output_tokens":4}}}""",
        )) {
            val result = stream(ApiFormat.OPENAI_RESPONSES, frames(
                """{"type":"response.output_text.delta","delta":"partial"}""", terminal, "[DONE]",
            ))
            assertEquals(1, result.count { it is ModelEvent.Failed })
            assertFalse(result.any { it is ModelEvent.Completed })
            assertFalse(result.toString().contains(key))
        }
    }

    @Test
    fun oneApiMissingResponsesRouteFailsWithoutSwitchingProtocolOrReplaying() = runBlocking {
        var calls = 0
        HttpClient(MockEngine { request ->
            calls++
            assertEquals("/proxy/v1/responses", request.url.encodedPath)
            respond("""{"error":{"message":"Unknown endpoint","type":"invalid_request_error","code":"route_not_found"}}""",
                HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { http ->
            val profile = ModelProfile("profile", "provider", ModelRole.CHAT, model, emptySet(),
                contextLimit = 4096, outputLimit = 32, revision = 1)
            val result = OpenAiResponsesAdapter(http, base).testConnection(profile, key.toCharArray())
            assertTrue(result is ProviderConnectionResult.Failure)
            assertEquals(ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED, (result as ProviderConnectionResult.Failure).code)
            assertEquals(404, result.httpStatus)
            assertEquals(1, calls)
        }
    }

    @Test
    fun embeddingGatewayUsesIndependentEndpointWithTheConfiguredAliasAndBearer() = runBlocking {
        for (format in ApiFormat.entries) {
            var calls = 0
            HttpClient(MockEngine { request ->
                calls++
                assertEquals("/proxy/v1/embeddings", request.url.encodedPath)
                assertEquals("Bearer $key", request.headers[HttpHeaders.Authorization])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(model, body["model"]!!.jsonPrimitive.content)
                respond("""{"object":"list","model":"public/fixture-model","data":[{"object":"embedding","index":0,"embedding":[0.25,0.75]}],"usage":{"prompt_tokens":2,"total_tokens":2}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }).use { http ->
                val batch = OpenAiAdapterFactory.create(format, http, base).embed(EmbeddingRequest(model, listOf("text")), key.toCharArray())
                assertArrayEquals(floatArrayOf(0.25f, 0.75f), batch.vectors.single(), 0.0f)
                assertEquals(1, calls)
            }
        }
    }

    private suspend fun stream(format: ApiFormat, body: String): List<ModelEvent> =
        HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")) }).use { http ->
            OpenAiAdapterFactory.create(format, http, base).stream(ModelRequest(model, listOf(ChatMessage("user", "hello"))), key.toCharArray()).toList()
        }

    private fun frames(vararg data: String): String = data.joinToString("") { "data: $it\r\n\r\n" }

    private fun jsonAnswer(format: ApiFormat): String = if (format == ApiFormat.OPENAI_COMPATIBLE)
        """{"id":"chat_proxy","object":"chat.completion","choices":[{"message":{"role":"assistant","content":"proxy answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":4}}"""
    else """{"id":"resp_proxy","object":"response","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"proxy answer"}]}],"usage":{"input_tokens":11,"output_tokens":4}}"""
}
