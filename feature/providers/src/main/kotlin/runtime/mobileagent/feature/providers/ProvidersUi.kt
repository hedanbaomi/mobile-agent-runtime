// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.providers

import androidx.compose.ui.res.stringResource

import runtime.mobileagent.domain.BudgetValidationError
import runtime.mobileagent.domain.ContextLimitSource
import runtime.mobileagent.domain.ContextLimitMode
import runtime.mobileagent.domain.contextWindowTarget
import runtime.mobileagent.domain.contextWindowTargetMatches
import runtime.mobileagent.domain.validateBudgetSelection
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.advancedOutputLimitOverride
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** AUTO means the app sends no output cap of its own. */
    val outputLimitMode: String = OutputLimitMode.MANUAL.name,
    /** AUTO means use the upstream window when known; unknown stays unknown. */
    val contextLimitMode: String = ContextLimitMode.AUTO.name,
    /** Optional externally known window (provider documentation) for AUTO. */
    val contextWindowValue: String = "",
    /**
     * The target the recorded window was declared for, and whether a window was
     * recorded at all.  The row re-validates against the live target, so a window
     * recorded for another endpoint or model is shown as stale, never as if it
     * were in force.
     */
    val contextWindowTarget: String = "",
    val contextWindowRecorded: Boolean = false,
    val contextWindowSource: ContextLimitSource = ContextLimitSource.USER_DECLARED,
)

/** What the context window column says: no fabricated, no silently stale number. */
enum class ContextWindowDisplayState { MANUAL, EFFECTIVE, STALE, UNKNOWN }

/**
 * The row label.  It reuses `contextWindowTargetMatches`, the same decision the
 * runtime budget applies, so the UI can never advertise a window the next
 * request would refuse to rely on.
 */
fun contextWindowLabel(model: ProviderModelUi, currentTarget: String, zh: Boolean): String = when {
    parseContextLimitMode(model.contextLimitMode) == ContextLimitMode.MANUAL -> {
        val value = model.contextLimit
        if (zh) "上下文 手动 $value" else "context manual $value"
    }
    !model.contextWindowRecorded ->
        if (zh) "上下文 未知（未识别到可信窗口）" else "context unknown (no trusted window)"
    !contextWindowTargetMatches(model.contextWindowTarget, currentTarget) -> {
        if (zh) "上下文 失效（目标已变，当前未知）"
        else "context stale (target changed; current unknown)"
    }
    parsePositiveProviderBudget(model.contextWindowValue) == null ->
        if (zh) "上下文 未知（已记录窗口无效）" else "context unknown (recorded window is not a valid number)"
    else -> {
        val value = model.contextWindowValue
        val source = if (model.contextWindowSource == ContextLimitSource.PROVIDER_METADATA) {
            if (zh) "服务商目录" else "provider catalog"
        } else {
            if (zh) "用户声明" else "user declared"
        }
        if (zh) "上下文 有效 $value（$source）" else "context effective $value ($source)"
    }
}

fun outputLimitLabel(model: ProviderModelUi, zh: Boolean): String {
    if (parseOutputLimitMode(model.outputLimitMode) == OutputLimitMode.AUTO) {
        return if (zh) "输出 跟随服务商" else "output follow provider"
    }
    return if (zh) "输出 手动 ${model.outputLimit}" else "output manual ${model.outputLimit}"
}

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
    // Only used when [outputLimitMode] is MANUAL.  It is a suggested value for
    // the manual path, never a hidden default: an AUTO profile sends no cap.
    val outputLimit: String = "4096",
    val outputLimitMode: String = OutputLimitMode.AUTO.name,
    /** New configurations try the upstream capability first. */
    val contextLimitMode: String = ContextLimitMode.AUTO.name,
    /** Optional window value taken from provider documentation (USER_DECLARED). */
    val contextWindowValue: String = "",
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

fun providerBudgetError(contextLimit: String, outputLimit: String, zh: Boolean): String? =
    providerBudgetError(contextLimit, outputLimit, OutputLimitMode.MANUAL.name, zh)

