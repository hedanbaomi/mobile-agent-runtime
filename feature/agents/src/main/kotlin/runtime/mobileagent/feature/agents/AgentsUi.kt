// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.domain.WorkspaceBackendType

/** Stable semantics identifiers used by device tests and accessibility tooling. */
object AgentTestTags {
    const val SCREEN = "agents.screen"
    const val LIST = "agents.list"
    const val NEW = "agents.new"
    const val QUERY = "agents.query"
    const val SUMMARY = "agents.summary"
    const val CARD_PREFIX = "agents.card."
    const val EDITOR = "agents.editor"
    const val NAME = "agents.editor.name"
    const val PROMPT = "agents.editor.prompt"
    const val SAVE = "agents.editor.save"
    const val CANCEL = "agents.editor.cancel"
    const val RESOURCES = "agents.editor.resources"
    const val GRANTS = "agents.editor.grants"
    const val GRANT_ADD = "agents.editor.grants.add"
    const val GRANT_ROW_PREFIX = "agents.editor.grants.row."
    const val GRANT_TOGGLE_PREFIX = "agents.editor.grants.toggle."
    const val GRANT_CAPABILITY = "agents.editor.grants.capability"
    const val GRANT_WORKSPACE = "agents.editor.grants.workspace"
    const val GRANT_SCOPE = "agents.editor.grants.scope"
    const val GRANT_SKILL = "agents.editor.grants.skill"
    const val GRANT_LIFETIME = "agents.editor.grants.lifetime"
    const val GRANT_LIFETIME_CONTEXT = "agents.editor.grants.lifetime.context"
    const val GRANT_SHELL = "agents.editor.grants.shell.execute"
    const val SNAPSHOT = "agents.snapshot"
}

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

/** Safe, display-only workspace metadata.  It intentionally has no root/URI/path field. */
data class AgentWorkspaceUi(
    val id: String,
    val displayName: String,
    val backendType: WorkspaceBackendType,
    val readable: Boolean,
    val writable: Boolean,
    val quotaBytes: Long?,
    val maxFileBytes: Long,
    val enabled: Boolean,
    val revision: Long,
)

/** A canonical grant plus labels resolved from the current workspace/Skill inventory. */
data class AgentGrantUi(
    val grant: CapabilityGrant,
    val workspaceName: String? = null,
    val skillName: String? = null,
    val expired: Boolean = false,
    val enabled: Boolean = !grant.revoked && !expired,
    val skillTrusted: Boolean = true,
)

/** Trusted Skill package identity is retained for exact binding, but never rendered as a path. */
data class AgentTrustedSkillUi(
    val installId: String,
    val packageHash: String,
    val name: String,
    val enabled: Boolean,
    val trusted: Boolean,
    val capabilities: Set<String> = emptySet(),
    val grantRevision: Int = 0,
)

/** Snapshot binding labels are display-only; the canonical binding remains the source of truth. */
data class AgentSnapshotGrantUi(
    val binding: SnapshotGrantBinding,
    val workspaceName: String? = null,
)

/**
 * Draft for one new canonical grant.  The context-free Agent editor may persist only ONCE or
 * PERSISTENT lifetimes; TASK/SESSION grants are created by a runtime flow with real identities.
 */
data class AgentGrantDraftUi(
    val capability: CapabilityId = CapabilityId(CapabilityId.FILE_LIST),
    val workspaceId: String? = null,
    val pathScope: String? = null,
    val lifetime: GrantLifetime = GrantLifetime.PERSISTENT,
    val skillInstallId: String? = null,
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
    val workspaces: List<AgentWorkspaceUi> = emptyList(),
    val grants: List<AgentGrantUi> = emptyList(),
    val trustedSkills: List<AgentTrustedSkillUi> = emptyList(),
    val snapshotGrantBindings: List<AgentSnapshotGrantUi> = emptyList(),
    val grantDraft: AgentGrantDraftUi? = null,
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
    /** False only when the VM could not resolve its injected grant port. */
    val grantStoreAvailable: Boolean = true,
    val grantStoreError: String? = null,
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
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp).testTag(AgentTestTags.SCREEN)) {
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
    state.editor?.let {
        AgentEditorDialog(
            editor = it,
            actions = actions,
            zh = state.language.equals("zh-CN", true),
            error = state.error,
            grantStoreAvailable = state.grantStoreAvailable,
            grantStoreError = state.grantStoreError,
        )
    }
}

