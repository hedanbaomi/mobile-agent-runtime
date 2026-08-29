// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.mcp.McpCallResult
import runtime.mobileagent.provider.mcp.McpClientInfo
import runtime.mobileagent.provider.mcp.KtorMcpStreamableHttpTransport
import runtime.mobileagent.provider.mcp.RemoteMcpAdapter
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/** Immutable MCP binding captured for one persisted Agent snapshot. */
data class McpSnapshot(
    val snapshotId: String,
    val agentId: String,
    val endpoint: String,
    val host: String,
    val namespace: String,
    val grantId: String,
    val discoveryRevision: Long,
    val discoveryFingerprint: String,
    val tools: List<McpSnapshotTool>,
)

data class McpSnapshotTool(
    val namespacedName: String,
    val description: String,
    val inputSchemaJson: String,
    val schemaHash: String,
)

/**
 * Capture the currently approved MCP tool list for a real Agent snapshot.
 * A missing endpoint, discovery, grant, or persisted snapshot binding returns
 * null and therefore cannot silently enable network tools.
 */
fun captureMcpSnapshot(container: AppContainer, snapshotId: String, agentId: String): McpSnapshot? {
    if (snapshotId.isBlank() || agentId.isBlank()) return null
    val agentSnapshot = runCatching { container.agents.getSnapshot(snapshotId) }.getOrNull() ?: return null
    if (agentSnapshot.agentId != agentId) return null
    val config = McpConfigStore.read(container).value ?: return null
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision <= 0) return null
    val fingerprint = config.discoveryFingerprint ?: return null
    val grant = config.grants.singleOrNull { it.agentId == agentId } ?: return null
    val byName = config.tools.associateBy { it.namespacedName }
    if (grant.toolNames.isEmpty() || grant.toolNames.any { it !in byName }) return null
    if (grant.schemaHashes.keys != grant.toolNames.toSet()) return null
    if (grant.schemaHashes.any { (name, hash) -> byName[name]?.schemaHash != hash }) return null
    if (grant.revision != config.discoveryRevision) return null

    val existing = config.snapshots.singleOrNull { it.snapshotId == snapshotId && it.agentId == agentId }
    val stored = existing ?: McpStoredSnapshot(
        snapshotId = snapshotId,
        agentId = agentId,
        grantId = grant.grantId,
        revision = grant.revision,
        discoveryFingerprint = fingerprint,
        toolNames = grant.toolNames.sorted(),
        schemaHashes = grant.schemaHashes.toSortedMap(),
    ).also { created ->
        McpConfigStore.write(
            container,
            config.copy(
                snapshots = (config.snapshots.filterNot { it.snapshotId == snapshotId } + created).takeLast(MAX_SNAPSHOTS),
            ),
        )
    }
    if (stored.grantId != grant.grantId || stored.revision != grant.revision ||
        stored.discoveryFingerprint != fingerprint || stored.toolNames.toSet() != grant.toolNames.toSet() ||
        stored.schemaHashes != grant.schemaHashes
    ) return null
    return publicMcpSnapshot(config, stored)
}

/**
 * Load only a binding that was already persisted for this exact Agent snapshot.
 * This is intentionally read-only: unlike [captureMcpSnapshot], it never creates
 * a grant or snapshot entry for an older session.
 */
fun loadMcpSnapshot(container: AppContainer, snapshotId: String, agentId: String): McpSnapshot? {
    if (snapshotId.isBlank() || agentId.isBlank()) return null
    val agentSnapshot = runCatching { container.agents.getSnapshot(snapshotId) }.getOrNull() ?: return null
    if (agentSnapshot.agentId != agentId) return null
    val config = McpConfigStore.read(container).value ?: return null
    val stored = config.snapshots.singleOrNull { it.snapshotId == snapshotId && it.agentId == agentId }
        ?: return null
    val snapshot = publicMcpSnapshot(config, stored) ?: return null
    return snapshot.takeIf { snapshotBindingIsCurrent(config, it) }
}

