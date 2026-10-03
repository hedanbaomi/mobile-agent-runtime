// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.mcp.*
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.tooling.ToolExecutorFactory

class McpLiveAuthorizationTest {
    @Test fun revocationDuringInitializationOrDiscoveryPreventsToolDispatch(): Unit = runBlocking {
        for (method in listOf("initialize", "tools/list")) {
            for (unavailable in listOf(false, true)) {
                val f = fixture()
                f.transport.holdMethod = method
                val call = ToolCall("revoked-$method-$unavailable", f.executor.specs.single().name, "{}")
                assertEquals(ToolResult.NeedsApproval, f.executor.invoke(call))
                val approved = async { f.executor.approve(call.callId) }
                withTimeout(5_000) { f.transport.entered.await() }
                f.live.set(if (unavailable) null else f.config.copy(grants = emptyList(), snapshots = emptyList()))
                f.transport.release.complete(Unit)
                assertTrue(approved.await() is ToolResult.Denied)
                assertEquals(0, f.transport.methods.count { it == "tools/call" })
                if (method == "initialize") assertFalse("tools/list" in f.transport.methods)
            }
        }
    }

    @Test fun revocationOrReadFailureAfterDispatchWithholdsResultAndNeverReplays(): Unit = runBlocking {
        for (readFailure in listOf(false, true)) {
            for (remoteUnknown in listOf(false, true)) {
                val f = fixture()
                f.transport.holdMethod = "tools/call"
                f.transport.unknownCall = remoteUnknown
                val call = ToolCall("dispatched-$readFailure-$remoteUnknown", f.executor.specs.single().name, "{}")
                assertEquals(ToolResult.NeedsApproval, f.executor.invoke(call))
                val approved = async { f.executor.approve(call.callId) }
                withTimeout(5_000) { f.transport.entered.await() }
                if (readFailure) f.failRead = true else f.live.set(f.config.copy(grants = emptyList(), snapshots = emptyList()))
                f.transport.release.complete(Unit)
                val result = approved.await()
                assertTrue(result is ToolResult.UnknownOutcome)
                assertFalse(result.toString().contains(SENTINEL))
                assertFalse(result.toString().contains("private-read-error"))
                f.failRead = false; f.live.set(f.config)
                assertFalse(f.executor.authorizeReplay(call))
                f.executor.approve(call.callId)
                f.executor.invoke(call)
                assertEquals(1, f.transport.methods.count { it == "tools/call" })
            }
        }
    }

    @Test fun validBindingReturnsOneResultAndStillDisallowsCachedReplay(): Unit = runBlocking {
        val f = fixture()
        val call = ToolCall("valid-call", f.executor.specs.single().name, "{}")
        assertEquals(ToolResult.NeedsApproval, f.executor.invoke(call))
        val result = f.executor.approve(call.callId)
        assertTrue(result is ToolResult.Value)
        assertTrue((result as ToolResult.Value).json.contains(SENTINEL))
        assertFalse(f.executor.authorizeReplay(call))
        f.executor.invoke(call); f.executor.approve(call.callId)
        assertEquals(1, f.transport.methods.count { it == "tools/call" })
    }

    private suspend fun fixture(): Fixture {
        val transport = Transport()
        val bootstrap = RemoteMcpAdapter(transport, "fixture")
        bootstrap.initialize()
        val tools = bootstrap.discoverTools()
        val tool = tools.single()
        val fingerprint = mcpFingerprint(tools)
        val hashes = mapOf(tool.namespacedName to tool.schemaHash)
        val config = McpStoredConfig(endpoint = "https://example.invalid/mcp", host = "example.invalid", namespace = "fixture",
            networkApprovedAt = "2026-10-03T00:00:00Z", discoveryRevision = 1, discoveryFingerprint = fingerprint,
            tools = listOf(McpStoredTool(tool.namespacedName, inputSchemaJson = tool.inputSchema.toString(), schemaHash = tool.schemaHash)),
            grants = listOf(McpStoredGrant("agent", "grant", 1, hashes.keys.toList(), hashes)),
            snapshots = listOf(McpStoredSnapshot("snapshot", "agent", "grant", 1, fingerprint, hashes.keys.toList(), hashes)))
        val snapshot = McpSnapshot("snapshot", "agent", config.endpoint, config.host, config.namespace, "grant", 1, fingerprint,
            listOf(McpSnapshotTool(tool.namespacedName, "fixture", tool.inputSchema.toString(), tool.schemaHash)))
        transport.methods.clear()
        val f = Fixture(config, transport)
        val child = mcpTools(snapshot, readConfig = {
            if (f.failRead) error("private-read-error")
            f.live.get()
        }, createAdapter = { _, _, authorized ->
            transport.authorized = authorized
            RemoteMcpAdapter(transport, "fixture")
        })
        f.executor = ToolExecutorFactory(mcp = child).createLegacyExecutor()
        return f
    }

    private class Fixture(val config: McpStoredConfig, val transport: Transport) {
        val live = AtomicReference<McpStoredConfig?>(config)
        var failRead = false
        lateinit var executor: ToolExecutor
    }
    private class Transport : McpStreamableHttpTransport {
        val methods = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdMethod: String? = null
        var unknownCall = false
        var authorized: () -> Boolean = { true }
        override suspend fun request(message: JsonObject): McpTransportResponse {
            if (!authorized()) throw McpDispatchDeniedException()
            val method = message.getValue("method").jsonPrimitive.content
            methods += method
            if (method == holdMethod) { entered.complete(Unit); release.await() }
            if (method == "tools/call" && unknownCall) throw McpTransportException(null, "synthetic unknown")
            val result = when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION_2025_06_18)
                    put("capabilities", buildJsonObject {})
                    put("serverInfo", buildJsonObject { put("name", "fixture"); put("version", "1") })
                }
                "tools/list" -> buildJsonObject {
                    put("tools", buildJsonArray { add(buildJsonObject {
                        put("name", "echo"); put("inputSchema", buildJsonObject { put("type", "object") })
                    }) })
                }
                else -> buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", SENTINEL) }) })
                }
            }
            return McpTransportResponse.Messages(listOf(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", message.getValue("id")); put("result", result)
            }))
        }
        override suspend fun notify(message: JsonObject) = McpTransportResponse.Accepted()
        override suspend fun cancel(requestId: String, reason: String) = McpTransportResponse.Accepted()
    }
    private companion object { const val SENTINEL = "business-result-sentinel" }
}
