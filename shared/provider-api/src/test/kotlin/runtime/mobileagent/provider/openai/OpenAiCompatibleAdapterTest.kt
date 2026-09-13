// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import java.net.SocketTimeoutException
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.CapabilityProbeStatus
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.ProviderConnectionErrorCode
import runtime.mobileagent.provider.ProviderConnectionResult
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
    fun explicitReasoningFieldsBecomeTypedDeltasWithoutInventingReasoning() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val canonical = OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"reasoning_content":"think"}}]}""",
            buf,
        )
        val alias = OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"reasoning":"also think","content":"answer"}}]}""",
            buf,
        )
        assertEquals(listOf(ModelEvent.ReasoningDelta("think")), canonical)
        assertEquals(
            listOf(ModelEvent.ReasoningDelta("also think"), ModelEvent.TextDelta("answer")),
            alias,
        )
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

    @Test
    fun usageOnlyFrameWithEmptyChoicesIsPreserved() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val events = OpenAiSse.eventsFromLine(
            """data: {"choices":[],"usage":{"prompt_tokens":8,"completion_tokens":2}}""",
            buf,
        )
        assertEquals(listOf(ModelEvent.Usage(8, 2)), events)
    }

    @Test
    fun cumulativeUsageSnapshotsRemainVisibleAcrossUsageOnlyFrames() = runTest {
        val engine = MockEngine {
            respond(
                content = buildString {
                    append("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n")
                    listOf(0, 1, 2, 2).forEach { output ->
                        append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":$output}}\n\n")
                    }
                    append("data: [DONE]\n\n")
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(
            listOf(ModelEvent.Usage(8, 2)),
            events.filterIsInstance<ModelEvent.Usage>(),
        )
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun contentFreeCompletionIsTerminalFailureAfterRetainingUsage() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(ModelEvent.Usage(8, 2), events.first())
        assertEquals(ModelEvent.Failed("INVALID_RESPONSE"), events.last())
        assertTrue(events.none { it == ModelEvent.Completed })
    }

    @Test
    fun reasoningOnlyCompletionIsNotPresentedAsSuccessfulAnswer() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertTrue(events.contains(ModelEvent.ReasoningDelta("thinking")))
        assertEquals(ModelEvent.Failed("INVALID_RESPONSE"), events.last())
        assertTrue(events.none { it == ModelEvent.Completed })
    }

    @Test
    fun toolCallOnlyCompletionRemainsSuccessfulForTheNextRuntimeRound() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\\\"1+1\\\"}\"}}]}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "calculate"))),
            "token".toCharArray(),
        ).toList()
        assertTrue(events.any { it is ModelEvent.ToolCallDelta })
        assertEquals(ModelEvent.Completed, events.last())
        assertTrue(events.none { it is ModelEvent.Failed })
    }

    @Test
    fun refusalOnlyCompletionRemainsReadableAndSuccessful() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"refusal\":\"cannot help\"}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.RefusalDelta("cannot help"), ModelEvent.Completed), events)
    }

    @Test
    fun finishReasonLengthIsContextOverflowAndRetainsLatestUsage() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2}}\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(ModelEvent.TextDelta("partial"), events[0])
        assertEquals(ModelEvent.Usage(8, 2), events[1])
        assertEquals(ModelEvent.Failed(ErrorCode.CONTEXT_OVERFLOW.name), events.last())
    }

    @Test
    fun finishReasonLengthDefersUntilTrailingUsageFrameAndDone() = runTest {
        val engine = MockEngine {
            respond(
                content = buildString {
                    append("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\n")
                    append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":3}}\n\n")
                    append("data: [DONE]\n\n")
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("partial"),
                ModelEvent.Usage(8, 3),
                ModelEvent.Failed(ErrorCode.CONTEXT_OVERFLOW.name),
            ),
            events,
        )
    }

    @Test
    fun sseErrorFrameRetainsUsageBeforeFailure() = runTest {
        val engine = MockEngine {
            respond(
                content = "data: {\"error\":{\"message\":\"provider rejected\"},\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(
            listOf(ModelEvent.Usage(5, 1), ModelEvent.Failed("provider rejected")),
            events,
        )
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
    fun redactsExplicitReasoningDeltasAndKeepsThemSeparateFromAnswer() = runTest {
        val secret = "reasoning-secret-token"
        val engine = MockEngine {
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$secret\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            secret.toCharArray(),
        ).toList()
        val reasoning = events.filterIsInstance<ModelEvent.ReasoningDelta>().joinToString("") { it.text }
        val answer = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(reasoning.contains("***"))
        assertFalse(reasoning.contains(secret))
        assertEquals("answer", answer)
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
    fun completeJsonReasoningFieldIsTypedAndNotFoldedIntoAnswer() = runTest {
        val engine = MockEngine {
            respond(
                content = "{\"choices\":[{\"message\":{\"reasoning\":\"think\",\"content\":\"answer\"}}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi"))),
            "token".toCharArray(),
        ).toList()
        assertEquals(ModelEvent.ReasoningDelta("think"), events[0])
        assertEquals(ModelEvent.TextDelta("answer"), events[1])
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
            respond(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
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
        assertEquals(listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed), events)
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
    fun liveProbeRequiresConsentAndVerifiesDeclaredStreamSemantics() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests += 1
            if (request.url.encodedPath.endsWith("/models/demo")) {
                respond("{\"id\":\"demo\",\"object\":\"model\"}", HttpStatusCode.OK)
            } else {
                assertTrue(request.url.encodedPath.endsWith("/chat/completions"))
                assertTrue((request.body as io.ktor.http.content.TextContent).text.contains("\"stream\":true"))
                respond(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }
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
        assertTrue(noProbe.source.contains("consent-required"))
        val live = adapter.probe(profile, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED, "p2")
        assertEquals(2, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.SUCCEEDED, live.status)
        assertTrue(live.supportsStream)
        assertFalse(live.supportsTools)
        assertFalse(live.supportsImages)
        assertTrue(live.charged)
        assertTrue(live.source.contains("metadata=verified"))
        assertTrue(live.source.contains("stream=verified"))
        assertTrue(live.source.contains("tools=not-declared"))
        assertTrue(live.source.contains("image=not-declared"))
    }

    @Test
    fun metadataProbeFallsBackToModelsListForEncodedSlashModelIds() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/models/deepseek-ai%2FDeepSeek-V3.2") ->
                    respond("not found", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/models") ->
                    respond("{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-ai/DeepSeek-V3.2\"}]}", HttpStatusCode.OK)
                else -> error("unexpected probe path ${request.url.encodedPath}")
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val report = adapter.probe(
            ModelProfile(
                id = "profile-siliconflow",
                providerId = "provider-siliconflow",
                modelId = "deepseek-ai/DeepSeek-V3.2",
                role = ModelRole.CHAT,
                capabilities = emptySet(),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
        )
        assertEquals(
            listOf(
                "/v1/models/deepseek-ai%2FDeepSeek-V3.2",
                "/v1/models",
            ),
            paths,
        )
        assertEquals(CapabilityProbeStatus.SUCCEEDED, report.status)
        assertEquals(CapabilityCheckStatus.VERIFIED, report.checks.first().status)
        assertTrue(report.source.contains("metadata=verified"))
    }

    @Test
    fun metadataProbeFallsBackToModelsListForIdsWithoutSlash() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/models/deepseek-v4-flash") ->
                    respond("not found", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/models") ->
                    respond("{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-v4-flash\"}]}", HttpStatusCode.OK)
                else -> error("unexpected probe path ${request.url.encodedPath}")
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://api.deepseek.com")
        val report = adapter.probe(
            ModelProfile(
                id = "profile-deepseek",
                providerId = "provider-deepseek",
                modelId = "deepseek-v4-flash",
                role = ModelRole.CHAT,
                capabilities = emptySet(),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
        )
        assertEquals(
            listOf("/models/deepseek-v4-flash", "/models"),
            paths,
        )
        assertTrue(paths.none { it.contains("/v1") })
        assertEquals(CapabilityProbeStatus.SUCCEEDED, report.status)
        assertEquals(CapabilityCheckStatus.VERIFIED, report.checks.first().status)
        assertTrue(report.source.contains("metadata=verified"))
    }

    @Test
    fun officialRootBaseWithTrailingSlashDoesNotInsertV1OnChat() = runBlocking {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://api.deepseek.com/")
        val profile = ModelProfile(
            id = "profile-root",
            providerId = "provider-deepseek",
            modelId = "deepseek-v4-pro",
            role = ModelRole.CHAT,
            capabilities = emptySet(),
            contextLimit = 4096,
            outputLimit = 64,
            revision = 1,
        )
        val connection = adapter.testConnection(profile, "token".toCharArray())
        assertTrue(connection is ProviderConnectionResult.Success)
        assertEquals(listOf("https://api.deepseek.com/chat/completions"), urls)
        assertTrue(urls.none { it.contains("/v1") })
    }

    @Test
    fun metadataMismatchDoesNotFallBackOrPromoteCapabilities() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond("{\"id\":\"different-model\"}", HttpStatusCode.OK)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val report = adapter.probe(
            ModelProfile(
                id = "profile-mismatch",
                providerId = "provider",
                modelId = "vendor/model",
                role = ModelRole.CHAT,
                capabilities = setOf("stream"),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
        )
        assertEquals(listOf("/v1/models/vendor%2Fmodel"), paths)
        assertEquals(CapabilityProbeStatus.FAILED, report.status)
        assertEquals(CapabilityCheckStatus.FAILED, report.checks.first().status)
        assertFalse(report.supportsStream)
    }

    @Test
    fun metadataAuthFailureDoesNotFallBackForSlashModel() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond("unauthorized", HttpStatusCode.Unauthorized)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val report = adapter.probe(
            ModelProfile(
                id = "profile-auth",
                providerId = "provider",
                modelId = "vendor/model",
                role = ModelRole.CHAT,
                capabilities = setOf("stream"),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
        )
        assertEquals(listOf("/v1/models/vendor%2Fmodel"), paths)
        assertEquals(CapabilityProbeStatus.FAILED, report.status)
        assertEquals(CapabilityCheckStatus.FAILED, report.checks.first().status)
        assertEquals(401, report.checks.first().httpStatus)
    }

    @Test
    fun testConnectionUsesMinimalChatPathAndReturnsTypedSuccess() = runBlocking {
        var requests = 0
        var path = ""
        var body = ""
        var auth = ""
        val engine = MockEngine { request ->
            requests += 1
            path = request.url.encodedPath
            body = (request.body as io.ktor.http.content.TextContent).text
            auth = request.headers[HttpHeaders.Authorization].orEmpty()
            respond(
                content = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val result = adapter.testConnection(
            ModelProfile(
                id = "profile-connection",
                providerId = "provider-connection",
                modelId = "demo",
                role = ModelRole.CHAT,
                capabilities = setOf("stream"),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "connection-secret".toCharArray(),
        )
        assertTrue(result is ProviderConnectionResult.Success)
        assertEquals(1, requests)
        assertEquals("/v1/chat/completions", path)
        assertTrue(auth.startsWith("Bearer "))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("Reply with ok."))
    }

    @Test
    fun testConnectionMapsAuthModelAndRateLimitFailures() = runBlocking {
        listOf(
            HttpStatusCode.Unauthorized to ProviderConnectionErrorCode.AUTH_FAILED,
            HttpStatusCode.NotFound to ProviderConnectionErrorCode.MODEL_NOT_FOUND,
            HttpStatusCode.TooManyRequests to ProviderConnectionErrorCode.RATE_LIMITED,
        ).forEach { (httpStatus, expected) ->
            val engine = MockEngine {
                respond("rejected", httpStatus)
            }
            val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
            val result = adapter.testConnection(
                ModelProfile(
                    id = "profile-failure-${httpStatus.value}",
                    providerId = "provider-failure",
                    modelId = "demo",
                    role = ModelRole.CHAT,
                    capabilities = emptySet(),
                    contextLimit = 4096,
                    outputLimit = 64,
                    revision = 1,
                ),
                "connection-secret".toCharArray(),
            )
            assertTrue(result is ProviderConnectionResult.Failure)
            result as ProviderConnectionResult.Failure
            assertEquals(expected, result.code)
            assertEquals(httpStatus.value, result.httpStatus)
            assertTrue(result.charged)
        }
    }

    @Test
    fun testConnectionMapsTransportTimeoutAndMalformedSuccess() = runBlocking {
        val timeoutAdapter = OpenAiCompatibleAdapter(
            HttpClient(MockEngine { throw SocketTimeoutException("fixture") }),
            "https://example.invalid/v1",
        )
        val timeout = timeoutAdapter.testConnection(
            ModelProfile(
                id = "timeout-profile",
                providerId = "provider",
                modelId = "demo",
                role = ModelRole.CHAT,
                capabilities = emptySet(),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "secret".toCharArray(),
        )
        assertEquals(ProviderConnectionErrorCode.TIMEOUT, (timeout as ProviderConnectionResult.Failure).code)
        assertTrue(timeout.charged)

        val invalidAdapter = OpenAiCompatibleAdapter(
            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            "https://example.invalid/v1",
        )
        val invalid = invalidAdapter.testConnection(
            ModelProfile(
                id = "invalid-profile",
                providerId = "provider",
                modelId = "demo",
                role = ModelRole.CHAT,
                capabilities = emptySet(),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "secret".toCharArray(),
        )
        assertEquals(ProviderConnectionErrorCode.INVALID_RESPONSE, (invalid as ProviderConnectionResult.Failure).code)
        assertTrue(invalid.charged)
    }

    @Test
    fun chatCanConnectWhenMetadataIsUnsupportedAndCapabilityRemainsIndependent() = runBlocking {
        var requests = 0
        val engine = MockEngine { request ->
            requests += 1
            when {
                request.url.encodedPath.endsWith("/models/demo") -> respond("not found", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/models") && request.method.value == "GET" ->
                    respond("not found", HttpStatusCode.NotFound)
                else -> respond(
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val profile = ModelProfile(
            id = "metadata-missing",
            providerId = "provider",
            modelId = "demo",
            role = ModelRole.CHAT,
            capabilities = emptySet(),
            contextLimit = 4096,
            outputLimit = 64,
            revision = 1,
        )
        val connection = adapter.testConnection(profile, "secret".toCharArray())
        assertTrue(connection is ProviderConnectionResult.Success)
        assertEquals(1, requests)
        val report = adapter.probe(profile, "secret".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED)
        assertEquals(CapabilityProbeStatus.PARTIAL, report.status)
        assertEquals(3, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityCheckStatus.UNSUPPORTED, report.checks.first().status)
        assertEquals(404, report.checks.first().httpStatus)
    }

    @Test
    fun metadataHttpSuccessWithMalformedBodyDoesNotPromoteManualCapabilities() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            respond("{}", HttpStatusCode.OK)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val profile = ModelProfile(
            id = "profile-1",
            providerId = "provider-1",
            modelId = "demo",
            role = ModelRole.CHAT,
            capabilities = setOf("stream", "tools", "image"),
            contextLimit = 4096,
            outputLimit = 1024,
            revision = 1,
        )
        val report = adapter.probe(profile, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED, "p3")
        assertEquals(1, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.FAILED, report.status)
        assertFalse(report.supportsStream)
        assertFalse(report.supportsTools)
        assertFalse(report.supportsImages)
        assertFalse(report.charged)
        assertTrue(report.source.contains("metadata=invalid-response"))
        assertTrue(report.source.contains("stream=not-run"))
        assertTrue(report.source.contains("tools=not-run"))
        assertTrue(report.source.contains("image=not-run"))
    }

    @Test
    fun metadataHttpFailureDoesNotPromoteManualCapabilities() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            respond("unauthorized", HttpStatusCode.Unauthorized)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val profile = ModelProfile(
            id = "profile-1",
            providerId = "provider-1",
            modelId = "demo",
            role = ModelRole.VISION,
            capabilities = setOf("stream", "tools", "image"),
            contextLimit = 4096,
            outputLimit = 1024,
            revision = 1,
        )
        val report = adapter.probe(profile, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED, "p3-http")
        assertEquals(1, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.FAILED, report.status)
        assertFalse(report.supportsStream)
        assertFalse(report.supportsTools)
        assertFalse(report.supportsImages)
        assertFalse(report.charged)
        assertTrue(report.source.contains("metadata=http-401"))
    }

    @Test
    fun toolsAndImageRequireSemanticResponsesAndRecordRejectedFeature() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests += 1
            when {
                request.url.encodedPath.endsWith("/models/demo") ->
                    respond("{\"id\":\"demo\"}", HttpStatusCode.OK)
                (request.body as io.ktor.http.content.TextContent).text.contains("tool_choice") ->
                    respond(
                        "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"probe\",\"type\":\"function\",\"function\":{\"name\":\"mar_probe_noop\",\"arguments\":\"{}\"}}]}}]}",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> respond("unsupported image", HttpStatusCode.BadRequest)
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val profile = ModelProfile(
            id = "profile-1",
            providerId = "provider-1",
            modelId = "demo",
            role = ModelRole.VISION,
            capabilities = setOf("tools", "image"),
            contextLimit = 4096,
            outputLimit = 1024,
            revision = 1,
        )
        val report = adapter.probe(profile, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED, "p4")
        assertEquals(3, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.PARTIAL, report.status)
        assertTrue(report.supportsTools)
        assertFalse(report.supportsImages)
        assertFalse(report.supportsStream)
        assertTrue(report.charged)
        assertTrue(report.source.contains("tools=verified"))
        assertTrue(report.source.contains("image=http-400"))
    }

    @Test
    fun toolsCapabilityProbeUsesBoundedBudgetForFunctionCall() = runTest {
        var requests = 0
        var toolProbeBudget = 0
        val engine = MockEngine { request ->
            requests += 1
            when {
                request.url.encodedPath.endsWith("/models/demo") ->
                    respond("{\"id\":\"demo\"}", HttpStatusCode.OK)
                else -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    assertTrue(body.contains("tool_choice"), body)
                    toolProbeBudget = Regex("\\\"max_tokens\\\":(\\d+)").find(body)?.groupValues?.get(1)?.toInt()
                        ?: error("missing max_tokens in $body")
                    if (toolProbeBudget <= 1) {
                        // A one-token completion is a realistic truncation:
                        // the provider never reaches a complete function call.
                        respond(
                            "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"probe\",\"type\":\"function\",\"function\":{\"name\":\"mar_probe_noop\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val profile = ModelProfile(
            id = "profile-tool-budget",
            providerId = "provider-tool-budget",
            modelId = "demo",
            role = ModelRole.CHAT,
            capabilities = setOf("tools"),
            contextLimit = 4096,
            outputLimit = 32,
            revision = 1,
        )
        val report = adapter.probe(
            profile,
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
            "tool-budget",
        )
        assertEquals(2, requests)
        assertTrue(toolProbeBudget > 1)
        assertTrue(toolProbeBudget <= profile.outputLimit)
        assertTrue(report.supportsTools)
        assertEquals(CapabilityProbeStatus.SUCCEEDED, report.status)
        assertTrue(report.source.contains("tools=verified"))
    }

    @Test
    fun toolsCapabilityProbeWithoutAForcedCallIsInconclusiveNotFailed() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/models/demo") ->
                    respond("{\"id\":\"demo\"}", HttpStatusCode.OK)
                else -> respond(
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val report = adapter.probe(
            ModelProfile(
                id = "profile-tools-inconclusive",
                providerId = "provider-tools",
                modelId = "demo",
                role = ModelRole.CHAT,
                capabilities = setOf("tools"),
                contextLimit = 4096,
                outputLimit = 32,
                revision = 1,
            ),
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
        )
        assertFalse(report.supportsTools)
        assertEquals(CapabilityProbeStatus.PARTIAL, report.status)
        assertEquals(CapabilityCheckStatus.UNKNOWN, report.checks.first { it.capability == CapabilityCheck.TOOLS }.status)
        assertTrue(report.source.contains("tools=inconclusive"))
    }

    @Test
    fun toolsProbeRetriesWithoutForcedToolChoiceWhenTheProviderRejectsTheShape() = runTest {
        var forcedRequests = 0
        var plainRequests = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/models/demo") ->
                    respond("{\"id\":\"demo\"}", HttpStatusCode.OK)
                else -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    if (body.contains("\"tool_choice\"")) {
                        // DeepSeek thinking mode rejects a forced tool choice while still
                        // supporting live tool calling.
                        forcedRequests += 1
                        respond(
                            "{\"error\":{\"message\":\"Thinking mode does not support this tool_choice\"}}",
                            HttpStatusCode.BadRequest,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        plainRequests += 1
                        respond(
                            "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"probe\",\"type\":\"function\",\"function\":{\"name\":\"mar_probe_noop\",\"arguments\":\"{}\"}}]}}]}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val report = adapter.probe(
            ModelProfile(
                id = "profile-forced-choice",
                providerId = "provider-forced-choice",
                modelId = "demo",
                role = ModelRole.CHAT,
                capabilities = setOf("tools"),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
            ),
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
        )
        assertEquals(1, forcedRequests)
        assertEquals(1, plainRequests)
        assertTrue(report.supportsTools)
        assertEquals(
            CapabilityCheckStatus.VERIFIED,
            report.checks.first { it.capability == CapabilityCheck.TOOLS }.status,
        )
        assertTrue(report.source.contains("tools=verified-without-forced-tool-choice"))
    }

    @Test
    fun featureProbePayloadCarriesValidatedModelParameters() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/models/demo") ->
                    respond("{\"id\":\"demo\"}", HttpStatusCode.OK)
                else -> {
                    bodies += (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        adapter.probe(
            ModelProfile(
                id = "profile-parameters",
                providerId = "provider-parameters",
                modelId = "demo",
                role = ModelRole.CHAT,
                capabilities = setOf("tools", "stream"),
                contextLimit = 4096,
                outputLimit = 64,
                revision = 1,
                parametersJson = "{\"thinking\":{\"type\":\"disabled\"},\"temperature\":0.2}",
            ),
            "token".toCharArray(),
            runtime.mobileagent.provider.ProbeConsent.GRANTED,
        )
        assertTrue(bodies.isNotEmpty())
        bodies.forEach { body ->
            assertTrue(body.contains("\"thinking\""), body)
            assertTrue(body.contains("\"type\":\"disabled\""), body)
            assertTrue(body.contains("\"temperature\":0.2"), body)
        }
    }
    @Test
    fun testConnectionClampsProbeBudgetFarBelowProfileLimit() = runBlocking {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1").testConnection(
            ModelProfile(
                id = "profile-clamped",
                providerId = "provider-clamped",
                modelId = "demo",
                role = ModelRole.CHAT,
                capabilities = setOf("stream"),
                contextLimit = 4096,
                outputLimit = 10240,
                revision = 1,
            ),
            "connection-secret".toCharArray(),
        )
        assertTrue(result is ProviderConnectionResult.Success)
        assertTrue(body.contains("\"max_tokens\":64"), body)
        assertFalse(body.contains("10240"))
    }

    @Test
    fun streamWithoutValidPayloadAndDoneIsNotVerified() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests += 1
            if (request.url.encodedPath.endsWith("/models/demo")) {
                respond("{\"id\":\"demo\"}", HttpStatusCode.OK)
            } else {
                respond(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }
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
        val report = adapter.probe(profile, "token".toCharArray(), runtime.mobileagent.provider.ProbeConsent.GRANTED, "p5")
        assertEquals(2, requests)
        assertEquals(runtime.mobileagent.provider.CapabilityProbeStatus.PARTIAL, report.status)
        assertFalse(report.supportsStream)
        assertTrue(report.charged)
        assertTrue(report.source.contains("stream=invalid-response"))
    }
}