@Composable
private fun AgentListPane(state: AgentsUiState, actions: AgentsActions, modifier: Modifier) {
    val zh = state.language.equals("zh-CN", true)
    Column(modifier.testTag(AgentTestTags.LIST)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "智能体" else "Agents", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { actions.onOpenEditor(null) }, modifier = Modifier.testTag(AgentTestTags.NEW)) {
                Text(if (zh) "新建" else "New agent")
            }
        }
        OutlinedTextField(
            state.query,
            actions.onQuery,
            label = { Text(if (zh) "筛选智能体" else "Filter agents") },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(AgentTestTags.QUERY),
        )
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("${AgentTestTags.CARD_PREFIX}${agent.id}"),
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
    Column(Modifier.fillMaxWidth().testTag(AgentTestTags.SUMMARY)) {
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
        Text(if (zh) "能力授权" else "Capability grants", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        if (!state.grantStoreAvailable || state.grantStoreError != null) {
            Text(
                state.grantStoreError ?: if (zh) "授权存储未就绪，无法编辑或创建授权。" else "Grant storage is unavailable; grants cannot be edited or created.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (editor.grants.isEmpty()) {
            Text(if (zh) "没有显式能力授权。" else "No explicit capability grants.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        } else {
            editor.grants.forEach { grant ->
                val location = listOfNotNull(grant.workspaceName, grant.grant.pathScope?.let { if (zh) "范围：$it" else "Scope: $it" }).joinToString(" · ")
                val stateLabel = when {
                    grant.grant.revoked -> if (zh) "已撤销" else "Revoked"
                    grant.expired -> if (zh) "已过期" else "Expired"
                    !grant.skillTrusted -> if (zh) "Skill 绑定未验证" else "Skill binding unverified"
                    else -> if (zh) "有效" else "Active"
                }
                Text(
                    listOfNotNull(
                        grant.grant.capability.value,
                        location.takeIf { it.isNotBlank() },
                        grantLifetimeLabel(grant.grant.lifetime, zh),
                        "r${grant.grant.revision}",
                        grant.skillName?.let { if (zh) "Skill：$it" else "Skill: $it" },
                        stateLabel,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (editor.trustedSkills.isNotEmpty()) {
            Text(if (zh) "可信 Skill 绑定" else "Trusted Skill bindings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            editor.trustedSkills.forEach { skill ->
                val status = when {
                    !skill.enabled -> if (zh) "未启用" else "Disabled"
                    skill.trusted -> if (zh) "已验证安装与授权" else "Verified install and grant"
                    else -> if (zh) "待授权或包已变化" else "Grant missing or package changed"
                }
                Text("${skill.name} · $status", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (editor.snapshotGrantBindings.isNotEmpty()) {
            Text(if (zh) "当前快照授权绑定" else "Current snapshot grant bindings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            editor.snapshotGrantBindings.forEach { binding ->
                val scope = binding.binding.pathScope?.let { if (zh) "范围：$it" else "Scope: $it" }
                Text(
                    listOfNotNull(binding.binding.capability.value, binding.workspaceName, scope).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Text(if (zh) "检索模式：${editor.retrievalMode}" else "Retrieval mode: ${editor.retrievalMode}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton(onClick = actions.onSnapshot, modifier = Modifier.padding(top = 8.dp).testTag(AgentTestTags.SNAPSHOT)) {
            Text(if (zh) "创建快照边界" else "Create snapshot boundary")
        }
    }
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
private fun AgentEditorDialog(
    editor: AgentEditorUi,
    actions: AgentsActions,
    zh: Boolean,
    error: String?,
    grantStoreAvailable: Boolean,
    grantStoreError: String?,
) {
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(if (editor.id == null) { if (zh) "新建智能体" else "New agent" } else { if (zh) "编辑智能体" else "Edit agent" }) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag(AgentTestTags.EDITOR),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    editor.name,
                    { actions.onEditorChange(editor.copy(name = it)) },
                    label = { Text(if (zh) "名称" else "Name") },
                    modifier = Modifier.fillMaxWidth().testTag(AgentTestTags.NAME),
                )
                OutlinedTextField(
                    editor.prompt,
                    { actions.onEditorChange(editor.copy(prompt = it)) },
                    label = { Text(if (zh) "提示词修订" else "Prompt revision") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag(AgentTestTags.PROMPT),
                )
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
                Column(Modifier.fillMaxWidth().testTag(AgentTestTags.RESOURCES)) {
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
                AgentGrantEditor(
                    editor = editor,
                    actions = actions,
                    zh = zh,
                    grantStoreAvailable = grantStoreAvailable,
                    grantStoreError = grantStoreError,
                )
            }
        },
        confirmButton = {
            Button(onClick = actions.onSave, modifier = Modifier.testTag(AgentTestTags.SAVE)) {
                Text(if (zh) "保存" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCloseEditor, modifier = Modifier.testTag(AgentTestTags.CANCEL)) {
                Text(if (zh) "取消" else "Cancel")
            }
        },
    )
}

private val agentCapabilityOptions = listOf(
    CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
    CapabilityId(CapabilityId.FILE_LIST),
    CapabilityId(CapabilityId.FILE_STAT),
    CapabilityId(CapabilityId.FILE_READ_TEXT),
    CapabilityId(CapabilityId.FILE_WRITE_TEXT),
    CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
    CapabilityId(CapabilityId.FILE_MOVE),
    CapabilityId(CapabilityId.FILE_DELETE),
    CapabilityId(CapabilityId.MEMORY_READ),
    CapabilityId(CapabilityId.MEMORY_SEARCH),
    CapabilityId(CapabilityId.MEMORY_APPEND),
    CapabilityId(CapabilityId.MEMORY_REPLACE),
    CapabilityId(CapabilityId.SHELL_EXECUTE),
)

private fun capabilityLabel(capability: CapabilityId, zh: Boolean): String = when (capability.value) {
    CapabilityId.WORKSPACE_ENUMERATE -> if (zh) "workspace.enumerate（工作区枚举）" else "workspace.enumerate (workspace list)"
    CapabilityId.FILE_LIST -> if (zh) "file.list（文件列表）" else "file.list (list files)"
    CapabilityId.FILE_STAT -> if (zh) "file.stat（文件信息）" else "file.stat (file metadata)"
    CapabilityId.FILE_READ_TEXT -> if (zh) "file.read_text（读取文本）" else "file.read_text (read text)"
    CapabilityId.FILE_WRITE_TEXT -> if (zh) "file.write_text（写入文本）" else "file.write_text (write text)"
    CapabilityId.FILE_CREATE_DIRECTORY -> if (zh) "file.create_directory（创建目录）" else "file.create_directory (create directory)"
    CapabilityId.FILE_MOVE -> if (zh) "file.move（移动文件）" else "file.move (move files)"
    CapabilityId.FILE_DELETE -> if (zh) "file.delete（删除文件）" else "file.delete (delete files)"
    CapabilityId.MEMORY_READ -> if (zh) "memory.read（读取记忆）" else "memory.read (read memory)"
    CapabilityId.MEMORY_SEARCH -> if (zh) "memory.search（搜索记忆）" else "memory.search (search memory)"
    CapabilityId.MEMORY_APPEND -> if (zh) "memory.append（追加记忆）" else "memory.append (append memory)"
    CapabilityId.MEMORY_REPLACE -> if (zh) "memory.replace（替换记忆）" else "memory.replace (replace memory)"
    CapabilityId.SHELL_EXECUTE -> if (zh) "shell.execute（高风险命令）" else "shell.execute (high risk)"
    else -> capability.value
}

private val agentEditorGrantLifetimes = listOf(GrantLifetime.ONCE, GrantLifetime.PERSISTENT)

private fun grantLifetimeLabel(lifetime: GrantLifetime, zh: Boolean): String = when (lifetime) {
    GrantLifetime.ONCE -> if (zh) "单次（ONCE）" else "Once (ONCE)"
    GrantLifetime.TASK -> if (zh) "任务（TASK）" else "Task (TASK)"
    GrantLifetime.SESSION -> if (zh) "会话（SESSION）" else "Session (SESSION)"
    GrantLifetime.PERSISTENT -> if (zh) "长期（PERSISTENT）" else "Persistent (PERSISTENT)"
}

@Composable
private fun AgentGrantEditor(
    editor: AgentEditorUi,
    actions: AgentsActions,
    zh: Boolean,
    grantStoreAvailable: Boolean,
    grantStoreError: String?,
) {
    val grantEditorReady = grantStoreAvailable && grantStoreError == null
    Column(Modifier.fillMaxWidth().testTag(AgentTestTags.GRANTS)) {
        Text(if (zh) "能力授权" else "Capability grants", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        grantStoreError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!grantStoreAvailable) {
            Text(
                if (zh) "授权存储未就绪；保存不会静默忽略授权变更。" else "Grant storage is unavailable; grant changes will not be silently ignored.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (editor.grants.isEmpty()) {
            Text(if (zh) "暂无现有授权。" else "No existing grants.", style = MaterialTheme.typography.bodySmall)
        } else {
            editor.grants.forEach { grant ->
                val location = listOfNotNull(
                    grant.workspaceName,
                    grant.grant.pathScope?.let { if (zh) "范围：$it" else "Scope: $it" },
                ).joinToString(" · ")
                val stateLabel = when {
                    grant.grant.revoked -> if (zh) "已撤销" else "Revoked"
                    grant.expired -> if (zh) "已过期" else "Expired"
                    !grant.skillTrusted -> if (zh) "Skill 绑定未验证" else "Skill binding unverified"
                    grant.enabled -> if (zh) "有效" else "Active"
                    else -> if (zh) "待撤销" else "Pending revoke"
                }
                Row(
                    Modifier.fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("${AgentTestTags.GRANT_ROW_PREFIX}${grant.grant.grantId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = grant.enabled,
                        onCheckedChange = { checked ->
                            actions.onEditorChange(
                                editor.copy(grants = editor.grants.map {
                                    if (it.grant.grantId == grant.grant.grantId) it.copy(enabled = checked) else it
                                }),
                            )
                        },
                        enabled = !grant.grant.revoked && !grant.expired && grant.skillTrusted,
                        modifier = Modifier.testTag("${AgentTestTags.GRANT_TOGGLE_PREFIX}${grant.grant.grantId}"),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(capabilityLabel(grant.grant.capability, zh))
                        Text(
                            listOfNotNull(location.takeIf { it.isNotBlank() }, grantLifetimeLabel(grant.grant.lifetime, zh), "r${grant.grant.revision}", stateLabel).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        grant.skillName?.let { Text(if (zh) "可信 Skill：$it" else "Trusted Skill: $it", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        if (editor.grantDraft == null) {
            OutlinedButton(
                onClick = { actions.onEditorChange(editor.copy(grantDraft = AgentGrantDraftUi())) },
                enabled = grantEditorReady,
                modifier = Modifier.fillMaxWidth().testTag(AgentTestTags.GRANT_ADD),
            ) {
                Text(if (zh) "添加能力授权" else "Add capability grant")
            }
        } else {
            AgentGrantDraftEditor(editor, actions, zh, grantEditorReady)
        }
    }
}

@Composable
private fun AgentGrantDraftEditor(editor: AgentEditorUi, actions: AgentsActions, zh: Boolean, enabled: Boolean) {
    val draft = editor.grantDraft ?: return
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (zh) "新授权（保存 Agent 时写入）" else "New grant (saved with the Agent)", fontWeight = FontWeight.SemiBold)
        CapabilityDropdown(draft.capability, zh, enabled) {
            actions.onEditorChange(
                editor.copy(
                    grantDraft = draft.copy(
                        capability = it,
                        workspaceId = if (it.value == CapabilityId.SHELL_EXECUTE) null else draft.workspaceId,
                        pathScope = if (it.value == CapabilityId.SHELL_EXECUTE) null else draft.pathScope,
                    ),
                ),
            )
        }
        if (draft.capability.value == CapabilityId.SHELL_EXECUTE) {
            Text(
                if (zh) "shell.execute 仅在已选 Authority、Dangerous Mode 和实时策略均允许时生效。" else "shell.execute is effective only when the selected Authority, Dangerous Mode, and live policy allow it.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(AgentTestTags.GRANT_SHELL),
            )
        }
        WorkspaceDropdown(
            selectedId = draft.workspaceId,
            workspaces = editor.workspaces,
            zh = zh,
            enabled = enabled && draft.capability.value != CapabilityId.SHELL_EXECUTE,
        ) { workspaceId -> actions.onEditorChange(editor.copy(grantDraft = draft.copy(workspaceId = workspaceId))) }
        OutlinedTextField(
            value = draft.pathScope.orEmpty(),
            onValueChange = { actions.onEditorChange(editor.copy(grantDraft = draft.copy(pathScope = it.ifBlank { null }))) },
            enabled = enabled && draft.capability.value != CapabilityId.SHELL_EXECUTE,
            label = { Text(if (zh) "相对范围（可选）" else "Relative scope (optional)") },
            supportingText = { Text(if (zh) "仅填写工作区内的相对范围，不是设备路径。" else "Use a workspace-relative scope, never a device path.") },
            modifier = Modifier.fillMaxWidth().testTag(AgentTestTags.GRANT_SCOPE),
        )
        val hasEditorLifetimeContext = draft.lifetime in agentEditorGrantLifetimes
        Text(
            if (zh) {
                if (hasEditorLifetimeContext) {
                    "此编辑页只创建单次或长期授权。TASK/SESSION 只能由具有真实任务或会话上下文的运行时流程创建。"
                } else {
                    "当前草稿包含 TASK/SESSION，但此编辑页没有真实任务或会话上下文；保存将被拒绝且不会写入授权。请在运行时流程中创建。"
                }
            } else {
                if (hasEditorLifetimeContext) {
                    "This editor creates only ONCE or PERSISTENT grants. TASK/SESSION grants require a real task or conversation context from a runtime flow."
                } else {
                    "This draft contains TASK/SESSION, but this editor has no real task or conversation context. Saving is rejected and no grant is written; create it from a runtime flow."
                }
            },
            color = if (hasEditorLifetimeContext) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(AgentTestTags.GRANT_LIFETIME_CONTEXT),
        )
        LifetimeSelector(draft.lifetime, zh, enabled) { lifetime ->
            actions.onEditorChange(editor.copy(grantDraft = draft.copy(lifetime = lifetime)))
        }
        SkillBindingDropdown(
            selectedId = draft.skillInstallId,
            skills = editor.trustedSkills,
            zh = zh,
            enabled = enabled,
        ) { installId -> actions.onEditorChange(editor.copy(grantDraft = draft.copy(skillInstallId = installId))) }
        TextButton(onClick = { actions.onEditorChange(editor.copy(grantDraft = null)) }, enabled = enabled) {
            Text(if (zh) "移除此新授权" else "Remove this new grant")
        }
    }
}

@Composable
private fun CapabilityDropdown(selected: CapabilityId, zh: Boolean, enabled: Boolean, onSelect: (CapabilityId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(AgentTestTags.GRANT_CAPABILITY),
    ) {
        Text(if (zh) "能力：${capabilityLabel(selected, true)}" else "Capability: ${capabilityLabel(selected, false)}", modifier = Modifier.fillMaxWidth())
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        agentCapabilityOptions.forEach { capability ->
            DropdownMenuItem(
                text = { Text(capabilityLabel(capability, zh)) },
                onClick = { expanded = false; onSelect(capability) },
            )
        }
    }
}

@Composable
private fun WorkspaceDropdown(
    selectedId: String?,
    workspaces: List<AgentWorkspaceUi>,
    zh: Boolean,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = workspaces.firstOrNull { it.id == selectedId }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(AgentTestTags.GRANT_WORKSPACE)) {
        Text(
            if (zh) "工作区：${selected?.displayName ?: "Agent 级（不限定工作区）"}"
            else "Workspace: ${selected?.displayName ?: "Agent scoped (no workspace)"}",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(if (zh) "Agent 级（不限定工作区）" else "Agent scoped (no workspace)") },
            onClick = { expanded = false; onSelect(null) },
        )
        workspaces.forEach { workspace ->
            DropdownMenuItem(
                text = {
                    Text(
                        "${workspace.displayName}${if (workspace.enabled) "" else if (zh) "（已停用）" else " (disabled)"}",
                    )
                },
                onClick = { expanded = false; onSelect(workspace.id) },
                enabled = workspace.enabled,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LifetimeSelector(selected: GrantLifetime, zh: Boolean, enabled: Boolean, onSelect: (GrantLifetime) -> Unit) {
    Text(if (zh) "授权期限" else "Grant lifetime", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(AgentTestTags.GRANT_LIFETIME))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        agentEditorGrantLifetimes.forEach { lifetime ->
            FilterChip(
                selected = selected == lifetime,
                onClick = { onSelect(lifetime) },
                enabled = enabled,
                label = { Text(grantLifetimeLabel(lifetime, zh)) },
                modifier = Modifier.testTag("${AgentTestTags.GRANT_LIFETIME}.${lifetime.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun SkillBindingDropdown(
    selectedId: String?,
    skills: List<AgentTrustedSkillUi>,
    zh: Boolean,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = skills.firstOrNull { it.installId == selectedId }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(AgentTestTags.GRANT_SKILL)) {
        Text(
            if (zh) "Skill 包绑定：${selected?.name ?: "无（Agent 级授权）"}"
            else "Skill package binding: ${selected?.name ?: "None (Agent scoped)"}",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(if (zh) "无（Agent 级授权）" else "None (Agent scoped)") },
            onClick = { expanded = false; onSelect(null) },
        )
        skills.forEach { skill ->
            DropdownMenuItem(
                text = {
                    Text(
                        "${skill.name}${if (skill.trusted) "" else if (zh) "（未验证授权）" else " (grant not verified)"}",
                    )
                },
                onClick = { expanded = false; onSelect(skill.installId) },
                enabled = skill.trusted && skill.enabled,
            )
        }
    }
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
