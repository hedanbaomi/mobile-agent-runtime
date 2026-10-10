// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.settings

import androidx.compose.ui.res.stringResource

import runtime.mobileagent.domain.WebSearchProvider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.DangerousMode

enum class SettingsDiagnosticsFeedback {
    NONE, ENABLED, DISABLED, SAVE_FAILED, EXPORT_CANCELLED, EXPORTING, EXPORTED, EXPORT_FAILED, CLEARED, CLEAR_FAILED;

    fun text(zh: Boolean): String = when (this) {
        NONE -> ""
        ENABLED -> if (zh) "诊断记录已开启。" else "Diagnostics enabled."
        DISABLED -> if (zh) "诊断记录已关闭；已有记录仍可导出或清除。" else "Diagnostics disabled; existing records can still be exported or cleared."
        SAVE_FAILED -> if (zh) "无法保存诊断设置。" else "Could not save the diagnostics setting."
        EXPORT_CANCELLED -> if (zh) "已取消诊断导出。" else "Diagnostics export cancelled."
        EXPORTING -> if (zh) "正在导出诊断 ZIP…" else "Exporting diagnostics ZIP…"
        EXPORTED -> if (zh) "诊断 ZIP 已保存；原生崩溃或系统强杀仍可能需要 ADB Logcat。" else "Diagnostics ZIP saved; native crashes or system kills may still require ADB Logcat."
        EXPORT_FAILED -> if (zh) "诊断导出失败；原记录未清除。" else "Diagnostics export failed; existing records were preserved."
        CLEARED -> if (zh) "诊断记录已清除。" else "Diagnostics cleared."
        CLEAR_FAILED -> if (zh) "清除诊断记录失败。" else "Could not clear diagnostics."
    }
}

data class ThirdPartyNoticeFileUi(
    val label: String,
    val path: String,
)

data class ThirdPartyNoticeUi(
    val id: String,
    val name: String,
    val version: String = "",
    val license: String = "",
    val source: String = "",
    val files: List<ThirdPartyNoticeFileUi> = emptyList(),
)

