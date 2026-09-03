// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import runtime.mobileagent.McpUiAgent
import runtime.mobileagent.McpUiState
import runtime.mobileagent.McpUiTool

/** UI callbacks deliberately keep all persistence and network work in the ViewModel. */
data class McpActions(
    val onSaveEndpoint: (endpoint: String, namespace: String, password: CharArray) -> Boolean = { _, _, _ -> false },
    val onRequestDiscovery: () -> Unit = {},
    val onConfirmDiscovery: () -> Unit = {},
    val onCancelDiscovery: () -> Unit = {},
    val onSelectAgent: (String?) -> Unit = {},
    val onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
    val onRequestGrant: () -> Unit = {},
    val onConfirmGrant: () -> Unit = {},
    val onCancelGrant: () -> Unit = {},
    val onRevokeGrant: () -> Unit = {},
    val onClearConfig: () -> Unit = {},
)

/**
 * Standalone MCP settings surface.  It shows the destination and explicit
 * authorization scope, while never displaying secret references or token
 * values.  Discovery and each grant are confirmed by separate user actions.
 */
@Composable
fun McpSettingsScreen(
    state: McpUiState,
    actions: McpActions = McpActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
) {
    var endpoint by remember(state.endpoint) { mutableStateOf(state.endpoint) }
    var namespace by remember(state.namespace) { mutableStateOf(state.namespace.ifBlank { "mcp" }) }
    var password by remember { mutableStateOf("") }
    var agentMenu by remember { mutableStateOf(false) }
    var clearRequested by remember { mutableStateOf(false) }
    val selectedAgent = state.agents.firstOrNull { it.id == state.selectedAgentId }
    val zh = true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPageTitle) {
            Text(if (zh) "MCP 设置" else "MCP settings", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            if (zh) "默认不配置服务器、不联网，也不会启动 stdio。MCP 描述来自远端，仅视为不可信参考。"
            else "No server, network, or stdio process is enabled by default. Remote MCP descriptions are untrusted reference text.",
            style = MaterialTheme.typography.bodySmall,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (zh) "端点与凭据" else "Endpoint and credential", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(if (zh) "HTTPS 端点（仅 443）" else "HTTPS endpoint (443 only)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = namespace,
                    onValueChange = { namespace = it },
                    label = { Text(if (zh) "本地命名空间" else "Local namespace") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (zh) "访问令牌（可选，仅本次提交）" else "Access token (optional, this submission only)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    if (zh) "令牌只写入 Android Keystore 加密凭据表，并绑定当前主机；界面不会回填或保存明文。更换主机必须重新输入。"
                    else "The token is encrypted through Android Keystore and bound to this host; it is never echoed or persisted as plaintext. Changing hosts requires re-entry.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val accepted = actions.onSaveEndpoint(endpoint, namespace, password.toCharArray())
                            if (accepted) password = ""
                        },
                    ) { Text(if (zh) "保存本地配置" else "Save locally") }
                    OutlinedButton(
                        onClick = actions.onRequestDiscovery,
                        enabled = state.configured && !state.loading,
                    ) { Text(if (zh) "发现工具" else "Discover tools") }
                }
                if (state.configured) {
                    Text("https://${state.host}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (state.authConfigured) {
                            if (zh) "已配置主机绑定的 Keystore 凭据" else "Host-bound Keystore credential configured"
                        } else {
                            if (zh) "未配置凭据；端点可能要求认证" else "No credential configured; endpoint may require authentication"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        if (state.networkApproved) "已完成一次明确的目的地/成本确认；调用仍需逐次批准。"
                        else "尚未确认目的地/成本；保存配置本身不会联网。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.loading) CircularProgressIndicator()
            }
        }

        if (state.discovered) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (zh) "发现结果与 Agent 授权" else "Discovery and Agent grants", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (zh) "修订 ${state.discoveryRevision}；新增、删除、排序或 schema 变化会使全部旧授权失效。"
                        else "Revision ${state.discoveryRevision}; additions, removals, ordering, or schema changes invalidate old grants.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box {
                        OutlinedButton(
                            onClick = { agentMenu = true },
                            enabled = state.agents.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedAgent?.name ?: if (zh) "选择目标 Agent" else "Select target Agent")
                        }
                        DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                            state.agents.forEach { agent ->
                                AgentMenuItem(agent, state.selectedAgentId == agent.id) {
                                    agentMenu = false
                                    actions.onSelectAgent(agent.id)
                                }
                            }
                        }
                    }
                    if (state.agents.isEmpty()) {
                        Text(if (zh) "当前没有可绑定的 Agent。" else "No Agent is available for binding.", style = MaterialTheme.typography.bodySmall)
                    }
                    state.tools.forEach { tool ->
                        McpToolRow(tool, zh, actions.onToggleTool)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = actions.onRequestGrant,
                            enabled = state.selectedAgentId != null && state.selectedToolNames.isNotEmpty(),
                        ) { Text(if (zh) "保存勾选授权" else "Save checked grant") }
                        OutlinedButton(
                            onClick = actions.onRevokeGrant,
                            enabled = state.tools.any { it.granted },
                        ) { Text(if (zh) "撤销当前 Agent" else "Revoke Agent grant") }
                    }
                    Text(
                        if (zh) "每次工具调用（包括远端标记为只读的工具）都会再次请求批准；批准后才会重新发现并调用。"
                        else "Every tool call, including remotely labeled read-only tools, requests approval again; only then does the app re-discover and call.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else if (state.configured) {
            Text(if (zh) "尚未发现工具；请先明确确认目的地和潜在费用。" else "No tools discovered; explicitly confirm destination and possible cost first.", style = MaterialTheme.typography.bodySmall)
        }

        if (state.configured) {
            OutlinedButton(onClick = { clearRequested = true }) { Text(if (zh) "清除 MCP 配置" else "Clear MCP configuration") }
        }
    }

    if (state.pendingDiscoveryConfirmation) {
        AlertDialog(
            onDismissRequest = actions.onCancelDiscovery,
            title = { Text(if (zh) "确认 MCP 工具发现" else "Confirm MCP discovery") },
            text = {
                Text(
                    if (zh) "将只向以下 HTTPS 主机发送 initialize/tools-list 请求：${state.host}。远端工具描述不可信，发现可能产生流量或服务费用；不会自动授权或执行工具。"
                    else "Only initialize/tools-list will be sent to this HTTPS host: ${state.host}. Remote descriptions are untrusted and discovery may incur traffic or service cost; no tool is auto-authorized or executed.",
                )
            },
            confirmButton = { Button(onClick = actions.onConfirmDiscovery) { Text(if (zh) "确认并发现" else "Confirm and discover") } },
            dismissButton = { TextButton(onClick = actions.onCancelDiscovery) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    if (state.pendingGrantConfirmation) {
        AlertDialog(
            onDismissRequest = actions.onCancelGrant,
            title = { Text(if (zh) "确认 Agent 工具授权" else "Confirm Agent tool grant") },
            text = {
                Text(
                    if (zh) "将为 ${selectedAgent?.name ?: "所选 Agent"} 授权 ${state.selectedToolNames.size} 个工具，目标主机为 ${state.host}；每次实际调用仍会逐次请求批准。"
                    else "Grant ${state.selectedToolNames.size} tools to ${selectedAgent?.name ?: "the selected Agent"} at ${state.host}; each actual call still requires approval.",
                )
            },
            confirmButton = { Button(onClick = actions.onConfirmGrant) { Text(if (zh) "确认授权" else "Confirm grant") } },
            dismissButton = { TextButton(onClick = actions.onCancelGrant) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    if (clearRequested) {
        AlertDialog(
            onDismissRequest = { clearRequested = false },
            title = { Text(if (zh) "清除 MCP 配置？" else "Clear MCP configuration?") },
            text = { Text(if (zh) "将移除端点、发现结果和 Agent 授权。不会读取、回显或自动复用旧凭据。" else "This removes the endpoint, discovery, and Agent grants. Old credentials are never read, echoed, or reused automatically.") },
            confirmButton = {
                Button(onClick = { clearRequested = false; actions.onClearConfig() }) { Text(if (zh) "清除" else "Clear") }
            },
            dismissButton = { TextButton(onClick = { clearRequested = false }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
}

@Composable
private fun AgentMenuItem(agent: McpUiAgent, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (selected) "✓ ${agent.name}" else agent.name) },
        onClick = onClick,
    )
}

@Composable
private fun McpToolRow(tool: McpUiTool, zh: Boolean, onToggle: (String, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = tool.selected,
                    onCheckedChange = { onToggle(tool.namespacedName, it) },
                )
                Column(Modifier.weight(1f)) {
                    Text(tool.namespacedName, style = MaterialTheme.typography.titleSmall)
                    Text("scope: ${tool.capabilityScope}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                if (tool.granted) {
                    if (zh) "已在当前 Agent 授权" else "Granted to current Agent"
                } else {
                    if (zh) "未授权" else "Not granted"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            Text("schemaHash: ${tool.schemaHash}", style = MaterialTheme.typography.labelSmall)
            Text(
                if (zh) "远端描述（不可信）：${tool.description}" else "Remote description (untrusted): ${tool.description}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "inputSchema: ${tool.inputSchemaJson.take(512)}${if (tool.inputSchemaJson.length > 512) "…" else ""}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
