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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.hasAdvancedOutputLimitOverride
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterLayers

/**
 * An explicit advanced-parameter cap is the user's own override; the wrappers
 * must not add a second protocol alias next to it (the adapters reject that as
 * a conflict even though the user only wrote one legal value).
 */
class AdvancedOutputOverridePayloadTest {
    private fun engine(captured: MutableList<String>) = MockEngine { request ->
        captured += (request.body as io.ktor.http.content.TextContent).text
        respond(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }

    private suspend fun chatBody(parameters: ParameterLayers, outputTokenLimit: Int?): String {
        val captured = mutableListOf<String>()
        OpenAiCompatibleAdapter(HttpClient(engine(captured)), "https://example.invalid/v1").stream(
            ModelRequest("demo", listOf(ChatMessage("user", "hi")), parameters = parameters, outputTokenLimit = outputTokenLimit),
            "token".toCharArray(),
        ).toList()
        return captured.single()
    }

    private suspend fun responsesBody(parameters: ParameterLayers, outputTokenLimit: Int?): String {
        val captured = mutableListOf<String>()
        OpenAiResponsesAdapter(HttpClient(engine(captured)), "https://example.invalid/v1").stream(
            ModelRequest("gpt", listOf(ChatMessage("user", "hi")), parameters = parameters, outputTokenLimit = outputTokenLimit),
            "token".toCharArray(),
        ).toList()
        return captured.single()
    }

    @Test
    fun chatNativeOverrideAloneIsSentWithoutAnInjectedAlias() = runTest {
        val body = chatBody(ParameterLayers(customJson = "{\"max_completion_tokens\":4096}"), null)
        assertTrue(body.contains("\"max_completion_tokens\":4096"), body)
        assertFalse(body.contains("\"max_tokens\""), body)
    }

    @Test
    fun responsesNativeOverrideAloneIsSentWithoutAnInjectedAlias() = runTest {
        val body = responsesBody(ParameterLayers(customJson = "{\"max_output_tokens\":4096}"), null)
        assertTrue(body.contains("\"max_output_tokens\":4096"), body)
        assertFalse(body.contains("\"max_tokens\""), body)
    }

    @Test
    fun overrideDetectionOnlyMatchesRealOutputAliases() {
        assertTrue(hasAdvancedOutputLimitOverride("{\"max_completion_tokens\":4096}"))
        assertTrue(hasAdvancedOutputLimitOverride(null, "{\"max_output_tokens\":4096}"))
        assertFalse(hasAdvancedOutputLimitOverride("{\"temperature\":0.2}"))
        assertFalse(hasAdvancedOutputLimitOverride("not json", null))
    }
}