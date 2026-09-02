// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.ProviderConnectionErrorCode

data class ProviderCardUi(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiFormat: String,
    val status: String = "",
    val modelCount: Int = 0,
    val secretConfigured: Boolean = false,
)

data class ProviderModelUi(
    val id: String,
    val modelId: String,
    val role: String = "CHAT",
    val capabilities: Set<String> = emptySet(),
    val contextLimit: Int? = null,
    val outputLimit: Int? = null,
)

data class ProviderDraft(
    val id: String? = null,
    val modelProfileId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiFormat: String = "OPENAI_COMPATIBLE",
    val modelId: String = "",
    val apiKey: String = "",
    val vision: Boolean = false,
    val tools: Boolean = false,
    val role: String = "CHAT",
    val parametersJson: String = "{}",
    // Keep budgets as text while editing so clearing a field does not
    // immediately restore the previous value.  Persistence validation
    // happens when the draft is submitted.
    val contextLimit: String = "32768",
    val outputLimit: String = "4096",
    val mcpConfigured: Boolean = false,
)

/**
 * Parses the exact positive-integer form accepted by the Provider editor.
 * The persisted ModelProfile remains Int-typed; this helper is only for the
 * transient editor draft and its validation UI.
 */
fun parsePositiveProviderBudget(raw: String): Int? {
    val normalized = raw.trim()
    if (normalized.isEmpty() || normalized.any { it !in '0'..'9' }) return null
    return normalized.toLongOrNull()
        ?.takeIf { it in 1..Int.MAX_VALUE }
        ?.toInt()
}

fun providerBudgetError(contextLimit: String, outputLimit: String, zh: Boolean): String? {
    val context = parsePositiveProviderBudget(contextLimit)
        ?: return if (zh) "上下文预算必须是正整数。" else "Context budget must be a positive integer."
    val output = parsePositiveProviderBudget(outputLimit)
        ?: return if (zh) "输出预算必须是正整数。" else "Output budget must be a positive integer."
    return if (output > context) {
        if (zh) "输出预算不能超过上下文预算。" else "Output budget cannot exceed the context budget."
    } else {
        null
    }
}

enum class ProbePhase { IDLE, RUNNING, SUCCESS, PARTIAL, FAILURE }

enum class ProbeOperation { NONE, CONNECTION, CAPABILITY }

data class ConnectionCheckUi(
    val success: Boolean,
    val latencyMs: Long? = null,
    val error: ProviderConnectionErrorCode? = null,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
    val charged: Boolean = false,
)

data class ProbeCheckUi(
    val capability: CapabilityCheck,
    val status: CapabilityCheckStatus,
    val httpStatus: Int? = null,
)

/**
 * Typed, render-ready state for both independent provider operations.  The
 * legacy [ProbeUiState] name remains a type alias so callers can migrate
 * without reintroducing string phases or free-form result text.
 */
data class ProviderProbeUiState(
    val phase: ProbePhase = ProbePhase.IDLE,
    val operation: ProbeOperation = ProbeOperation.NONE,
    /** Stable local targets prevent a late result from being shown for another model. */
    val providerId: String? = null,
    val modelId: String? = null,
    val connection: ConnectionCheckUi? = null,
    val checks: List<ProbeCheckUi> = emptyList(),
    val error: ProviderConnectionErrorCode? = null,
    val charged: Boolean = false,
    val latencyMs: Long? = null,
    val lastChecked: String? = null,
)

typealias ProbeUiState = ProviderProbeUiState

data class ProvidersUiState(
    val providers: List<ProviderCardUi> = emptyList(),
    val selectedProviderId: String? = null,
    val models: List<ProviderModelUi> = emptyList(),
    val draft: ProviderDraft = ProviderDraft(),
    val probe: ProbeUiState = ProbeUiState(),
    val editorOpen: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val status: String = "",
    val language: String = "zh-CN",
    val mcpReason: String = "MCP 适配器报告已配置端点后，MCP 设置才可用。",
    /** The configuration entry is available even when no MCP endpoint is configured. */
    val mcpEntryEnabled: Boolean = false,
    val editorError: String? = null,
    val deleteModelCount: Int = 0,
    val deleteSnapshotCount: Int = 0,
)

data class ProvidersActions(
    val onSelectProvider: (String) -> Unit = {},
    val onDraftChange: (ProviderDraft) -> Unit = {},
    val onOpenEditor: (String?) -> Unit = {},
    val onCloseEditor: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onEditModel: (String?) -> Unit = {},
    val onDeleteModel: (String) -> Unit = {},
    val onProbe: () -> Unit = {},
    val onCloseProbe: () -> Unit = {},
    val onOpenMcpSettings: () -> Unit = {},
    val onTestConnection: () -> Unit = {},
)

