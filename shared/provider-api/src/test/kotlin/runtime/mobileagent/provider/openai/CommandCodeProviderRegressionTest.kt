// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import runtime.mobileagent.domain.ContextLimitSource
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.contextWindowTarget
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.ContextWindowProducer
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ProbeConsent

class CommandCodeProviderRegressionTest {
    private val formats = listOf("chat", "responses")
    private val base = "https://api.commandcode.ai/provider/v1"
    private val model = "deepseek/deepseek-v4.1-flash"
    private val profile = ModelProfile(
        id = "catalog-probe", providerId = "provider", modelId = model, role = ModelRole.VISION,
        capabilities = setOf("image"), contextLimit = 4096, outputLimit = 2048, revision = 1,
    )
    private fun adapter(format: String, http: HttpClient, url: String = base): ModelAdapter =
        if (format == "responses") OpenAiResponsesAdapter(http, url) else OpenAiCompatibleAdapter(http, url)

    @TestFactory
    fun documentedCatalogProducesContextWindowOnBothProtocols() = formats.map { format ->
        DynamicTest.dynamicTest("$format documented catalog yields AUTO window") {
            runBlocking {
                var requests = 0
                HttpClient(MockEngine { request ->
                    requests++
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("$base/models", request.url.toString())
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respond("""{"object":"list","data":[{"id":"other","context_length":8192},{"id":"deepseek/deepseek-v4.1-flash","context_length":1000000}]}""", HttpStatusCode.OK)
                }).use { http ->
                    val fact = ContextWindowProducer().metadata(
                        contextWindowTarget(profile.providerId, base, model), profile, adapter(format, http),
                    )
                    assertEquals(1000000, fact.value)
                    assertEquals(ContextLimitSource.PROVIDER_METADATA, fact.source)
                    assertEquals(1, requests)
                }
            }
        }
    }

    @TestFactory
    fun unknownProxyDoesNotBorrowCommandCodeMetadata() = formats.map { format ->
        DynamicTest.dynamicTest("$format proxy leaves context unknown without requests") {
            runBlocking {
                var requests = 0
                HttpClient(MockEngine {
                    requests++
                    error("Untrusted provider must not dispatch catalog requests")
                }).use { http ->
                    val proxy = "https://api.commandcode.ai.example.invalid/provider/v1"
                    val fact = ContextWindowProducer().metadata(
                        contextWindowTarget(profile.providerId, proxy, model), profile, adapter(format, http, proxy),
                    )
                    assertNull(fact.value)
                    assertEquals(ContextLimitSource.UNKNOWN, fact.source)
                    assertEquals(0, requests)
                }
            }
        }
    }

    private fun imageUrl(format: String, body: JsonObject): String {
        val rows = body.getValue(if (format == "responses") "input" else "messages") as JsonArray
        val parts = rows.first().jsonObject.getValue("content") as JsonArray
        val image = parts.first { it.jsonObject["type"]?.jsonPrimitive?.content in setOf("image_url", "input_image") }.jsonObject
        val value = image.getValue("image_url")
        return if (value is JsonObject) value.getValue("url").jsonPrimitive.content else value.jsonPrimitive.content
    }
    private fun response(format: String, complete: Boolean): String = if (format == "responses") {
        if (complete) """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"red"}]}]}"""
        else """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"Considering the image"}]}],"usage":{"output_tokens":64,"output_tokens_details":{"reasoning_tokens":64}}}"""
    } else {
        if (complete) """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"red"}}]}"""
        else """{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":null,"reasoning_content":"Considering the image"}}],"usage":{"completion_tokens":64,"completion_tokens_details":{"reasoning_tokens":64}}}"""
    }

    @TestFactory
    fun imageFixtureIsDecodableAndProcessorSizedOnBothProtocols() = formats.map { format ->
        DynamicTest.dynamicTest("$format image fixture decodes at usable size") {
            runBlocking {
                var width = 0
                HttpClient(MockEngine { request ->
                    if (request.method == HttpMethod.Get) respond(if (request.url.encodedPath.endsWith("/models")) """{"data":[{"id":"$model"}]}""" else """{"id":"$model"}""", HttpStatusCode.OK)
                    else if (format == "responses" && !(request.body as TextContent).text.contains("input_image")) respond(response(format, true), HttpStatusCode.OK)
                    else {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val bytes = Base64.getDecoder().decode(imageUrl(format, body).substringAfter(','))
                        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
                        width = decoded?.width ?: 0
                        assertEquals(128, decoded?.height)
                        assertEquals(0xFF0000, decoded!!.getRGB(32, 64) and 0xFFFFFF)
                        assertEquals(0x0000FF, decoded.getRGB(96, 64) and 0xFFFFFF)
                        if (width < 64) respond("""{"error":{"message":"Image too small"}}""", HttpStatusCode.BadRequest)
                        else respond(response(format, true), HttpStatusCode.OK)
                    }
                }).use { http ->
                    val result = adapter(format, http).probe(profile, "fixture-secret".toCharArray(), ProbeConsent.GRANTED)
                    assertTrue(width >= 64, "$format width=$width")
                    assertEquals(CapabilityCheckStatus.VERIFIED, result.checks.single { it.capability == CapabilityCheck.IMAGE }.status)
                }
            }
        }
    }

