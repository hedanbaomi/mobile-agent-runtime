// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonPrimitive
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.provider.RequestHeaderValue

class OpenAiSseTest {
    @Test
    fun textDeltaAndDone() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val delta = OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            buf,
        )
        val done = OpenAiSse.eventsFromLine("data: [DONE]", buf)
        assertEquals(listOf(ModelEvent.TextDelta("Hello")), delta)
        assertEquals(listOf(ModelEvent.Completed), done)
    }

    @Test
    fun interleavedToolIndexesDoNotCrossAppend() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val indexToId = mutableMapOf<Int, String>()
        OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-a","function":{"name":"a","arguments":"{\"x\":"}}]}}]}""",
            buf,
            indexToId = indexToId,
        )
        OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call-b","function":{"name":"b","arguments":"{\"y\":1}"}}]}}]}""",
            buf,
            indexToId = indexToId,
        )
        OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""",
            buf,
            indexToId = indexToId,
        )
        val done = OpenAiSse.eventsFromLine("data: [DONE]", buf, indexToId = indexToId)
        val calls = done.filterIsInstance<ModelEvent.ToolCallDelta>()
        assertEquals("call-a", calls[0].callId)
        assertEquals("""{"x":1}""", calls[0].argumentsJson)
        assertEquals("call-b", calls[1].callId)
        assertEquals("""{"y":1}""", calls[1].argumentsJson)
        assertEquals(ModelEvent.Completed, done.last())
    }

    @Test
    fun commentsAreIgnored() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        assertTrue(OpenAiSse.eventsFromLine(": keep-alive", buf).isEmpty())
    }

    @Test
    fun errorMessageIsRedactedWithProvidedSecret() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val secret = "synthetic-provider-token-12345"
        val events = OpenAiSse.eventsFromLine(
            """data: {"error":{"message":"Invalid credential: $secret"}}""",
            buf,
            listOf(secret),
        )
        val failed = events.single() as ModelEvent.Failed
        assertFalse(failed.sanitizedMessage.contains(secret))
        assertTrue(failed.sanitizedMessage.contains("***"))
    }
}

class OpenAiCompatibleAdapterTest {
    @Test
    fun streamsMockedSseWithoutLeakingSecret() = runTest {
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            assertTrue(auth!!.startsWith("Bearer "))
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "test-secret-token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.TextDelta("Hi"), ModelEvent.Completed), events)
    }

    @Test
    fun redactsPrimarySecretAcrossTextDeltasAndPreservesNormalText() = runTest {
        val secret = "main-secret-token"
        val chunks = listOf("before-", "main-", "secret-", "token", "-after")
        val engine = MockEngine {
            val body = buildString {
                chunks.forEach { chunk ->
                    append("data: {\"choices\":[{\"delta\":{\"content\":\"$chunk\"}}]}\n\n")
                }
                append("data: [DONE]\n\n")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            secret.toCharArray(),
        ).toList()
        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("before-"))
        assertTrue(text.contains("-after"))
        assertTrue(text.contains("***"))
        assertFalse(text.contains(secret))
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun redactsCustomSecretHeaderAcrossTextDeltas() = runTest {
        val secret = "custom-header-secret"
        val chunks = listOf("left-", "custom-", "header-", "secret", "-right")
        var seenHeader = ""
        val engine = MockEngine { request ->
            seenHeader = request.headers["X-Trace"].orEmpty()
            val body = buildString {
                chunks.forEach { chunk ->
                    append("data: {\"choices\":[{\"delta\":{\"content\":\"$chunk\"}}]}\n\n")
                }
                append("data: [DONE]\n\n")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val adapter = OpenAiCompatibleAdapter(
            HttpClient(engine),
            "https://example.invalid/v1",
            headerSecretResolver = { host, ref ->
                assertEquals("example.invalid", host)
                assertEquals("trace-ref", ref)
                secret.toCharArray()
            },
        )
        val events = adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                headers = mapOf("X-Trace" to RequestHeaderValue.SecretRef("trace-ref")),
            ),
            "primary-token".toCharArray(),
        ).toList()
        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals(secret, seenHeader)
        assertTrue(text.contains("left-"))
        assertTrue(text.contains("-right"))
        assertTrue(text.contains("***"))
        assertFalse(text.contains(secret))
    }

    @Test
    fun incompleteCredentialPrefixIsDiscardedOnEof() = runTest {
        val secret = "main-secret-token"
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"safe-main-secr\"}}]}\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            secret.toCharArray(),
        ).toList()
        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("safe-", text)
        assertFalse(text.contains("main-secr"))
        assertEquals(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name), events.last())
    }

    @Test
    fun redactsSecretsInCompleteJsonContent() = runTest {
        val primary = "primary-json-secret"
        val custom = "custom-json-secret"
        val engine = MockEngine {
            respond(
                content = """{"choices":[{"message":{"content":"before $primary middle $custom after"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(
            HttpClient(engine),
            "https://example.invalid/v1",
            headerSecretResolver = { _, _ -> custom.toCharArray() },
        )
        val events = adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                headers = mapOf("X-Trace" to RequestHeaderValue.SecretRef("trace-ref")),
            ),
            primary.toCharArray(),
        ).toList()
        val text = events.filterIsInstance<ModelEvent.TextDelta>().single().text
        assertTrue(text.contains("before"))
        assertTrue(text.contains("after"))
        assertFalse(text.contains(primary))
        assertFalse(text.contains(custom))
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun rejectsToolArgumentsContainingCredentialsWithoutEmittingCall() = runTest {
        val secret = "tool-secret-value"
        val engine = MockEngine {
            respond(
                content = """{"choices":[{"message":{"content":"safe","tool_calls":[{"id":"call-1","type":"function","function":{"name":"send","arguments":"{\"value\":\"$secret\"}"}}]}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            secret.toCharArray(),
        ).toList()
        assertTrue(events.none { it is ModelEvent.ToolCallDelta })
        assertEquals(listOf(ModelEvent.Failed(ErrorCode.UNKNOWN_OUTCOME.name)), events)
        assertTrue(events.none { it.toString().contains(secret) })
    }

    @Test
    fun unauthorizedIsMapped() = runTest {
        val engine = MockEngine {
            respond("nope", HttpStatusCode.Unauthorized)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "sk-testsecretvalue".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed("PROVIDER_UNAUTHORIZED")), events)
    }

    @Test
    fun incompleteStreamIsNotCompleted() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "test-secret-token".toCharArray(),
        ).toList()
        assertEquals(ModelEvent.TextDelta("partial"), events.first())
        assertEquals(ModelEvent.Failed("UNKNOWN_OUTCOME"), events.last())
        assertTrue(events.none { it is ModelEvent.Completed })
    }

    @Test
    fun errorFrameIsNotFollowedByCompleted() = runTest {
        val secret = "fixture-secret-token"
        val engine = MockEngine {
            respond(
                content = "data: {\"error\":{\"message\":\"fixture rejected $secret\"}}\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            secret.toCharArray(),
        ).toList()
        assertEquals(1, events.size)
        val failed = events.single() as ModelEvent.Failed
        assertFalse(failed.sanitizedMessage.contains(secret))
        assertTrue(events.none { it is ModelEvent.Completed })
    }

    @Test
    fun emptyHttp200IsUnknownOutcome() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "test-secret-token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed("UNKNOWN_OUTCOME")), events)
    }

    @Test
    fun requestBodyEncodesAssistantToolCallsAndImages() = runTest {
        var captured = ""
        val engine = MockEngine { request ->
            captured = when (val body = request.body) {
                is io.ktor.http.content.TextContent -> body.text
                else -> body.toString()
            }
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(
                    ChatMessage(role = "assistant", toolCalls = listOf(
                        runtime.mobileagent.provider.AssistantToolCall("t1", "calculator", """{"expression":"1"}"""),
                    )),
                    ChatMessage(role = "tool", text = "ok", toolCallId = "t1"),
                    ChatMessage(
                        role = "user",
                        text = "see",
                        images = listOf(runtime.mobileagent.provider.InlineImage("image/png", "QQ==", "a1")),
                    ),
                ),
            ),
            "test-secret-token".toCharArray(),
        ).toList()
        assertTrue(captured.contains("\"tool_calls\""))
        assertTrue(captured.contains("\"tool_call_id\":\"t1\""))
        assertTrue(captured.contains("image_url"))
        assertTrue(captured.contains("data:image/png;base64,QQ=="))
    }

    @Test
    fun parameterLayersAndSecretRefHeadersReachWireButReservedFieldsDoNot() = runTest {
        var captured = ""
        var resolvedHost = ""
        val engine = MockEngine { request ->
            captured = (request.body as io.ktor.http.content.TextContent).text
            assertEquals("resolved-header", request.headers["X-Trace"])
            respond("data: [DONE]\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val adapter = OpenAiCompatibleAdapter(
            HttpClient(engine),
            "https://example.invalid/v1",
            headerSecretResolver = { host, ref ->
                resolvedHost = "$host:$ref"
                "resolved-header".toCharArray()
            },
        )
        adapter.stream(
            ModelRequest(
                modelId = "real-model",
                messages = listOf(ChatMessage(role = "user", text = "hello")),
                parameters = ParameterLayers(
                    adapterDefaults = mapOf("temperature" to JsonPrimitive(0.1)),
                    modelParameters = mapOf("top_p" to JsonPrimitive(0.8)),
                    agentOverrides = mapOf("temperature" to JsonPrimitive(0.2)),
                    customJson = "{\"max_tokens\":12}",
                ),
                headers = mapOf("X-Trace" to RequestHeaderValue.SecretRef("trace-ref")),
            ),
            "token".toCharArray(),
        ).toList()
        assertEquals("example.invalid:trace-ref", resolvedHost)
        assertTrue(captured.contains("\"temperature\":0.2"))
        assertTrue(captured.contains("\"top_p\":0.8"))
        assertTrue(captured.contains("\"max_tokens\":12"))
        assertTrue(captured.contains("\"model\":\"real-model\""))
        assertTrue(!captured.contains("authorization"))
    }

    @Test
    fun outputTokenLimitIsInjectedAfterParameterMerge() = runTest {
        var captured = ""
        val engine = MockEngine { request ->
            captured = (request.body as io.ktor.http.content.TextContent).text
            respond("data: [DONE]\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                outputTokenLimit = 17,
            ),
            "token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Completed), events)
        assertTrue(captured.contains("\"max_tokens\":17"))
    }

    @Test
    fun outputTokenLimitRejectsInvalidOverridesBeforeNetwork() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            respond("data: [DONE]\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                outputTokenLimit = 10,
                parameters = ParameterLayers(customJson = "{\"max_tokens\":11}"),
            ),
            "token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed(ErrorCode.INVALID_CONFIG.name)), events)
        assertEquals(0, requests)

        val bothEvents = adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                outputTokenLimit = 10,
                parameters = ParameterLayers(customJson = "{\"max_tokens\":4,\"max_completion_tokens\":4}"),
            ),
            "token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed(ErrorCode.INVALID_CONFIG.name)), bothEvents)
        assertEquals(0, requests)
    }

    @Test
    fun embeddingsUseExactEndpointAndReorderByUniqueIndex() = runTest {
        var path = ""
        var body = ""
        var seenHeader = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            body = (request.body as io.ktor.http.content.TextContent).text
            seenHeader = request.headers["X-Embedding-Trace"].orEmpty()
            respond(
                content = """{"object":"list","data":[{"index":1,"embedding":[0.4,0.5,0.6]},{"index":0,"embedding":[0.1,0.2,0.3]}],"model":"embed-v1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(
            HttpClient(engine),
            "https://example.invalid/v1",
            headerSecretResolver = { host, ref ->
                assertEquals("example.invalid", host)
                assertEquals("embed-ref", ref)
                "embedding-header-secret".toCharArray()
            },
            defaultHeaders = mapOf("X-Embedding-Trace" to RequestHeaderValue.SecretRef("embed-ref")),
        )
        val result = adapter.embed(
            EmbeddingRequest("embed-v1", listOf("one", "two")),
            "embedding-token".toCharArray(),
        )
        assertEquals("/v1/embeddings", path)
        assertEquals("{\"model\":\"embed-v1\",\"input\":[\"one\",\"two\"]}", body)
        assertEquals("embedding-header-secret", seenHeader)
        assertEquals(3, result.dimension)
        assertEquals(listOf(0.1f, 0.2f, 0.3f), result.vectors[0].toList())
        assertEquals(listOf(0.4f, 0.5f, 0.6f), result.vectors[1].toList())
    }

    @Test
    fun malformedEmbeddingIndexesAndDimensionsAreUnknownOutcome() = runTest {
        val malformedBodies = listOf(
            """{"data":[{"index":0,"embedding":[0.1,0.2]},{"index":0,"embedding":[0.3,0.4]}]}""",
            """{"data":[{"index":0,"embedding":[0.1,0.2]},{"index":1,"embedding":[0.3]}]}""",
            """{"data":[{"index":0,"embedding":["not-a-number"]},{"index":1,"embedding":[0.3]}]}""",
        )
        malformedBodies.forEach { malformed ->
            val engine = MockEngine {
                respond(malformed, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
            val failure = try {
                adapter.embed(EmbeddingRequest("embed-v1", listOf("one", "two")), "token".toCharArray())
                null
            } catch (error: AppException) {
                error
            }
            assertEquals(ErrorCode.UNKNOWN_OUTCOME, failure?.error?.code)
        }
    }

    @Test
    fun invalidEmbeddingBatchIsRejectedBeforeNetwork() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            respond("{}", HttpStatusCode.OK)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val failure = try {
            adapter.embed(EmbeddingRequest("embed-v1", emptyList()), "token".toCharArray())
            null
        } catch (error: AppException) {
            error
        }
        assertEquals(ErrorCode.INVALID_CONFIG, failure?.error?.code)
        assertEquals(0, requests)
    }

    @Test
    fun previewUsesWireBuilderAndRedactsInlineImageBytes() = runTest {
        val engine = MockEngine {
            respond("data: [DONE]\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val preview = adapter.previewRequest(
            ModelRequest(
                modelId = "demo",
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        text = "see",
                        images = listOf(runtime.mobileagent.provider.InlineImage("image/png", "SECRET_BYTES", "asset-1")),
                    ),
                ),
                parameters = ParameterLayers(customJson = "{\"temperature\":0.3}"),
            ),
        )
        assertTrue(preview.contains("asset-1"))
        assertTrue(preview.contains("redacted-image"))
        assertTrue("SECRET_BYTES" !in preview)
        assertTrue(preview.contains("temperature"))
    }

    @Test
    fun liveProbeRequiresConsentAndUsesMetadataOnlyEndpoint() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests += 1
            assertTrue(request.url.encodedPath.endsWith("/models/demo"))
            respond("{}", HttpStatusCode.OK)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val profile = ModelProfile(
            id = "profile-1",
            providerId = "provider-1",
            modelId = "demo",
            role = ModelRole.CHAT,
            capabilities = setOf("stream"),
            contextLimit = 4096,
            outputLimit = 1024,
            revision = 1,
        )
        val noProbe = adapter.probe(profile, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.NOT_GRANTED, "p1")
        assertEquals(0, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.PROFILE_ONLY, noProbe.status)
        val live = adapter.probe(profile, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED, "p2")
        assertEquals(1, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.SUCCEEDED, live.status)
        assertTrue(!live.charged)
    }
}
