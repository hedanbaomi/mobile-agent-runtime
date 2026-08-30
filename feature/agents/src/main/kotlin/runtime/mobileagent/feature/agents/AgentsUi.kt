// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class AgentCardUi(
    val id: String,
    val name: String,
    val revision: Int,
    val modelLabel: String = "",
    val status: String = "",
)

data class AgentModelOptionUi(
    val id: String,
    val label: String,
    val role: String,
    val capabilities: Set<String> = emptySet(),
)

data class PromptRevisionUi(
    val id: String,
    val revision: Int,
    val label: String,
    val template: String,
    val createdAt: String = "",
    val parentRevisionId: String? = null,
    val active: Boolean = false,
)

data class AgentResourceBindingUi(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val permissionSummary: String = "",
    /** Whether this resource can be added or removed from the Agent in the editor. */
    val selectable: Boolean = true,
    /** Whether an enabled binding is currently valid for persistence. */
    val available: Boolean = true,
)

data class AgentEditorUi(
    val id: String? = null,
    val name: String = "",
    val modelOptions: List<AgentModelOptionUi> = emptyList(),
    val chatModelId: String? = null,
    val visionModelId: String? = null,
    val embeddingModelId: String? = null,
    val rerankerModelId: String? = null,
    val prompt: String = "",
    val promptRevisions: List<PromptRevisionUi> = emptyList(),
    val parameters: Map<String, String> = emptyMap(),
    val parameterSchema: List<String> = emptyList(),
    val resourceBindings: List<AgentResourceBindingUi> = emptyList(),
    val retrievalMode: String = "explicit",
    val snapshotLabel: String = "",
    val revision: Int = 0,
)

data class AgentsUiState(
    val agents: List<AgentCardUi> = emptyList(),
    val selectedAgentId: String? = null,
    val summary: AgentEditorUi? = null,
    val editor: AgentEditorUi? = null,
    val editorOpen: Boolean = false,
    val editorDirty: Boolean = false,
    val hasRerankerModels: Boolean = false,
    val query: String = "",
    val language: String = "zh-CN",
    val loading: Boolean = false,
    val error: String? = null,
    val status: String = "",
)

data class AgentsActions(
    val onQuery: (String) -> Unit = {},
    val onSelectAgent: (String) -> Unit = {},
    val onOpenEditor: (String?) -> Unit = {},
    val onCloseEditor: () -> Unit = {},
    val onEditorChange: (AgentEditorUi) -> Unit = {},
    val onSave: () -> Unit = {},
    val onSavePromptRevision: () -> Unit = {},
    val onRestorePrompt: (String) -> Unit = {},
    val onToggleResource: (String, Boolean) -> Unit = { _, _ -> },
    val onSnapshot: () -> Unit = {},
)

@Composable
fun AgentsScreen(state: AgentsUiState, actions: AgentsActions = AgentsActions(), modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp)) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AgentListPane(state, actions, Modifier.weight(0.38f).fillMaxSize())
                Column(Modifier.weight(0.62f).fillMaxSize().verticalScroll(rememberScrollState())) { AgentSummary(state, actions) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentListPane(state, actions, Modifier.fillMaxWidth())
                AgentSummary(state, actions)
            }
        }
    }
    state.editor?.let { AgentEditorDialog(it, actions, state.language.equals("zh-CN", true), state.error) }
}

