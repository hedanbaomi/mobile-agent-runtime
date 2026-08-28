// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class OpenAiSseTest {
    @Test
    fun textDeltaAndDone() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        val delta = OpenAiSse.eventsFromLine(
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            buf,
        )
        val done = OpenAiSse.eventsFromLine("data: [DONE]", buf)
        assertEquals(listOf(ModelEvent.TextDelta("Hello")), delta)
        assertEquals(listOf(ModelEvent.Completed), done)
    }

    @Test
    fun commentsAreIgnored() {
        val buf = linkedMapOf<String, Pair<String, StringBuilder>>()
        assertTrue(OpenAiSse.eventsFromLine(": keep-alive", buf).isEmpty())
    }
}

class OpenAiCompatibleAdapterTest {
    @Test
    fun streamsMockedSseWithoutLeakingSecret() = runTest {
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            assertTrue(auth!!.startsWith("Bearer "))
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(mapOf("role" to "user", "content" to "hi"))),
            "test-secret-token".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.TextDelta("Hi"), ModelEvent.Completed), events)
    }

    @Test
    fun unauthorizedIsMapped() = runTest {
        val engine = MockEngine {
            respond("nope", HttpStatusCode.Unauthorized)
        }
        val adapter = OpenAiCompatibleAdapter(HttpClient(engine), "https://example.invalid/v1")
        val events = adapter.stream(
            ModelRequest(modelId = "demo", messages = listOf(mapOf("role" to "user", "content" to "hi"))),
            "sk-testsecretvalue".toCharArray(),
        ).toList()
        assertEquals(listOf(ModelEvent.Failed("PROVIDER_UNAUTHORIZED")), events)
    }
}
