// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.DangerousMode

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
    val statsEnabled: Boolean = false,
    val requestInspectionEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val diagnosticsSizeBytes: Long = 0L,
    val diagnosticsLimitBytes: Long = 0L,
    val diagnosticsState: String = "",
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
    val onLanguage: (String) -> Unit = {},
    val onTheme: (String) -> Unit = {},
    val onStats: (Boolean) -> Unit = {},
    val onRequestInspection: (Boolean) -> Unit = {},
    val onDiagnosticsEnabled: (Boolean) -> Unit = {},
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
    val onSaveWebSearch: (String) -> Unit = {},
    val onWebSearchEnabled: (Boolean) -> Unit = {},
    val onClearWebSearch: () -> Unit = {},
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

/** State-driven alias for hosts that still route the settings tab through AboutScreen. */
@Composable
fun AboutScreen(state: SettingsUiState, actions: SettingsActions = SettingsActions(), modifier: Modifier = Modifier) {
    SettingsScreen(state, actions, modifier)
}

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions = SettingsActions(), modifier: Modifier = Modifier) {
    val zh = state.language.equals("zh-CN", true) || state.language.equals("system", true)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var languageMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var agplText by remember(state.licenseText) { mutableStateOf(state.licenseText) }
    LaunchedEffect(showLicense, state.licenseText) {
        if (showLicense && agplText == null) {
            agplText = ThirdPartyNoticeAssets.loadAgplText(context).getOrElse {
                if (zh) "无法读取 AGPL-3.0-only 文本。" else "AGPL-3.0-only text is unavailable."
            }
        }
    }
    Column(
        modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings.screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (zh) "设置" else "Settings", style = MaterialTheme.typography.headlineSmall)
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
        Card(Modifier.fillMaxWidth().testTag("settings.appearance")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (zh) "外观与语言" else "Appearance and language", style = MaterialTheme.typography.titleMedium)
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
            var apiKey by remember { mutableStateOf("") }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (zh) "联网搜索（Brave Search API）" else "Web search (Brave Search API)", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (zh) "查询只会在每次工具调用得到你的确认后发送给 Brave；API 可能计费。密钥使用 Android Keystore 加密保存，不进入导出、诊断或请求检查器。"
                    else "Each query is sent to Brave only after your per-call approval; the API may charge. The key is encrypted with Android Keystore and excluded from exports, diagnostics, and the request inspector.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (zh) "Brave Search API Key" else "Brave Search API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { actions.onSaveWebSearch(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank()) {
                        Text(if (zh) "保存并启用" else "Save and enable")
                    }
                    if (state.webSearchConfigured) {
                        OutlinedButton(onClick = actions.onClearWebSearch) { Text(if (zh) "移除密钥" else "Remove key") }
                    }
                }
                SettingSwitch(
                    if (zh) "允许提供联网搜索工具" else "Expose web-search tool",
                    state.webSearchEnabled,
                    actions.onWebSearchEnabled,
                )
                Text(
                    if (zh) "当前：${if (state.webSearchConfigured) "密钥已配置" else "未配置密钥"}。返回结果属于不可信外部内容，应用不会自动打开结果页面。"
                    else "Current: ${if (state.webSearchConfigured) "key configured" else "no key configured"}. Results are untrusted external content and are never opened automatically.",
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
                Text(if (zh) "全局根提示词" else "Global root prompt", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (zh) "位于不可编辑的运行时协议与 Agent 提示词之间。不能授予工具、网络、文件或 Python 隔离。"
                    else "Inserted between the immutable runtime contract and the agent prompt. It cannot grant tools, network, files, or Python isolation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.globalRootPromptUnlocked) {
                    OutlinedButton(onClick = actions.onUnlockRootPrompt) { Text(if (zh) "解锁高级编辑" else "Unlock advanced editing") }
                } else {
                    OutlinedTextField(draftPrompt, { draftPrompt = it }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.onSaveRootPrompt(draftPrompt) }) { Text(if (zh) "保存覆盖" else "Save override") }
                        OutlinedButton(onClick = actions.onRestoreRootPrompt) { Text(if (zh) "恢复默认" else "Restore default") }
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
                Text(if (zh) "隐私与调试" else "Privacy and diagnostics", style = MaterialTheme.typography.titleMedium)
                SettingSwitch(if (zh) "匿名使用统计" else "Anonymous usage statistics", state.statsEnabled, actions.onStats)
                SettingSwitch(if (zh) "显示请求检查器" else "Show request inspector", state.requestInspectionEnabled, actions.onRequestInspection)
                SettingSwitch(if (zh) "应用内诊断记录（默认关闭）" else "In-app diagnostics (off by default)", state.diagnosticsEnabled, actions.onDiagnosticsEnabled)
                Text(
                    if (zh) "${if (state.diagnosticsEnabled) "已开启" else "已关闭"} · ${formatDiagnosticBytes(state.diagnosticsSizeBytes)} / ${formatDiagnosticBytes(state.diagnosticsLimitBytes)}"
                    else "${if (state.diagnosticsEnabled) "On" else "Off"} · ${formatDiagnosticBytes(state.diagnosticsSizeBytes)} / ${formatDiagnosticBytes(state.diagnosticsLimitBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (zh) "仅记录有限的设备/错误元数据与匿名能力开关 breadcrumbs，不含聊天正文、Prompt、知识文件名/路径、密钥、请求头或请求正文。导出可能包含设备/错误元数据；原生崩溃或系统强杀仍可能需要 ADB Logcat。"
                    else "Only bounded device/error metadata and anonymous capability breadcrumbs are recorded; chat text, prompts, knowledge filenames/paths, keys, headers, and request bodies are excluded. Export may contain device/error metadata; native crashes or system kills may still require ADB Logcat.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onExportDiagnostics) { Text(if (zh) "导出诊断 ZIP" else "Export diagnostics ZIP") }
                    OutlinedButton(onClick = actions.onClearDiagnostics, enabled = state.diagnosticsSizeBytes > 0) { Text(if (zh) "清除诊断" else "Clear diagnostics") }
                }
                if (state.diagnosticsState.isNotBlank()) Text(state.diagnosticsState, style = MaterialTheme.typography.bodySmall)
                Text(if (zh) "API 密钥不会进入导出文件或请求检查器。" else "API keys never enter exports or the request inspector.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.data_backup")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (zh) "数据与备份" else "Data and backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (zh) {
                        "导出为 ZIP，写入你选择的位置；若选择云端文档提供方，该提供方可能上传或同步。应用不另行上传。扩展内容默认关闭，密钥与授权不进入导出；总大小上限 512 MiB，单项上限 50 MiB。"
                    } else {
                        "Export is a ZIP written to the location you choose. If you choose a cloud document provider, that provider may upload or sync it. The app does not upload it separately. Optional content is off by default; keys and authorizations are excluded. Total limit: 512 MiB; each item: 50 MiB."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onExport) { Text(if (zh) "导出" else "Export") }
                    OutlinedButton(onClick = actions.onImport) { Text(if (zh) "导入" else "Import") }
                }
                if (state.exportState.isNotBlank()) Text(state.exportState, style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.feature_entry")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (zh) "功能入口" else "Feature entry points", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = false, onClick = actions.onOpenProviders, label = { Text(if (zh) "服务商" else "Providers") })
                    FilterChip(selected = false, onClick = actions.onOpenKnowledge, label = { Text(if (zh) "知识" else "Knowledge") })
                    FilterChip(selected = false, onClick = actions.onOpenSkills, label = { Text(if (zh) "技能" else "Skills") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = false, onClick = actions.onOpenAnnouncements, label = { Text(if (zh) "公告" else "News") })
                    OutlinedButton(onClick = actions.onOpenMcpSettings, enabled = state.mcpEntryEnabled) { Text(if (zh) "MCP 设置" else "MCP settings") }
                }
                Text(state.mcpDisabledReason, style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth().testTag("settings.about")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (zh) "关于" else "About", style = MaterialTheme.typography.titleMedium)
                Text("mobileAgentRuntime")
                Text("${state.versionName} (${state.gitRevision})", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (zh) "数据库 schema ${state.schemaVersion} · 构建 ${state.buildTimeUtc}"
                    else "DB schema ${state.schemaVersion} · built ${state.buildTimeUtc}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("AGPL-3.0-only", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAbout = true }) { Text(if (zh) "查看版本信息" else "Version details") }
                    OutlinedButton(onClick = { showLicense = true }) { Text(if (zh) "查看许可证" else "License") }
                    if (state.noticeCount > 0) TextButton(onClick = actions.onOpenAnnouncements) { Text(if (zh) "公告 (${state.noticeCount})" else "News (${state.noticeCount})") }
                }
                OutlinedButton(onClick = actions.onOpenThirdPartyNotices) {
                    Text(if (zh) "第三方声明" else "Third-party notices")
                }
                OutlinedButton(onClick = actions.onCheckUpdates) { Text(if (zh) "检查更新" else "Check updates") }
                if (state.updateState.isNotBlank()) Text(state.updateState, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("mobileAgentRuntime") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.versionName}\n${state.diagnosticText.trim()}\nAGPL-3.0-only")
                    Text(
                        if (zh) "诊断不含密钥。工具能力开关崩溃仍需绑定此 revision 的完整 Logcat。"
                        else "Diagnostics omit secrets. A tools-capability crash still needs full Logcat bound to this revision.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { Button(onClick = { showAbout = false }) { Text(if (zh) "关闭" else "Close") } },
            dismissButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.diagnosticText)) }) {
                    Text(if (zh) "复制诊断" else "Copy diagnostics")
                }
            },
        )
    }
    if (showLicense) {
        val license = agplText ?: if (zh) "正在读取 AGPL-3.0-only 文本…" else "Loading AGPL-3.0-only text…"
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("AGPL-3.0-only") }, text = { Text(license, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { Button(onClick = { showLicense = false }) { Text(if (zh) "关闭" else "Close") } })
    }
    if (state.thirdPartyNotices.opened) {
        ThirdPartyNoticesDialog(
            state = state.thirdPartyNotices,
            chinese = zh,
            onSelect = actions.onSelectThirdPartyNotice,
            onClose = actions.onCloseThirdPartyNotices,
        )
    }
}

