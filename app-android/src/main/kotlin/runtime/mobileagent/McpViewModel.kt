// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import runtime.mobileagent.provider.mcp.MCP_PROTOCOL_VERSION_2025_06_18
import runtime.mobileagent.provider.mcp.McpToolDefinition
import runtime.mobileagent.skills.HttpPolicy

private const val MCP_CONFIG_KEY = "mcp.config.v1"
private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
private const val MAX_SCHEMA_CHARS = 512 * 1024

@Serializable
internal data class McpStoredTool(
    val namespacedName: String,
    val serverName: String = "",
    val description: String = "",
    val inputSchemaJson: String,
    val schemaHash: String,
)

@Serializable
internal data class McpStoredGrant(
    val agentId: String,
    val grantId: String,
    val revision: Long,
    val toolNames: List<String>,
    val schemaHashes: Map<String, String>,
)

@Serializable
internal data class McpStoredSnapshot(
    val snapshotId: String,
    val agentId: String,
    val grantId: String,
    val revision: Long,
    val discoveryFingerprint: String,
    val toolNames: List<String>,
    val schemaHashes: Map<String, String>,
)

@Serializable
internal data class McpStoredConfig(
    val schemaVersion: Int = 1,
    val protocolVersion: String = MCP_PROTOCOL_VERSION_2025_06_18,
    val endpoint: String,
    val host: String,
    val namespace: String,
    val clientName: String = "mobileAgentRuntime",
    val clientVersion: String = "1",
    /** Reference only; ciphertext lives in AndroidSecretStore. */
    val secretRef: String? = null,
    /** Host binding for the immutable secret reference. */
    val secretHost: String? = null,
    val networkApprovedAt: String? = null,
    val discoveredAt: String? = null,
    val discoveryRevision: Long = 0,
    val discoveryFingerprint: String? = null,
    val tools: List<McpStoredTool> = emptyList(),
    val grants: List<McpStoredGrant> = emptyList(),
    val snapshots: List<McpStoredSnapshot> = emptyList(),
)

/** A bounded, non-secret view of a discovered MCP tool for the settings UI. */
data class McpUiTool(
    val namespacedName: String,
    val description: String,
    val inputSchemaJson: String,
    val schemaHash: String,
    val capabilityScope: String,
    val granted: Boolean,
    val selected: Boolean,
)

data class McpUiAgent(
    val id: String,
    val name: String,
)

data class McpUiState(
    val configured: Boolean = false,
    val endpoint: String = "",
    val host: String = "",
    val namespace: String = "",
    val authConfigured: Boolean = false,
    val networkApproved: Boolean = false,
    val discovered: Boolean = false,
    val discoveryRevision: Long = 0,
    val agents: List<McpUiAgent> = emptyList(),
    val selectedAgentId: String? = null,
    val selectedToolNames: Set<String> = emptySet(),
    val tools: List<McpUiTool> = emptyList(),
    val pendingDiscoveryConfirmation: Boolean = false,
    val pendingGrantConfirmation: Boolean = false,
    val loading: Boolean = false,
    val status: String = "",
    val error: String? = null,
)

/**
 * Android-only MCP configuration and grant controller.
 *
 * Construction and [reload] are local-only.  The endpoint is contacted only
 * from [confirmDiscovery], after the UI has shown the destination and cost
 * confirmation.  Configuration is deliberately kept out of the database
 * migration surface because it contains only endpoint metadata and references.
 */
class McpViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MobileAgentApp
    val state = mutableStateOf(McpUiState())

    init {
        reload()
    }

    /** Load local configuration and the current Agent list; never performs network I/O. */
    fun reload() {
        val agents = app.container.agents.list()
            .map { McpUiAgent(it.id, it.name) }
        val stored = McpConfigStore.read(app.container)
        if (stored.error != null) {
            state.value = McpUiState(agents = agents, error = "MCP 配置已损坏，已安全禁用：${stored.error}")
            return
        }
        val config = stored.value
        val selected = state.value.selectedAgentId?.takeIf { id -> agents.any { it.id == id } }
        state.value = toUiState(config, agents, selected, status = state.value.status)
    }

    /** Save endpoint metadata locally.  [password] is transient and is zeroed on return. */
    fun saveEndpoint(endpointInput: String, namespaceInput: String, password: CharArray = charArrayOf()): Boolean {
        try {
            val endpoint = validateEndpoint(endpointInput)
            val namespace = validateNamespace(namespaceInput)
            val previous = McpConfigStore.read(app.container).value
            val hostChanged = previous != null && previous.host != endpoint.host
            if (hostChanged && previous?.secretRef != null && password.isEmpty()) {
                throw IllegalArgumentException("更换 MCP 主机前必须重新输入访问令牌；旧凭据不会跨主机复用。")
            }

            val replacingSecret = password.isNotEmpty()
            val secretRef = if (replacingSecret) {
                val ref = "mcp:${UUID.randomUUID()}"
                // AndroidSecretStore.put encrypts and clears the supplied chars.
                app.container.secrets.put(ref, password)
                ref
            } else {
                previous?.secretRef
            }
            val secretHost = secretRef?.let { endpoint.host }
            val endpointChanged = previous == null || previous.endpoint != endpoint.value || previous.namespace != namespace
            val credentialChanged = previous?.secretRef != secretRef
            val next = if (previous != null && !endpointChanged && !credentialChanged) {
                previous.copy(endpoint = endpoint.value, host = endpoint.host, namespace = namespace)
            } else {
                McpStoredConfig(
                    endpoint = endpoint.value,
                    host = endpoint.host,
                    namespace = namespace,
                    clientName = previous?.clientName ?: "mobileAgentRuntime",
                    clientVersion = previous?.clientVersion ?: "1",
                    secretRef = secretRef,
                    secretHost = secretHost,
                )
            }
            McpConfigStore.write(app.container, next)
            reload()
            state.value = state.value.copy(status = "MCP 端点已保存；尚未联网，需明确确认后发现工具。", error = null)
            return true
        } catch (error: Exception) {
            state.value = state.value.copy(error = safeMessage(error, "MCP 端点保存失败"), status = "")
            return false
        } finally {
            password.fill('\u0000')
        }
    }

    /** Request the explicit destination/cost confirmation dialog without networking. */
    fun requestDiscovery() {
        if (!state.value.configured) {
            state.value = state.value.copy(error = "请先保存 HTTPS MCP 端点。")
            return
        }
        state.value = state.value.copy(pendingDiscoveryConfirmation = true, error = null)
    }

    fun cancelDiscovery() {
        state.value = state.value.copy(pendingDiscoveryConfirmation = false)
    }

    /** Perform initialize + tools/list only after [requestDiscovery] was confirmed by the user. */
    fun confirmDiscovery() {
        if (!state.value.pendingDiscoveryConfirmation || state.value.loading) return
        state.value = state.value.copy(pendingDiscoveryConfirmation = false, loading = true, error = null, status = "正在连接 MCP 端点并发现工具…")
        viewModelScope.launch {
            try {
                val before = McpConfigStore.read(app.container).value ?: error("MCP endpoint is not configured")
                val adapter = createMcpAdapter(app.container, before)
                val discovered = withContext(Dispatchers.IO) {
                    adapter.initialize()
                    adapter.discoverTools()
                }
                val storedTools = discovered.map(::toStoredTool)
                val fingerprint = mcpFingerprint(discovered)
                // A settings change may happen while the remote request is in flight.  Never
                // let a result for the old host/credential overwrite newer local state.
                val current = McpConfigStore.read(app.container).value
                    ?: error("MCP endpoint was removed during discovery")
                require(
                    current.endpoint == before.endpoint && current.host == before.host &&
                        current.namespace == before.namespace && current.secretRef == before.secretRef &&
                        current.secretHost == before.secretHost,
                ) { "MCP configuration changed during discovery; result discarded" }
                val changed = current.discoveryFingerprint != fingerprint ||
                    current.tools.map { it.namespacedName to it.schemaHash } != storedTools.map { it.namespacedName to it.schemaHash }
                val nextRevision = when {
                    current.discoveryRevision <= 0 -> 1
                    changed -> current.discoveryRevision + 1
                    else -> current.discoveryRevision
                }
                val next = current.copy(
                    networkApprovedAt = Instant.now().toString(),
                    discoveredAt = Instant.now().toString(),
                    discoveryRevision = nextRevision,
                    discoveryFingerprint = fingerprint,
                    tools = storedTools,
                    grants = if (changed) emptyList() else current.grants,
                    snapshots = if (changed) emptyList() else current.snapshots,
                )
                McpConfigStore.write(app.container, next)
                reload()
                state.value = state.value.copy(
                    loading = false,
                    status = if (changed && current.tools.isNotEmpty()) {
                        "工具列表或 schema 已变化；旧授权已失效，请逐项重新确认。"
                    } else {
                        "工具发现完成；外部描述仅供参考，尚未授权任何工具。"
                    },
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                state.value = state.value.copy(loading = false, status = "MCP 工具发现已取消；不会自动重试。")
                throw cancelled
            } catch (error: Exception) {
                state.value = state.value.copy(loading = false, error = safeMessage(error, "MCP 工具发现失败"), status = "未确认发现结果；不会自动重试。")
            }
        }
    }

    fun selectAgent(agentId: String?) {
        val selected = agentId?.takeIf { id -> state.value.agents.any { it.id == id } }
        val config = McpConfigStore.read(app.container).value
        val grant = config?.grants?.firstOrNull { it.agentId == selected }
        state.value = state.value.copy(
            selectedAgentId = selected,
            selectedToolNames = grant?.toolNames?.toSet().orEmpty(),
            pendingGrantConfirmation = false,
            error = null,
        )
    }

    fun toggleTool(namespacedName: String, selected: Boolean) {
        if (state.value.tools.none { it.namespacedName == namespacedName }) return
        val names = state.value.selectedToolNames.toMutableSet()
        if (selected) names += namespacedName else names -= namespacedName
        state.value = state.value.copy(selectedToolNames = names)
    }

    fun requestGrant() {
        val current = state.value
        if (current.selectedAgentId == null) {
            state.value = current.copy(error = "请先选择目标 Agent。")
            return
        }
        if (!current.networkApproved || current.tools.isEmpty()) {
            state.value = current.copy(error = "请先明确确认端点并完成工具发现。")
            return
        }
        if (current.selectedToolNames.isEmpty()) {
            state.value = current.copy(error = "至少勾选一个工具；撤销请使用单独的撤销操作。")
            return
        }
        state.value = current.copy(pendingGrantConfirmation = true, error = null)
    }

    /** Persist exactly the checked tools after an explicit Agent + tool scope confirmation. */
    fun confirmGrant() {
        val current = state.value
        val agentId = current.selectedAgentId ?: return
        if (!current.pendingGrantConfirmation) return
        val config = McpConfigStore.read(app.container).value
        if (config == null || !current.networkApproved || config.networkApprovedAt.isNullOrBlank() ||
            config.discoveryRevision <= 0 || config.discoveryFingerprint.isNullOrBlank()) {
            state.value = current.copy(pendingGrantConfirmation = false, error = "MCP 发现授权已失效，请重新发现工具。")
            return
        }
        if (app.container.agents.get(agentId) == null) {
            state.value = current.copy(pendingGrantConfirmation = false, error = "目标 Agent 已不存在，不能建立 MCP 授权。")
            return
        }
        val selected = current.selectedToolNames
        val available = config.tools.associateBy { it.namespacedName }
        if (selected.isEmpty() || selected.any { it !in available }) {
            state.value = current.copy(pendingGrantConfirmation = false, error = "工具列表已变化；请重新选择授权范围。")
            return
        }
        val grant = McpStoredGrant(
            agentId = agentId,
            grantId = "mcp-grant:${UUID.randomUUID()}",
            revision = config.discoveryRevision,
            toolNames = selected.toList().sorted(),
            schemaHashes = selected.associateWith { available.getValue(it).schemaHash },
        )
        McpConfigStore.write(
            app.container,
            config.copy(
                grants = config.grants.filterNot { it.agentId == agentId } + grant,
                // Any old runtime snapshot must be recaptured after a grant change.
                snapshots = config.snapshots.filterNot { it.agentId == agentId },
            ),
        )
        reload()
        state.value = state.value.copy(
            selectedAgentId = agentId,
            selectedToolNames = grant.toolNames.toSet(),
            pendingGrantConfirmation = false,
            status = "已为 ${current.agents.firstOrNull { it.id == agentId }?.name ?: agentId} 显式授权 ${grant.toolNames.size} 个工具；每次调用仍需批准。",
            error = null,
        )
    }

    fun cancelGrant() {
        state.value = state.value.copy(pendingGrantConfirmation = false)
    }

    fun revokeGrant(agentId: String? = state.value.selectedAgentId) {
        val id = agentId ?: return
        val config = McpConfigStore.read(app.container).value ?: return
        McpConfigStore.write(
            app.container,
            config.copy(
                grants = config.grants.filterNot { it.agentId == id },
                snapshots = config.snapshots.filterNot { it.agentId == id },
            ),
        )
        reload()
        state.value = state.value.copy(status = "已撤销该 Agent 的 MCP 工具授权；运行时不会自动恢复。", error = null)
    }

    /** Clear only MCP metadata.  The encrypted secret row is not deleted implicitly. */
    fun clearConfig() {
        app.container.uiPreferences.edit().remove(MCP_CONFIG_KEY).apply()
        reload()
        state.value = state.value.copy(status = "MCP 配置已清除；旧凭据仍不会被读取或自动复用。", error = null)
    }

    internal fun configuredSnapshot(snapshotId: String, agentId: String): McpStoredSnapshot? =
        McpConfigStore.read(app.container).value?.snapshots?.firstOrNull { it.snapshotId == snapshotId && it.agentId == agentId }

    private fun toUiState(config: McpStoredConfig?, agents: List<McpUiAgent>, selectedAgentId: String?, status: String): McpUiState {
        val grant = config?.grants?.firstOrNull { it.agentId == selectedAgentId }
        val selected = grant?.toolNames?.toSet().orEmpty()
        val granted = grant?.toolNames?.toSet().orEmpty()
        return McpUiState(
            configured = config != null,
            endpoint = config?.endpoint.orEmpty(),
            host = config?.host.orEmpty(),
            namespace = config?.namespace.orEmpty(),
            authConfigured = config?.secretRef != null,
            networkApproved = !config?.networkApprovedAt.isNullOrBlank(),
            discovered = !config?.discoveryFingerprint.isNullOrBlank(),
            discoveryRevision = config?.discoveryRevision ?: 0,
            agents = agents,
            selectedAgentId = selectedAgentId,
            selectedToolNames = selected,
            tools = config?.tools.orEmpty().map { tool ->
                McpUiTool(
                    namespacedName = tool.namespacedName,
                    description = tool.description,
                    inputSchemaJson = tool.inputSchemaJson,
                    schemaHash = tool.schemaHash,
                    capabilityScope = "mcp:${config?.namespace.orEmpty()}",
                    granted = tool.namespacedName in granted,
                    selected = tool.namespacedName in selected,
                )
            },
            pendingDiscoveryConfirmation = state.value.pendingDiscoveryConfirmation,
            pendingGrantConfirmation = state.value.pendingGrantConfirmation,
            loading = state.value.loading,
            status = status,
            error = state.value.error,
        )
    }

    private fun toStoredTool(tool: McpToolDefinition): McpStoredTool = McpStoredTool(
        namespacedName = tool.namespacedName,
        serverName = tool.serverName,
        description = tool.description,
        inputSchemaJson = tool.inputSchema.toString().also { require(it.length <= MAX_SCHEMA_CHARS) },
        schemaHash = tool.schemaHash,
    )

    private fun safeMessage(error: Throwable, fallback: String): String =
        error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(256)?.ifBlank { fallback } ?: fallback

    private data class Endpoint(val value: String, val host: String)

    private fun validateEndpoint(raw: String): Endpoint {
        val input = raw.trim()
        require(input.length <= 2048) { "MCP endpoint is too long" }
        val uri = URI(input)
        require(uri.scheme.equals("https", true)) { "MCP endpoint must use HTTPS" }
        require(uri.rawUserInfo == null) { "MCP endpoint userinfo is not allowed" }
        require(uri.query == null && uri.fragment == null) { "MCP endpoint query/fragment is not allowed" }
        require(uri.port == -1 || uri.port == 443) { "MCP endpoint must use HTTPS port 443" }
        val host = uri.host?.lowercase()?.trim('.')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("MCP endpoint host is invalid")
        require(!HttpPolicy.isIpLiteral(host)) { "MCP endpoint IP literals are not allowed" }
        require(!HttpPolicy.isForbiddenHost(host)) { "MCP endpoint loopback/private host is not allowed" }
        HttpPolicy.assertRequest(input, setOf(host))
        return Endpoint(uri.toASCIIString(), host)
    }

    private fun validateNamespace(raw: String): String {
        val value = raw.trim()
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "MCP namespace is invalid" }
        return value
    }

}

