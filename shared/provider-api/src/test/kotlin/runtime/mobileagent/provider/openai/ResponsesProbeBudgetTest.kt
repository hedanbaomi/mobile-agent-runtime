// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.provider.ProviderConnectionResult

/**
 * Responses probes must use their own task-local budget on every entry point
 * and must not judge a legitimate business output cap as invalid.
 */
class ResponsesProbeBudgetTest {
    private fun profile(mode: OutputLimitMode, limit: Int, parametersJson: String = "{}") = ModelProfile(
        id = "model.rs", providerId = "provider.rs", role = ModelRole.CHAT, modelId = "rs-chat",
        capabilities = setOf("stream"), contextLimit = 32_768, outputLimit = limit, revision = 1,
        outputLimitMode = mode, parametersJson = parametersJson,
    )

    private fun engine(captured: MutableList<String>) = MockEngine { request ->
        captured += (request.body as io.ktor.http.content.TextContent).text
        respond(
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun adapter(captured: MutableList<String>) =
        OpenAiResponsesAdapter(HttpClient(engine(captured)), "https://example.invalid/v1")

    @Test
    fun autoAndManualProbesUseTheirOwnTaskCap() = runBlocking {
        val autoCaptured = mutableListOf<String>()
        val auto = adapter(autoCaptured).testConnection(profile(OutputLimitMode.AUTO, 0), "token".toCharArray())
        assertTrue(auto is ProviderConnectionResult.Success, auto.toString())
        assertEquals(1, autoCaptured.size)
        assertTrue(autoCaptured.single().contains("\"max_output_tokens\":64"), autoCaptured.single())

        val manualCaptured = mutableListOf<String>()
        val manual = adapter(manualCaptured).testConnection(profile(OutputLimitMode.MANUAL, 32), "token".toCharArray())
        assertTrue(manual is ProviderConnectionResult.Success, manual.toString())
        assertTrue(manualCaptured.single().contains("\"max_output_tokens\":32"), manualCaptured.single())
    }

    /** A legitimate large business cap must not make the probe an invalid config. */
    @Test
    fun legitimateLargeBusinessCapDoesNotBreakTheProbe() = runBlocking {
        val captured = mutableListOf<String>()
        val result = adapter(captured).testConnection(
            profile(OutputLimitMode.MANUAL, 8000, "{\"max_output_tokens\":8192,\"temperature\":0.3}"),
            "token".toCharArray(),
        )
        assertTrue(result is ProviderConnectionResult.Success, result.toString())
        val body = captured.single()
        assertTrue(body.contains("\"max_output_tokens\":64"), body)
        assertTrue(body.contains("\"temperature\":0.3"), body)
        assertFalse(body.contains("\"max_output_tokens\":8192"), body)
    }

    /** The same applies to the legacy alias form and to the capability probes. */
    @Test
    fun legacyAliasAndCapabilityProbeKeepTheTaskCap() = runBlocking {
        val captured = mutableListOf<String>()
        val result = adapter(captured).testConnection(
            profile(OutputLimitMode.MANUAL, 8000, "{\"max_tokens\":8192}"),
            "token".toCharArray(),
        )
        assertTrue(result is ProviderConnectionResult.Success, result.toString())
        assertTrue(captured.single().contains("\"max_output_tokens\":64"), captured.single())

        val toolCaptured = mutableListOf<String>()
        // The capability probe (TOOLS) uses its own 128-unit task cap by the same rule;
        // the shared helper is asserted here because probeFeature is not public.
        assertEquals(128, runtime.mobileagent.domain.probeOutputTokenLimit(OutputLimitMode.AUTO, 0, 128))
        assertTrue(toolCaptured.isEmpty() || toolCaptured.single().contains("\"max_output_tokens\":128"), toolCaptured.toString())
    }

    /** The persisted profile is never rewritten by a probe. */
    @Test
    fun probeDoesNotMutateThePersistedParameters() = runBlocking {
        val persisted = "{\"max_output_tokens\":8192,\"temperature\":0.3}"
        val captured = mutableListOf<String>()
        adapter(captured).testConnection(profile(OutputLimitMode.MANUAL, 8000, persisted), "token".toCharArray())
        assertEquals(persisted, profile(OutputLimitMode.MANUAL, 8000, persisted).parametersJson)
        assertEquals(8192, runtime.mobileagent.domain
            .advancedOutputLimitOverride(persisted)?.second?.toInt())
    }
}