data class ThirdPartyNoticesUiState(
    val overview: String = "",
    val components: List<ThirdPartyNoticeUi> = emptyList(),
    val selectedComponentId: String? = null,
    val selectedLicenseText: String? = null,
    val opened: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Presentation-only projection of the shared authority lifecycle enums. The
 * Android adapter maps canonical enum names into these bounded strings so the
 * feature module does not define a second authority model.
 */
data class AuthorityUiState(
    val authority: String,
    val selected: Boolean = false,
    val userIntentEnabled: Boolean = false,
    val platformGrant: String = "UNKNOWN",
    val availability: String = "UNSUPPORTED",
    val connection: String = "DISCONNECTED",
    val configured: Boolean = false,
    val trust: String = "",
)

data class SafWorkspaceUiState(
    val configured: Boolean = false,
    val readGranted: Boolean = false,
    val writeGranted: Boolean = false,
    val persisted: Boolean = false,
    val status: String = "REVOKED",
)

/**
 * Ephemeral presentation state for the foreground Wired ADB pairing flow.
 *
 * The token itself is deliberately not a field here. The Android host passes
 * an ephemeral accessor through [SettingsActions] only while this screen is
 * visible. This state is occasionally included in test/debug output and must
 * therefore contain metadata only.
 */
data class WiredPairingUiState(
    val hasToken: Boolean = false,
    val expiresAtEpochMs: Long = 0L,
    val remainingAttempts: Int = 0,
    val status: String = "",
    val replacingExistingTrust: Boolean = false,
    val completing: Boolean = false,
) {
    override fun toString(): String =
        "WiredPairingUiState(expiresAtEpochMs=$expiresAtEpochMs, " +
        "remainingAttempts=$remainingAttempts, status=$status, " +
        "replacingExistingTrust=$replacingExistingTrust, completing=$completing, " +
        "hasToken=$hasToken)"
}

data class SettingsUiState(
    val versionName: String = "",
    val gitRevision: String = "",
    val gitDirty: Boolean = false,
    val schemaVersion: Int = 0,
    val buildTimeUtc: String = "",
    val buildType: String = "",
    val diagnosticText: String = "",
    /** zh-CN is the product default; the ViewModel may replace it with the persisted choice. */
    val language: String = "zh-CN",
    /** Light is the first-install default; 66ccff remains an explicit selectable accent. */
    val themeMode: String = "light",
    val statsEnabled: Boolean = true,
    val requestInspectionEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val diagnosticsLogLevel: String = "INFO",
    val diagnosticsSizeBytes: Long = 0L,
    val diagnosticsLimitBytes: Long = 0L,
    val diagnosticsState: String = "",
    val diagnosticsFeedback: SettingsDiagnosticsFeedback = SettingsDiagnosticsFeedback.NONE,
    val exportState: String = "",
    val updateState: String = "",
    val noticeCount: Int = 0,
    val mcpConfigured: Boolean = false,
    val mcpDisabledReason: String = "适配器报告已配置端点后，MCP 设置才可用。",
    val licenseText: String? = null,
    val error: String? = null,
    /** The configuration entry is available even when no MCP endpoint is configured. */
    val mcpEntryEnabled: Boolean = false,
    /** Local-only third-party notice browser state; the host may populate it from APK assets. */
    val thirdPartyNotices: ThirdPartyNoticesUiState = ThirdPartyNoticesUiState(),
    val globalRootPrompt: String = "",
    val globalRootPromptOverride: String? = null,
    val globalRootPromptUnlocked: Boolean = false,
    val globalRootPromptRevision: Int = 0,
    val globalRootPromptUpdatedAt: String = "",
    val webSearchConfigured: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val webSearchProviderId: String = "brave",
    val webSearchState: String = "",
    val appPrivateExecutionActive: Boolean = true,
    val selectedAuthority: String = "NONE",
    val shizukuAuthority: AuthorityUiState = AuthorityUiState("SHIZUKU"),
    val wiredAdbAuthority: AuthorityUiState = AuthorityUiState("WIRED_ADB"),
    val wiredPairing: WiredPairingUiState = WiredPairingUiState(),
    val safWorkspace: SafWorkspaceUiState = SafWorkspaceUiState(),
    val dangerousMode: String = DangerousMode.DISABLED.name,
    /** Durable policy is shown separately from the effective fail-closed policy. */
    val dangerousModeDurable: String = DangerousMode.DISABLED.name,
    val dangerousModeBuildAllowed: Boolean = false,
    val dangerousModeBuildKnown: Boolean = false,
    val dangerousModeReason: String = "DANGEROUS_MODE_BUILD_DENIED",
)

data class SettingsActions(
    val onOpenArchivedConversations: () -> Unit = {},
    val onLanguage: (String) -> Unit = {},
    val onTheme: (String) -> Unit = {},
    val onStats: (Boolean) -> Unit = {},
    val onOpenAbout: () -> Unit = {},
    val onOpenSource: () -> Unit = {},
    val onRequestInspection: (Boolean) -> Unit = {},
    val onDiagnosticsEnabled: (Boolean) -> Unit = {},
    val onDiagnosticsLogLevel: (String) -> Unit = {},
    val onExportDiagnostics: () -> Unit = {},
    val onClearDiagnostics: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
    val onOpenProviders: () -> Unit = {},
    val onOpenKnowledge: () -> Unit = {},
    val onOpenSkills: () -> Unit = {},
    val onOpenAnnouncements: () -> Unit = {},
    val onOpenMcpSettings: () -> Unit = {},
    val onOpenThirdPartyNotices: () -> Unit = {},
    val onCloseThirdPartyNotices: () -> Unit = {},
    val onSelectThirdPartyNotice: (String) -> Unit = {},
    val onUnlockRootPrompt: () -> Unit = {},
    val onSaveRootPrompt: (String) -> Unit = {},
    val onRestoreRootPrompt: () -> Unit = {},
    val onSaveWebSearch: (String, String) -> Unit = { _, _ -> },
    val onWebSearchEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onWebSearchProvider: (String) -> Unit = {},
    val onClearWebSearch: (String) -> Unit = {},
    val onSelectAuthority: (String) -> Unit = {},
    val onAuthorityIntent: (String, Boolean) -> Unit = { _, _ -> },
    val onRefreshAuthority: (String) -> Unit = {},
    val onRequestShizukuPermission: () -> Unit = {},
    val onEnableShizuku: () -> Unit = {},
    val onOpenShizuku: () -> Unit = {},
    val onRequestWiredPairing: (Boolean) -> Unit = {},
    val onCompleteWiredPairing: () -> Unit = {},
    val onCancelWiredPairing: () -> Unit = {},
    /** Returns the in-memory one-time token only for immediate rendering/copy. */
    val onWiredPairingToken: () -> String? = { null },
    val onForgetWiredAdb: () -> Unit = {},
    val onSelectSafTree: () -> Unit = {},
    val onReauthorizeSaf: () -> Unit = {},
    val onRevokeSaf: () -> Unit = {},
    val onOpenAgents: () -> Unit = {},
    val onSetDangerousMode: (String) -> Unit = {},
    val onDisableDangerousMode: () -> Unit = {},
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions = SettingsActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
) {
    val zh = state.language.equals("zh-CN", true) || state.language.equals("system", true)
    var languageMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("settings.screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPageTitle) {
            Text(stringResource(R.string.sett_title), style = MaterialTheme.typography.headlineSmall)
        }
        if (state.error != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.appearance")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sett_appearance_language), style = MaterialTheme.typography.titleMedium)
                SelectorRow(if (zh) "语言" else "Language", state.language, { languageMenu = true }) {
                    DropdownMenu(languageMenu, { languageMenu = false }) {
                        listOf("zh-CN" to "简体中文", "en-US" to "English", "system" to if (zh) "跟随系统" else "System").forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { languageMenu = false; actions.onLanguage(key) })
                        }
                    }
                }
                SelectorRow(if (zh) "主题" else "Theme", state.themeMode, { themeMenu = true }) {
                    DropdownMenu(themeMenu, { themeMenu = false }) {
                        listOf("light" to if (zh) "浅色" else "Light", "dark" to if (zh) "深色" else "Dark", "66ccff" to "66ccff").forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { themeMenu = false; actions.onTheme(key) })
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.web_search")) {
            var apiKey by remember(state.webSearchProviderId) { mutableStateOf("") }
            var providerMenu by remember { mutableStateOf(false) }
            val providerName = WebSearchProvider.fromId(state.webSearchProviderId)?.displayName.orEmpty()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sett_search_title), style = MaterialTheme.typography.titleMedium)
                SelectorRow(if (zh) "搜索服务商" else "Search provider", providerName.ifBlank { if (zh) "请选择" else "Select" }, { providerMenu = true }) {
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        WebSearchProvider.entries.forEach { provider ->
                            DropdownMenuItem(text = { Text(provider.displayName) }, onClick = {
                                providerMenu = false; apiKey = ""; actions.onWebSearchProvider(provider.id)
                            }, modifier = Modifier.testTag("settings.web_search.provider.${provider.id}"))
                        }
                    }
                }
                Text(
                    stringResource(R.string.sett_search_key_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth().testTag("settings.web_search.key"),
                    label = { Text("$providerName API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { actions.onSaveWebSearch(state.webSearchProviderId, apiKey); apiKey = "" }, enabled = apiKey.isNotBlank()) {
                        Text(stringResource(R.string.sett_search_save_enable))
                    }
                    if (state.webSearchConfigured) {
                        OutlinedButton(onClick = { actions.onClearWebSearch(state.webSearchProviderId) }) { Text(stringResource(R.string.sett_search_remove_key)) }
                    }
                }
                SettingSwitch(
                    if (zh) "启用搜索服务" else "Enable search service",
                    state.webSearchEnabled,
                    { actions.onWebSearchEnabled(state.webSearchProviderId, it) },
                )
                Text(
                    if (zh) "${providerName}：${if (state.webSearchConfigured) "密钥已保存" else "未配置密钥"}。搜索结果不可信，应用不会自动打开网页。"
                    else "$providerName: ${if (state.webSearchConfigured) "key saved" else "no key"}. Results are untrusted; pages are not opened automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.webSearchState.isNotBlank()) Text(state.webSearchState, style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.root_prompt")) {
            var draftPrompt by remember(state.globalRootPrompt, state.globalRootPromptUnlocked) {
                mutableStateOf(state.globalRootPrompt)
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sett_root_prompt_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.sett_root_prompt_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.globalRootPromptUnlocked) {
                    OutlinedButton(onClick = actions.onUnlockRootPrompt) { Text(stringResource(R.string.sett_root_prompt_unlock)) }
                } else {
                    OutlinedTextField(draftPrompt, { draftPrompt = it }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.onSaveRootPrompt(draftPrompt) }) { Text(stringResource(R.string.sett_root_prompt_save)) }
                        OutlinedButton(onClick = actions.onRestoreRootPrompt) { Text(stringResource(R.string.sett_root_prompt_restore)) }
                    }
                    Text(
                        "r${state.globalRootPromptRevision} · ${state.globalRootPromptUpdatedAt}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        AuthoritySettingsCard(state, actions, zh)
        Card(Modifier.fillMaxWidth().testTag("settings.privacy_diagnostics")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sett_privacy_diagnostics), style = MaterialTheme.typography.titleMedium)
                SettingSwitch(if (zh) "匿名使用统计" else "Anonymous usage statistics", state.statsEnabled, actions.onStats,
                    modifier = Modifier.testTag("settings.stats.switch"), labelClickable = true)
                Text(
                    stringResource(R.string.sett_stats_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                SettingSwitch(if (zh) "显示请求检查器" else "Show request inspector", state.requestInspectionEnabled, actions.onRequestInspection)
                SettingSwitch(if (zh) "应用内诊断记录（默认关闭）" else "In-app diagnostics (off by default)", state.diagnosticsEnabled, actions.onDiagnosticsEnabled)
                Text(stringResource(R.string.sett_log_level), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (level in listOf("INFO", "DEBUG")) {
                        FilterChip(
                            selected = state.diagnosticsLogLevel == level,
                            onClick = { actions.onDiagnosticsLogLevel(level) },
                            label = { Text(level) },
                            modifier = Modifier.testTag("settings.diagnostics.level.${level.lowercase()}"),
                        )
                    }
                }
                Text(
                    if (zh) "${if (state.diagnosticsEnabled) "已开启" else "已关闭"} · ${formatDiagnosticBytes(state.diagnosticsSizeBytes)} / ${formatDiagnosticBytes(state.diagnosticsLimitBytes)}"
                    else "${if (state.diagnosticsEnabled) "On" else "Off"} · ${formatDiagnosticBytes(state.diagnosticsSizeBytes)} / ${formatDiagnosticBytes(state.diagnosticsLimitBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (state.diagnosticsLogLevel == "DEBUG") {
                        if (zh) "DEBUG 记录详细进度和视觉处理文本；凭据会过滤，分享前检查敏感内容。"
                        else "DEBUG records detailed progress and Vision text. Credentials are filtered; check sensitive content before sharing."
                    } else {
                        if (zh) "INFO 记录阶段、结果与错误，不记录视觉处理正文。"
                        else "INFO records stages, results and errors, without Vision bodies."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.sett_diagnostics_rotation),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onExportDiagnostics) { Text(stringResource(R.string.sett_diagnostics_export)) }
                    OutlinedButton(onClick = actions.onClearDiagnostics, enabled = state.diagnosticsSizeBytes > 0) { Text(stringResource(R.string.sett_diagnostics_clear)) }
                }
                val diagnosticsMessage = state.diagnosticsFeedback.text(zh).ifBlank { state.diagnosticsState }
                if (diagnosticsMessage.isNotBlank()) Text(diagnosticsMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings.diagnostics.feedback"))
                Text(stringResource(R.string.sett_diagnostics_secrets), style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.data_backup")) {
            var importHelpExpanded by remember { mutableStateOf(false) }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sett_data_backup), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = actions.onOpenArchivedConversations, modifier = Modifier.testTag("settings.archived_conversations")) {
                    Text(stringResource(R.string.sett_archived_conversations))
                }
                Text(
                    if (zh) {
                        "ZIP 保存到所选位置，提供方可能上传或同步。扩展内容默认关闭，密钥与授权不导出。上限 512 MiB，单项 32 MiB。"
                    } else {
                        "Saves a ZIP to your chosen location; its provider may upload or sync it. Optional content is off by default; keys and grants are excluded. Limits: 512 MiB total, 32 MiB per item."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onExport) { Text(stringResource(R.string.sett_export)) }
                    OutlinedButton(onClick = actions.onImport) { Text(stringResource(R.string.sett_import)) }
                }
                if (state.exportState.isNotBlank()) Text(state.exportState, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { importHelpExpanded = !importHelpExpanded }) {
                    Text(if (importHelpExpanded) { if (zh) "收起导入说明" else "Hide import help" } else { if (zh) "导入兼容说明" else "Import compatibility help" })
                }
                if (importHelpExpanded) Text(
                    stringResource(R.string.sett_import_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onOpenProviders) { Text(stringResource(R.string.sett_configure_provider)) }
                    OutlinedButton(onClick = actions.onOpenAgents) { Text(stringResource(R.string.sett_review_agent)) }
                }
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.feature_entry")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sett_feature_entry), style = MaterialTheme.typography.titleMedium)
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = false, onClick = actions.onOpenProviders, label = { Text(stringResource(R.string.sett_entry_providers)) })
                    FilterChip(selected = false, onClick = actions.onOpenKnowledge, label = { Text(stringResource(R.string.sett_entry_knowledge)) })
                    FilterChip(selected = false, onClick = actions.onOpenSkills, label = { Text(stringResource(R.string.sett_entry_skills)) })
                }
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = false, onClick = actions.onOpenAnnouncements, label = { Text(stringResource(R.string.sett_entry_news)) })
                    OutlinedButton(onClick = actions.onOpenMcpSettings, enabled = state.mcpEntryEnabled) { Text(stringResource(R.string.sett_entry_mcp)) }
                }
                Text(
                    if (state.mcpDisabledReason == "适配器报告已配置端点后，MCP 设置才可用。" && !zh)
                        "MCP settings are available after the adapter reports a configured endpoint."
                    else state.mcpDisabledReason,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.about")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = actions.onOpenAbout, modifier = Modifier.testTag("settings.open_about")) {
                    Text(stringResource(R.string.ui_version_license_and_source_5256f67e))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AuthoritySettingsCard(
    state: SettingsUiState,
    actions: SettingsActions,
    chinese: Boolean,
) {
    var authorityMenu by remember { mutableStateOf(false) }
    var dangerousMenu by remember { mutableStateOf(false) }
    var pendingDangerousMode by remember { mutableStateOf<String?>(null) }
    var pendingWiredPairingReplacement by remember { mutableStateOf(false) }
    // RuntimeIntegration already applies the build admission fail-closed
    // policy to dangerousMode. Keep that effective value visible while still
    // exposing a durable enabled policy so the user can explicitly clear it.
    val displayedDangerousMode = state.dangerousMode
    val durableDangerousMode = state.dangerousModeDurable
    val dangerousModeSelectorEnabled = state.dangerousModeBuildAllowed ||
        durableDangerousMode != DangerousMode.DISABLED.name ||
        displayedDangerousMode != DangerousMode.DISABLED.name
    val selectedLabel = authorityLabel(state.selectedAuthority, chinese)
    val selectedProvider = when (state.selectedAuthority) {
        "SHIZUKU" -> state.shizukuAuthority
        "WIRED_ADB" -> state.wiredAdbAuthority
        else -> null
    }
    val shellAvailable = displayedDangerousMode != DangerousMode.DISABLED.name &&
        selectedProvider?.let {
            it.platformGrant == "GRANTED" &&
                it.availability == "READY" &&
                it.connection == "CONNECTED"
        } == true
    val safConfigured = state.safWorkspace.configured || state.safWorkspace.status == "GRANT_LOST"

    Card(Modifier.fillMaxWidth().testTag("settings.authorities")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_commands_and_authorities_7f33a147), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ui_system_enhancement_uses_shizuku_or_wired_49279879),
                style = MaterialTheme.typography.bodySmall,
            )
            SelectorRow(
                if (chinese) "当前系统增强通道" else "Current system enhancement channel",
                selectedLabel,
                { authorityMenu = true },
                modifier = Modifier.testTag("settings.authority.selected"),
                enabled = true,
            ) {
                DropdownMenu(authorityMenu, { authorityMenu = false }) {
                    listOf(
                        "NONE" to authorityLabel("NONE", chinese),
                        "SHIZUKU" to authorityLabel("SHIZUKU", chinese),
                        "WIRED_ADB" to authorityLabel("WIRED_ADB", chinese),
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                authorityMenu = false
                                actions.onSelectAuthority(value)
                            },
                            modifier = Modifier.testTag("settings.authority.option.$value"),
                        )
                    }
                }
            }
            AuthorityRow(
                if (chinese) "基础工作区" else "Basic workspace",
                if (state.appPrivateExecutionActive) {
                    if (chinese) "已启用（逐次确认）" else "Enabled (per-call approval)"
                } else {
                    if (chinese) "当前不可用" else "Currently unavailable"
                },
                Modifier.testTag("settings.workspace.internal"),
            )

            ProviderLifecycleBlock(
                state = state.shizukuAuthority,
                label = "Shizuku",
                chinese = chinese,
                modifier = Modifier.testTag("settings.authority.shizuku"),
                onIntent = { actions.onAuthorityIntent("SHIZUKU", it) },
                onRefresh = { actions.onRefreshAuthority("SHIZUKU") },
                onPrimaryAction = actions.onEnableShizuku,
                primaryActionLabel = if (chinese) "启用并选用 Shizuku" else "Enable and select Shizuku",
                primaryActionEnabled = state.shizukuAuthority.availability != "UNSUPPORTED",
                primaryActionTestTag = "settings.authority.shizuku.enable",
                onSecondaryAction = actions.onOpenShizuku,
                secondaryActionLabel = if (chinese) "打开 Shizuku" else "Open Shizuku",
            )
            Text(
                stringResource(R.string.ui_this_records_user_intent_selects_shizuku_d1e33de1),
                style = MaterialTheme.typography.bodySmall,
            )

            ProviderLifecycleBlock(
                state = state.wiredAdbAuthority,
                label = if (chinese) "有线 ADB（设备常驻）" else "USB ADB (device resident)",
                chinese = chinese,
                modifier = Modifier.testTag("settings.authority.wired_adb"),
                onIntent = { actions.onAuthorityIntent("WIRED_ADB", it) },
                onRefresh = { actions.onRefreshAuthority("WIRED_ADB") },
                onPrimaryAction = {
                    val hasExistingTrust = state.wiredAdbAuthority.configured ||
                        state.wiredAdbAuthority.trust in setOf("TRUSTED", "REAUTH_REQUIRED")
                    if (hasExistingTrust) pendingWiredPairingReplacement = true
                    else actions.onRequestWiredPairing(false)
                },
                primaryActionLabel = if (state.wiredAdbAuthority.configured ||
                    state.wiredAdbAuthority.trust in setOf("TRUSTED", "REAUTH_REQUIRED")
                ) {
                    if (chinese) "替换已保存信任" else "Replace saved trust"
                } else {
                    if (chinese) "开始配对" else "Start pairing"
                },
                primaryActionEnabled = state.wiredAdbAuthority.availability != "UNSUPPORTED",
                onSecondaryAction = actions.onForgetWiredAdb,
                secondaryActionLabel = if (chinese) "撤销 ADB 激活" else "Revoke ADB activation",
                secondaryActionEnabled = state.wiredAdbAuthority.configured || state.wiredAdbAuthority.trust.isNotBlank(),
            )

            WiredPairingBlock(
                pairing = state.wiredPairing,
                chinese = chinese,
                tokenProvider = actions.onWiredPairingToken,
                onComplete = actions.onCompleteWiredPairing,
                onCancel = actions.onCancelWiredPairing,
            )

            val adbDownloadLinks = LocalUriHandler.current
            ActionRow {
                TextButton(onClick = {
                    adbDownloadLinks.openUri("https://github.com/hedanbaomi/mobile-agent-runtime/releases/latest")
                }) { Text(stringResource(R.string.ui_download_desktop_tool_93932577)) }
                TextButton(onClick = {
                    adbDownloadLinks.openUri("https://developer.android.com/tools/releases/platform-tools")
                }) { Text(stringResource(R.string.ui_get_official_adb_a29a5c1d)) }
            }

            Text(
                stringResource(R.string.ui_connect_a_usb_data_cable_enable_4dc371d0),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings.wired_adb.usb.instructions"),
            )

            Column(
                Modifier.fillMaxWidth().testTag("settings.workspace.saf"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.ui_user_authorized_files_saf_070a3e28), style = MaterialTheme.typography.labelLarge)
                Text(
                    if (chinese) {
                        "状态：${safStatusLabel(state.safWorkspace.status, chinese)} · 读取：${readWriteLabel(state.safWorkspace.readGranted, chinese)} · 写入：${readWriteLabel(state.safWorkspace.writeGranted, chinese)}"
                    } else {
                        "Status: ${safStatusLabel(state.safWorkspace.status, chinese)} · Read: ${readWriteLabel(state.safWorkspace.readGranted, chinese)} · Write: ${readWriteLabel(state.safWorkspace.writeGranted, chinese)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (chinese) {
                        if (state.safWorkspace.persisted) "目录已授权给应用；还需到智能体页选择“只读”或“读写”，再用该智能体新建会话。"
                        else "未记录持久授权；请选择目录以授予读取或写入能力。"
                    } else {
                        if (state.safWorkspace.persisted) "The app can access this directory. Choose read-only or read-write on the Agents page, then start a new conversation with that Agent."
                        else "No persisted grant is recorded; choose a directory to grant read or write access."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = actions.onSelectSafTree,
                        modifier = Modifier.testTag("settings.saf.authorize"),
                    ) { Text(stringResource(R.string.ui_choose_directory_1aaa14b7)) }
                    OutlinedButton(
                        onClick = actions.onReauthorizeSaf,
                        enabled = safConfigured,
                        modifier = Modifier.testTag("settings.saf.reauthorize"),
                    ) { Text(stringResource(R.string.ui_re_authorize_e730ab80)) }
                    OutlinedButton(
                        onClick = actions.onRevokeSaf,
                        enabled = safConfigured,
                        modifier = Modifier.testTag("settings.saf.revoke"),
                    ) { Text(stringResource(R.string.ui_revoke_411a6c97)) }
                    OutlinedButton(
                        onClick = actions.onOpenAgents,
                        enabled = state.safWorkspace.persisted,
                        modifier = Modifier.testTag("settings.saf.open_agents"),
                    ) { Text(stringResource(R.string.ui_open_agent_grants_8c9a1fb1)) }
                }
            }

            Column(
                Modifier.fillMaxWidth().testTag("settings.dangerous_mode"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.ui_dangerous_mode_466f2b83), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.ui_allows_the_agent_to_execute_android_abdfa0ee),
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectorRow(
                    if (chinese) "策略" else "Policy",
                    dangerousModeLabel(displayedDangerousMode, chinese),
                    { dangerousMenu = true },
                    modifier = Modifier.testTag("settings.dangerous_mode.selector"),
                    enabled = dangerousModeSelectorEnabled,
                ) {
                    DropdownMenu(dangerousMenu, { dangerousMenu = false }) {
                        listOf(
                            DangerousMode.DISABLED.name to dangerousModeLabel(DangerousMode.DISABLED.name, chinese),
                            DangerousMode.ENABLED_CONFIRM_HIGH_RISK.name to dangerousModeLabel(DangerousMode.ENABLED_CONFIRM_HIGH_RISK.name, chinese),
                            DangerousMode.ENABLED_AUTONOMOUS.name to dangerousModeLabel(DangerousMode.ENABLED_AUTONOMOUS.name, chinese),
                        ).forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                enabled = value == DangerousMode.DISABLED.name || state.dangerousModeBuildAllowed,
                                onClick = {
                                    dangerousMenu = false
                                    if (value == DangerousMode.DISABLED.name) actions.onDisableDangerousMode()
                                    else pendingDangerousMode = value
                                },
                                modifier = Modifier.testTag("settings.dangerous_mode.option.$value"),
                            )
                        }
                    }
                }
                if (!state.dangerousModeBuildAllowed) {
                    Text(
                        if (chinese) {
                            if (state.buildType.equals("debug", true)) "当前为 Debug 构建，危险模式被安全禁用；请安装 Review 构建进行核验。"
                            else if (state.dangerousModeBuildKnown) "当前构建未获高权限控制面许可；危险模式保持关闭。"
                            else "构建变体未知；危险模式安全关闭，必须由受审查构建明确许可。"
                        } else {
                            if (state.buildType.equals("debug", true)) "Dangerous Mode is safely disabled in Debug builds; install a Review build to verify it."
                            else if (state.dangerousModeBuildKnown) "This build is not admitted to the high-privilege control plane; Dangerous Mode stays off."
                            else "The build variant is unknown; Dangerous Mode stays fail-closed until an explicitly reviewed build admits it."
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("settings.dangerous_mode.fail_closed"),
                    )
                }
                if (durableDangerousMode != DangerousMode.DISABLED.name &&
                    displayedDangerousMode == DangerousMode.DISABLED.name
                ) {
                    Text(
                        if (chinese) {
                            "持久策略：${dangerousModeLabel(durableDangerousMode, true)}；当前有效策略：已关闭（构建未获许可）。可在此清除持久策略。"
                        } else {
                            "Durable policy: ${dangerousModeLabel(durableDangerousMode, false)}; effective policy: Disabled (build not admitted). Clear the durable policy here."
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("settings.dangerous_mode.durable_fail_closed"),
                    )
                }
                if (displayedDangerousMode != DangerousMode.DISABLED.name) {
                    Text(
                        if (chinese) "危险模式：已开启 · Shell：${if (shellAvailable) "可用" else "当前不可用"}"
                        else "Dangerous Mode: enabled · Shell: ${if (shellAvailable) "available" else "currently unavailable"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("settings.dangerous_mode.shell_state"),
                    )
                    Text(
                        when (displayedDangerousMode) {
                            DangerousMode.ENABLED_AUTONOMOUS.name -> if (chinese) "完全自主：不会逐条询问，但仍受能力、选定通道、超时、输出与审计约束。" else "Autonomous: no per-command prompt, while capability, selected channel, timeout, output, and audit gates remain."
                            else -> if (chinese) "高危命令确认：明显高风险操作仍需单次确认；检测不是安全沙箱。" else "High-risk confirmation: clearly risky operations still require one confirmation; detection is not a safety sandbox."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.ui_the_setting_is_not_cleared_by_76cb4088),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (pendingWiredPairingReplacement) {
        AlertDialog(
            onDismissRequest = { pendingWiredPairingReplacement = false },
            title = { Text(stringResource(R.string.ui_confirm_replacing_saved_trust_5229aa78)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .testTag("settings.wired_adb.replace.risk_dialog"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (chinese) {
                            "重新激活会建立新的设备连接。若常驻服务仍在运行，请先撤销 ADB 激活；激活失败不会删除已有凭据。"
                        } else {
                            "Reactivation establishes a new device connection. Revoke activation first if the resident service is still running. Failed activation keeps existing credentials."
                        },
                    )
                    Text(
                        stringResource(R.string.ui_the_one_time_token_is_shown_208c3072),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingWiredPairingReplacement = false
                        actions.onRequestWiredPairing(true)
                    },
                    modifier = Modifier.testTag("settings.wired_adb.replace.confirm"),
                ) { Text(stringResource(R.string.ui_replace_and_start_pairing_54979497)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingWiredPairingReplacement = false }) {
                    Text(stringResource(R.string.ui_cancel_998b9c48))
                }
            },
        )
    }

    pendingDangerousMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { pendingDangerousMode = null },
            title = { Text(stringResource(R.string.ui_confirm_dangerous_mode_4ca71053)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).testTag("settings.dangerous_mode.risk_dialog"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.ui_after_enabling_the_agent_may_execute_6ecec320),
                    )
                    Text(
                        stringResource(R.string.ui_i_understand_the_risk_and_know_d00797c8),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDangerousMode = null
                        actions.onSetDangerousMode(mode)
                    },
                    modifier = Modifier.testTag("settings.dangerous_mode.confirm"),
                ) { Text(stringResource(R.string.ui_enable_8eeb2a71)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDangerousMode = null }) { Text(stringResource(R.string.ui_cancel_998b9c48)) }
            },
        )
    }
}