/** Shared local preference codec for the ViewModel and the runtime tool factory. */
internal object McpConfigStore {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    data class ReadResult(val value: McpStoredConfig?, val error: String? = null)

    fun read(container: AppContainer): ReadResult {
        val raw = container.uiPreferences.getString(MCP_CONFIG_KEY, null)?.trim().orEmpty()
        if (raw.isBlank()) return ReadResult(null)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) return ReadResult(null, "配置超过大小上限")
        return try {
            val config = json.decodeFromString<McpStoredConfig>(raw)
            validate(config)
            ReadResult(config)
        } catch (error: Exception) {
            ReadResult(null, error.message?.take(256) ?: "配置格式无效")
        }
    }

    fun write(container: AppContainer, config: McpStoredConfig) {
        validate(config)
        val encoded = json.encodeToString(config)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "MCP 配置超过大小上限" }
        container.uiPreferences.edit().putString(MCP_CONFIG_KEY, encoded).apply()
    }

    private fun validate(config: McpStoredConfig) {
        require(config.schemaVersion == 1) { "不支持的 MCP 配置版本" }
        require(config.protocolVersion == MCP_PROTOCOL_VERSION_2025_06_18) { "不支持的 MCP 协议版本" }
        require(config.endpoint.length <= 2048 && config.host.isNotBlank()) { "MCP 端点无效" }
        val endpoint = URI(config.endpoint)
        require(endpoint.scheme.equals("https", true) && endpoint.rawUserInfo == null) { "MCP 端点必须为无凭据 HTTPS" }
        require(endpoint.query == null && endpoint.fragment == null && (endpoint.port == -1 || endpoint.port == 443)) { "MCP 端点 URI 范围无效" }
        val endpointHost = endpoint.host?.lowercase()?.trim('.')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("MCP 端点 host 无效")
        require(endpointHost == config.host) { "MCP 端点 host 绑定不一致" }
        require(!HttpPolicy.isIpLiteral(endpointHost) && !HttpPolicy.isForbiddenHost(endpointHost)) { "MCP 端点 host 不允许" }
        require(config.namespace.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "MCP namespace 无效" }
        require(config.discoveryRevision >= 0) { "MCP discovery revision 无效" }
        config.secretRef?.let {
            require(it.matches(Regex("mcp:[0-9a-fA-F-]{36}"))) { "MCP secret reference 无效" }
            require(config.secretHost == config.host) { "MCP secret host binding 无效" }
        }
        config.discoveryFingerprint?.let { require(it.matches(Regex("[0-9a-f]{64}"))) { "MCP discovery fingerprint 无效" } }
        if (config.discoveryRevision > 0) require(config.discoveryFingerprint != null) { "MCP discovery fingerprint 缺失" }
        val names = config.tools.map { it.namespacedName }
        require(names.size == names.toSet().size && names.none { it.isBlank() || it.length > 256 }) { "MCP tool list 无效" }
        config.tools.forEach { tool ->
            require(tool.inputSchemaJson.length <= MAX_SCHEMA_CHARS) { "MCP tool schema 过大" }
            val schema = runCatching { json.parseToJsonElement(tool.inputSchemaJson) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                ?: throw IllegalArgumentException("MCP tool schema 必须为 JSON object")
            require(tool.schemaHash.matches(Regex("[0-9a-f]{64}"))) { "MCP tool schema hash 无效" }
            require(sha256Hex(schema.toString()) == tool.schemaHash) { "MCP tool schema hash 不匹配" }
            require(tool.description.length <= 4096) { "MCP tool description 过长" }
        }
        config.grants.forEach { grant ->
            require(grant.agentId.isNotBlank() && grant.grantId.startsWith("mcp-grant:")) { "MCP grant 无效" }
            require(grant.revision > 0 && grant.toolNames.size == grant.toolNames.toSet().size && grant.toolNames.all { it.length <= 256 }) { "MCP grant revision/tool list 无效" }
            require(grant.toolNames.all { it in names }) { "MCP grant references unknown tool" }
            require(grant.schemaHashes.keys == grant.toolNames.toSet()) { "MCP grant schema binding 无效" }
            require(grant.schemaHashes.values.all { it.matches(Regex("[0-9a-f]{64}")) }) { "MCP grant schema hash 无效" }
            require(grant.schemaHashes.all { (name, hash) -> config.tools.first { it.namespacedName == name }.schemaHash == hash }) { "MCP grant schema hash 不匹配" }
        }
        require(config.grants.map { it.agentId }.size == config.grants.map { it.agentId }.toSet().size) { "MCP grant agent duplicate" }
        config.snapshots.forEach { snapshot ->
            require(snapshot.snapshotId.isNotBlank() && snapshot.agentId.isNotBlank()) { "MCP snapshot 无效" }
            require(snapshot.grantId.startsWith("mcp-grant:") && snapshot.revision > 0) { "MCP snapshot grant 无效" }
            require(snapshot.discoveryFingerprint.matches(Regex("[0-9a-f]{64}"))) { "MCP snapshot fingerprint 无效" }
            require(snapshot.toolNames.size == snapshot.toolNames.toSet().size && snapshot.toolNames.all { it in names }) { "MCP snapshot tools 无效" }
            require(snapshot.schemaHashes.keys == snapshot.toolNames.toSet()) { "MCP snapshot schema binding 无效" }
            require(snapshot.schemaHashes.all { (name, hash) -> config.tools.first { it.namespacedName == name }.schemaHash == hash }) { "MCP snapshot schema hash 不匹配" }
        }
    }
}

internal fun mcpFingerprint(tools: List<McpToolDefinition>): String =
    sha256Hex(tools.joinToString("\n") {
        "${it.namespacedName}\u0000${it.description}\u0000${it.inputSchema}\u0000${it.outputSchema ?: JsonNull}"
    })

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