    @TestFactory
    fun imageProbeLeavesRoomForReasoningWithoutUsingTheFullProfileBudget() = formats.map { format ->
        DynamicTest.dynamicTest("$format image probe has room for reasoning") {
            runBlocking {
                var budget = 0
                var posts = 0
                HttpClient(MockEngine { request ->
                    if (request.method == HttpMethod.Get) respond(if (request.url.encodedPath.endsWith("/models")) """{"data":[{"id":"$model"}]}""" else """{"id":"$model"}""", HttpStatusCode.OK)
                    else if (format == "responses" && !(request.body as TextContent).text.contains("input_image")) respond(response(format, true), HttpStatusCode.OK)
                    else {
                        posts++
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        budget = body.getValue(if (format == "responses") "max_output_tokens" else "max_tokens").jsonPrimitive.int
                        respond(response(format, budget >= 128), HttpStatusCode.OK)
                    }
                }).use { http ->
                    val result = adapter(format, http).probe(profile, "fixture-secret".toCharArray(), ProbeConsent.GRANTED)
                    assertEquals(CapabilityCheckStatus.VERIFIED, result.checks.single { it.capability == CapabilityCheck.IMAGE }.status)
                    assertTrue(budget in 128..1024, "$format probe budget=$budget")
                    assertEquals(1, posts, "No larger paid retry")
                }
            }
        }
    }

    @TestFactory
    fun reasoningOnlyImageProbeIsInconclusiveAndNeverAutomaticallyReplayed() = formats.map { format ->
        DynamicTest.dynamicTest("$format image reasoning exhaustion is inconclusive") {
            runBlocking {
                var posts = 0
                HttpClient(MockEngine { request ->
                    if (request.method == HttpMethod.Get) respond(if (request.url.encodedPath.endsWith("/models")) """{"data":[{"id":"$model"}]}""" else """{"id":"$model"}""", HttpStatusCode.OK)
                    else if (format == "responses" && !(request.body as TextContent).text.contains("input_image")) respond(response(format, true), HttpStatusCode.OK)
                    else {
                        posts++
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals(64, body.getValue(if (format == "responses") "max_output_tokens" else "max_tokens").jsonPrimitive.int)
                        respond(response(format, false), HttpStatusCode.OK)
                    }
                }).use { http ->
                    val limited = profile.copy(outputLimit = 64, outputLimitMode = OutputLimitMode.MANUAL)
                    val result = adapter(format, http).probe(limited, "fixture-secret".toCharArray(), ProbeConsent.GRANTED)
                    val image = result.checks.single { it.capability == CapabilityCheck.IMAGE }
                    assertEquals(CapabilityCheckStatus.UNKNOWN, image.status)
                    assertFalse(result.supportsImages)
                    assertTrue(result.charged)
                    assertEquals(1, posts)
                }
            }
        }
    }
    @TestFactory
    fun catalogRejectsUntrustedOrAmbiguousFacts() = formats.flatMap { format ->
        listOf(
            """{"data":[]}""",
            """{"data":[{"id":"other","context_length":1000000}]}""",
            """{"data":[{"id":"$model","context_length":"1000000"}]}""",
            """{"data":[{"id":"$model","context_length":0}]}""",
            """{"data":[{"id":"$model","context_length":-1}]}""",
            """{"data":[{"id":"$model","context_length":1.5}]}""",
            """{"data":[{"id":"$model","context_length":2147483648}]}""",
            """{"data":[{"id":"$model","context_length":8192},{"id":"$model","context_length":1000000}]}""",
            "x".repeat(2_000_001),
        ).mapIndexed { index, raw ->
            DynamicTest.dynamicTest("$format invalid catalog $index remains unknown") {
                runBlocking {
                    HttpClient(MockEngine { respond(raw, HttpStatusCode.OK) }).use { http ->
                        val fact = ContextWindowProducer().metadata(contextWindowTarget(profile.providerId, base, model), profile, adapter(format, http))
                        assertNull(fact.value)
                        assertEquals(ContextLimitSource.UNKNOWN, fact.source)
                    }
                }
            }
        }
    }

    @TestFactory
    fun catalogNeverQueriesWrongOrigins() = listOf(
        "http://api.commandcode.ai/provider/v1",
        "$base?key=untrusted", "$base#fragment",
        "https://user@api.commandcode.ai/provider/v1",
        "https://api.commandcode.ai:8443/provider/v1",
        "https://api.commandcode.ai/other/v1",
    ).map { url ->
        DynamicTest.dynamicTest("reject catalog origin $url") {
            assertFalse(CommandCodeContextCatalog.eligible(url, model))
        }
    }

    @TestFactory
    fun malformedImageResponsesStillFail() = formats.map { format ->
        DynamicTest.dynamicTest("$format malformed response is not inconclusive") {
            runBlocking {
                HttpClient(MockEngine { request ->
                    if (request.method == HttpMethod.Get) respond("""{"id":"$model"}""", HttpStatusCode.OK)
                    else if (format == "responses" && !(request.body as TextContent).text.contains("input_image")) respond(response(format, true), HttpStatusCode.OK)
                    else respond("""{"unexpected":true}""", HttpStatusCode.OK)
                }).use { http ->
                    val result = adapter(format, http).probe(profile, "fixture-secret".toCharArray(), ProbeConsent.GRANTED)
                    assertEquals(CapabilityCheckStatus.FAILED, result.checks.single { it.capability == CapabilityCheck.IMAGE }.status)
                }
            }
        }
    }

}
