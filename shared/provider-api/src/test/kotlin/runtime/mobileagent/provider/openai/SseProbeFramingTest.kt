// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.provider.ProviderConnectionResult

class SseProbeFramingTest {
    private val profile = ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("stream"),
        contextLimit = 4096, outputLimit = 256, revision = 1)
    private fun client(body: String) = HttpClient(MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    })

    @Test fun chatConnectionProbeRequiresARealBlankLineAfterDone() = runBlocking {
        val payload = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]"
        for (end in listOf("\n", "\r", "\r\n")) {
            client(payload + end).use { http ->
                assertTrue(OpenAiCompatibleAdapter(http, "https://example.invalid/v1").testConnection(profile,
                    "synthetic".toCharArray()) is ProviderConnectionResult.Failure)
            }
        }
        client(payload + "\n\n").use { http ->
            assertTrue(OpenAiCompatibleAdapter(http, "https://example.invalid/v1").testConnection(profile,
                "synthetic".toCharArray()) is ProviderConnectionResult.Success)
        }
    }

    @Test fun responsesConnectionProbeRequiresARealBlankLineAfterCompleted() = runBlocking {
        val payload = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"
        for (end in listOf("\n", "\r", "\r\n")) {
            client(payload + end).use { http ->
                assertTrue(OpenAiResponsesAdapter(http, "https://example.invalid/v1").testConnection(profile,
                    "synthetic".toCharArray()) is ProviderConnectionResult.Failure)
            }
        }
        client(payload + "\n\n").use { http ->
            assertTrue(OpenAiResponsesAdapter(http, "https://example.invalid/v1").testConnection(profile,
                "synthetic".toCharArray()) is ProviderConnectionResult.Success)
        }
    }
}
