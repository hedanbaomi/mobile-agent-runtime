// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** A long streamed reply is never cut off by the client's total-request ceiling. */
class StreamingRequestTimeoutTest {
    private val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private fun client(withTimeout: Boolean, seen: MutableList<HttpTimeoutConfig?>) = HttpClient(MockEngine { request ->
        seen += request.getCapabilityOrNull(HttpTimeoutCapability)
        respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }) {
        if (withTimeout) install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
    }

    private val request = ModelRequest(modelId = "demo", messages = listOf(ChatMessage(role = "user", text = "hi")))

    @Test fun chatCompletionsStreamLiftsOnlyTheTotalRequestCeiling() = runBlocking<Unit> {
        val seen = mutableListOf<HttpTimeoutConfig?>()
        val events = OpenAiCompatibleAdapter(client(true, seen), "https://example.invalid/v1")
            .stream(request, "token".toCharArray()).toList()
        assertTrue(events.contains(ModelEvent.Completed))
        val timeout = seen.single()!!
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, timeout.requestTimeoutMillis)
        // Connect and inactivity limits keep the client's bounded values, so a stall still fails.
        assertEquals(180_000L, timeout.socketTimeoutMillis)
        assertEquals(15_000L, timeout.connectTimeoutMillis)
    }

    @Test fun responsesStreamLiftsOnlyTheTotalRequestCeiling() = runBlocking<Unit> {
        val seen = mutableListOf<HttpTimeoutConfig?>()
        OpenAiResponsesAdapter(client(true, seen), "https://example.invalid/v1")
            .stream(request, "token".toCharArray()).toList()
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, seen.single()!!.requestTimeoutMillis)
    }

    @Test fun clientWithoutTimeoutPluginIsLeftUntouched() = runBlocking<Unit> {
        val seen = mutableListOf<HttpTimeoutConfig?>()
        val events = OpenAiCompatibleAdapter(client(false, seen), "https://example.invalid/v1")
            .stream(request, "token".toCharArray()).toList()
        assertTrue(events.contains(ModelEvent.Completed))
        assertNull(seen.single())
    }
}
