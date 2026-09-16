// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.provider.ParameterMerger
import kotlinx.serialization.json.JsonPrimitive

/**
 * Vertical payload coverage for the automatic output cap: the setting must be
 * visible on the wire, not just in the domain model.
 *
 * AUTO means the application adds no output-limit field at all.  MANUAL sends
 * exactly the field for the target protocol.  An explicit advanced-parameter
 * cap is an override and must be what the user is told about.
 */
class AutoOutputLimitPayloadTest {
    private fun capturingEngine(captured: MutableList<String>) = MockEngine { request ->
        captured += when (val body = request.body) {
            is io.ktor.http.content.TextContent -> body.text
            else -> body.toString()
        }
        respond(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }

    private suspend fun chatPayload(
        outputTokenLimit: Int?,
        parameters: ParameterLayers = ParameterLayers(),
    ): String {
        val captured = mutableListOf<String>()
        val adapter = OpenAiCompatibleAdapter(HttpClient(capturingEngine(captured)), "https://example.invalid/v1")
        adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                parameters = parameters,
                outputTokenLimit = outputTokenLimit,
            ),
            "token".toCharArray(),
        ).toList()
        return captured.single()
    }

    private suspend fun responsesPayload(
        outputTokenLimit: Int?,
        parameters: ParameterLayers = ParameterLayers(),
    ): String {
        val captured = mutableListOf<String>()
        val adapter = OpenAiResponsesAdapter(HttpClient(capturingEngine(captured)), "https://example.invalid/v1")
        adapter.stream(
            ModelRequest(
                modelId = "gpt-responses",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                parameters = parameters,
                outputTokenLimit = outputTokenLimit,
            ),
            "token".toCharArray(),
        ).toList()
        return captured.single()
    }

    @Test
    fun chatAutoSendsNoOutputLimitField() = runTest {
        val body = chatPayload(null)
        assertFalse(body.contains("\"max_tokens\""), body)
        assertFalse(body.contains("\"max_completion_tokens\""), body)
        assertFalse(body.contains("\"max_output_tokens\""), body)
    }

    @Test
    fun chatManualSendsExactlyTheChatField() = runTest {
        val body = chatPayload(8192)
        assertTrue(body.contains("\"max_tokens\":8192"), body)
        assertFalse(body.contains("\"max_completion_tokens\""), body)
        assertFalse(body.contains("\"max_output_tokens\""), body)
    }

    @Test
    fun responsesAutoSendsNoMaxOutputTokens() = runTest {
        val body = responsesPayload(null)
        assertFalse(body.contains("\"max_output_tokens\""), body)
        assertFalse(body.contains("\"max_tokens\""), body)
        assertFalse(body.contains("\"max_completion_tokens\""), body)
    }

    @Test
    fun responsesManualSendsMaxOutputTokens() = runTest {
        val body = responsesPayload(8192)
        assertTrue(body.contains("\"max_output_tokens\":8192"), body)
        assertFalse(body.contains("\"max_tokens\""), body)
        assertFalse(body.contains("\"max_completion_tokens\""), body)
    }

    /** An explicit advanced-parameter cap wins over AUTO and must be visible. */
    @Test
    fun advancedParameterOverrideIsSentUnderAuto() = runTest {
        val body = chatPayload(null, ParameterLayers(customJson = "{\"max_tokens\":777}"))
        assertTrue(body.contains("\"max_tokens\":777"), body)
    }

    /** A manual cap is a real limit: a larger advanced value is a conflict. */
    @Test
    fun advancedParameterAboveTheManualCapIsRejectedNotSilentlyTrimmed() = runTest {
        val captured = mutableListOf<String>()
        val adapter = OpenAiCompatibleAdapter(HttpClient(capturingEngine(captured)), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(
                modelId = "demo",
                messages = listOf(ChatMessage(role = "user", text = "hi")),
                parameters = ParameterLayers(customJson = "{\"max_tokens\":9000}"),
                outputTokenLimit = 8192,
            ),
            "token".toCharArray(),
        ).toList()
        assertTrue(captured.isEmpty(), "no request may be sent for a conflicting cap")
        assertTrue(events.last() is ModelEvent.Failed, events.toString())
    }

    /** A matching advanced value is the same explicit cap, not a second field. */
    @Test
    fun advancedParameterEqualToTheManualCapSendsOneField() = runTest {
        val body = chatPayload(8192, ParameterLayers(customJson = "{\"max_tokens\":8192}"))
        assertEquals(1, body.split("\"max_tokens\"").size - 1, body)
        assertTrue(body.contains("\"max_tokens\":8192"), body)
    }

    @Test
    fun automaticModeNeverBecomesAZeroOrHugeLimit() = runTest {
        val body = chatPayload(null, ParameterLayers(adapterDefaults = emptyMap(), modelParameters = emptyMap()))
        assertFalse(body.contains("\"max_tokens\":0"), body)
        assertFalse(body.contains("\"max_tokens\":${Int.MAX_VALUE}"), body)
        assertFalse(body.contains("\"max_tokens\":4096"), body)
        assertFalse(body.contains("\"max_tokens\":10240"), body)
    }

    @Test
    fun parameterMergerDoesNotInventAnOutputLimitFromNothing() {
        val merged = ParameterMerger.merge(
            layers = ParameterLayers(customJson = "{\"temperature\":0.2}"),
            legacyExtras = emptyMap(),
            runtimeFields = mapOf<String, kotlinx.serialization.json.JsonElement>("model" to JsonPrimitive("demo")),
            operationId = "unit",
        )
        assertFalse(merged.containsKey("max_tokens"), merged.toString())
        assertFalse(merged.containsKey("max_completion_tokens"), merged.toString())
        assertFalse(merged.containsKey("max_output_tokens"), merged.toString())
    }
}