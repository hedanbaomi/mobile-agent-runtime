// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.*
import runtime.mobileagent.provider.openai.OpenAiCompatibleAdapter
import runtime.mobileagent.provider.openai.OpenAiResponsesAdapter

/** Exercises Runtime -> visual grouping -> actual transport JSON -> SSE -> final answer. */
class VisualAnalysisOutputBudgetTest {
    private enum class Protocol { CHAT, RESPONSES }
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jYQAAAABJRU5ErkJggg=="
    private fun images(): List<InlineImage> {
        val bytes = Base64.getDecoder().decode(png)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return (1..6).map { InlineImage("image/png", "", "source-$it", bytes.size.toLong(), hash) }
    }
    private data class Outcome(val run: AgentRun, val bodies: List<JsonObject>, val events: List<RuntimeEvent>)
    private fun execute(
        protocol: Protocol = Protocol.CHAT,
        limit: Int? = 8192,
        field: String? = null,
        parameters: ParameterLayers = ParameterLayers(),
        analysisNotes: String? = null,
    ): Outcome = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val client = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            bodies += body
            val cap = listOf("max_tokens", "max_completion_tokens", "max_output_tokens")
                .firstNotNullOfOrNull { body[it]?.jsonPrimitive?.intOrNull } ?: 8192
            val disabled = body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "disabled" ||
                body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content == "none"
            val exhausted = !disabled && cap <= 1024
            val text = if (bodies.size == 1) "Source notes for six knowledge pages." else "A brief knowledge-base overview."
            val content = if (bodies.size == 1 && analysisNotes != null) analysisNotes else if (body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "json_object") {
                buildJsonObject { put(if (bodies.size == 1) "notes" else "overview", text) }.toString()
            } else text
            val encodedContent = JsonPrimitive(content).toString()
            val response = when (protocol) {
                Protocol.CHAT -> if (exhausted) {
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Fixture reasoning\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":$cap,\"completion_tokens_details\":{\"reasoning_tokens\":$cap}}}\n\n" +
                        "data: [DONE]\n\n"
                } else {
                    "data: {\"choices\":[{\"delta\":{\"content\":$encodedContent}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":32}}\n\n" +
                        "data: [DONE]\n\n"
                }
                Protocol.RESPONSES -> if (exhausted) {
                    "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"Fixture reasoning\"}\n\n" +
                        "data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":100,\"output_tokens\":$cap,\"output_tokens_details\":{\"reasoning_tokens\":$cap}}}}\n\n"
                } else {
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":$encodedContent}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":32}}}\n\n"
                }
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        try {
            val adapter: ModelAdapter = when (protocol) {
                Protocol.CHAT -> OpenAiCompatibleAdapter(client, "https://fixture.invalid/v1")
                Protocol.RESPONSES -> OpenAiResponsesAdapter(client, "https://fixture.invalid/v1")
            }
            val run = AgentRun("run", "snapshot", "conversation", budget = RunBudget(maxModelRounds = 4))
            val events = AgentRuntime(adapter).run(AgentRuntimeRequest(
                run, EffectivePrompt("Honor the configured response format, including JSON when selected.", "", emptyList(), emptyList(), emptyList(), "简要介绍知识库内容", currentImages = images()),
                "selected-model", "fixture-secret".toCharArray(), false,
                parameters = parameters, outputTokenLimit = limit, outputTokenField = field,
                batchAllImages = true, imageLoader = { it.copy(base64 = png) },
            )).toList()
            Outcome(run, bodies, events)
        } finally { client.close() }
    }

    private fun assertOverview(result: Outcome) {
        assertEquals(RunState.COMPLETED, result.run.state,
            "Six-page image analysis must leave a usable receipt and reach the main answer: ${result.run.stopReason}")
        assertEquals(2, result.bodies.size, "One image analysis and one main request; no automatic retry")
        assertEquals(1, result.events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
        val answer = result.events.filterIsInstance<RuntimeEvent.ModelEvent>().map { it.event }
            .filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("A brief knowledge-base overview.", answer)
        assertEquals(2, result.run.modelRounds)
        assertEquals(2, result.events.filterIsInstance<RuntimeEvent.ModelEvent>().count { it.event is ModelEvent.Usage })
        assertEquals("selected-model", result.bodies.first()["model"]?.jsonPrimitive?.content)
        assertFalse(result.bodies.first().containsKey("tools"))
        assertEquals(6, Regex("\\\"type\\\":\\\"(?:image_url|input_image)\\\"").findAll(result.bodies.first().toString()).count())
        assertFalse(result.bodies.last().toString().contains(png), "Analyzed images must not be retransmitted to the main request")
    }

    @Test fun sixKnowledgeImagesUseSelectedManualBudgetAndReachOverview() {
        for (protocol in Protocol.entries) {
            val result = execute(protocol)
            assertOverview(result)
            assertEquals(8192, result.bodies.first()[if (protocol == Protocol.CHAT) "max_tokens" else "max_output_tokens"]?.jsonPrimitive?.int)
        }
    }
    @Test fun automaticModeDoesNotInventAVisualOutputCapForEitherProtocol() {
        for (protocol in Protocol.entries) {
            val result = execute(protocol, limit = null)
            assertOverview(result)
            for (body in result.bodies) for (key in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
                assertFalse(body.containsKey(key), "$protocol AUTO added $key")
            }
        }
    }
    @Test fun explicitOutputAliasesRemainUnchangedInEachProtocol() {
        for ((protocol, field) in listOf(Protocol.CHAT to "max_completion_tokens", Protocol.RESPONSES to "max_output_tokens")) {
            val result = execute(protocol, field = field,
                parameters = ParameterLayers(modelParameters = mapOf(field to JsonPrimitive(8192))))
            assertOverview(result)
            for (body in result.bodies) {
                assertEquals(8192, body[field]?.jsonPrimitive?.int)
                assertEquals(1, body.keys.count { it in setOf("max_tokens", "max_completion_tokens", "max_output_tokens") })
            }
        }
    }
    @Test fun disabledThinkingAndParameterLayerPrecedenceSurviveSmallExplicitBudget() {
        val result = execute(limit = 512, parameters = ParameterLayers(
            adapterDefaults = mapOf("temperature" to JsonPrimitive(0.8)),
            modelParameters = mapOf("temperature" to JsonPrimitive(0.6), "thinking" to buildJsonObject { put("type", "enabled") }),
            agentOverrides = mapOf("temperature" to JsonPrimitive(0.4)),
            customJson = "{\"thinking\":{\"type\":\"disabled\"},\"temperature\":0.2}",
        ))
        assertOverview(result)
        for (body in result.bodies) {
            assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals(0.2, body["temperature"]?.jsonPrimitive?.double)
            assertEquals(512, body["max_tokens"]?.jsonPrimitive?.int)
        }
    }
    @Test fun explicitSmallThinkingBudgetStillFailsWithoutReplayOrInventedAnswer() {
        for (protocol in Protocol.entries) {
            val result = execute(protocol, limit = 512)
            assertEquals(RunState.FAILED, result.run.state)
            assertEquals(1, result.bodies.size)
            assertEquals(1, result.run.modelRounds)
            assertTrue(result.events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().isEmpty())
            val modelEvents = result.events.filterIsInstance<RuntimeEvent.ModelEvent>().map { it.event }
            assertEquals("REASONING_EXHAUSTED", modelEvents.filterIsInstance<ModelEvent.Failed>().single().sanitizedMessage)
            assertTrue(modelEvents.filterIsInstance<ModelEvent.TextDelta>().isEmpty())
            assertEquals(512, result.bodies.single()[if (protocol == Protocol.CHAT) "max_tokens" else "max_output_tokens"]?.jsonPrimitive?.int)
        }
    }

    @Test fun configuredJsonFormatIsRetainedAndExplicitlyRequestedForImageNotes() {
        val format = buildJsonObject { put("type", "json_object") }
        val result = execute(parameters = ParameterLayers(modelParameters = mapOf("response_format" to format)))
        assertEquals(RunState.COMPLETED, result.run.state)
        assertEquals(2, result.bodies.size)
        for (body in result.bodies) assertEquals(format, body["response_format"])
        val instruction = result.bodies.first()["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(instruction.contains("JSON"), "JSON mode requires an explicit instruction in the separate visual request")
        val answer = result.events.filterIsInstance<RuntimeEvent.ModelEvent>().map { it.event }
            .filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("A brief knowledge-base overview.", Json.parseToJsonElement(answer).jsonObject["overview"]?.jsonPrimitive?.content)
        assertEquals(1, result.events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
    }

    @Test fun emptyStructuredNotesFailBeforeReceiptWithoutReplay() {
        val format = buildJsonObject { put("type", "json_object") }
        for (notes in listOf("{}", "[]", "{\"notes\":\" \"}", "{\"notes\":[null,{\"text\":\"\"}]}")) {
            val result = execute(analysisNotes = notes,
                parameters = ParameterLayers(modelParameters = mapOf("response_format" to format)))
            assertEquals(RunState.FAILED, result.run.state, "Empty JSON evidence must not reach the main answer: $notes")
            assertEquals(1, result.bodies.size)
            assertTrue(result.events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().isEmpty())
            assertTrue(result.events.filterIsInstance<RuntimeEvent.ModelEvent>().none { it.event is ModelEvent.TextDelta })
            assertTrue(result.run.stopReason.orEmpty().contains("INVALID_RESPONSE"))
        }
        assertOverview(execute(analysisNotes = "{\"figureCount\":0,\"hasTables\":false}"))
    }

    @Test fun oversizedNotesFailBeforeReceiptWithoutReplayInEitherProtocol() {
        for (protocol in Protocol.entries) {
            val result = execute(protocol, analysisNotes = "x".repeat(16_001))
            assertEquals(RunState.FAILED, result.run.state)
            assertEquals(1, result.bodies.size)
            assertTrue(result.events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().isEmpty())
            assertTrue(result.events.filterIsInstance<RuntimeEvent.ModelEvent>().none { it.event is ModelEvent.TextDelta })
            assertTrue(result.run.stopReason.orEmpty().contains("INVALID_RESPONSE"))
        }
    }
}
