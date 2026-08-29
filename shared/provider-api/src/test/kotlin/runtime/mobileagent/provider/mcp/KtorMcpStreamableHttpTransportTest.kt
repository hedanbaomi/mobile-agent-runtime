// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.RequestHeaderValue

class KtorMcpStreamableHttpTransportTest {
    @Test
    fun postsJsonRpcWithSessionAndResolvesSecretHeaderWithoutReplay() = runTest {
        var requests = 0
        var sessionSeen: String? = null
        var customHeader: String? = null
        val engine = MockEngine { request ->
            requests += 1
            sessionSeen = request.headers["MCP-Session-Id"]
            customHeader = request.headers["X-Executor"]
            assertEquals(MCP_PROTOCOL_VERSION_2025_06_18, request.headers["MCP-Protocol-Version"])
            if (requests == 1) {
                respond(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        "MCP-Session-Id" to listOf("session-1"),
                    ),
                )
            } else {
                respond(
                    "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }
        }
        val transport = KtorMcpStreamableHttpTransport(
            http = HttpClient(engine),
            endpoint = "https://example.invalid/mcp",
            defaultHeaders = mapOf("X-Executor" to RequestHeaderValue.SecretRef("executor-secret")),
            headerSecretResolver = { host, ref ->
                assertEquals("example.invalid", host)
                assertEquals("executor-secret", ref)
                "secret-header".toCharArray()
            },
        )

        val first = transport.request(buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(1))
            put("method", JsonPrimitive("initialize"))
        })
        val second = transport.request(buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(2))
            put("method", JsonPrimitive("tools/list"))
        })

        assertTrue(first is McpTransportResponse.Messages)
        assertTrue(second is McpTransportResponse.Messages)
        assertEquals(2, requests)
        assertEquals("session-1", sessionSeen)
        assertEquals("secret-header", customHeader)
    }
}