@Composable
private fun AgentListPane(state: AgentsUiState, actions: AgentsActions, modifier: Modifier) {
    val zh = state.language.equals("zh-CN", true)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "智能体" else "Agents", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { actions.onOpenEditor(null) }) { Text(if (zh) "新建" else "New agent") }
        }
        OutlinedTextField(state.query, actions.onQuery, label = { Text(if (zh) "筛选智能体" else "Filter agents") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        if (state.loading) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        else if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        else {
            val visible = state.agents.filter { state.query.isBlank() || it.name.contains(state.query, true) }
            if (visible.isEmpty()) Text(if (zh) "暂无智能体。" else "No agents available.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(280.dp).padding(top = 12.dp)) {
                items(visible, key = { it.id }) { agent -> AgentCard(agent, agent.id == state.selectedAgentId) { actions.onSelectAgent(agent.id) } }
            }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentCardUi, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(agent.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("r${agent.revision}", style = MaterialTheme.typography.labelSmall)
            }
            if (agent.modelLabel.isNotBlank()) Text(agent.modelLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (agent.status.isNotBlank()) Text(agent.status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun AgentSummary(state: AgentsUiState, actions: AgentsActions) {
    val zh = state.language.equals("zh-CN", true)
    val editor = state.summary
    if (editor == null) {
        Text(if (zh) "选择智能体以查看提示词、模型角色和资源绑定。" else "Select an agent to inspect its prompt, model roles, and resource bindings.", modifier = Modifier.padding(24.dp))
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(editor.name.ifBlank { if (zh) "智能体" else "Agent" }, style = MaterialTheme.typography.headlineSmall)
            Text(if (zh) "修订版 ${editor.revision}" else "Revision ${editor.revision}", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { actions.onOpenEditor(state.selectedAgentId) }) { Text(if (zh) "编辑" else "Edit") }
    }
    if (editor.snapshotLabel.isNotBlank()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(editor.snapshotLabel, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(if (zh) "模型角色" else "Model roles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    ModelRoleRow(if (zh) "对话" else "Chat", editor.chatModelId, editor, actions, zh)
    ModelRoleRow(if (zh) "视觉（可选）" else "Vision (optional)", editor.visionModelId, editor, actions, zh)
    if (state.hasRerankerModels) {
        ModelRoleRow(if (zh) "重排" else "Reranker", editor.rerankerModelId, editor, actions, zh)
    }
    Text(if (zh) "提示词" else "Prompt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(editor.prompt.ifBlank { if (zh) "暂无提示词修订版。" else "No prompt revision is available." }, modifier = Modifier.padding(top = 6.dp))
    if (editor.promptRevisions.isNotEmpty()) {
        Text(if (zh) "提示词历史" else "Prompt history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        editor.promptRevisions.forEach { revision ->
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("r${revision.revision} · ${revision.label}", fontWeight = FontWeight.SemiBold)
                    if (revision.createdAt.isNotBlank()) Text(revision.createdAt, style = MaterialTheme.typography.labelSmall)
                    if (revision.active) Text(if (zh) "当前生效" else "Active", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    Text(if (zh) "参数" else "Parameters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (editor.parameters.isEmpty()) Text(if (zh) "没有参数覆盖。" else "No parameter overrides.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    editor.parameters.forEach { (name, value) -> Text("$name: $value", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    Text(if (zh) "资源" else "Resources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    if (editor.resourceBindings.isEmpty()) Text(if (zh) "没有绑定知识库或技能。" else "No knowledge bases or skills are bound.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    editor.resourceBindings.forEach { binding ->
        Text(
            "${binding.name} · ${binding.type}${if (binding.enabled) "" else if (zh) "（未启用）" else " (disabled)"}${if (binding.permissionSummary.isBlank()) "" else " · ${binding.permissionSummary}"}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(if (zh) "检索模式：${editor.retrievalMode}" else "Retrieval mode: ${editor.retrievalMode}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    OutlinedButton(onClick = actions.onSnapshot, modifier = Modifier.padding(top = 8.dp)) { Text(if (zh) "创建快照边界" else "Create snapshot boundary") }
}

@Composable
private fun ModelRoleRow(role: String, selectedId: String?, editor: AgentEditorUi, actions: AgentsActions, zh: Boolean) {
    val selected = editor.modelOptions.firstOrNull { it.id == selectedId }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(role, Modifier.weight(0.25f), style = MaterialTheme.typography.labelLarge)
        if (selected == null) Text(if (zh) "未配置" else "Not configured", Modifier.weight(0.75f), style = MaterialTheme.typography.bodySmall)
        else FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(selected.label) })
    }
}

@Composable
private fun AgentEditorDialog(editor: AgentEditorUi, actions: AgentsActions, zh: Boolean, error: String?) {
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(if (editor.id == null) { if (zh) "新建智能体" else "New agent" } else { if (zh) "编辑智能体" else "Edit agent" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(editor.name, { actions.onEditorChange(editor.copy(name = it)) }, label = { Text(if (zh) "名称" else "Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(editor.prompt, { actions.onEditorChange(editor.copy(prompt = it)) }, label = { Text(if (zh) "提示词修订" else "Prompt revision") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                editor.promptRevisions.filter { !it.active }.forEach { revision ->
                    TextButton(onClick = { actions.onRestorePrompt(revision.id) }) {
                        Text(if (zh) "载入 r${revision.revision} ${revision.label}" else "Load r${revision.revision} ${revision.label}")
                    }
                }
                OutlinedTextField(editor.retrievalMode, { actions.onEditorChange(editor.copy(retrievalMode = it)) }, label = { Text(if (zh) "检索模式" else "Retrieval mode") }, modifier = Modifier.fillMaxWidth())
                Text(if (zh) "模型角色" else "Model roles", style = MaterialTheme.typography.titleMedium)
                RoleDropdown(if (zh) "对话" else "Chat", editor.chatModelId, editor.modelOptions, "CHAT", zh) { actions.onEditorChange(editor.copy(chatModelId = it)) }
                RoleDropdown(if (zh) "视觉（可选）" else "Vision (optional)", editor.visionModelId, editor.modelOptions, "VISION", zh) { actions.onEditorChange(editor.copy(visionModelId = it)) }
                if (editor.modelOptions.any { it.role.equals("RERANKER", true) }) {
                    RoleDropdown(if (zh) "重排" else "Reranker", editor.rerankerModelId, editor.modelOptions, "RERANKER", zh) { actions.onEditorChange(editor.copy(rerankerModelId = it)) }
                }
                Text(if (zh) "参数" else "Parameters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                val parameterNames = (editor.parameterSchema + editor.parameters.keys).distinct()
                if (parameterNames.isEmpty()) Text(if (zh) "参数模式不可用，因此不显示覆盖字段。" else "Parameter schema unavailable; no override field is shown.", style = MaterialTheme.typography.bodySmall)
                parameterNames.forEach { name ->
                    OutlinedTextField(
                        value = editor.parameters[name].orEmpty(),
                        onValueChange = { actions.onEditorChange(editor.copy(parameters = editor.parameters + (name to it))) },
                        label = { Text(name) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(if (zh) "资源绑定" else "Resource bindings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (editor.resourceBindings.isEmpty()) Text(if (zh) "暂无可绑定的知识库或技能。" else "No knowledge bases or skills are available to bind.", style = MaterialTheme.typography.bodySmall)
                editor.resourceBindings.forEach { binding ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = binding.enabled,
                            onCheckedChange = { actions.onToggleResource(binding.id, it) },
                            enabled = binding.selectable,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(binding.name)
                            Text("${binding.type} · ${binding.permissionSummary}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = actions.onSave) { Text(if (zh) "保存" else "Save") } },
        dismissButton = { TextButton(onClick = actions.onCloseEditor) { Text(if (zh) "取消" else "Cancel") } },
    )
}

@Composable
private fun RoleDropdown(
    label: String,
    selectedId: String?,
    options: List<AgentModelOptionUi>,
    role: String,
    zh: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val matching = when (role.uppercase()) {
        "CHAT" -> options.filter { it.role.equals("CHAT", true) || it.role.equals("VISION", true) }
        "VISION" -> options.filter { it.role.equals("VISION", true) || "image" in it.capabilities }
        "RERANKER" -> options.filter { it.role.equals("RERANKER", true) }
        else -> emptyList()
    }
    val selected = options.firstOrNull { it.id == selectedId }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selected?.label ?: if (zh) "未配置" else "Not configured"}", modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (matching.isEmpty()) {
                DropdownMenuItem(text = { Text(if (zh) "没有可用的 $role 模型" else "No $role models available") }, onClick = { expanded = false }, enabled = false)
            } else {
                matching.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { expanded = false; onSelect(option.id) },
                    )
                }
            }
        }
    }
}