/**
 * AUTO validates only the context budget: following the provider means the app
 * has no output number to validate and will not send one.  MANUAL keeps the
 * exact positive-integer and `output <= context` contract.
 */
fun providerBudgetError(
    contextLimit: String,
    outputLimit: String,
    outputLimitMode: String,
    zh: Boolean,
): String? {
    val context = parsePositiveProviderBudget(contextLimit)
        ?: return if (zh) "上下文预算必须是正整数。" else "Context budget must be a positive integer."
    if (parseOutputLimitMode(outputLimitMode) == OutputLimitMode.AUTO) return null
    val output = parsePositiveProviderBudget(outputLimit)
        ?: return if (zh) "手动输出预算必须是正整数。" else "A manual output budget must be a positive integer."
    return if (output > context) {
        if (zh) "输出预算不能超过上下文预算。" else "Output budget cannot exceed the context budget."
    } else {
        null
    }
}

/**
 * Mode-aware budget validation.  Only the *effective* sources decide:
 * - a MANUAL context window must be a positive number;
 * - an AUTO window may be absent (unknown) and never borrows the hidden legacy
 *   number the editor does not show;
 * - a manual output cap is compared against the effective window only when one
 *   is actually known.
 */
fun providerBudgetError(
    contextLimit: String,
    outputLimit: String,
    outputLimitMode: String,
    contextLimitMode: String,
    contextWindowValue: String,
    zh: Boolean,
): String? {
    // Shared semantics with ProvidersViewModel: the same domain decision decides
    // whether the editor may save.
    val error = validateBudgetSelection(
        manualContext = parsePositiveProviderBudget(contextLimit),
        manualOutput = parsePositiveProviderBudget(outputLimit),
        contextMode = parseContextLimitMode(contextLimitMode),
        outputMode = parseOutputLimitMode(outputLimitMode),
        declaredWindow = parsePositiveProviderBudget(contextWindowValue),
        declaredWindowRawFilled = contextWindowValue.isNotBlank(),
    )
    return when (error) {
        null -> null
        BudgetValidationError.MANUAL_CONTEXT_REQUIRED ->
            if (zh) "手动上下文窗口必须是正整数。" else "A manual context window must be a positive integer."
        BudgetValidationError.DECLARED_WINDOW_INVALID ->
            if (zh) "已知上下文窗口必须是正整数。" else "The known context window must be a positive integer."
        BudgetValidationError.MANUAL_OUTPUT_REQUIRED ->
            if (zh) "手动输出预算必须是正整数。" else "A manual output budget must be a positive integer."
        BudgetValidationError.OUTPUT_EXCEEDS_WINDOW ->
            if (zh) "输出预算不能超过已知的上下文窗口。" else "Output budget cannot exceed the known context window."
    }
}
fun parseContextLimitMode(raw: String): ContextLimitMode =
    runCatching { ContextLimitMode.valueOf(raw.trim().uppercase()) }.getOrDefault(ContextLimitMode.MANUAL)

/**
 * What the next request will actually rely on for the context window.  A user
 * must never see "automatic" while a fabricated number is in force: unknown is
 * shown as unknown, and the local protection ceiling is described as local.
 */
