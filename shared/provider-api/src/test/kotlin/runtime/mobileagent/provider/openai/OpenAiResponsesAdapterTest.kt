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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.ProbeConsent
import runtime.mobileagent.provider.ProviderConnectionErrorCode

class OpenAiResponsesSseTest {
    @Test
    fun parsesTextLifecycleReasoningAndUnknownEventsWithoutCrashing() {
        val state = OpenAiResponsesSse.State()
        assertTrue(OpenAiResponsesSse.eventsFromLine("event: response.created", state).isEmpty())
        assertTrue(OpenAiResponsesSse.eventsFromLine("data: {\"type\":\"response.created\",\"response\":{}}", state).isEmpty())
        assertEquals(
            listOf(ModelEvent.ReasoningDelta("plan")),
            OpenAiResponsesSse.eventsFromLine("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"plan\"}", state),
        )
        assertEquals(
            listOf(ModelEvent.TextDelta("Hello")),
            OpenAiResponsesSse.eventsFromLine("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}", state),
        )
        // A text done marker is a lifecycle acknowledgement, not a second answer delta.
        assertTrue(
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.output_text.done\",\"text\":\"Hello\"}",
                state,
            ).isEmpty(),
        )
        assertTrue(OpenAiResponsesSse.eventsFromLine("data: {\"type\":\"response.custom.future_event\"}", state).isEmpty())
    }

    @Test
    fun doneEventsSupplyTextWhenProviderOmitsDeltas() {
        assertEquals(
            listOf(ModelEvent.TextDelta("complete answer")),
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_1\",\"content_index\":0,\"text\":\"complete answer\"}",
                OpenAiResponsesSse.State(),
            ),
        )
        assertEquals(
            listOf(ModelEvent.ReasoningDelta("complete summary")),
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"rs_1\",\"summary_text\":\"complete summary\"}",
                OpenAiResponsesSse.State(),
            ),
        )
    }

    @Test
    fun accumulatesFunctionArgumentsAndEmitsOneToolCallAtDone() {
        val state = OpenAiResponsesSse.State()
        OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"weather\",\"arguments\":\"\"}}",
            state,
        )
        OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"delta\":\"{\\\"city\\\":\"}",
            state,
        )
        OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"delta\":\"\\\"Paris\\\"}\"}",
            state,
        )
        val done = OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}",
            state,
        )
        assertEquals(listOf(ModelEvent.ToolCallDelta("call_1", "weather", "{\"city\":\"Paris\"}")), done)
        assertTrue(
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}",
                state,
            ).isEmpty(),
        )
    }

    @Test
    fun emitsCompletionUsageAndSanitizedFailure() {
        val state = OpenAiResponsesSse.State()
        val completed = OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}}",
            state,
        )
        assertEquals(listOf(ModelEvent.Usage(3, 2), ModelEvent.Completed), completed)
        val secret = "responses-secret"
        val failed = OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"error\",\"message\":\"bad $secret\"}",
            OpenAiResponsesSse.State(),
            listOf(secret),
        )
        assertEquals(1, failed.size)
        assertFalse((failed.single() as ModelEvent.Failed).sanitizedMessage.contains(secret))
        val nestedFailed = OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"nested failure\"}}}",
            OpenAiResponsesSse.State(),
        )
        assertEquals(listOf(ModelEvent.Failed("nested failure")), nestedFailed)
    }

    @Test
    fun functionArgumentsDoneCanIntroduceTheCallNameWithoutAddedItem() {
        val events = OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_2\",\"call_id\":\"call_2\",\"name\":\"lookup\",\"arguments\":\"{}\"}",
            OpenAiResponsesSse.State(),
        )
        assertEquals(listOf(ModelEvent.ToolCallDelta("call_2", "lookup", "{}")), events)
    }
}

class OpenAiResponsesAdapterTest {
    private fun profile(formatModel: String = "gpt-responses") = ModelProfile(
        id = "model.responses",
        providerId = "provider.responses",
        role = ModelRole.CHAT,
        modelId = formatModel,
        capabilities = setOf("stream", "tools", "image"),
        contextLimit = 4_096,
        outputLimit = 256,
        revision = 1,
    )

    @Test
    fun responsesUrlNormalizesRootSlashesAndExistingResponsesSuffix() {
        assertEquals("https://example.invalid/v1/responses", OpenAiResponsesAdapter.url("https://example.invalid/v1", "/responses"))
        assertEquals("https://example.invalid/v1/responses", OpenAiResponsesAdapter.url("https://example.invalid/v1/", "/responses"))
        assertEquals("https://example.invalid/v1/responses", OpenAiResponsesAdapter.url("https://example.invalid/v1/responses", "/responses"))
    }

