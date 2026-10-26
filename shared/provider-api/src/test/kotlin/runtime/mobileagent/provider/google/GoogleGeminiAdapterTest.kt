// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.google

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.provider.*

class GoogleGeminiAdapterTest {
    @Test fun `native and common parameter spellings honor layer precedence`() {
        val payload = GoogleGeminiPayload.build(request().copy(parameters = ParameterLayers(
            modelParameters = mapOf("generationConfig" to Json.parseToJsonElement("""{"temperature":0.8,"topP":0.9}""")),
            agentOverrides = mapOf("temperature" to JsonPrimitive(0.4), "top_p" to JsonPrimitive(0.5)),
            customJson = """{"generationConfig":{"temperature":0.2,"topP":0.3}}""",
        )))
        val config = payload.getValue("generationConfig").jsonObject
        assertEquals(0.2, config.getValue("temperature").jsonPrimitive.doubleOrNull)
        assertEquals(0.3, config.getValue("topP").jsonPrimitive.doubleOrNull)
    }

    @Test fun `unsupported media output options fail before dispatch`() = runTest {
        var sent = 0
        val subject = adapter(MockEngine { sent++; respond(reply()) })
        for (config in listOf("""{"imageConfig":{}}""", """{"speechConfig":{}}""",
            """{"responseModalities":["AUDIO"]}""", """{"responseModalities":["TEXT","IMAGE"]}""")) {
            val events = subject.stream(request().copy(parameters = ParameterLayers(customJson = "{\"generationConfig\":$config}")), key).toList()
            assertTrue(events.last() is ModelEvent.Failed)
        }
        assertEquals(0, sent)
        assertEquals(ModelEvent.Completed, subject.stream(request().copy(parameters = ParameterLayers(
            customJson = """{"generationConfig":{"responseModalities":["TEXT"]}}""")), key).toList().last())
        assertEquals(1, sent)
    }