private fun publicMcpSnapshot(config: McpStoredConfig, stored: McpStoredSnapshot): McpSnapshot? {
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision <= 0) return null
    val fingerprint = config.discoveryFingerprint ?: return null
    if (stored.revision != config.discoveryRevision || stored.discoveryFingerprint != fingerprint) return null
    val grant = config.grants.singleOrNull { it.agentId == stored.agentId } ?: return null
    if (grant.grantId != stored.grantId || grant.revision != stored.revision) return null
    if (grant.toolNames.toSet() != stored.toolNames.toSet() || grant.schemaHashes != stored.schemaHashes) return null
    val byName = config.tools.associateBy { it.namespacedName }
    val tools = stored.toolNames.mapNotNull { name ->
        byName[name]?.let { tool ->
            McpSnapshotTool(tool.namespacedName, tool.description, tool.inputSchemaJson, tool.schemaHash)
        }
    }
    if (tools.size != stored.toolNames.size || tools.isEmpty()) return null
    return McpSnapshot(
        snapshotId = stored.snapshotId,
        agentId = stored.agentId,
        endpoint = config.endpoint,
        host = config.host,
        namespace = config.namespace,
        grantId = stored.grantId,
        discoveryRevision = stored.revision,
        discoveryFingerprint = stored.discoveryFingerprint,
        tools = tools,
    )
}

/**
 * Return an app-level MCP executor for a previously captured binding.  The
 * returned empty executor is deliberate for old sessions, revoked grants, or
 * any metadata mismatch.  Construction never performs network I/O.
 */
fun mcpTools(container: AppContainer, snapshot: McpSnapshot): ToolExecutor {
    val config = McpConfigStore.read(container).value ?: return EmptyMcpToolExecutor
    if (!snapshotBindingIsCurrent(config, snapshot)) return EmptyMcpToolExecutor
    return AppMcpToolExecutor(container, snapshot)
}

/**
 * Resolve MCP for an existing AgentSnapshot without capturing a new binding.
 * Old sessions therefore remain MCP-disabled unless their binding was captured
 * at session creation and is still valid under the current grant.
 */
fun mcpTools(container: AppContainer, snapshot: AgentSnapshot): ToolExecutor =
    loadMcpSnapshot(container, snapshot.id, snapshot.agentId)?.let { mcpTools(container, it) }
        ?: EmptyMcpToolExecutor

/** Explicitly empty executor used when no valid MCP grant is available. */
private object EmptyMcpToolExecutor : ToolExecutor {
    override val specs: List<ToolSpec> = emptyList()

    override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Denied("MCP tool grant is not configured")

    override suspend fun approve(callId: String): ToolResult = ToolResult.Denied("MCP tool grant is not configured")
}