    @Test
    fun factorySelectsNativeResponsesAdapterWithoutChangingCompatibleAdapter() {
        val http = HttpClient(MockEngine { error("network must not be called") })
        assertTrue(
            OpenAiAdapterFactory.create(ApiFormat.OPENAI_RESPONSES, http, "https://example.invalid/v1") is OpenAiResponsesAdapter,
        )
        assertTrue(
            OpenAiAdapterFactory.create(ApiFormat.OPENAI_COMPATIBLE, http, "https://example.invalid/v1") is OpenAiCompatibleAdapter,
        )
        assertEquals(
            "https://example.invalid/v1/responses",
            OpenAiAdapterFactory.requestEndpoint(ApiFormat.OPENAI_RESPONSES, "https://example.invalid/v1/responses"),
        )
        assertEquals(
            "https://example.invalid/v1/chat/completions",
            OpenAiAdapterFactory.requestEndpoint(ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1"),
        )
    }

    @Test
    fun normalizesMaxCompletionTokensAndRejectsConflictingOutputBudgets() {
        val adapter = OpenAiResponsesAdapter(HttpClient(MockEngine { error("network must not be called") }), "https://example.invalid/v1")
        val preview = adapter.previewRequest(
            ModelRequest(
                modelId = "gpt-responses",
                messages = listOf(ChatMessage("user", "hi")),
                stream = false,
                parameters = ParameterLayers(customJson = "{\"max_completion_tokens\":12}"),
            ),
        )
        assertTrue(preview.contains("\"max_output_tokens\":12"))
        assertFalse(preview.contains("max_completion_tokens"))
        assertThrows(AppException::class.java) {
            adapter.previewRequest(
                ModelRequest(
                    modelId = "gpt-responses",
                    messages = listOf(ChatMessage("user", "hi")),
                    stream = false,
                    parameters = ParameterLayers(customJson = "{\"max_tokens\":12,\"max_completion_tokens\":12}"),
                ),
            )
        }
    }

    @Test
    fun embeddingsRemainOnEmbeddingsEndpointWhenBaseIncludesResponses() = runTest {
        var path = ""
        var body = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            body = (request.body as io.ktor.http.content.TextContent).text
            respond(
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1/responses")
            .embed(EmbeddingRequest("text-embedding-3-small", listOf("hello")), "token".toCharArray())
        assertEquals("/v1/embeddings", path)
        assertTrue(body.contains("\"model\":\"text-embedding-3-small\""))
        assertEquals(1, result.vectors.size)
        assertEquals(2, result.dimension)
    }

    @Test
    fun postsResponsesPayloadToResponsesEndpointAndMapsInputToolsAndParameters() = runTest {
        var path = ""
        var body = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            body = (request.body as io.ktor.http.content.TextContent).text
            respond(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiResponsesAdapter(HttpClient(engine), "https://api.openai.com/v1/")
        val events = adapter.stream(
            ModelRequest(
                modelId = "gpt-responses",
                messages = listOf(
                    ChatMessage("system", "You are concise."),
                    ChatMessage("user", "Look", images = listOf(InlineImage("image/png", "QQ=="))),
                    ChatMessage("assistant", toolCalls = listOf(AssistantToolCall("call_1", "weather", "{}"))),
                    ChatMessage("tool", "{\"temperature\":20}", toolCallId = "call_1"),
                ),
                tools = listOf(mapOf("name" to "weather", "description" to "Get weather", "parameters" to "{\"type\":\"object\"}")),
                parameters = ParameterLayers(modelParameters = mapOf("temperature" to kotlinx.serialization.json.JsonPrimitive(0.2))),
                extra = mapOf("top_p" to 0.8),
                outputTokenLimit = 123,
            ),
            "test-token".toCharArray(),
        ).toList()
        assertEquals("/v1/responses", path)
        assertTrue(body.contains("\"instructions\":\"You are concise.\""))
        assertTrue(body.contains("\"input\""))
        assertTrue(body.contains("input_image"))
        assertTrue(body.contains("function_call_output"))
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"temperature\":0.2"))
        assertTrue(body.contains("\"top_p\":0.8"))
        assertTrue(body.contains("\"max_output_tokens\":123"))
        assertFalse(body.contains("chat/completions"))
        assertEquals("ok", events.filterIsInstance<ModelEvent.TextDelta>().single().text)
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun testConnectionUsesResponsesAndDistinguishesEndpointModelAuthAndMalformed() = runBlocking {
        val responses = listOf(
            Triple(HttpStatusCode.NotFound, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"model missing\"}}", ProviderConnectionErrorCode.MODEL_NOT_FOUND),
            Triple(HttpStatusCode.NotFound, "{\"error\":{\"message\":\"route is not found\"}}", ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED),
            Triple(HttpStatusCode.Unauthorized, "unauthorized", ProviderConnectionErrorCode.AUTH_FAILED),
        )
        responses.forEach { (status, body, expected) ->
            var path = ""
            val engine = MockEngine { request ->
                path = request.url.encodedPath
                respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val result = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1").testConnection(
                profile(),
                "token".toCharArray(),
            ) as runtime.mobileagent.provider.ProviderConnectionResult.Failure
            assertEquals("/v1/responses", path)
            assertEquals(expected, result.code)
        }
        val malformedEngine = MockEngine { respond("{not-json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }
        val malformed = OpenAiResponsesAdapter(HttpClient(malformedEngine), "https://example.invalid/v1").testConnection(
            profile(),
            "token".toCharArray(),
        ) as runtime.mobileagent.provider.ProviderConnectionResult.Failure
        assertEquals(ProviderConnectionErrorCode.INVALID_RESPONSE, malformed.code)
    }

    @Test
    fun capabilityProbeClassifiesResponsesEndpointModelAndNetworkFailures() = runBlocking {
        val endpointEngine = MockEngine {
            respond(
                "{\"error\":{\"message\":\"route is not found\"}}",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val endpoint = OpenAiResponsesAdapter(HttpClient(endpointEngine), "https://example.invalid/v1")
            .probe(profile(), "token".toCharArray(), ProbeConsent.GRANTED)
        assertTrue(endpoint.source.contains("metadata=endpoint-unsupported"))
        assertEquals(CapabilityCheckStatus.UNSUPPORTED, endpoint.checks.single { it.capability == CapabilityCheck.METADATA }.status)

        val modelEngine = MockEngine {
            respond(
                "{\"error\":{\"code\":\"model_not_found\",\"message\":\"model missing\"}}",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val model = OpenAiResponsesAdapter(HttpClient(modelEngine), "https://example.invalid/v1")
            .probe(profile(), "token".toCharArray(), ProbeConsent.GRANTED)
        assertTrue(model.source.contains("metadata=model-not-found"))

        val networkEngine = MockEngine { throw java.io.IOException("offline") }
        val network = OpenAiResponsesAdapter(HttpClient(networkEngine), "https://example.invalid/v1")
            .probe(profile(), "token".toCharArray(), ProbeConsent.GRANTED)
        assertTrue(network.source.contains("metadata=network-unreachable"))
    }

    @Test
    fun capabilityProbeForcesTheDeclaredNoOpToolInsteadOfRelyingOnModelChoice() = runBlocking {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = (request.body as io.ktor.http.content.TextContent).text
            bodies += body
            when {
                body.contains("\"tool_choice\"") -> respond(
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc_probe\",\"call_id\":\"call_probe\",\"name\":\"mar_probe_noop\",\"arguments\":\"{}\"}]}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                body.contains("\"stream\":true") -> respond(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
                else ->
                respond(
                    "{\"status\":\"completed\",\"output_text\":\"ok\"}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }

        val report = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1")
            .probe(
                profile().copy(capabilities = setOf("tools")),
                "token".toCharArray(),
                ProbeConsent.GRANTED,
            )

        val toolProbeBody = bodies.single { it.contains("\"tool_choice\"") }
        assertTrue(
            toolProbeBody.contains(
                "\"tool_choice\":{\"type\":\"function\",\"name\":\"mar_probe_noop\"}",
            ),
        )
        assertTrue(report.supportsTools)
        assertEquals(CapabilityCheckStatus.VERIFIED, report.checks.single { it.capability == CapabilityCheck.TOOLS }.status)
    }

    @Test
    fun streamMapsProviderErrorAndMissingTerminalToSafeEvents() = runTest {
        val errorEngine = MockEngine {
            respond(
                "data: {\"type\":\"error\",\"message\":\"provider failed\"}\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val errorEvents = OpenAiResponsesAdapter(HttpClient(errorEngine), "https://example.invalid/v1").stream(
            ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed("provider failed")), errorEvents)

        val incompleteEngine = MockEngine {
            respond(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val incomplete = OpenAiResponsesAdapter(HttpClient(incompleteEngine), "https://example.invalid/v1").stream(
            ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name), incomplete.last())
    }

    @Test
    fun storeDefaultsFalseAndAllowsExplicitBooleanOverride() {
        val adapter = OpenAiResponsesAdapter(HttpClient(MockEngine { error("network must not be called") }), "https://example.invalid/v1")
        val base = ModelRequest(modelId = "gpt-responses", messages = listOf(ChatMessage("user", "hi")), stream = false)
        assertTrue(adapter.previewRequest(base).contains("\"store\":false"))
        assertTrue(adapter.previewRequest(base).contains("\"include\":[\"reasoning.encrypted_content\"]"))

        val explicit = base.copy(parameters = ParameterLayers(customJson = "{\"store\":true}"))
        val explicitPreview = adapter.previewRequest(explicit)
        assertTrue(explicitPreview.contains("\"store\":true"))
        assertFalse(explicitPreview.contains("reasoning.encrypted_content"))

        val customInclude = base.copy(parameters = ParameterLayers(customJson = "{\"include\":[\"code_interpreter_call.outputs\"]}"))
        val customPreview = adapter.previewRequest(customInclude)
        assertTrue(customPreview.contains("code_interpreter_call.outputs"))
        assertFalse(customPreview.contains("reasoning.encrypted_content"))

        assertThrows(AppException::class.java) {
            adapter.previewRequest(base.copy(parameters = ParameterLayers(customJson = "{\"store\":\"yes\"}")))
        }
        assertThrows(AppException::class.java) {
            adapter.previewRequest(base.copy(parameters = ParameterLayers(customJson = "{\"store\":1}")))
        }
    }

    @Test
    fun refusalStreamingAndNonStreamingSurfaceAsRefusal() {
        val state = OpenAiResponsesSse.State()
        assertEquals(
            listOf(ModelEvent.RefusalDelta("I cannot")),
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.refusal.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"I cannot\"}",
                state,
            ),
        )
        // The done marker only supplies the missing tail, like text and reasoning.
        assertEquals(
            listOf(ModelEvent.RefusalDelta(" help.")),
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.refusal.done\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"refusal\":\"I cannot help.\"}",
                state,
            ),
        )
    }

    @Test
    fun reasoningTextEventsSurfaceAsReasoningWithoutFabrication() {
        assertEquals(
            listOf(ModelEvent.ReasoningDelta("thinking")),
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"thinking\"}",
                OpenAiResponsesSse.State(),
            ),
        )
        assertEquals(
            listOf(ModelEvent.ReasoningDelta("done thinking")),
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.reasoning_text.done\",\"item_id\":\"rs_1\",\"text\":\"done thinking\"}",
                OpenAiResponsesSse.State(),
            ),
        )
    }

    @Test
    fun completedReasoningItemIsCapturedAsPrivateContinuation() {
        val state = OpenAiResponsesSse.State()
        val added = OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}",
            state,
        )
        assertTrue(added.isEmpty())
        val done = OpenAiResponsesSse.eventsFromLine(
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc-blob\"}}",
            state,
        )
        assertEquals(1, done.size)
        val continuation = done.single() as ModelEvent.ProviderContinuation
        assertEquals("rs_1", continuation.item.itemId)
        assertEquals("enc-blob", continuation.item.encryptedContent)
        // Replays and items without payload never surface.
        assertTrue(
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc-blob\"}}",
                state,
            ).isEmpty(),
        )
        assertTrue(
            OpenAiResponsesSse.eventsFromLine(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_2\"}}",
                OpenAiResponsesSse.State(),
            ).isEmpty(),
        )
    }

    @Test
    fun continuationIsReplayedOnNextRoundButAbsentFromPreview() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            respond(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_9\",\"encrypted_content\":\"enc-9\"}}\n\n" +
                    "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_9\",\"call_id\":\"call-9\",\"name\":\"lookup\",\"arguments\":\"{}\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1")
        val first = adapter.stream(
            ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))),
            "token".toCharArray(),
        ).toList()
        val continuation = first.filterIsInstance<ModelEvent.ProviderContinuation>().single()
        assertEquals("enc-9", continuation.item.encryptedContent)

        val next = ModelRequest(
            "gpt-responses",
            listOf(
                ChatMessage("user", "hi"),
                ChatMessage(
                    "assistant",
                    toolCalls = listOf(AssistantToolCall("call-9", "lookup", "{}")),
                    providerContinuationItems = listOf(continuation.item),
                ),
                ChatMessage("tool", "{}", toolCallId = "call-9"),
            ),
        )
        val preview = adapter.previewRequest(next)
        // The include directive stays visible; the encrypted blob never does.
        assertTrue(preview.contains("\"include\":[\"reasoning.encrypted_content\"]"))
        assertFalse(preview.contains("enc-9"))
        assertFalse(preview.contains("\"type\":\"reasoning\""))

        adapter.stream(next, "token".toCharArray()).toList()
        assertEquals(2, bodies.size)
        val roundTwo = bodies.last()
        assertTrue(roundTwo.contains("\"type\":\"reasoning\""))
        assertTrue(roundTwo.contains("\"encrypted_content\":\"enc-9\""))
        assertTrue(roundTwo.contains("function_call_output"))
        assertTrue(roundTwo.contains("\"call_id\":\"call-9\""))
    }

    @Test
    fun nonStreamingRefusalAndReasoningItemsAreTyped() = runTest {
        val engine = MockEngine {
            respond(
                "{\"status\":\"completed\",\"output\":[" +
                    "{\"type\":\"reasoning\",\"id\":\"rs_3\",\"encrypted_content\":\"enc-3\"}," +
                    "{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"I must refuse.\"}]}" +
                    "]}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val events = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1").stream(
            ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi")), stream = false),
            "token".toCharArray(),
        ).toList()
        val continuation = events.filterIsInstance<ModelEvent.ProviderContinuation>().single()
        assertEquals("enc-3", continuation.item.encryptedContent)
        assertTrue(events.any { it is ModelEvent.RefusalDelta && it.text == "I must refuse." })
        assertTrue(events.none { it is ModelEvent.Failed })
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun testConnectionAcceptsCompletedRefusalAsProtocolSuccess() = runBlocking {
        val engine = MockEngine {
            respond(
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no.\"}]}]}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1").testConnection(
            profile(),
            "token".toCharArray(),
        )
        assertTrue(result is runtime.mobileagent.provider.ProviderConnectionResult.Success)
    }

    @Test
    fun probeOutputBudgetIsClampedFarBelowProfileLimit() = runBlocking {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            val body = bodies.last()
            if (body.contains("\"tool_choice\"")) {
                respond(
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc_p\",\"call_id\":\"c_p\",\"name\":\"mar_probe_noop\",\"arguments\":\"{}\"}]}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else if (body.contains("\"stream\":true")) {
                respond(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            } else {
                respond(
                    "{\"status\":\"completed\",\"output_text\":\"ok\"}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val adapter = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1")
        val big = profile().copy(outputLimit = 10_240)

        adapter.testConnection(big, "token".toCharArray())
        val connectionBody = bodies.single()
        assertTrue(connectionBody.contains("\"max_output_tokens\":64"), connectionBody)
        assertFalse(connectionBody.contains("10240"))
        bodies.clear()

        val report = adapter.probe(big, "token".toCharArray(), ProbeConsent.GRANTED)
        assertTrue(report.status == runtime.mobileagent.provider.CapabilityProbeStatus.SUCCEEDED)
        val toolBody = bodies.single { it.contains("\"tool_choice\"") }
        assertTrue(toolBody.contains("\"max_output_tokens\":128"), toolBody)
        bodies.filter { !it.contains("\"tool_choice\"") }.forEach {
            assertTrue(it.contains("\"max_output_tokens\":64"), it)
            assertFalse(it.contains("10240"))
        }
    }

    @Test
    fun probeDistinguishesRefusalFromMalformedResponse() = runBlocking {
        val engine = MockEngine {
            respond(
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no.\"}]}]}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val report = OpenAiResponsesAdapter(HttpClient(engine), "https://example.invalid/v1")
            .probe(profile(), "token".toCharArray(), ProbeConsent.GRANTED)
        assertTrue(report.source.contains("metadata=refusal"), report.source)
        assertFalse(report.source.contains("invalid-response"))
    }

    @Test
    fun nonStreamingTopLevelOutputTextAndHttpFailuresAreTyped() = runTest {
        val completeEngine = MockEngine {
            respond(
                "{\"status\":\"completed\",\"output_text\":\"top-level answer\"}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val complete = OpenAiResponsesAdapter(HttpClient(completeEngine), "https://example.invalid/v1").stream(
            ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi")), stream = false),
            "token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.TextDelta("top-level answer"), ModelEvent.Completed), complete)

        listOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError).forEach { status ->
            val errorEngine = MockEngine {
                respond("{\"error\":{\"message\":\"rejected\"}}", status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val failure = OpenAiResponsesAdapter(HttpClient(errorEngine), "https://example.invalid/v1").stream(
                ModelRequest("gpt-responses", listOf(ChatMessage("user", "hi"))),
                "token".toCharArray(),
            ).toList().single() as ModelEvent.Failed
            assertEquals(ProviderConnectionErrorCode.PROVIDER_REJECTED.name, failure.sanitizedMessage)
        }
    }
}