@Composable
private fun WiredPairingBlock(
    pairing: WiredPairingUiState,
    chinese: Boolean,
    tokenProvider: () -> String?,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // A newly issued prompt has a new expiry. Never carry a previous reveal
    // choice into a replacement prompt, and never save the token in Compose
    // state or SavedState.
    LaunchedEffect(pairing.expiresAtEpochMs) {
        showToken = false
    }

    // The token is read only for this composition/copy action and is never
    // stored in SettingsUiState or rememberSaveable state.
    val token = if (pairing.hasToken) tokenProvider() else null
    if (token != null) {
        Column(
            Modifier.fillMaxWidth().testTag("settings.wired_adb.pairing"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.ui_one_time_pairing_token_this_settings_a5952c3d),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                if (chinese) {
                    "电脑端选择 adb.exe 和 USB 设备，输入令牌；看到“等待手机完成配对”后点“完成配对”。手机显示已连接才算激活成功，之后可关闭电脑窗口并拔线。重启或设备服务被系统终止后需重新激活。"
                } else {
                    "Select adb.exe and the USB device, enter the token, then tap \"Complete pairing\" when prompted. Activation succeeds once the phone shows connected; you can then close the computer window and unplug USB. Reactivate after reboot or if the system terminates the device service."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings.wired_adb.pairing.instructions"),
            )
            if (showToken) {
                Text(
                    token,
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("settings.wired_adb.pairing.token"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.ui_the_token_is_hidden_reveal_it_3d16c03e),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("settings.wired_adb.pairing.token.hidden"),
                )
            }
            Text(
                if (chinese) {
                    "令牌由手机限时校验 · 剩余尝试：${pairing.remainingAttempts}"
                } else {
                    "Token expiry is checked on this phone · Attempts remaining: ${pairing.remainingAttempts}"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings.wired_adb.pairing.expiry"),
            )
            if (pairing.replacingExistingTrust) {
                Text(
                    stringResource(R.string.ui_credentials_change_only_after_the_new_bdf83f97),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ActionRow {
                TextButton(
                    onClick = { showToken = !showToken },
                    modifier = Modifier.testTag("settings.wired_adb.pairing.reveal"),
                ) { Text(if (showToken) if (chinese) "隐藏令牌" else "Hide token" else if (chinese) "查看令牌" else "View token") }
                OutlinedButton(
                    onClick = { if (showToken) clipboard.setText(AnnotatedString(token)) },
                    enabled = showToken,
                    modifier = Modifier.testTag("settings.wired_adb.pairing.copy"),
                ) { Text(stringResource(R.string.ui_copy_token_40e8f958)) }
                Button(
                    onClick = onComplete,
                    enabled = !pairing.completing && pairing.remainingAttempts > 0,
                    modifier = Modifier.testTag("settings.wired_adb.pairing.complete"),
                ) { Text(if (pairing.completing) if (chinese) "正在完成…" else "Completing…" else if (chinese) "完成配对" else "Complete pairing") }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !pairing.completing,
                    modifier = Modifier.testTag("settings.wired_adb.pairing.cancel"),
                ) { Text(stringResource(R.string.ui_cancel_998b9c48)) }
            }
        }
    } else if (pairing.status.isNotBlank()) {
        Text(
            pairingStatusLabel(pairing.status, chinese),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().testTag("settings.wired_adb.pairing.status"),
        )
    }
}

@Composable
private fun ProviderLifecycleBlock(
    state: AuthorityUiState,
    label: String,
    chinese: Boolean,
    modifier: Modifier = Modifier,
    onIntent: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onPrimaryAction: () -> Unit,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean,
    primaryActionTestTag: String? = null,
    onSecondaryAction: () -> Unit,
    secondaryActionLabel: String,
    secondaryActionEnabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (state.selected) {
            Text(
                stringResource(R.string.ui_currently_selected_channel_8f0a8796),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("settings.authority.${state.authority.lowercase()}.selected"),
            )
        }
        Text(
            if (chinese) "用户意图：${if (state.userIntentEnabled) "已启用" else "未启用"} · 平台授权：${grantLabel(state.platformGrant, chinese)}"
            else "User intent: ${if (state.userIntentEnabled) "enabled" else "disabled"} · Platform grant: ${grantLabel(state.platformGrant, chinese)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.ui_availability_s_connection_s_180222dd, (availabilityLabel(state.availability, chinese)), (connectionLabel(state.connection, chinese))),
            style = MaterialTheme.typography.bodySmall,
        )
        val trust = state.trust.ifBlank { "FORGOTTEN".takeIf { state.authority == "WIRED_ADB" }.orEmpty() }
        if (trust.isNotBlank()) {
            Text(
                stringResource(R.string.ui_trust_s_ce4bb255, (trustLabel(trust, chinese))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingSwitch(
            if (chinese) "保留此通道的用户意图" else "Keep user intent for this channel",
            state.userIntentEnabled,
            onIntent,
            modifier = Modifier.testTag("settings.authority.${state.authority.lowercase()}.intent"),
        )
        ActionRow {
            Button(
                onClick = onPrimaryAction,
                enabled = primaryActionEnabled,
                modifier = Modifier.testTag(
                    primaryActionTestTag ?: "settings.authority.${state.authority.lowercase()}.primary",
                ),
            ) { Text(primaryActionLabel) }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("settings.authority.${state.authority.lowercase()}.refresh")) {
                Text(stringResource(R.string.ui_refresh_cba212b1))
            }
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = Modifier.testTag("settings.authority.${state.authority.lowercase()}.secondary"),
            ) { Text(secondaryActionLabel) }
        }
        if (!state.configured) {
            Text(
                stringResource(R.string.ui_this_channel_has_no_persistent_configuration_2dab4478),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun authorityLabel(value: String, chinese: Boolean): String = when (value.uppercase()) {
    "SHIZUKU" -> "Shizuku"
    "WIRED_ADB" -> if (chinese) "有线 ADB" else "Wired ADB"
    else -> if (chinese) "无（仅基础工作区）" else "None (basic workspace only)"
}

private fun dangerousModeLabel(value: String, chinese: Boolean): String = when (value) {
    DangerousMode.ENABLED_CONFIRM_HIGH_RISK.name -> if (chinese) "开启：高危命令确认" else "Enabled: high-risk confirmation"
    DangerousMode.ENABLED_AUTONOMOUS.name -> if (chinese) "开启：完全自主" else "Enabled: autonomous"
    else -> if (chinese) "已关闭" else "Disabled"
}

private fun grantLabel(value: String, chinese: Boolean): String = when (value) {
    "GRANTED" -> if (chinese) "已授权" else "Granted"
    "DENIED" -> if (chinese) "已拒绝" else "Denied"
    "REVOKED" -> if (chinese) "已撤销" else "Revoked"
    else -> if (chinese) "未知" else "Unknown"
}

private fun readWriteLabel(value: Boolean, chinese: Boolean): String =
    if (value) {
        if (chinese) "已授权" else "Granted"
    } else {
        if (chinese) "未授权" else "Not granted"
    }

private fun availabilityLabel(value: String, chinese: Boolean): String = when (value) {
    "READY" -> if (chinese) "可用" else "Ready"
    "TEMPORARILY_UNAVAILABLE" -> if (chinese) "暂不可用" else "Temporarily unavailable"
    else -> if (chinese) "不支持" else "Unsupported"
}

private fun connectionLabel(value: String, chinese: Boolean): String = when (value) {
    "CONNECTING" -> if (chinese) "连接中" else "Connecting"
    "CONNECTED" -> if (chinese) "已连接" else "Connected"
    "DEGRADED" -> if (chinese) "已降级" else "Degraded"
    else -> if (chinese) "已断开" else "Disconnected"
}

private fun trustLabel(value: String, chinese: Boolean): String = when (value) {
    "TRUSTED" -> if (chinese) "已信任" else "Trusted"
    "REAUTH_REQUIRED" -> if (chinese) "需要重新授权" else "Re-authorization required"
    else -> if (chinese) "未配置" else "Not configured"
}

private fun pairingStatusLabel(value: String, chinese: Boolean): String = when (value) {
    "EXPIRED" -> if (chinese) "配对令牌已过期；请重新开始前台配对。" else "The pairing token expired; start a new foreground pairing."
    "CANCELLED" -> if (chinese) "前台配对已取消；令牌已清除。" else "Foreground pairing was cancelled; the token was cleared."
    "COMPLETED" -> if (chinese) "设备 ADB 已连接，可以退出电脑工具并拔线。" else "Device ADB connected. You can close the desktop tool and unplug USB."
    "FAILED" -> if (chinese) "配对未完成；请检查电脑端状态后重试或取消。" else "Pairing did not complete; check the computer and retry or cancel."
    else -> if (chinese) "配对状态已更新。" else "Pairing status updated."
}

private fun safStatusLabel(value: String, chinese: Boolean): String = when (value) {
    "ACTIVE" -> if (chinese) "有效" else "Active"
    "GRANT_LOST" -> if (chinese) "授权已丢失" else "Grant lost"
    else -> if (chinese) "已撤销" else "Revoked"
}

private fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"
    else -> "${bytes / (1024L * 1024L)} MiB"
}

@Composable
private fun SelectorRow(
    label: String,
    value: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menu: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        BoxedSelector(value, onOpen, menu, enabled, modifier)
    }
}

@Composable
private fun BoxedSelector(
    value: String,
    onOpen: () -> Unit,
    menu: @Composable () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = onOpen, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) { Text(value, maxLines = 2) }
        menu()
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    labelClickable: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).then(if (labelClickable) Modifier.clickable { onChange(!checked) } else Modifier))
        Switch(checked = checked, onCheckedChange = onChange, modifier = modifier.heightIn(min = 48.dp))
    }
}