fun effectiveContextWindowSource(draft: ProviderDraft, zh: Boolean, recordedModel: ProviderModelUi? = null): String {
    if (parseContextLimitMode(draft.contextLimitMode) == ContextLimitMode.MANUAL) {
        val value = parsePositiveProviderBudget(draft.contextLimit)
        return if (value == null) {
            if (zh) "实际来源：手动窗口（尚未填写有效数字，无法保存）" else "Effective source: manual window (no valid number yet; cannot save)"
        } else {
            if (zh) "实际来源：手动窗口 $value（用户覆盖）" else "Effective source: manual window $value (user override)"
        }
    }
    val declared = parsePositiveProviderBudget(draft.contextWindowValue)
    if (draft.contextWindowValue.isBlank() && recordedModel != null &&
        recordedModel.id == draft.modelProfileId &&
        parseContextLimitMode(recordedModel.contextLimitMode) == ContextLimitMode.AUTO &&
        recordedModel.contextWindowSource == ContextLimitSource.PROVIDER_METADATA &&
        recordedModel.contextWindowRecorded
    ) {
        val target = contextWindowTarget(
            draft.id.orEmpty(), draft.baseUrl.trim(),
            draft.modelId.trim().ifBlank { recordedModel.modelId },
        )
        val catalogWindow = parsePositiveProviderBudget(recordedModel.contextWindowValue)
        if (catalogWindow != null && contextWindowTargetMatches(recordedModel.contextWindowTarget, target)) {
            return if (zh) "窗口：$catalogWindow（服务商目录）" else "Window: $catalogWindow (provider catalog)"
        }
    }
    return if (declared == null) {
        if (zh) "窗口未知；本地保护上限仍生效。" else "Window unknown; local protection still applies."
    } else {
        if (zh) "窗口：$declared（用户声明，仅对当前目标）" else "Window: $declared (user declared, current target)"
    }
}
fun parseOutputLimitMode(raw: String): OutputLimitMode =
    runCatching { OutputLimitMode.valueOf(raw.trim().uppercase()) }.getOrDefault(OutputLimitMode.MANUAL)



/**
 * What the next request will actually use.  A user must never see "follow the
 * provider" while an advanced-parameter cap is silently sent.
 */
