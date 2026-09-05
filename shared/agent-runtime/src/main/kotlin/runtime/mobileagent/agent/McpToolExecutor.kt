// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import runtime.mobileagent.provider.mcp.McpCallResult
import runtime.mobileagent.provider.mcp.McpToolGrant
import runtime.mobileagent.provider.mcp.RemoteMcpAdapter
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/**
 * Bridges the provider-layer MCP state machine into the runtime's generic tool
 * port.  The grant provider is deliberately supplied by the host so a stale
 * or revoked user grant cannot be silently replaced by this adapter.
 */
class McpToolExecutor(
    private val adapter: RemoteMcpAdapter,
    private val grantProvider: () -> McpToolGrant,
) : ToolExecutor {
    override val specs: List<ToolSpec>
        get() {
            val grant = grantProvider()
            if (adapter.isGrantStale(grant)) return emptyList()
            return grant.tools.values.map { tool ->
                ToolSpec(
                    name = tool.namespacedName,
                    description = "[Untrusted MCP server description] ${tool.description}",
                    parametersJson = tool.inputSchema.toString(),
                    capability = "mcp:${tool.namespace}",
                    sideEffect = true,
                )
            }
        }

    override suspend fun invoke(call: ToolCall): ToolResult {
        val arguments = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
            ?: return ToolResult.Invalid("MCP tool arguments must be a JSON object")
        return when (val result = adapter.callTool(grantProvider(), call.callId, call.name, arguments)) {
            is McpCallResult.Success -> ToolResult.Value(result.result.toString())
            is McpCallResult.ToolError -> ToolResult.Invalid(result.result.toString())
            is McpCallResult.ProtocolError -> ToolResult.Invalid("MCP protocol error ${result.code}: ${result.message}")
            is McpCallResult.Denied -> ToolResult.Denied(result.reason)
            is McpCallResult.UnknownOutcome -> ToolResult.UnknownOutcome(result.reason)
        }
    }

    /** MCP grants are explicit user approvals; this bridge never auto-approves a second time. */
    override suspend fun approve(callId: String): ToolResult =
        ToolResult.Invalid("MCP tool grant approval is required before invocation")

    /**
     * Remote MCP tools have no local completed-call store in this bridge, so a
     * cached payload can never be revalidated here.  Deny disclosure
     * fail-closed (b07 follow-up finding A: MCP/remote safe-default); the
     * model must issue a new call id, which passes dispatch-time checks.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean = false
}