@Composable
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
            Text(if (chinese) "命令与权限" else "Commands and authorities", style = MaterialTheme.typography.titleMedium)
            Text(
                if (chinese) "基础工作区始终与系统增强通道隔离。仅支持 Shizuku 与有线 ADB；当前通道不可用时不会自动切换。"
                else "The basic workspace stays separate from system enhancement. Only Shizuku and wired ADB are supported; an unavailable channel never switches automatically.",
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
                if (chinese) "启用后会同时记录用户意图、选用 Shizuku，并在需要时请求系统授权。"
                else "This records user intent, selects Shizuku, and requests the platform grant when needed.",
                style = MaterialTheme.typography.bodySmall,
            )

            ProviderLifecycleBlock(
                state = state.wiredAdbAuthority,
                label = if (chinese) "有线 ADB" else "Wired ADB",
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
                secondaryActionLabel = if (chinese) "忘记此电脑" else "Forget computer",
                secondaryActionEnabled = state.wiredAdbAuthority.configured || state.wiredAdbAuthority.trust.isNotBlank(),
            )

            WiredPairingBlock(
                pairing = state.wiredPairing,
                chinese = chinese,
                tokenProvider = actions.onWiredPairingToken,
                onComplete = actions.onCompleteWiredPairing,
                onCancel = actions.onCancelWiredPairing,
            )

            Column(
                Modifier.fillMaxWidth().testTag("settings.workspace.saf"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (chinese) "用户授权文件（SAF）" else "User-authorized files (SAF)", style = MaterialTheme.typography.labelLarge)
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
                ActionRow {
                    Button(
                        onClick = actions.onSelectSafTree,
                        modifier = Modifier.testTag("settings.saf.authorize"),
                    ) { Text(if (chinese) "选择目录" else "Choose directory") }
                    OutlinedButton(
                        onClick = actions.onReauthorizeSaf,
                        enabled = safConfigured,
                        modifier = Modifier.testTag("settings.saf.reauthorize"),
                    ) { Text(if (chinese) "重新授权" else "Re-authorize") }
                    OutlinedButton(
                        onClick = actions.onRevokeSaf,
                        enabled = safConfigured,
                        modifier = Modifier.testTag("settings.saf.revoke"),
                    ) { Text(if (chinese) "撤销" else "Revoke") }
                    OutlinedButton(
                        onClick = actions.onOpenAgents,
                        enabled = state.safWorkspace.persisted,
                        modifier = Modifier.testTag("settings.saf.open_agents"),
                    ) { Text(if (chinese) "去智能体授权" else "Open Agent grants") }
                }
            }

            Column(
                Modifier.fillMaxWidth().testTag("settings.dangerous_mode"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (chinese) "危险模式" else "Dangerous Mode", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (chinese) "允许 Agent 直接执行 Android Shell 命令。它可能修改或删除文件、停止应用、修改部分系统设置；这不是 Root。"
                    else "Allows the Agent to execute Android shell commands directly. It may modify or delete files, stop apps, or change some system settings; this is not Root.",
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
                        if (chinese) "授权不会因任务结束、会话结束、后台、USB 拔出或 Binder 中断自动关闭；请使用上方策略选择“已关闭”。"
                        else "The setting is not cleared by task/session end, backgrounding, USB removal, or Binder loss; choose Disabled above to turn it off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (pendingWiredPairingReplacement) {
        AlertDialog(
            onDismissRequest = { pendingWiredPairingReplacement = false },
            title = { Text(if (chinese) "确认替换已保存信任" else "Confirm replacing saved trust") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .testTag("settings.wired_adb.replace.risk_dialog"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (chinese) {
                            "这会替换当前已保存的有线 ADB 信任关系，并开始一次新的前台配对。旧信任不会继续用于本次配对。"
                        } else {
                            "This replaces the saved wired ADB trust and starts a new foreground pairing. The old trust will not be used for this pairing."
                        },
                    )
                    Text(
                        if (chinese) "令牌只在当前设置页面临时显示；请确认你已准备好在电脑端完成配对。"
                        else "The one-time token is shown only temporarily on this Settings screen; make sure you are ready to complete pairing on the computer.",
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
                ) { Text(if (chinese) "替换并开始配对" else "Replace and start pairing") }
            },
            dismissButton = {
                TextButton(onClick = { pendingWiredPairingReplacement = false }) {
                    Text(if (chinese) "取消" else "Cancel")
                }
            },
        )
    }

    pendingDangerousMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { pendingDangerousMode = null },
            title = { Text(if (chinese) "确认开启危险模式" else "Confirm Dangerous Mode") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).testTag("settings.dangerous_mode.risk_dialog"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (chinese) "开启后，Agent 可以使用当前 Shizuku 或有线 ADB 的 Shell 权限直接执行命令。错误命令可能导致数据丢失、应用停止或设备状态异常。此功能不是 Root，也不是安全沙箱。"
                        else "After enabling, the Agent may execute commands directly with the current Shizuku or wired ADB shell authority. Mistakes can cause data loss, stopped apps, or unexpected device state. This is not Root and not a safety sandbox.",
                    )
                    Text(
                        if (chinese) "我理解风险，并知道系统增强通道不可用时 Shell 仍会保持不可用，不会自动切换。"
                        else "I understand the risk and know that Shell remains unavailable when the selected enhancement channel is unavailable; it will not switch automatically.",
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
                ) { Text(if (chinese) "确认开启" else "Enable") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDangerousMode = null }) { Text(if (chinese) "取消" else "Cancel") }
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
                if (chinese) "一次性配对令牌（仅保留在当前设置页面）" else "One-time pairing token (this Settings screen only)",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                if (chinese) {
                    "先在电脑运行 `mar-bridge pair --serial <serial>`，将上面的令牌粘贴到电脑提示中；成功后再点“完成配对”。"
                } else {
                    "First run `mar-bridge pair --serial <serial>` on the computer, paste the token above into the computer prompt, then tap \"Complete pairing\"."
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
                    if (chinese) "令牌已隐藏；点击“查看令牌”后才能复制。" else "The token is hidden; reveal it before copying.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("settings.wired_adb.pairing.token.hidden"),
                )
            }
            Text(
                if (chinese) {
                    "过期时间（时间戳）：${pairing.expiresAtEpochMs} · 剩余尝试：${pairing.remainingAttempts}"
                } else {
                    "Expiry (timestamp): ${pairing.expiresAtEpochMs} · Attempts remaining: ${pairing.remainingAttempts}"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings.wired_adb.pairing.expiry"),
            )
            if (pairing.replacingExistingTrust) {
                Text(
                    if (chinese) "正在替换已保存信任；完成前旧信任不会用于本次配对。"
                    else "Saved trust is being replaced; the old trust is not used for this pairing.",
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
                ) { Text(if (chinese) "复制令牌" else "Copy token") }
                Button(
                    onClick = onComplete,
                    enabled = !pairing.completing && pairing.remainingAttempts > 0,
                    modifier = Modifier.testTag("settings.wired_adb.pairing.complete"),
                ) { Text(if (pairing.completing) if (chinese) "正在完成…" else "Completing…" else if (chinese) "完成配对" else "Complete pairing") }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !pairing.completing,
                    modifier = Modifier.testTag("settings.wired_adb.pairing.cancel"),
                ) { Text(if (chinese) "取消" else "Cancel") }
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
                if (chinese) "当前选定通道" else "Currently selected channel",
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
            if (chinese) "可用性：${availabilityLabel(state.availability, chinese)} · 连接：${connectionLabel(state.connection, chinese)}"
            else "Availability: ${availabilityLabel(state.availability, chinese)} · Connection: ${connectionLabel(state.connection, chinese)}",
            style = MaterialTheme.typography.bodySmall,
        )
        val trust = state.trust.ifBlank { "FORGOTTEN".takeIf { state.authority == "WIRED_ADB" }.orEmpty() }
        if (trust.isNotBlank()) {
            Text(
                if (chinese) "信任：${trustLabel(trust, chinese)}" else "Trust: ${trustLabel(trust, chinese)}",
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
                Text(if (chinese) "刷新" else "Refresh")
            }
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = Modifier.testTag("settings.authority.${state.authority.lowercase()}.secondary"),
            ) { Text(secondaryActionLabel) }
        }
        if (!state.configured) {
            Text(
                if (chinese) "此通道尚未完成持久配置；连接状态不会替代用户授权。" else "This channel has no persistent configuration; connection state does not replace user authorization.",
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
    "COMPLETED" -> if (chinese) "有线 ADB 配对已完成。" else "Wired ADB pairing completed."
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
        OutlinedButton(onClick = onOpen, enabled = enabled, modifier = modifier) { Text(value) }
        menu()
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = modifier)
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
    chinese: Boolean,
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
                        if (chinese) "以下内容仅来自 APK 内置声明资产；浏览不会联网，也不会改变第三方原文。"
                        else "The content below comes only from notices bundled in the APK. Browsing does not use the network or alter third-party text.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.overview.isBlank()) {
                        if (!state.loading && state.error == null) {
                            Text(if (chinese) "暂无第三方声明总览。" else "No third-party notice overview is available.", style = MaterialTheme.typography.bodySmall)
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
                    Text(if (chinese) "组件清单" else "Components", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (chinese) "搜索名称、版本或许可证" else "Search name, version, or license") },
                        singleLine = true,
                    )
                    if (state.components.isEmpty()) {
                        Text(if (chinese) "暂无可显示的组件。" else "No components are available.", style = MaterialTheme.typography.bodySmall)
                    } else if (filteredComponents.isEmpty()) {
                        Text(if (chinese) "没有匹配的组件。" else "No components match the search.", style = MaterialTheme.typography.bodySmall)
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
                                        if (chinese) "声明文件：${component.files.size} 个；点击查看完整原文"
                                        else "Notice files: ${component.files.size}; tap to view the complete text",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { detailComponentId = null }) {
                        Text(if (chinese) "返回组件清单" else "Back to components")
                    }
                    val meta = listOfNotNull(
                        selected.version.takeIf { it.isNotBlank() },
                        selected.license.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
                    if (selected.source.isNotBlank()) {
                        Text(
                            if (chinese) "来源：${selected.source}" else "Source: ${selected.source}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    selected.files.forEach { file ->
                        Text(
                            "${file.label} · ${file.path}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(if (chinese) "完整原文" else "Complete text", style = MaterialTheme.typography.titleSmall)
                    val licenseText = state.selectedLicenseText
                    if (state.selectedComponentId != selected.id || licenseText == null) {
                        if (state.loading) {
                            Text(if (chinese) "正在读取完整原文…" else "Loading complete text…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(licenseText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(if (chinese) "关闭" else "Close") } },
    )
}