fun effectiveOutputLimitSource(draft: ProviderDraft, zh: Boolean): String {
    advancedOutputLimitOverride(draft.parametersJson)?.let { (key, value) ->
        return if (zh) "实际来源：高级参数 $key=$value（覆盖模式设置）" else "Effective source: advanced parameter $key=$value (overrides the mode)"
    }
    return when (parseOutputLimitMode(draft.outputLimitMode)) {
        OutputLimitMode.MANUAL -> {
            val value = parsePositiveProviderBudget(draft.outputLimit)
            if (value == null) {
                if (zh) "实际来源：手动限制（尚未填写有效数字，无法保存）" else "Effective source: manual (no valid number yet; cannot save)"
            } else {
                if (zh) "实际来源：手动限制 $value" else "Effective source: manual limit $value"
            }
        }
        OutputLimitMode.AUTO ->
            if (zh) "实际来源：跟随服务商（应用不发送输出上限）" else "Effective source: follow the provider (no output cap is sent)"
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
fun ProvidersScreen(
    state: ProvidersUiState,
    actions: ProvidersActions = ProvidersActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
    renderEditorAsPage: Boolean = false,
) {
    val zh = state.language.equals("zh-CN", true)
    if (state.editorOpen && renderEditorAsPage) {
        ProviderEditorPage(state, actions, zh, modifier)
        return
    }
    var deleteProviderId by remember { mutableStateOf<String?>(null) }
    var deleteModelId by remember { mutableStateOf<String?>(null) }
    var probeRequested by remember { mutableStateOf(false) }
    var connectionRequested by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProviderListPane(state, actions, zh, showPageTitle, Modifier.weight(0.42f).fillMaxSize())
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
                ProviderListPane(state, actions, zh, showPageTitle, Modifier.fillMaxWidth())
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
            title = { Text(stringResource(R.string.ui_test_provider_connection_1033d112)) },
            text = {
                Text(
                    if (zh) {
                        "按当前配置发送一次最小请求，可能收费。"
                    } else {
                        "Send one minimal request using this configuration. Provider charges may apply."
                    },
                )
            },
            confirmButton = {
                Button(onClick = { connectionRequested = false; actions.onTestConnection() }) {
                    Text(stringResource(R.string.ui_test_connection_00a680a7))
                }
            },
            dismissButton = {
                TextButton(onClick = { connectionRequested = false }) { Text(stringResource(R.string.ui_cancel_998b9c48)) }
            },
        )
    }
    if (probeRequested) {
        AlertDialog(
            onDismissRequest = { probeRequested = false },
            title = { Text(stringResource(R.string.ui_run_provider_probe_0d20bf18)) },
            text = { Text(stringResource(R.string.ui_connection_streaming_tools_and_images_may_5e7ca12d)) },
            confirmButton = { Button(onClick = { probeRequested = false; actions.onProbe() }) { Text(stringResource(R.string.ui_run_probe_505e98f3)) } },
            dismissButton = { TextButton(onClick = { probeRequested = false }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
        )
    }
    deleteProviderId?.let { providerId ->
        val name = state.providers.firstOrNull { it.id == providerId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleteProviderId = null },
            title = { Text(stringResource(R.string.ui_delete_provider_b27fe928)) },
            text = { Text(
                stringResource(R.string.ui_delete_s_and_its_model_metadata_53e098e3, (name), (state.deleteModelCount), (state.deleteSnapshotCount))
            ) },
            confirmButton = { Button(onClick = { deleteProviderId = null; actions.onDelete() }) { Text(stringResource(R.string.ui_delete_5b875326)) } },
            dismissButton = { TextButton(onClick = { deleteProviderId = null }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
        )
    }
    deleteModelId?.let { modelId ->
        AlertDialog(
            onDismissRequest = { deleteModelId = null },
            title = { Text(stringResource(R.string.ui_delete_model_metadata_c109a272)) },
            text = { Text(stringResource(R.string.ui_this_removes_the_model_from_the_05f9aab4)) },
            confirmButton = { Button(onClick = { deleteModelId = null; actions.onDeleteModel(modelId) }) { Text(stringResource(R.string.ui_delete_5b875326)) } },
            dismissButton = { TextButton(onClick = { deleteModelId = null }) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
        )
    }
}

@Composable
private fun ProviderListPane(
    state: ProvidersUiState,
    actions: ProvidersActions,
    zh: Boolean,
    showPageTitle: Boolean,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showPageTitle) {
                Text(stringResource(R.string.ui_providers_e98de897), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = { actions.onOpenEditor(null) }) { Text(stringResource(R.string.ui_add_provider_bf4c9732)) }
        }
        if (state.status.isNotBlank()) ProviderStatus(state.status, Modifier.padding(vertical = 8.dp))
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(top = 16.dp).size(24.dp))
        } else if (state.error != null) {
            ProviderStatus(state.error, Modifier.padding(top = 16.dp), error = true)
        } else if (state.providers.isEmpty()) {
            EmptyProviderState(zh)
        } else {
            LazyColumn(modifier = Modifier.height(340.dp).padding(top = 12.dp)) {
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
            Text(stringResource(R.string.ui_no_providers_configured_d8e9b861), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_add_a_provider_to_select_a_ffa7447f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ProviderCard(provider: ProviderCardUi, selected: Boolean, zh: Boolean, onClick: () -> Unit) {
    val statusColor = when {
        provider.status.equals("ready", true) || provider.status.equals("connected", true) -> MaterialTheme.colorScheme.tertiary
        provider.status.contains("error", true) || provider.status.contains("fail", true) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable(onClick = onClick)
                .testTag("providers.row.${provider.id}")
                .padding(horizontal = 4.dp, vertical = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (provider.status.isNotBlank()) {
                    Text(provider.status, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = statusColor)
                }
            }
            Text("${provider.apiFormat} · ${provider.baseUrl}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text(provider.modelCount.toString(), fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.ui_models_c9b1f445), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp))
                Spacer(Modifier.weight(1f))
                Text(if (provider.secretConfigured) { if (zh) "密钥已配置" else "Key configured" }
                    else { if (zh) "未配置密钥" else "No key" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

@OptIn(ExperimentalLayoutApi::class)
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
        Text(stringResource(R.string.ui_select_a_provider_to_inspect_models_7a56067a), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
        return
    }
    // The window state is judged against the live target of this provider, exactly
    // as the runtime budget does; a recorded window for another endpoint is stale.

    Text(provider.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(
        provider.baseUrl,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    var providerMenuOpen by remember { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag("provider.actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onRequestConnection,
            modifier = Modifier.testTag("provider.testConnection"),
        ) { Text(stringResource(R.string.ui_test_connection_00a680a7)) }
        OutlinedButton(
            onClick = onRequestProbe,
            modifier = Modifier.testTag("provider.capabilityProbe"),
        ) { Text(stringResource(R.string.ui_capability_probe_cd9280e9)) }
        Box {
            IconButton(
                onClick = { providerMenuOpen = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("provider.overflow"),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = if (zh) "服务商更多操作" else "Provider more actions",
                )
            }
            DropdownMenu(
                expanded = providerMenuOpen,
                onDismissRequest = { providerMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_edit_e9740dbb)) },
                    onClick = { providerMenuOpen = false; actions.onOpenEditor(provider.id) },
                    modifier = Modifier.testTag("provider.overflow.edit"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_delete_5b875326)) },
                    onClick = { providerMenuOpen = false; onRequestDeleteProvider(provider.id) },
                    modifier = Modifier.testTag("provider.overflow.delete"),
                )
            }
        }
    }
    Text(stringResource(R.string.ui_models_and_capabilities_1b9330b3), style = MaterialTheme.typography.titleMedium)
    if (state.models.isEmpty()) Text(stringResource(R.string.ui_no_model_metadata_is_available_62aa07af), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    state.models.forEach { model ->
        var modelMenuOpen by remember(model.id) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("provider.model.${model.id}")) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.modelId,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        IconButton(
                            onClick = { modelMenuOpen = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("provider.model.overflow.${model.id}"),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = if (zh) "模型更多操作" else "Model more actions",
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuOpen,
                            onDismissRequest = { modelMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_edit_e9740dbb)) },
                                onClick = { modelMenuOpen = false; actions.onEditModel(model.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_delete_5b875326)) },
                                onClick = { modelMenuOpen = false; onRequestDeleteModel(model.id) },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.ui_role_s_4222ab95, (model.role)), style = MaterialTheme.typography.bodySmall)
                val capabilityLabel = if (model.capabilities.isEmpty()) {
                    if (zh) "能力不可用" else "Capabilities unavailable"
                } else {
                    if (zh) "能力：${model.capabilities.sorted().joinToString()}" else "Capabilities: ${model.capabilities.sorted().joinToString()}"
                }
                Text(capabilityLabel, style = MaterialTheme.typography.bodySmall)
                // The effective/unknown/stale state comes from the same target match the
                // runtime applies; the raw legacy number is never shown as if it were in force.
                Text(
                    listOf(
                        contextWindowLabel(model, contextWindowTarget(provider.id, provider.baseUrl, model.modelId), zh),
                        outputLimitLabel(model, zh),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("provider.model.${model.id}.limits"),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.ui_mcp_tools_1e943d14), style = MaterialTheme.typography.titleMedium)
    Text(state.mcpReason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    OutlinedButton(onClick = actions.onOpenMcpSettings, enabled = state.mcpEntryEnabled, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.ui_open_mcp_settings_4c0d5a3a))
    }
}

@Composable
private fun ProviderEditorDialog(state: ProvidersUiState, actions: ProvidersActions, zh: Boolean) {
    val draft = state.draft
    val showModelFields = draft.modelProfileId != null || draft.modelId.isNotBlank() || draft.id == null
    val budgetError = if (showModelFields) providerBudgetError(draft.contextLimit, draft.outputLimit, draft.outputLimitMode, draft.contextLimitMode, draft.contextWindowValue, zh) else null
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(providerEditorTitle(draft, zh)) },
        text = {
            ProviderEditorFields(
                state = state,
                actions = actions,
                zh = zh,
                showModelFields = showModelFields,
                budgetError = budgetError,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { Button(onClick = actions.onSave, enabled = budgetError == null) { Text(stringResource(R.string.ui_save_ec8e6d58)) } },
        dismissButton = { TextButton(onClick = actions.onCloseEditor) { Text(stringResource(R.string.ui_cancel_998b9c48)) } },
    )
}

@Composable
private fun ProviderEditorPage(
    state: ProvidersUiState,
    actions: ProvidersActions,
    zh: Boolean,
    modifier: Modifier,
) {
    val draft = state.draft
    val showModelFields = draft.modelProfileId != null || draft.modelId.isNotBlank() || draft.id == null
    val budgetError = if (showModelFields) providerBudgetError(draft.contextLimit, draft.outputLimit, draft.outputLimitMode, draft.contextLimitMode, draft.contextWindowValue, zh) else null
    Surface(modifier.fillMaxSize().testTag("provider.editor.page")) {
        Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            ProviderEditorFields(
                state = state,
                actions = actions,
                zh = zh,
                showModelFields = showModelFields,
                budgetError = budgetError,
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = actions.onCloseEditor) { Text(stringResource(R.string.ui_cancel_998b9c48)) }
                Button(onClick = actions.onSave, enabled = budgetError == null) { Text(stringResource(R.string.ui_save_ec8e6d58)) }
            }
        }
    }
}

private fun providerEditorTitle(draft: ProviderDraft, zh: Boolean): String = when {
    draft.modelProfileId != null -> if (zh) "编辑模型" else "Edit model"
    draft.id == null -> if (zh) "添加服务商" else "Add provider"
    else -> if (zh) "编辑服务商" else "Edit provider"
}

@Composable
private fun ProviderEditorFields(
    state: ProvidersUiState,
    actions: ProvidersActions,
    zh: Boolean,
    showModelFields: Boolean,
    budgetError: String?,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                budgetError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(draft.name, { actions.onDraftChange(draft.copy(name = it)) }, label = { Text(stringResource(R.string.ui_name_dd4dc4c5)) }, keyboardOptions = noCorrectionText, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.baseUrl, { actions.onDraftChange(draft.copy(baseUrl = it)) }, label = { Text(stringResource(R.string.ui_base_url_434ba70e)) }, keyboardOptions = uriOptions, modifier = Modifier.fillMaxWidth())
                var apiFormatMenuOpen by remember(draft.id) { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { apiFormatMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().testTag("provider.apiFormat"),
                    ) {
                        Text(
                            stringResource(R.string.ui_api_format_s_b2e6629e, (providerApiFormatLabel(draft.apiFormat, zh))),
                        )
                    }
                    DropdownMenu(
                        expanded = apiFormatMenuOpen,
                        onDismissRequest = { apiFormatMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(providerApiFormatLabel("OPENAI_COMPATIBLE", zh)) },
                            onClick = {
                                apiFormatMenuOpen = false
                                actions.onDraftChange(draft.copy(apiFormat = "OPENAI_COMPATIBLE"))
                            },
                            modifier = Modifier.testTag("provider.apiFormat.compatible"),
                        )
                        DropdownMenuItem(
                            text = { Text(providerApiFormatLabel("OPENAI_RESPONSES", zh)) },
                            onClick = {
                                apiFormatMenuOpen = false
                                actions.onDraftChange(draft.copy(apiFormat = "OPENAI_RESPONSES"))
                            },
                            modifier = Modifier.testTag("provider.apiFormat.responses"),
                        )
                    }
                }
                Text(
                    providerApiFormatExplanation(draft.apiFormat, zh),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("provider.apiFormat.explanation"),
                )
                if (showModelFields) {
                    OutlinedTextField(draft.modelId, { actions.onDraftChange(draft.copy(modelId = it)) }, label = { Text(stringResource(R.string.ui_model_id_263d1656)) }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.ui_role_chat_embedding_reranker_chat_may_cb832d98), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.role, { actions.onDraftChange(draft.copy(role = it)) }, label = { Text(stringResource(R.string.ui_operation_role_3b8ad69f)) }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.parametersJson, { actions.onDraftChange(draft.copy(parametersJson = it)) }, label = { Text(stringResource(R.string.ui_parameters_json_055688f2)) }, keyboardOptions = noCorrectionText, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.ui_context_window_4753e4c3), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("provider.contextLimit.title"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = parseContextLimitMode(draft.contextLimitMode) == ContextLimitMode.AUTO,
                            onClick = { actions.onDraftChange(draft.copy(contextLimitMode = ContextLimitMode.AUTO.name)) },
                            label = { Text(stringResource(R.string.ui_automatic_70aefbbf)) },
                            modifier = Modifier.testTag("provider.contextLimitMode.auto"),
                        )
                        FilterChip(
                            selected = parseContextLimitMode(draft.contextLimitMode) == ContextLimitMode.MANUAL,
                            onClick = { actions.onDraftChange(draft.copy(contextLimitMode = ContextLimitMode.MANUAL.name)) },
                            label = { Text(stringResource(R.string.ui_manual_override_ba44e3e9)) },
                            modifier = Modifier.testTag("provider.contextLimitMode.manual"),
                        )
                    }
                    Text(effectiveContextWindowSource(draft, zh, state.models.firstOrNull { it.id == draft.modelProfileId }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("provider.contextLimit.effectiveSource"))
                    if (parseContextLimitMode(draft.contextLimitMode) == ContextLimitMode.AUTO) {
                        OutlinedTextField(draft.contextWindowValue, { actions.onDraftChange(draft.copy(contextWindowValue = it)) }, label = { Text(stringResource(R.string.ui_declared_window_optional_overrides_catalog_3d6373f6)) }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth().testTag("provider.contextWindowValue"))
                    } else {
                        OutlinedTextField(draft.contextLimit, { actions.onDraftChange(draft.copy(contextLimit = it)) }, label = { Text(stringResource(R.string.ui_context_window_manual_02e8213a)) }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth(), isError = budgetError != null && parsePositiveProviderBudget(draft.contextLimit) == null)
                    }
                    Text(stringResource(R.string.ui_maximum_output_388d082c), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("provider.outputLimit.title"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = parseOutputLimitMode(draft.outputLimitMode) == OutputLimitMode.AUTO,
                            onClick = { actions.onDraftChange(draft.copy(outputLimitMode = OutputLimitMode.AUTO.name)) },
                            label = { Text(stringResource(R.string.ui_follow_provider_3a7dba1c)) },
                            modifier = Modifier.testTag("provider.outputLimitMode.auto"),
                        )
                        FilterChip(
                            selected = parseOutputLimitMode(draft.outputLimitMode) == OutputLimitMode.MANUAL,
                            onClick = { actions.onDraftChange(draft.copy(outputLimitMode = OutputLimitMode.MANUAL.name)) },
                            label = { Text(stringResource(R.string.ui_manual_c4e00403)) },
                            modifier = Modifier.testTag("provider.outputLimitMode.manual"),
                        )
                    }
                    Text(
                        effectiveOutputLimitSource(draft, zh),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("provider.outputLimit.effectiveSource"),
                    )
                    if (parseOutputLimitMode(draft.outputLimitMode) == OutputLimitMode.MANUAL) {
                        OutlinedTextField(draft.outputLimit, { actions.onDraftChange(draft.copy(outputLimit = it)) }, label = { Text(stringResource(R.string.ui_output_budget_963a4487)) }, keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth(), isError = budgetError != null && (parsePositiveProviderBudget(draft.outputLimit) == null || (parsePositiveProviderBudget(draft.contextLimit)?.let { context -> parsePositiveProviderBudget(draft.outputLimit)?.let { output -> output > context } } == true)))
                    }
                    Text(stringResource(R.string.ui_follow_the_provider_limit_local_run_2aa62ff2), style = MaterialTheme.typography.labelSmall)
                    CheckRow(if (zh) "输入包含图片" else "Input includes images", draft.vision) { actions.onDraftChange(draft.copy(vision = it)) }
                    CheckRow(if (zh) "可调用工具" else "Can call tools", draft.tools) { actions.onDraftChange(draft.copy(tools = it)) }
                }
                OutlinedTextField(draft.apiKey, { actions.onDraftChange(draft.copy(apiKey = it)) }, label = { Text(if (draft.id == null) { if (zh) "API 密钥" else "API key" } else { if (zh) "替换 API 密钥（可选）" else "Replace API key (optional)" }) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = noCorrectionAscii, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.ui_verify_capabilities_after_confirmation_provid_f616849a), style = MaterialTheme.typography.bodySmall)
    }
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
                    Text(stringResource(R.string.ui_checking_042eed1e), modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(probePhaseLabel(probe.phase, zh), modifier = Modifier.padding(top = 8.dp))
                }
                probe.connection?.let { connection ->
                    if (connection.success) {
                        Text(stringResource(R.string.ui_connection_succeeded_0d500b13), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        connection.latencyMs?.let { latency ->
                            Text(
                                stringResource(R.string.ui_model_response_normal_s_ms_9996fbc3, (latency)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.ui_connection_failed_s_0bb2bcac, (connectionErrorLabel(connection.error, zh))),
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
                        stringResource(R.string.ui_this_check_may_have_incurred_provider_f64a9cb5),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                probe.lastChecked?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { if (probe.phase != ProbePhase.RUNNING) Button(onClick = onClose) { Text(stringResource(R.string.ui_close_6cf4a777)) } },
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
    CapabilityCheckStatus.UNKNOWN -> if (zh) "未确认" else "Inconclusive"
}

private fun connectionErrorLabel(error: ProviderConnectionErrorCode?, zh: Boolean): String = when (error) {
    ProviderConnectionErrorCode.NETWORK_UNREACHABLE -> if (zh) "网络不可达" else "Network unreachable"
    ProviderConnectionErrorCode.TLS_FAILURE -> if (zh) "TLS 安全连接失败" else "TLS failure"
    ProviderConnectionErrorCode.TIMEOUT -> if (zh) "请求超时" else "Timeout"
    ProviderConnectionErrorCode.AUTH_FAILED -> if (zh) "认证失败" else "Authentication failed"
    ProviderConnectionErrorCode.ENDPOINT_UNSUPPORTED -> if (zh) "端点不支持" else "Endpoint unsupported"
    ProviderConnectionErrorCode.MODEL_NOT_FOUND -> if (zh) "模型不存在" else "Model not found"
    ProviderConnectionErrorCode.RATE_LIMITED -> if (zh) "请求受限" else "Rate limited"
    ProviderConnectionErrorCode.FEATURE_UNSUPPORTED -> if (zh) "请求能力不支持" else "Requested feature unsupported"
    ProviderConnectionErrorCode.PROVIDER_REJECTED -> if (zh) "服务商拒绝请求" else "Provider rejected request"
    ProviderConnectionErrorCode.INVALID_RESPONSE -> if (zh) "响应无效" else "Invalid response"
    ProviderConnectionErrorCode.CONFIG_INVALID -> if (zh) "配置无效" else "Invalid configuration"
    ProviderConnectionErrorCode.CREDENTIAL_UNAVAILABLE -> if (zh) "凭据不可用" else "Credential unavailable"
    ProviderConnectionErrorCode.UNKNOWN, null -> if (zh) "未知错误" else "Unknown error"
}

private fun providerApiFormatLabel(format: String, zh: Boolean): String = when (format.uppercase()) {
    "OPENAI_RESPONSES" -> if (zh) "OpenAI Responses（/responses）" else "OpenAI Responses (/responses)"
    else -> if (zh) "OpenAI Compatible（/chat/completions）" else "OpenAI Compatible (/chat/completions)"
}

private fun providerApiFormatExplanation(format: String, zh: Boolean): String = when (format.uppercase()) {
    "OPENAI_RESPONSES" -> if (zh) {
        "Responses 使用 input、function_call_output 和独立 SSE 事件；仅适用于支持 POST /responses 的服务。"
    } else {
        "Responses uses input, function_call_output, and its own SSE events; choose it only for providers supporting POST /responses."
    }
    else -> if (zh) {
        "Compatible 使用传统 messages 与 /chat/completions；保留用于兼容 OpenAI 风格旧端点。"
    } else {
        "Compatible uses traditional messages and /chat/completions for legacy OpenAI-style endpoints."
    }
}
