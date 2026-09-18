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
import runtime.mobileagent.domain.probeOutputTokenLimit

/**
 * A saved AUTO profile stores 0 in the legacy numeric column; the connection
 * probe must use its own task-local cap instead of interpreting that sentinel
 * as "1 token".
 */
class ProbeBudgetPayloadTest {
    private fun profile(mode: OutputLimitMode, limit: Int, format: ApiFormat = ApiFormat.OPENAI_COMPATIBLE) = ModelProfile(
        id = "model.probe", providerId = "provider.probe", role = ModelRole.CHAT, modelId = "probe-chat",
        capabilities = setOf("stream"), contextLimit = 32_768, outputLimit = limit, revision = 1,
        outputLimitMode = mode,
    )

    private fun engine(captured: MutableList<String>) = MockEngine { request ->
        captured += (request.body as io.ktor.http.content.TextContent).text
        respond(
            "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun autoProbeUsesTheTaskLocalCapNotTheSentinel() = runBlocking {
        val captured = mutableListOf<String>()
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine(captured)), "https://example.invalid/v1")
        adapter.testConnection(profile(OutputLimitMode.AUTO, 0), "token".toCharArray())
        val body = captured.single()
        assertTrue(body.contains("\"max_tokens\":64"), body)
        assertFalse(body.contains("\"max_tokens\":1,"), body)
    }

    /**
     * Real MANUAL probe: assert the returned result, the dispatch count and the
     * body so a non-dispatch is diagnosed from the transport result instead of
     * being hidden behind a helper assertion.
     */
    @Test
    fun manualProbeStillNeverExceedsTheProfileCap() = runBlocking {
        val captured = mutableListOf<String>()
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine(captured)), "https://example.invalid/v1")
        val result = adapter.testConnection(profile(OutputLimitMode.MANUAL, 32), "token".toCharArray())
        println("MANUAL_PROBE_RESULT=$result broadcasts=${captured.size}")
        assertTrue(result is runtime.mobileagent.provider.ProviderConnectionResult.Success, result.toString())
        assertEquals(1, captured.size, result.toString())
        assertTrue(captured.single().contains("\"max_tokens\":32"), captured.single())
    }
}