@Composable
private fun AuthorityRow(label: String, status: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ThirdPartyNoticesDialog(
    state: ThirdPartyNoticesUiState,
    chinese: Boolean = false,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    var detailComponentId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var overviewExpanded by remember { mutableStateOf(false) }
    val scrollState = remember(detailComponentId, searchQuery) { androidx.compose.foundation.ScrollState(0) }
    val selected = detailComponentId?.let { id -> state.components.firstOrNull { it.id == id } }
    val filteredComponents = remember(state.components, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            state.components
        } else {
            state.components.filter { component ->
                component.name.contains(query, ignoreCase = true) ||
                    component.version.contains(query, ignoreCase = true) ||
                    component.license.contains(query, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                selected?.name ?: if (chinese) "第三方许可声明" else "Third-party notices",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading) CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (selected == null) {
                    Text(
                        stringResource(R.string.ui_the_content_below_comes_only_from_54f70959),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.overview.isBlank()) {
                        if (!state.loading && state.error == null) {
                            Text(stringResource(R.string.ui_no_third_party_notice_overview_is_e9f83e28), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        TextButton(onClick = { overviewExpanded = !overviewExpanded }) {
                            Text(if (overviewExpanded) {
                                if (chinese) "收起总览" else "Hide overview"
                            } else {
                                if (chinese) "显示总览" else "Show overview"
                            })
                        }
                        if (overviewExpanded) Text(state.overview, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.ui_components_7d6565eb), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_search_name_version_or_license_61764483)) },
                        singleLine = true,
                    )
                    if (state.components.isEmpty()) {
                        Text(stringResource(R.string.ui_no_components_are_available_007fd1e1), style = MaterialTheme.typography.bodySmall)
                    } else if (filteredComponents.isEmpty()) {
                        Text(stringResource(R.string.ui_no_components_match_the_search_4f1e9b2f), style = MaterialTheme.typography.bodySmall)
                    } else {
                        filteredComponents.forEach { component ->
                            OutlinedButton(
                                onClick = {
                                    detailComponentId = component.id
                                    onSelect(component.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(component.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val meta = listOfNotNull(
                                        component.version.takeIf { it.isNotBlank() },
                                        component.license.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ")
                                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        stringResource(R.string.ui_notice_files_s_tap_to_view_5bb13369, (component.files.size)),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { detailComponentId = null }) {
                        Text(stringResource(R.string.ui_back_to_components_ccc15a1d))
                    }
                    val meta = listOfNotNull(
                        selected.version.takeIf { it.isNotBlank() },
                        selected.license.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
                    if (selected.source.isNotBlank()) {
                        Text(
                            stringResource(R.string.ui_source_s_deaac202, (selected.source)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    selected.files.forEach { file ->
                        Text(
                            "${file.label} · ${file.path}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(stringResource(R.string.ui_complete_text_f28fdb75), style = MaterialTheme.typography.titleSmall)
                    val licenseText = state.selectedLicenseText
                    if (state.selectedComponentId != selected.id || licenseText == null) {
                        if (state.loading) {
                            Text(stringResource(R.string.ui_loading_complete_text_c86dd633), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(licenseText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(stringResource(R.string.about_close)) } },
    )
}
