// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpStreamableHttpTest {
    @Test
    fun initializeDiscoverFreezeAndCallUseNamespacedExplicitGrant() = runBlocking {
        val transport = FakeTransport()
        val adapter = RemoteMcpAdapter(transport, namespace = "demo")

        val init = adapter.initialize()
        assertEquals(MCP_PROTOCOL_VERSION_2025_06_18, init.protocolVersion)
        val tools = adapter.discoverTools()
        assertEquals(listOf("demo/echo"), tools.map { it.namespacedName })
        val grant = adapter.freezeGrant("grant-1", 1, setOf("demo/echo"))
        val result = adapter.callTool(grant, "call-1", "demo/echo", JsonObject(emptyMap()))

        assertTrue(result is McpCallResult.Success)
        assertTrue(transport.messages.any { it["method"]?.jsonPrimitive?.content == "notifications/initialized" })
        val call = transport.messages.first { it["method"]?.jsonPrimitive?.content == "tools/call" }
        assertEquals("echo", call["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun listChangeInvalidatesGrantAndCallIdsCannotReplay() = runBlocking {
        val transport = FakeTransport()
        val adapter = RemoteMcpAdapter(transport, namespace = "demo")
        adapter.initialize()
        adapter.discoverTools()
        val grant = adapter.freezeGrant("grant-1", 1, setOf("demo/echo"))
        transport.changedTools = true
        adapter.discoverTools()

        val stale = adapter.callTool(grant, "call-stale", "demo/echo", JsonObject(emptyMap()))
        assertTrue(stale is McpCallResult.Denied)
        transport.changedTools = false
        val freshGrant = adapter.freezeGrant("grant-2", 2, setOf("demo/echo"))
        val first = adapter.callTool(freshGrant, "call-once", "demo/echo", JsonObject(emptyMap()))
        val replay = adapter.callTool(freshGrant, "call-once", "demo/echo", JsonObject(emptyMap()))
        assertTrue(first is McpCallResult.Success)
        assertTrue(replay is McpCallResult.Denied)
    }

    @Test
    fun cancellationNotificationDoesNotClaimRemoteStop() = runBlocking {
        val transport = FakeTransport().apply { holdCall = true }
        val adapter = RemoteMcpAdapter(transport, namespace = "demo")
        adapter.initialize()
        adapter.discoverTools()
        val grant = adapter.freezeGrant("grant-1", 1, setOf("demo/echo"))
        val call = async { adapter.callTool(grant, "call-cancel", "demo/echo", JsonObject(emptyMap())) }
        while (!transport.callStarted) yield()

        val cancelled = adapter.cancel("call-cancel")
        assertTrue(cancelled.accepted)
        assertEquals("UNKNOWN_OUTCOME", cancelled.terminalState)
        transport.releaseCall.complete(Unit)
        call.await()
    }

    private class FakeTransport : McpStreamableHttpTransport {
        val messages = mutableListOf<JsonObject>()
        val releaseCall = CompletableDeferred<Unit>()
        var holdCall = false
        var callStarted = false
        var changedTools = false

        override suspend fun request(message: JsonObject): McpTransportResponse {
            messages += message
            val method = message["method"]?.jsonPrimitive?.content
            val id = message["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            return when (method) {
                "initialize" -> McpTransportResponse.Messages(
                    listOf(
                        buildJsonObject {
                            put("jsonrpc", JsonPrimitive("2.0"))
                            put("id", JsonPrimitive(id))
                            put("result", buildJsonObject {
                                put("protocolVersion", JsonPrimitive(MCP_PROTOCOL_VERSION_2025_06_18))
                                put("capabilities", buildJsonObject {})
                                put("serverInfo", buildJsonObject { put("name", JsonPrimitive("fixture")) })
                            })
                        },
                    ),
                )
                "tools/list" -> McpTransportResponse.Messages(
                    listOf(
                        buildJsonObject {
                            put("jsonrpc", JsonPrimitive("2.0"))
                            put("id", JsonPrimitive(id))
                            put("result", buildJsonObject {
                                put("tools", kotlinx.serialization.json.buildJsonArray {
                                    add(buildJsonObject {
                                        put("name", JsonPrimitive("echo"))
                                        put("description", JsonPrimitive(if (changedTools) "changed" else "echo"))
                                        put("inputSchema", buildJsonObject { put("type", JsonPrimitive("object")) })
                                    })
                                })
                            })
                        },
                    ),
                )
                "tools/call" -> {
                    callStarted = true
                    if (holdCall) releaseCall.await()
                    McpTransportResponse.Messages(
                        listOf(
                            buildJsonObject {
                                put("jsonrpc", JsonPrimitive("2.0"))
                                put("id", JsonPrimitive(id))
                                put("result", buildJsonObject { put("content", kotlinx.serialization.json.buildJsonArray {}) })
                            },
                        ),
                    )
                }
                else -> McpTransportResponse.Messages(emptyList())
            }
        }

        override suspend fun notify(message: JsonObject): McpTransportResponse {
            messages += message
            return McpTransportResponse.Accepted()
        }

        override suspend fun cancel(requestId: String, reason: String): McpTransportResponse {
            messages += buildJsonObject {
                put("method", JsonPrimitive("notifications/cancelled"))
                put("requestId", JsonPrimitive(requestId))
            }
            return McpTransportResponse.Accepted()
        }
    }
}