@Composable
fun ProvidersScreen(state: ProvidersUiState, actions: ProvidersActions = ProvidersActions(), modifier: Modifier = Modifier) {
    val zh = state.language.equals("zh-CN", true)
    var deleteProviderId by remember { mutableStateOf<String?>(null) }
    var deleteModelId by remember { mutableStateOf<String?>(null) }
    var probeRequested by remember { mutableStateOf(false) }
    var connectionRequested by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProviderListPane(state, actions, zh, Modifier.weight(0.42f).fillMaxSize())
                Column(Modifier.weight(0.58f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProviderDetail(
                        state,
                        actions,
                        onRequestDeleteProvider = { deleteProviderId = it },
                        onRequestDeleteModel = { deleteModelId = it },
                        onRequestConnection = { connectionRequested = true },
                        onRequestProbe = { probeRequested = true },
                        zh = zh,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderListPane(state, actions, zh, Modifier.fillMaxWidth())
                ProviderDetail(
                    state,
                    actions,
                    onRequestDeleteProvider = { deleteProviderId = it },
                    onRequestDeleteModel = { deleteModelId = it },
                    onRequestConnection = { connectionRequested = true },
                    onRequestProbe = { probeRequested = true },
                    zh = zh,
                )
            }
        }
    }
    if (state.editorOpen) ProviderEditorDialog(state, actions, zh)
    if (state.probe.phase != ProbePhase.IDLE) ProbeDialog(state.probe, actions.onCloseProbe, zh)
    if (connectionRequested) {
        AlertDialog(
            onDismissRequest = { connectionRequested = false },
            title = { Text(if (zh) "测试服务商连接？" else "Test provider connection?") },
            text = {
                Text(
                    if (zh) {
                        "将按当前模型配置发送一次最小对话请求，以确认服务商可以正常响应。请求可能产生极小费用。"
                    } else {
                        "One minimal chat request will be sent using the current model configuration. It may incur a very small provider charge."
                    },
                )
            },
            confirmButton = {
                Button(onClick = { connectionRequested = false; actions.onTestConnection() }) {
                    Text(if (zh) "测试连接" else "Test connection")
                }
            },
            dismissButton = {
                TextButton(onClick = { connectionRequested = false }) { Text(if (zh) "取消" else "Cancel") }
            },
        )
    }
    if (probeRequested) {
        AlertDialog(
            onDismissRequest = { probeRequested = false },
            title = { Text(if (zh) "运行服务商探测？" else "Run provider probe?") },
            text = { Text(if (zh) "能力探测会向已配置端点发送 metadata 请求，并可能分别发送最多 3 个最小 Chat 请求来验证流式、工具与图片能力；这些请求可能分别产生服务商费用。" else "The probe sends a metadata request and may send up to three separate minimal chat requests to verify streaming, tools, and images. Each request may incur provider charges.") },
            confirmButton = { Button(onClick = { probeRequested = false; actions.onProbe() }) { Text(if (zh) "运行探测" else "Run probe") } },
            dismissButton = { TextButton(onClick = { probeRequested = false }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    deleteProviderId?.let { providerId ->
        val name = state.providers.firstOrNull { it.id == providerId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleteProviderId = null },
            title = { Text(if (zh) "删除服务商？" else "Delete provider?") },
            text = { Text(
                if (zh) "将删除 $name 及其模型元数据。当前引用：${state.deleteModelCount} 个模型，${state.deleteSnapshotCount} 个会话快照。无引用时密文会退休并进入垃圾回收；Keystore 条目由系统生命周期管理。"
                else "Delete $name and its model metadata. Current references: ${state.deleteModelCount} models, ${state.deleteSnapshotCount} conversation snapshots. Unreferenced ciphertext is retired and garbage-collected; Keystore entries follow the platform lifecycle."
            ) },
            confirmButton = { Button(onClick = { deleteProviderId = null; actions.onDelete() }) { Text(if (zh) "删除" else "Delete") } },
            dismissButton = { TextButton(onClick = { deleteProviderId = null }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
    deleteModelId?.let { modelId ->
        AlertDialog(
            onDismissRequest = { deleteModelId = null },
            title = { Text(if (zh) "删除模型元数据？" else "Delete model metadata?") },
            text = { Text(if (zh) "将从服务商配置中删除此模型；不会自动联系服务商。" else "This removes the model from the provider profile. The provider is not contacted automatically.") },
            confirmButton = { Button(onClick = { deleteModelId = null; actions.onDeleteModel(modelId) }) { Text(if (zh) "删除" else "Delete") } },
            dismissButton = { TextButton(onClick = { deleteModelId = null }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }
}

@Composable
private fun ProviderListPane(state: ProvidersUiState, actions: ProvidersActions, zh: Boolean, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "服务商" else "Providers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { actions.onOpenEditor(null) }) { Text(if (zh) "添加服务商" else "Add provider") }
        }
        if (state.status.isNotBlank()) ProviderStatus(state.status, Modifier.padding(vertical = 8.dp))
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(top = 16.dp).size(24.dp))
        } else if (state.error != null) {
            ProviderStatus(state.error, Modifier.padding(top = 16.dp), error = true)
        } else if (state.providers.isEmpty()) {
            EmptyProviderState(zh)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(280.dp).padding(top = 12.dp)) {
                items(state.providers, key = { it.id }) { provider ->
                    ProviderCard(provider, provider.id == state.selectedProviderId, zh) { actions.onSelectProvider(provider.id) }
                }
            }
        }
    }
}

@Composable
private fun EmptyProviderState(zh: Boolean) {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (zh) "尚未配置服务商" else "No providers configured", style = MaterialTheme.typography.titleMedium)
            Text(if (zh) "添加服务商以选择模型。凭据保留在本设备。" else "Add a provider to select a model. Credentials remain on this device.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ProviderCard(provider: ProviderCardUi, selected: Boolean, zh: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (provider.status.isNotBlank()) FilterChip(selected = provider.status.equals("ready", true), onClick = {}, enabled = false, label = { Text(provider.status) })
            }
            Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text(if (zh) "${provider.apiFormat} · ${provider.modelCount} 个模型" else "${provider.apiFormat} · ${provider.modelCount} models", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            Text(if (provider.secretConfigured) { if (zh) "已配置密钥引用" else "Credential reference configured" } else { if (zh) "未配置密钥引用" else "Credential reference missing" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ProviderStatus(message: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 5,
        )
    }
}

@Composable
private fun ProviderDetail(
    state: ProvidersUiState,
    actions: ProvidersActions,
    onRequestDeleteProvider: (String) -> Unit,
    onRequestDeleteModel: (String) -> Unit,
    onRequestConnection: () -> Unit,
    onRequestProbe: () -> Unit,
    zh: Boolean,
) {
    val provider = state.providers.firstOrNull { it.id == state.selectedProviderId }
    if (provider == null) {
        Text(if (zh) "选择服务商以查看模型和能力。" else "Select a provider to inspect models and capabilities.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
        return
    }
    Text(provider.name, style = MaterialTheme.typography.headlineSmall)
    Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRequestConnection) { Text(if (zh) "测试连接" else "Test connection") }
        OutlinedButton(onClick = onRequestProbe) { Text(if (zh) "能力探测" else "Capability probe") }
        OutlinedButton(onClick = { actions.onOpenEditor(provider.id) }) { Text(if (zh) "编辑" else "Edit") }
        OutlinedButton(onClick = { onRequestDeleteProvider(provider.id) }) { Text(if (zh) "删除" else "Delete") }
    }
    Text(if (zh) "模型与能力" else "Models and capabilities", style = MaterialTheme.typography.titleMedium)
    if (state.models.isEmpty()) Text(if (zh) "暂无模型元数据。" else "No model metadata is available.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    state.models.forEach { model ->
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.modelId, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.onEditModel(model.id) }) { Text(if (zh) "编辑" else "Edit") }
                    TextButton(onClick = { onRequestDeleteModel(model.id) }) { Text(if (zh) "删除" else "Delete") }
                }
                Text(if (zh) "角色：${model.role}" else "Role: ${model.role}", style = MaterialTheme.typography.bodySmall)
                val capabilityLabel = if (model.capabilities.isEmpty()) {
                    if (zh) "能力不可用" else "Capabilities unavailable"
                } else {
                    if (zh) "能力：${model.capabilities.sorted().joinToString()}" else "Capabilities: ${model.capabilities.sorted().joinToString()}"
                }
                Text(capabilityLabel, style = MaterialTheme.typography.bodySmall)
                val limits = listOfNotNull(model.contextLimit?.let { "context $it" }, model.outputLimit?.let { "output $it" })
                if (limits.isNotEmpty()) Text(limits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(if (zh) "MCP 工具" else "MCP tools", style = MaterialTheme.typography.titleMedium)
    Text(state.mcpReason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    OutlinedButton(onClick = actions.onOpenMcpSettings, enabled = state.mcpEntryEnabled, modifier = Modifier.padding(top = 8.dp)) {
        Text(if (zh) "打开 MCP 设置" else "Open MCP settings")
    }
}

@Composable
private fun ProviderEditorDialog(state: ProvidersUiState, actions: ProvidersActions, zh: Boolean) {
    val draft = state.draft
    val showModelFields = draft.modelProfileId != null || draft.modelId.isNotBlank() || draft.id == null
    val budgetError = if (showModelFields) providerBudgetError(draft.contextLimit, draft.outputLimit, zh) else null
    val noCorrectionText = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Text,
    )
    val noCorrectionAscii = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
    )
    val uriOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri,
    )
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(if (draft.modelProfileId != null) { if (zh) "编辑模型" else "Edit model" } else if (draft.id == null) { if (zh) "添加服务商" else "Add provider" } else { if (zh) "编辑服务商" else "Edit provider" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                budgetError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(draft.name, { actions.onDraftChange(draft.copy(name = it)) }, label = { Text(if (zh) "名称" else "Name") }, keyboardOptions = noCorrectionText, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.baseUrl, { actions.onDraftChange(draft.copy(baseUrl = it)) }, label = { Text(if (zh) "基础地址" else "Base URL") }, keyboardOptions = uriOptions, modifier = Modifier.fillMaxWidth())
                Text(if (zh) "API 格式：OpenAI Compatible（当前唯一支持的格式）" else "API format: OpenAI Compatible (the only supported format)", style = MaterialTheme.typography.bodySmall)
                if (showModelFields) {
                    OutlinedTextField(draft.modelId, { actions.onDraftChange(draft.copy(modelId = it)) }, label = { Text(if (zh) "模型 ID" else "Model id") }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth())
                    Text(if (zh) "操作：CHAT / EMBEDDING / RERANKER；图片是 Chat 的输入模态，不是独立服务。" else "Operation: CHAT / EMBEDDING / RERANKER. Images are a Chat input modality, not a separate service.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.role, { actions.onDraftChange(draft.copy(role = it)) }, label = { Text(if (zh) "操作/角色" else "Operation / role") }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.parametersJson, { actions.onDraftChange(draft.copy(parametersJson = it)) }, label = { Text(if (zh) "参数 JSON" else "Parameters JSON") }, keyboardOptions = noCorrectionText, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.contextLimit, { actions.onDraftChange(draft.copy(contextLimit = it)) }, label = { Text(if (zh) "上下文预算" else "Context budget") }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth(), isError = budgetError != null && parsePositiveProviderBudget(draft.contextLimit) == null)
                    OutlinedTextField(draft.outputLimit, { actions.onDraftChange(draft.copy(outputLimit = it)) }, label = { Text(if (zh) "输出预算" else "Output budget") }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth(), isError = budgetError != null && (parsePositiveProviderBudget(draft.outputLimit) == null || (parsePositiveProviderBudget(draft.contextLimit)?.let { context -> parsePositiveProviderBudget(draft.outputLimit)?.let { output -> output > context } } == true)))
                    CheckRow(if (zh) "输入包含图片" else "Input includes images", draft.vision) { actions.onDraftChange(draft.copy(vision = it)) }
                    CheckRow(if (zh) "可调用工具" else "Can call tools", draft.tools) { actions.onDraftChange(draft.copy(tools = it)) }
                }
                OutlinedTextField(draft.apiKey, { actions.onDraftChange(draft.copy(apiKey = it)) }, label = { Text(if (draft.id == null) { if (zh) "API 密钥" else "API key" } else { if (zh) "替换 API 密钥（可选）" else "Replace API key (optional)" }) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth())
                Text(if (zh) "能力探测分别记录用户声明与真实验证，可能产生服务商费用，且只在明确确认后运行。" else "Probes record user-declared vs verified behavior, can incur provider charges, and only run after explicit confirmation.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = actions.onSave, enabled = budgetError == null) { Text(if (zh) "保存" else "Save") } },
        dismissButton = { TextButton(onClick = actions.onCloseEditor) { Text(if (zh) "取消" else "Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

@Composable
private fun ProbeDialog(probe: ProviderProbeUiState, onClose: () -> Unit, zh: Boolean) {
    AlertDialog(
        onDismissRequest = { if (probe.phase != ProbePhase.RUNNING) onClose() },
        title = {
            Text(
                when (probe.operation) {
                    ProbeOperation.CONNECTION -> if (zh) "测试连接" else "Test connection"
                    ProbeOperation.CAPABILITY -> if (zh) "能力探测" else "Capability probe"
                    ProbeOperation.NONE -> if (zh) "服务商检查" else "Provider check"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (probe.phase == ProbePhase.RUNNING) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(if (zh) "正在检查…" else "Checking…", modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(probePhaseLabel(probe.phase, zh), modifier = Modifier.padding(top = 8.dp))
                }
                probe.connection?.let { connection ->
                    if (connection.success) {
                        Text(if (zh) "连接成功" else "Connection succeeded", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        connection.latencyMs?.let { latency ->
                            Text(
                                if (zh) "模型响应正常 · ${latency} ms" else "Model response normal · ${latency} ms",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            if (zh) "连接失败：${connectionErrorLabel(connection.error, zh)}" else "Connection failed: ${connectionErrorLabel(connection.error, zh)}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        connection.httpStatus?.let { status ->
                            Text(
                                if (zh) "HTTP ${status / 100}xx" else "HTTP ${status / 100}xx",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                probe.checks.forEach { check ->
                    Text(
                        "${capabilityLabel(check.capability, zh)}：${capabilityStatusLabel(check.status, zh)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (probe.charged && probe.phase != ProbePhase.RUNNING) {
                    Text(
                        if (zh) "本次检查可能产生服务商费用。" else "This check may have incurred provider charges.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                probe.lastChecked?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { if (probe.phase != ProbePhase.RUNNING) Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
    )
}

private fun probePhaseLabel(phase: ProbePhase, zh: Boolean): String = when (phase) {
    ProbePhase.IDLE -> if (zh) "尚未检查" else "Not checked"
    ProbePhase.RUNNING -> if (zh) "正在检查…" else "Checking…"
    ProbePhase.SUCCESS -> if (zh) "检查成功" else "Check succeeded"
    ProbePhase.PARTIAL -> if (zh) "部分能力已确认" else "Partially verified"
    ProbePhase.FAILURE -> if (zh) "检查失败" else "Check failed"
}

private fun capabilityLabel(capability: CapabilityCheck, zh: Boolean): String = when (capability) {
    CapabilityCheck.METADATA -> if (zh) "模型信息" else "Metadata"
    CapabilityCheck.STREAM -> if (zh) "流式输出" else "Streaming"
    CapabilityCheck.TOOLS -> if (zh) "工具调用" else "Tools"
    CapabilityCheck.IMAGE -> if (zh) "图片输入" else "Images"
}

private fun capabilityStatusLabel(status: CapabilityCheckStatus, zh: Boolean): String = when (status) {
    CapabilityCheckStatus.VERIFIED -> if (zh) "支持" else "Supported"
    CapabilityCheckStatus.UNSUPPORTED -> if (zh) "不支持" else "Unsupported"
    CapabilityCheckStatus.NOT_DECLARED -> if (zh) "未声明" else "Not declared"
    CapabilityCheckStatus.NOT_RUN -> if (zh) "未测试" else "Not tested"
    CapabilityCheckStatus.FAILED -> if (zh) "检查失败" else "Check failed"
    CapabilityCheckStatus.UNKNOWN -> if (zh) "未知" else "Unknown"
}

private fun connectionErrorLabel(error: ProviderConnectionErrorCode?, zh: Boolean): String = when (error) {
    ProviderConnectionErrorCode.NETWORK_UNREACHABLE -> if (zh) "网络不可达" else "Network unreachable"
    ProviderConnectionErrorCode.TLS_FAILURE -> if (zh) "TLS 安全连接失败" else "TLS failure"
    ProviderConnectionErrorCode.TIMEOUT -> if (zh) "请求超时" else "Timeout"
    ProviderConnectionErrorCode.AUTH_FAILED -> if (zh) "认证失败" else "Authentication failed"
    ProviderConnectionErrorCode.MODEL_NOT_FOUND -> if (zh) "模型不存在" else "Model not found"
    ProviderConnectionErrorCode.RATE_LIMITED -> if (zh) "请求受限" else "Rate limited"
    ProviderConnectionErrorCode.PROVIDER_REJECTED -> if (zh) "服务商拒绝请求" else "Provider rejected request"
    ProviderConnectionErrorCode.INVALID_RESPONSE -> if (zh) "响应无效" else "Invalid response"
    ProviderConnectionErrorCode.CONFIG_INVALID -> if (zh) "配置无效" else "Invalid configuration"
    ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE -> if (zh) "凭据不可用" else "Credential unavailable"
    ProviderConnectionErrorCode.UNKNOWN, null -> if (zh) "未知错误" else "Unknown error"
}
