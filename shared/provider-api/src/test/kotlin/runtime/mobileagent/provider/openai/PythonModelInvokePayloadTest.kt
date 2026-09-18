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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.OutputCapSource
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.pythonModelWireDecision
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterLayers

/**
 * The production Python `model.invoke` output decision driven through both real
 * adapters.
 *
 * The broker in `PythonSkillTools` holds the frozen snapshot parameter layers and
 * calls `pythonModelWireDecision`; the assertion here starts from that same
 * production function, maps its result exactly the way the broker does and then
 * lets the real adapter build the payload.  A dropped `outputTokenLimit` is
 * therefore visible as a rejected request or a missing field, never as a green
 * assertion on a copy of the decision logic.
 *
 * Layer priority under test: per-call tool argument, frozen agent override,
 * model advanced parameters, profile default (AUTO sends nothing).
 */
class PythonModelInvokePayloadTest {
    private val outputAliases = listOf("max_tokens", "max_completion_tokens", "max_output_tokens")

    private fun chatEngine(captured: MutableList<String>) = MockEngine { request ->
        captured += (request.body as TextContent).text
        respond(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }

    private fun responsesEngine(captured: MutableList<String>) = MockEngine { request ->
        captured += (request.body as TextContent).text
        respond(
            "{\"status\":\"completed\",\"output_text\":\"ok\",\"output\":[]}",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    /**
     * Exactly the mapping the broker performs after its single resolution: the
     * domain decision plus the frozen parameter layers become one ModelRequest.
     */
    private fun productionRequest(
        modelParametersJson: String,
        agentOverridesJson: String,
        requestedCap: Int?,
        modelId: String,
        mode: OutputLimitMode,
        limit: Int,
    ): ModelRequest {
        val decision = pythonModelWireDecision(mode, limit, modelParametersJson, agentOverridesJson, requestedCap)
        return ModelRequest(
            modelId = modelId,
            messages = listOf(ChatMessage("user", "hi")),
            parameters = ParameterLayers(
                modelParameters = Json.parseToJsonElement(modelParametersJson).jsonObject,
                agentOverrides = Json.parseToJsonElement(agentOverridesJson).jsonObject,
            ),
            outputTokenLimit = decision.outputTokenLimit,
            outputTokenField = decision.outputTokenField,
            operationId = "python-model-invoke-test",
        )
    }

    private fun chatBody(
        modelParametersJson: String = "{}",
        agentOverridesJson: String = "{}",
        requestedCap: Int? = null,
        modelId: String = "py-chat",
        mode: OutputLimitMode = OutputLimitMode.AUTO,
        limit: Int = 0,
    ): String {
        val captured = mutableListOf<String>()
        val adapter = OpenAiCompatibleAdapter(HttpClient(chatEngine(captured)), "https://example.invalid/v1")
        runBlocking {
            adapter.stream(
                productionRequest(modelParametersJson, agentOverridesJson, requestedCap, modelId, mode, limit),
                "token".toCharArray(),
            ).toList()
        }
        return captured.single()
    }

    private fun responsesBody(
        modelParametersJson: String = "{}",
        agentOverridesJson: String = "{}",
        requestedCap: Int? = null,
        modelId: String = "py-responses",
        mode: OutputLimitMode = OutputLimitMode.AUTO,
        limit: Int = 0,
    ): String {
        val captured = mutableListOf<String>()
        val adapter = OpenAiResponsesAdapter(HttpClient(responsesEngine(captured)), "https://example.invalid/v1")
        runBlocking {
            adapter.stream(
                productionRequest(modelParametersJson, agentOverridesJson, requestedCap, modelId, mode, limit),
                "token".toCharArray(),
            ).toList()
        }
        return captured.single()
    }

    private fun JsonObject.outputFieldCount(): Int = outputAliases.count { containsKey(it) }

    private fun JsonObject.outputLimitOf(field: String): Long? =
        (this[field] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun assertSingleCap(body: String, field: String, value: Long) {
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(1, root.outputFieldCount(), body)
        assertEquals(value, root.outputLimitOf(field), body)
    }

    private fun assertNoCap(body: String) {
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(0, root.outputFieldCount(), body)
    }

    // ---- AUTO: the decision sends nothing because it never invents a cap ----

    @Test
    fun chatAutoWithoutOverrideSendsNoOutputField() {
        assertNoCap(chatBody())
    }

    @Test
    fun responsesAutoWithoutOverrideSendsNoOutputField() {
        assertNoCap(responsesBody())
    }

    /** A MANUAL profile is an explicit user limit: it must survive the decision. */
    @Test
    fun chatManualProfileSendsTheManualCap() {
        assertSingleCap(chatBody(mode = OutputLimitMode.MANUAL, limit = 8192), "max_tokens", 8192)
    }

    @Test
    fun responsesManualProfileSendsTheProtocolNativeCap() {
        assertSingleCap(responsesBody(mode = OutputLimitMode.MANUAL, limit = 8192), "max_output_tokens", 8192)
    }

    // ---- Frozen agent override beats the model advanced parameter ----

    @Test
    fun chatAgentOverrideBeatsTheModelAdvancedParameter() {
        val body = chatBody(
            modelParametersJson = "{\"max_tokens\":512}",
            agentOverridesJson = "{\"max_completion_tokens\":4096}",
        )
        assertSingleCap(body, "max_completion_tokens", 4096)
        assertFalse(body.contains("\"max_tokens\":512"), body)
    }

    @Test
    fun responsesAgentOverrideBeatsTheModelAdvancedParameter() {
        val body = responsesBody(
            modelParametersJson = "{\"max_tokens\":512}",
            agentOverridesJson = "{\"max_output_tokens\":4096}",
        )
        assertSingleCap(body, "max_output_tokens", 4096)
    }

    @Test
    fun chatAutoWithAgentOverrideSendsThatValue() {
        assertSingleCap(chatBody(agentOverridesJson = "{\"max_completion_tokens\":1024}"), "max_completion_tokens", 1024)
    }

    // ---- The per-call tool argument is the most specific override ----

    @Test
    fun chatToolCapReplacesTheModelCapWithoutAnAliasConflict() {
        val body = chatBody(
            modelParametersJson = "{\"max_tokens\":4096}",
            requestedCap = 512,
        )
        assertSingleCap(body, "max_tokens", 512)
    }

    @Test
    fun responsesToolCapReplacesTheModelCapWithoutAnAliasConflict() {
        val body = responsesBody(
            modelParametersJson = "{\"max_tokens\":4096}",
            requestedCap = 512,
        )
        assertSingleCap(body, "max_output_tokens", 512)
    }

    // ---- The decision object itself: source and value, both protocols ----

    @Test
    fun decisionReportsTheSourceAndOneValuePerLayer() {
        val auto = pythonModelWireDecision(OutputLimitMode.AUTO, 0, "{}", "{}", null)
        assertNull(auto.outputTokenLimit)
        assertNull(auto.outputTokenField)
        assertEquals(OutputCapSource.PROVIDER_DEFAULT, auto.source)

        val manual = pythonModelWireDecision(OutputLimitMode.MANUAL, 8192, "{}", "{}", null)
        assertEquals(8192, manual.outputTokenLimit)
        assertNull(manual.outputTokenField, "a MANUAL profile keeps the protocol alias, not one chosen for it")
        assertEquals(OutputCapSource.PROFILE_MANUAL, manual.source)

        val agent = pythonModelWireDecision(
            OutputLimitMode.AUTO, 0, "{\"max_tokens\":512}", "{\"max_completion_tokens\":4096}", null,
        )
        assertEquals(4096, agent.outputTokenLimit)
        assertEquals("max_completion_tokens", agent.outputTokenField)
        assertEquals(OutputCapSource.ADVANCED_OVERRIDE, agent.source)

        val tool = pythonModelWireDecision(OutputLimitMode.MANUAL, 4096, "{}", "{}", 512)
        assertEquals(512, tool.outputTokenLimit)
        assertEquals("max_tokens", tool.outputTokenField)
    }

    /**
     * The adapters are the last line of defence: if a future caller ever manages
     * to construct a request with an alias but no value, both protocols refuse it
     * instead of quietly becoming AUTO.
     */
    @Test
    fun bothAdaptersRefuseAnAliasWithoutABudget() {
        val chatCaptured = mutableListOf<String>()
        val chatEvents = runBlocking {
            OpenAiCompatibleAdapter(HttpClient(chatEngine(chatCaptured)), "https://example.invalid/v1")
                .stream(
                    ModelRequest(
                        modelId = "py-chat",
                        messages = listOf(ChatMessage("user", "hi")),
                        outputTokenLimit = null,
                        outputTokenField = "max_completion_tokens",
                    ),
                    "token".toCharArray(),
                ).toList()
        }
        assertTrue(chatCaptured.isEmpty(), "no HTTP request may be sent for a field without a budget")
        assertEquals("INVALID_CONFIG", (chatEvents.lastOrNull() as? ModelEvent.Failed)?.sanitizedMessage, chatEvents.toString())

        val responsesCaptured = mutableListOf<String>()
        val responsesEvents = runBlocking {
            OpenAiResponsesAdapter(HttpClient(responsesEngine(responsesCaptured)), "https://example.invalid/v1")
                .stream(
                    ModelRequest(
                        modelId = "py-responses",
                        messages = listOf(ChatMessage("user", "hi")),
                        outputTokenLimit = null,
                        outputTokenField = "max_output_tokens",
                    ),
                    "token".toCharArray(),
                ).toList()
        }
        assertTrue(responsesCaptured.isEmpty(), "no HTTP request may be sent for a field without a budget")
        assertEquals("INVALID_CONFIG", (responsesEvents.lastOrNull() as? ModelEvent.Failed)?.sanitizedMessage, responsesEvents.toString())
    }
}