private class AppMcpToolExecutor(
    private val container: AppContainer,
    private val snapshot: McpSnapshot,
) : ToolExecutor {
    private val usedCallIds = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, ToolCall>()
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    /**
     * Every MCP call is treated as approval-required, including tools that the
     * remote server labels read-only.  The description carries destination and
     * scope context for the approval UI; remote prose remains untrusted.
     */
    override val specs: List<ToolSpec> = snapshot.tools.map { tool ->
        ToolSpec(
            name = tool.namespacedName,
            description = buildString {
                append("[Untrusted MCP server description] ")
                append(tool.description.take(4096))
                append(" Tool: ")
                append(tool.namespacedName)
                append(" Destination: ")
                append(snapshot.endpoint)
                append(" (host ")
                append(snapshot.host)
                append("). Scope: mcp:")
                append(snapshot.namespace)
                append(", grant ")
                append(snapshot.grantId)
                append("; explicit approval is required for every call.")
            },
            parametersJson = tool.inputSchemaJson,
            capability = "mcp:${snapshot.namespace}",
            sideEffect = true,
        )
    }

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (call.callId.isBlank()) return ToolResult.Invalid("MCP call ID is missing")
        if (usedCallIds.contains(call.callId)) return ToolResult.Invalid("MCP call ID was already used")
        if (snapshot.tools.none { it.namespacedName == call.name }) return ToolResult.Invalid("MCP tool is not in the snapshot grant")
        val args = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
            ?: return ToolResult.Invalid("MCP tool arguments must be a JSON object")
        if (!usedCallIds.add(call.callId)) return ToolResult.Invalid("MCP call ID was already used")
        pending[call.callId] = call.copy(argumentsJson = args.toString())
        return ToolResult.NeedsApproval
    }

    override suspend fun approve(callId: String): ToolResult {
        val call = pending.remove(callId) ?: return ToolResult.Invalid("No pending MCP approval")
        return try {
            executeApproved(call)
        } catch (error: CancellationException) {
            // The call ID remains consumed; a caller must not replay after an
            // interrupted network operation whose remote outcome is unknown.
            throw error
        } catch (error: Exception) {
            ToolResult.UnknownOutcome("MCP outcome is unknown: ${safeMessage(error)}")
        }
    }

    private suspend fun executeApproved(call: ToolCall): ToolResult {
        val config = McpConfigStore.read(container).value
            ?: return ToolResult.Denied("MCP configuration is unavailable")
        if (!snapshotBindingIsCurrent(config, snapshot)) {
            return ToolResult.Denied("MCP snapshot grant is stale or revoked")
        }
        val adapter = createMcpAdapter(container, config)
        // Re-discover immediately before the approved call.  This one request
        // sequence has no retry or reconnect loop and never adds new tools.
        adapter.initialize()
        val discovered = adapter.discoverTools()
        if (mcpFingerprint(discovered) != snapshot.discoveryFingerprint) {
            return ToolResult.Denied("MCP tool list changed; explicit re-discovery and re-authorization are required")
        }
        val byName = discovered.associateBy { it.namespacedName }
        if (snapshot.tools.any { byName[it.namespacedName]?.schemaHash != it.schemaHash }) {
            return ToolResult.Denied("MCP tool schema changed; explicit re-authorization is required")
        }
        val grant = adapter.freezeGrant(
            grantId = snapshot.grantId,
            revision = snapshot.discoveryRevision,
            toolNames = snapshot.tools.map { it.namespacedName }.toSet(),
        )
        val result = adapter.callTool(
            grant = grant,
            callId = call.callId,
            toolName = call.name,
            arguments = json.parseToJsonElement(call.argumentsJson).jsonObject,
        )
        return when (result) {
            is McpCallResult.Success -> ToolResult.Value(result.result.toString())
            is McpCallResult.ToolError -> ToolResult.Invalid(result.result.toString())
            is McpCallResult.ProtocolError -> ToolResult.Invalid("MCP protocol error ${result.code}: ${result.message}")
            is McpCallResult.Denied -> ToolResult.Denied(result.reason)
            is McpCallResult.UnknownOutcome -> ToolResult.UnknownOutcome(result.reason)
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(256) ?: "transport failure"
}

private fun snapshotBindingIsCurrent(config: McpStoredConfig, snapshot: McpSnapshot): Boolean {
    if (config.endpoint != snapshot.endpoint || config.host != snapshot.host || config.namespace != snapshot.namespace) return false
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision != snapshot.discoveryRevision) return false
    if (config.discoveryFingerprint != snapshot.discoveryFingerprint) return false
    val grant = config.grants.singleOrNull { it.agentId == snapshot.agentId } ?: return false
    if (grant.grantId != snapshot.grantId || grant.revision != snapshot.discoveryRevision) return false
    val storedSnapshot = config.snapshots.singleOrNull { it.snapshotId == snapshot.snapshotId && it.agentId == snapshot.agentId }
        ?: return false
    if (storedSnapshot.grantId != snapshot.grantId || storedSnapshot.revision != snapshot.discoveryRevision ||
        storedSnapshot.discoveryFingerprint != snapshot.discoveryFingerprint || storedSnapshot.toolNames.toSet() != snapshot.tools.map { it.namespacedName }.toSet()
    ) return false
    val configTools = config.tools.associateBy { it.namespacedName }
    return snapshot.tools.all { tool ->
        configTools[tool.namespacedName]?.schemaHash == tool.schemaHash &&
            grant.toolNames.contains(tool.namespacedName) && grant.schemaHashes[tool.namespacedName] == tool.schemaHash
    }
}

/** Shared transport construction with host-bound Android Keystore credentials. */
internal fun createMcpAdapter(container: AppContainer, config: McpStoredConfig): RemoteMcpAdapter {
    val secretRef = config.secretRef
    val headers: Map<String, RequestHeaderValue> =
        if (secretRef == null) emptyMap() else mapOf("Authorization" to RequestHeaderValue.SecretRef(secretRef))
    val resolver = secretRef?.let { expectedRef ->
        HeaderSecretResolver { host, ref ->
            if (host.lowercase().trim('.') != config.secretHost || ref != expectedRef) {
                throw IllegalArgumentException("MCP credential is bound to another host")
            }
            val raw = container.secrets.resolveForHost(ref)
            try {
                ("Bearer " + raw.concatToString()).toCharArray()
            } finally {
                raw.fill('\u0000')
            }
        }
    }
    val transport = KtorMcpStreamableHttpTransport(
        http = container.http,
        endpoint = config.endpoint,
        defaultHeaders = headers,
        headerSecretResolver = resolver,
    )
    return RemoteMcpAdapter(
        transport = transport,
        namespace = config.namespace,
        clientInfo = McpClientInfo(config.clientName, config.clientVersion),
    )
}

private const val MAX_SNAPSHOTS = 128