    private val base = "https://native.example.invalid/v1beta"
    private val key get() = "non-secret-test-value".toCharArray()
    private fun request(stream: Boolean = false) = ModelRequest("gemini-test", listOf(ChatMessage("user", "Hi")), stream = stream)
    private fun profile() = ModelProfile("p", "provider", ModelRole.CHAT, "gemini-test", setOf("stream", "tools", "image"), contextLimit = 4096, outputLimit = 1024, revision = 1)
    private fun adapter(engine: MockEngine, format: ApiFormat = ApiFormat.GOOGLE_AI_STUDIO, url: String = base,
        resolver: HeaderSecretResolver? = null) = GoogleGeminiAdapter(HttpClient(engine), url, format, resolver)
    private fun reply(text: String = "Hello", finish: String = "STOP") = """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},"finishReason":"$finish"}]}"""
    private fun engine(body: String = reply(), status: HttpStatusCode = HttpStatusCode.OK, type: String = "application/json") = MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, type))
    }

    @Test fun `native request uses system contents image tools and mapped config`() = runTest {
        var wire: JsonObject? = null
        val engine = MockEngine { req ->
            assertEquals("$base/models/gemini-test:generateContent", req.url.toString())
            assertEquals(key.concatToString(), req.headers["x-goog-api-key"])
            assertNull(req.headers[HttpHeaders.Authorization])
            wire = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            respond(reply(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val req = request().copy(messages = listOf(ChatMessage("system", "Be concise"), ChatMessage("user", "Hi",
            images = listOf(InlineImage("image/png", "AA==")))), tools = listOf(mapOf("name" to "clock", "parameters" to "{\"type\":\"object\"}")),
            parameters = ParameterLayers(modelParameters = mapOf("temperature" to JsonPrimitive(0.3), "top_p" to JsonPrimitive(0.7),
                "generationConfig" to obj("thinkingConfig" to obj("thinkingBudget" to JsonPrimitive(128))))), outputTokenLimit = 512)
        assertEquals(ModelEvent.Completed, adapter(engine).stream(req, key).toList().last())
        assertEquals("Be concise", wire!!["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(wire!!["contents"]!!.toString().contains("inlineData"))
        assertTrue(wire!!["tools"]!!.toString().contains("functionDeclarations"))
        val declaration = wire!!["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertEquals(Json.parseToJsonElement("{\"type\":\"object\"}"), declaration["parametersJsonSchema"])
        assertFalse(declaration.containsKey("parameters"))
        assertEquals(512, wire!!["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        assertFalse(wire!!.containsKey("messages"))
    }

    @Test fun `Vertex standard bearer and express key remain in headers`() = runBlocking {
        for ((format, url) in listOf(
            ApiFormat.GOOGLE_VERTEX_AI to "https://region.example.invalid/v1/projects/p/locations/global/publishers/google",
            ApiFormat.GOOGLE_VERTEX_EXPRESS to "https://native.example.invalid/v1/publishers/google",
            ApiFormat.GOOGLE_VERTEX_EXPRESS to "https://native.example.invalid/v1beta1/projects/p/locations/global/publishers/google",
        )) {
            val engine = MockEngine { req ->
                assertEquals("$url/models/gemini-4-argon:generateContent", req.url.toString())
                assertFalse(req.url.toString().contains(key.concatToString()))
                if (format == ApiFormat.GOOGLE_VERTEX_AI) { assertEquals("Bearer ${key.concatToString()}", req.headers[HttpHeaders.Authorization]); assertNull(req.headers["x-goog-api-key"]) }
                else { assertEquals(key.concatToString(), req.headers["x-goog-api-key"]); assertNull(req.headers[HttpHeaders.Authorization]) }
                respond(reply(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val subject = adapter(engine, format, url)
            val namespaced = request().copy(modelId = "google/gemini-4-argon")
            val namespacedProfile = profile().copy(modelId = "google/gemini-4-argon")
            assertEquals(ModelEvent.Completed, subject.stream(namespaced, key).toList().last())
            assertTrue(subject.testConnection(namespacedProfile, key) is ProviderConnectionResult.Success)
        }
    }

    @Test fun `endpoint rejects traversal query auth and full model resources`() {
        for (model in listOf("../x", "models/../x", "google/../x", "x/..", "x?key=a", "a/b/c",
            "projects/p/locations/g/models/x", "publishers/google/models/x", "models/models/x", "projects/x", "x%2Fy", "x\\y"))
            assertThrows(IllegalArgumentException::class.java) { GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_AI_STUDIO, base, model) }
        for (url in listOf("$base?key=x", "$base#x", "https://u:p@h/v1beta", "https://h/a/../v1beta", "https://h/%2e%2e/v1beta"))
            assertThrows(IllegalArgumentException::class.java) { GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_AI_STUDIO, url, "x") }
        assertEquals("$base/models/x:streamGenerateContent?alt=sse", GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_AI_STUDIO, base, "models/x", true))
        assertEquals("$base/models/google/gemini-4-argon:generateContent", GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_AI_STUDIO, base, "google/gemini-4-argon"))
        val vertexBase = "https://aiplatform.googleapis.com/v1/projects/demo/locations/global/publishers/google"
        val expressBase = "https://aiplatform.googleapis.com/v1/publishers/google"
        val expressProjectBase = "https://aiplatform.googleapis.com/v1beta1/projects/demo/locations/global/publishers/google"
        assertEquals(
            "$vertexBase/models/gemini-4-argon:generateContent",
            GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_VERTEX_AI, vertexBase, "google/gemini-4-argon"),
        )
        assertEquals(
            "$expressBase/models/gemini-4-argon:streamGenerateContent?alt=sse",
            GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_VERTEX_EXPRESS, expressBase, "models/google/gemini-4-argon", true),
        )
        assertEquals(
            "$expressProjectBase/models/gemini-4-argon:generateContent",
            GoogleGeminiAdapter.requestEndpoint(ApiFormat.GOOGLE_VERTEX_EXPRESS, expressProjectBase, "google/gemini-4-argon"),
        )
        runtime.mobileagent.provider.openai.OpenAiAdapterFactory.validateDraftSave(
            format = ApiFormat.GOOGLE_VERTEX_AI,
            baseUrl = vertexBase,
            saveModel = true,
            draftRole = ModelRole.CHAT,
            effectiveModelId = "google/gemini-4-argon",
        )
    }

    @Test fun `reserved fields nested injection and ambiguous aliases never dispatch`() = runTest {
        var sent = 0
        val adapter = adapter(MockEngine { sent++; respond(reply()) })
        for (json in listOf("{\"contents\":[]}", "{\"generationConfig\":{\"thinkingConfig\":{\"api_key\":\"x\"}}}",
            "{\"tools\":[]}", "{\"cachedContent\":\"x\"}", "{\"generationConfig\":{\"maxOutputTokens\":64},\"max_tokens\":32}",
            "{\"candidateCount\":2}", "{\"top_p\":2}", "{\"max_tokens\":0}", "{\"maxOutputTokens\":\"8\"}")) {
            val events = adapter.stream(request().copy(parameters = ParameterLayers(customJson = json)), key).toList()
            assertEquals("CONFIG_INVALID", (events.last() as ModelEvent.Failed).sanitizedMessage, json)
        }
        assertEquals(0, sent)
    }

    @Test fun `resolved output field replaces every older alias`() {
        val payload = GoogleGeminiPayload.build(request().copy(outputTokenField = "maxOutputTokens", outputTokenLimit = 80,
            parameters = ParameterLayers(modelParameters = mapOf("max_tokens" to JsonPrimitive(700), "generationConfig" to obj("maxOutputTokens" to JsonPrimitive(500))))))
        assertEquals(80, payload["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        assertFalse(payload.toString().contains("max_tokens"))
    }

    @Test fun `redirects never follow and preserve typed result`() = runBlocking {
        var count = 0
        val engine = MockEngine { count++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example.invalid/collect")) }
        val result = adapter(engine).testConnection(profile(), key)
        assertEquals(ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED, (result as ProviderConnectionResult.Failure).code)
        assertEquals(1, count)
    }

    @Test fun `typed HTTP connection outcomes`() = runBlocking {
        for ((status, code) in listOf(HttpStatusCode.Unauthorized to ProviderConnectionErrorCode.AUTH_FAILED,
            HttpStatusCode.NotFound to ProviderConnectionErrorCode.MODEL_NOT_FOUND, HttpStatusCode.TooManyRequests to ProviderConnectionErrorCode.RATE_LIMITED)) {
            val result = adapter(engine("{}", status)).testConnection(profile(), key) as ProviderConnectionResult.Failure
            assertEquals(code, result.code); assertEquals(status.value, result.httpStatus); assertTrue(result.charged)
        }
        assertEquals(ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE, (adapter(engine()).testConnection(profile(), charArrayOf()) as ProviderConnectionResult.Failure).code)
    }

    @Test fun `final dispatch gate is once after secret resolution and fail closed`() = runTest {
        var resolved = false
        var gates = 0
        var sends = 0
        val adapter = adapter(MockEngine { sends++; respond(reply()) }, resolver = HeaderSecretResolver { _, _ -> resolved = true; "synthetic-header".toCharArray() })
        val events = adapter.stream(request().copy(headers = mapOf("X-Test" to RequestHeaderValue.SecretRef("test-ref")), beforeDispatch = { gates++; assertTrue(resolved); false }), key).toList()
        assertEquals("CANCELLED", (events.last() as ModelEvent.Failed).sanitizedMessage)
        assertEquals(1, gates); assertEquals(0, sends)
        val throws = adapter.stream(request().copy(beforeDispatch = { throw IllegalStateException("private") }), key).toList()
        assertEquals("CONFIG_INVALID", (throws.last() as ModelEvent.Failed).sanitizedMessage)
    }

    @Test fun `authority headers reject and resolved mutable secret buffer is cleared`() = runTest {
        var sends = 0
        val secret = "synthetic-header".toCharArray()
        val adapter = adapter(MockEngine { sends++; respond(reply()) }, resolver = HeaderSecretResolver { _, _ -> secret })
        for (name in listOf("Host", "Authorization", "x-goog-api-key", "Content-Length", "Content-Type", "X\r\nTest")) {
            assertTrue(adapter.stream(request().copy(headers = mapOf(name to RequestHeaderValue.Plain("x"))), key).toList().last() is ModelEvent.Failed)
        }
        adapter.stream(request().copy(headers = mapOf("X-Test" to RequestHeaderValue.SecretRef("test-ref"))), key).toList()
        assertTrue(secret.all { it == '\u0000' }); assertEquals(1, sends)
    }

    @Test fun `SSE preserves signatures parts ids and cumulative thought usage`() = runTest {
        val frames = listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Thinking","thought":true}]}}],"usageMetadata":{"promptTokenCount":8,"thoughtsTokenCount":2}}""",
            """{"candidates":[{"content":{"parts":[{"text":"Working","thoughtSignature":"signed-text"},{"functionCall":{"id":"exact-id","name":"clock","args":{}},"thoughtSignature":"signed-call"} ]}}]}""",
            """{"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":4,"thoughtsTokenCount":3}}"""
        )
        val events = adapter(engine(frames.joinToString("") { "data: $it\r\n\r\n" }, type = "text/event-stream")).stream(request(true), key).toList()
        val usage = events.filterIsInstance<ModelEvent.Usage>().last()
        assertEquals(1, events.filterIsInstance<ModelEvent.Usage>().size)
        assertEquals(8, usage.inputTokens); assertEquals(7, usage.outputTokens); assertEquals(3, usage.reasoningTokens)
        val call = events.filterIsInstance<ModelEvent.ToolCallDelta>().single()
        assertEquals("exact-id", call.callId)
        val continuation = events.filterIsInstance<ModelEvent.ProviderContinuation>().single().item
        assertEquals(GOOGLE_PROTOCOL, continuation.protocol)
        val message = ChatMessage("assistant", "Working", toolCalls = listOf(AssistantToolCall(call.callId, call.name, call.argumentsJson)), providerContinuationItems = listOf(continuation))
        val next = request().copy(messages = listOf(ChatMessage("user", "Hi"), message, ChatMessage("tool", "{\"time\":1}", toolCallId = call.callId)))
        val native = GoogleGeminiPayload.build(next)
        val content = native["contents"]!!.jsonArray[1].jsonObject
        assertEquals(3, content["parts"]!!.jsonArray.size)
        assertEquals("signed-text", content["parts"]!!.jsonArray[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("exact-id", native["contents"]!!.jsonArray[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val preview = adapter(engine()).previewRequest(next)
        assertFalse(preview.contains("signed-text")); assertFalse(preview.contains("signed-call"))
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test fun `tool without provider id uses local correlation only`() = runTest {
        val events = adapter(engine("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"clock","args":{}}}]},"finishReason":"STOP"}]}""")).stream(request(), key).toList()
        val call = events.filterIsInstance<ModelEvent.ToolCallDelta>().single()
        val next = request().copy(messages = listOf(ChatMessage("assistant", toolCalls = listOf(AssistantToolCall(call.callId, call.name, call.argumentsJson))), ChatMessage("tool", "ok", toolCallId = call.callId)))
        assertFalse(GoogleGeminiPayload.build(next).toString().contains(LOCAL_CALL_PREFIX))
        assertFalse(GoogleGeminiPayload.build(next).toString().contains("\"id\""))
    }

    @Test fun `truncated tools never become executable and unknown empty outcomes fail`() = runTest {
        for ((body, code) in listOf(reply("part", "MAX_TOKENS") to "OUTPUT_TRUNCATED", reply("", "STOP") to "INVALID_RESPONSE",
            reply("part", "OTHER") to "UNKNOWN_OUTCOME", "{\"candidates\":[]}" to "INVALID_RESPONSE",
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"clock","args":{}}}]},"finishReason":"MAX_TOKENS"}]}""" to "OUTPUT_TRUNCATED")) {
            val events = adapter(engine(body)).stream(request(), key).toList()
            assertEquals(code, (events.last() as ModelEvent.Failed).sanitizedMessage)
            assertFalse(events.any { it is ModelEvent.ToolCallDelta || it is ModelEvent.Completed })
        }
    }

    @Test fun `safety outcome emits explicit refusal`() = runTest {
        val events = adapter(engine("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")).stream(request(), key).toList()
        assertTrue(events.any { it is ModelEvent.RefusalDelta }); assertEquals(ModelEvent.Completed, events.last())
    }

    @Test fun `profile probe without consent emits no traffic`() = runTest {
        var calls = 0
        val adapter = adapter(MockEngine { calls++; respond(reply()) })
        assertEquals(CapabilityProbeStatus.PROFILE_ONLY, adapter.probe(profile(), key, ProbeConsent.NOT_GRANTED).status)
        assertFalse(adapter.probe(profile()).charged); assertEquals(0, calls)
    }

    @Test fun `inline image budget blocks dispatch and estimator excludes foreign continuation`() = runTest {
        var sent = 0
        val adapter = adapter(MockEngine { sent++; respond(reply()) })
        val foreign = ProviderContinuationItem(encryptedContent = "foreign-value")
        val withForeign = request().copy(messages = listOf(ChatMessage("user", "Hi", providerContinuationItems = listOf(foreign))))
        assertEquals(adapter.estimateInput(request()).units, adapter.estimateInput(withForeign).units)
        val oversized = request().copy(messages = listOf(ChatMessage("user", images = listOf(InlineImage("image/png", "", byteLength = Long.MAX_VALUE)))))
        assertTrue(adapter.stream(oversized, key).toList().last() is ModelEvent.Failed); assertEquals(0, sent)
    }

    @Test fun `cancellation propagates through gate`() = runTest {
        var cancelled = false
        try { adapter(engine()).stream(request().copy(beforeDispatch = { throw CancellationException("cancel") }), key).toList() }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `unsigned large answer works while oversized signed content fails privately`() = runTest {
        val text = "x".repeat(40_000)
        assertEquals(ModelEvent.Completed, adapter(engine(reply(text))).stream(request(), key).toList().last())
        val body = """{"candidates":[{"content":{"parts":[{"text":"$text","thoughtSignature":"opaque"}]},"finishReason":"STOP"}]}"""
        val events = adapter(engine(body)).stream(request(), key).toList()
        assertTrue(events.last() is ModelEvent.Failed); assertFalse(events.any { it is ModelEvent.ProviderContinuation })
    }

    @Test fun `unterminated SSE and credential echoes never complete`() = runTest {
        val incomplete = adapter(engine("data: ${reply()}", type = "text/event-stream")).stream(request(true), key).toList()
        assertTrue(incomplete.last() is ModelEvent.Failed)
        val leaked = adapter(engine(reply(key.concatToString()))).stream(request(), key).toList()
        assertFalse(leaked.toString().contains(key.concatToString()))
        assertTrue(leaked.last() is ModelEvent.Failed)
    }

    @Test fun `parallel signed results replay native order despite runtime reordering including idless identical functions`() = runTest {
        for (ids in listOf(true, false)) {
            val idA = if (ids) "\"id\":\"id-a\"," else ""
            val idB = if (ids) "\"id\":\"id-b\"," else ""
            val body = """{"candidates":[{"content":{"parts":[{"functionCall":{${idA}"name":"clock","args":{}},"thoughtSignature":"opaque"},{"functionCall":{${idB}"name":"clock","args":{}}}]},"finishReason":"STOP"}]}"""
            val events = adapter(engine(body)).stream(request(), key).toList()
            val calls = events.filterIsInstance<ModelEvent.ToolCallDelta>()
            val continuation = events.filterIsInstance<ModelEvent.ProviderContinuation>().single().item
            val publicCalls = calls.reversed().map { AssistantToolCall(it.callId, it.name, it.argumentsJson) }
            val history = listOf(ChatMessage("assistant", toolCalls = publicCalls, providerContinuationItems = listOf(continuation))) +
                listOf(ChatMessage("tool", "{\"result\":\"B\"}", toolCallId = calls[1].callId), ChatMessage("tool", "{\"result\":\"A\"}", toolCallId = calls[0].callId))
            val wire = GoogleGeminiPayload.build(request().copy(messages = history))["contents"]!!.jsonArray
            assertEquals(2, wire.size)
            val responses = wire[1].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
            assertEquals(listOf("A", "B"), responses.map { it["response"]!!.jsonObject["result"]!!.jsonPrimitive.content })
            assertEquals(2, wire[0].jsonObject["parts"]!!.jsonArray.size)
        }
    }

    @Test fun `probe honors manual cap preserves sampling and requires SSE for stream verification`() = runBlocking {
        val caps = mutableListOf<Int>()
        val engine = MockEngine { req ->
            val root = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            val config = root["generationConfig"]!!.jsonObject
            caps += config["maxOutputTokens"]!!.jsonPrimitive.int
            assertEquals(0.4, config["temperature"]!!.jsonPrimitive.double)
            respond(reply(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val profile = profile().copy(capabilities = setOf("stream", "image"), outputLimit = 8,
            parametersJson = "{\"temperature\":0.4,\"generationConfig\":{\"maxOutputTokens\":8192}}")
        val adapter = adapter(engine)
        val result = adapter.testConnection(profile, key)
        assertTrue(result is ProviderConnectionResult.Success)
        val report = adapter.probe(profile, key, ProbeConsent.GRANTED)
        assertFalse(report.supportsStream); assertTrue(report.supportsImages)
        assertEquals(listOf(8, 8, 8), caps)
    }

    @Test fun `image probe AUTO uses independent1024 cap and valid metadata`() = runBlocking {
        var cap = 0
        val engine = MockEngine { req ->
            cap = Json.parseToJsonElement((req.body as TextContent).text).jsonObject["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int
            respond(reply(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val report = adapter(engine).probe(profile().copy(capabilities = setOf("image"), outputLimitMode = OutputLimitMode.AUTO, outputLimit = 0), key, ProbeConsent.GRANTED)
        assertTrue(report.supportsImages); assertEquals(1024, cap)
    }

    @Test fun `HTTP errors retain one usage snapshot and thrown status stays typed`() = runBlocking {
        val events = adapter(engine("{\"error\":{\"code\":429},\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"thoughtsTokenCount\":3}}", HttpStatusCode.TooManyRequests)).stream(request(), key).toList()
        assertEquals(1, events.filterIsInstance<ModelEvent.Usage>().size)
        assertEquals(5, events.filterIsInstance<ModelEvent.Usage>().single().outputTokens)
        var calls = 0
        val result = adapter(MockEngine { calls++; throw ProviderHttpResponseException(503) }).testConnection(profile(), key) as ProviderConnectionResult.Failure
        assertEquals(503, result.httpStatus); assertEquals(ProviderConnectionErrorCode.PROVIDER_REJECTED, result.code); assertEquals(1, calls)
    }

    @Test fun `native nested aliases reject and trailing dot host resolver is canonical`() = runTest {
        var sent = 0
        val adapter = adapter(MockEngine { sent++; respond(reply()) }, url = "https://native.example.invalid./v1beta",
            resolver = HeaderSecretResolver { host, _ -> assertEquals("native.example.invalid", host); "synthetic-header".toCharArray() })
        val invalid = request().copy(parameters = ParameterLayers(customJson = "{\"generationConfig\":{\"max_tokens\":1000}}"))
        assertTrue(adapter.stream(invalid, key).toList().last() is ModelEvent.Failed)
        assertEquals(0, sent)
        adapter.stream(request().copy(headers = mapOf("X-Test" to RequestHeaderValue.SecretRef("test-ref"))), key).toList()
        assertEquals(1, sent)
    }

    @Test fun `unsigned idless calls and responses share canonical original order`() {
        val first = AssistantToolCall("${LOCAL_CALL_PREFIX}scope:0", "clock", "{\"which\":\"A\"}")
        val second = AssistantToolCall("${LOCAL_CALL_PREFIX}scope:1", "clock", "{\"which\":\"B\"}")
        val messages = listOf(ChatMessage("assistant", toolCalls = listOf(second, first)),
            ChatMessage("tool", "{\"result\":\"B\"}", toolCallId = second.id), ChatMessage("tool", "{\"result\":\"A\"}", toolCallId = first.id))
        val wire = GoogleGeminiPayload.build(request().copy(messages = messages))["contents"]!!.jsonArray
        val nativeCalls = wire[0].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["functionCall"]!!.jsonObject }
        val nativeResults = wire[1].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
        assertEquals(listOf("A", "B"), nativeCalls.map { it["args"]!!.jsonObject["which"]!!.jsonPrimitive.content })
        assertEquals(listOf("A", "B"), nativeResults.map { it["response"]!!.jsonObject["result"]!!.jsonPrimitive.content })
    }

    @Test fun `invalid cap profile probe fails without charging or dispatch`() = runTest {
        var sent = 0
        val report = adapter(MockEngine { sent++; respond(reply()) }).probe(
            profile().copy(capabilities = setOf("image"), parametersJson = "{\"maxOutputTokens\":-2}"), key, ProbeConsent.GRANTED)
        assertEquals(CapabilityProbeStatus.FAILED, report.status); assertFalse(report.charged); assertEquals(0, sent)
    }

    @Test fun `MAX_TOKENS uses shared reported reasoning subset classification`() = runTest {
        for ((candidates, expected) in listOf(0 to "REASONING_EXHAUSTED", 2 to "OUTPUT_TRUNCATED")) {
            val body = """{"candidates":[{"content":{"parts":[{"text":"reasoning","thought":true}]},"finishReason":"MAX_TOKENS"}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":$candidates,"thoughtsTokenCount":5}}"""
            val events = adapter(engine(body)).stream(request(), key).toList()
            assertEquals(expected, (events.last() as ModelEvent.Failed).sanitizedMessage)
            assertEquals(1, events.filterIsInstance<ModelEvent.Usage>().size)
        }
    }

    @Test fun `post dispatch transport exceptions emit UNKNOWN_OUTCOME and diagnostic stages strip thoughtSignature`() = runTest {
        for (error in listOf(java.net.SocketTimeoutException("timeout"), java.io.IOException("reset"), javax.net.ssl.SSLException("tls"))) {
            val diagnostics = mutableListOf<ModelDiagnosticEvent>()
            val sink = object : ModelDiagnosticSink {
                override val captureContent = true
                override fun record(event: ModelDiagnosticEvent) { diagnostics += event }
            }
            val events = adapter(MockEngine { throw error }).stream(request().copy(diagnostics = sink), key).toList()
            assertEquals("UNKNOWN_OUTCOME", (events.last() as ModelEvent.Failed).sanitizedMessage)
            assertEquals(ModelDispatchStatus.UNKNOWN_AFTER_DISPATCH, diagnostics.last().dispatchStatus)
        }
        val stages = mutableListOf<ModelDiagnosticEvent>()
        val stageSink = object : ModelDiagnosticSink {
            override val captureContent = true
            override fun record(event: ModelDiagnosticEvent) { stages += event }
        }
        val sse = """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hi","thoughtSignature":"private-sig"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2}}""" + "\r\n\r\n"
        val events = adapter(engine(sse, type = "text/event-stream")).stream(request(true).copy(diagnostics = stageSink), key).toList()
        assertEquals(ModelEvent.Completed, events.last())
        assertTrue(stages.any { it.stage == ModelDiagnosticStage.REQUEST_READY })
        assertTrue(stages.any { it.stage == ModelDiagnosticStage.RESPONSE_HEADERS })
        assertTrue(stages.any { it.stage == ModelDiagnosticStage.STREAM_EVENT })
        val terminal = stages.last()
        assertEquals(ModelDiagnosticStage.TERMINAL, terminal.stage)
        assertEquals("STOP", terminal.finishReason)
        assertEquals(3, terminal.inputTokens)
        assertFalse(stages.joinToString("\n") { it.content.orEmpty() }.contains("private-sig"))
    }

    @Test fun `probe formats structured source for repository verification and rejects empty secret`() = runBlocking {
        var calls = 0
        val emptyReport = adapter(MockEngine { calls++; respond(reply()) }).probe(profile(), charArrayOf(), ProbeConsent.GRANTED)
        assertEquals(CapabilityProbeStatus.FAILED, emptyReport.status)
        assertTrue(emptyReport.source.startsWith("metadata=failed;"))
        assertEquals(0, calls)
        val okEngine = MockEngine {
            respond("""[{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}]""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val report = adapter(okEngine).probe(profile().copy(capabilities = setOf("image")), key, ProbeConsent.GRANTED)
        assertEquals(CapabilityProbeStatus.SUCCEEDED, report.status)
        assertEquals("metadata=verified;stream=not-declared;tools=not-declared;image=verified", report.source)
    }

    @Test fun `unsafe native call ids and unanswered tool calls fail closed while consecutive user turns merge`() = runTest {
        val unsafeBody = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"bad id/with spaces","name":"clock","args":{}}}]},"finishReason":"STOP"}]}"""
        val unsafeEvents = adapter(engine(unsafeBody)).stream(request(), key).toList()
        assertEquals("INVALID_RESPONSE", (unsafeEvents.last() as ModelEvent.Failed).sanitizedMessage)
        assertFalse(unsafeEvents.any { it is ModelEvent.ToolCallDelta })

        val callA = AssistantToolCall("call-a", "clock", "{}")
        val callB = AssistantToolCall("call-b", "clock", "{}")
        assertThrows(IllegalArgumentException::class.java) {
            GoogleGeminiPayload.build(request().copy(messages = listOf(
                ChatMessage("user", "hi"),
                ChatMessage("assistant", toolCalls = listOf(callA, callB)),
                ChatMessage("tool", "{\"ok\":true}", toolCallId = "call-a"),
                ChatMessage("user", "next turn without answering call-b"),
            )))
        }
        val merged = GoogleGeminiPayload.build(request().copy(messages = listOf(
            ChatMessage("user", "hi"),
            ChatMessage("assistant", toolCalls = listOf(callA)),
            ChatMessage("tool", "{\"ok\":true}", toolCallId = "call-a"),
            ChatMessage("user", "visual evidence", images = listOf(InlineImage("image/png", "AA=="))),
        )))["contents"]!!.jsonArray
        assertEquals(3, merged.size)
        assertEquals(3, merged[2].jsonObject["parts"]!!.jsonArray.size)

        assertThrows(IllegalArgumentException::class.java) {
            runtime.mobileagent.provider.openai.OpenAiAdapterFactory.validateDraftSave(
                format = ApiFormat.GOOGLE_VERTEX_AI,
                baseUrl = "https://aiplatform.googleapis.com/v1/projects/PROJECT_ID/locations/global/publishers/google",
                saveModel = false,
                draftRole = ModelRole.CHAT,
                effectiveModelId = "",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.mobileagent.provider.openai.OpenAiAdapterFactory.validateDraftSave(
                format = ApiFormat.GOOGLE_AI_STUDIO,
                baseUrl = base,
                saveModel = false,
                draftRole = ModelRole.CHAT,
                effectiveModelId = "",
                existingModels = listOf(profile().copy(role = ModelRole.EMBEDDING)),
            )
        }
    }
}
