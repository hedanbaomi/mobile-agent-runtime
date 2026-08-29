// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

data class SettingsUiState(
    val versionName: String = "",
    val gitRevision: String = "",
    val gitDirty: Boolean = false,
    val schemaVersion: Int = 0,
    val buildTimeUtc: String = "",
    val diagnosticText: String = "",
    /** zh-CN is the product default; the ViewModel may replace it with the persisted choice. */
    val language: String = "zh-CN",
    /** 66ccff is the product default light accent and is shown literally in the selector. */
    val themeMode: String = "66ccff",
    val statsEnabled: Boolean = false,
    val requestInspectionEnabled: Boolean = true,
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
)

data class SettingsActions(
    val onLanguage: (String) -> Unit = {},
    val onTheme: (String) -> Unit = {},
    val onStats: (Boolean) -> Unit = {},
    val onRequestInspection: (Boolean) -> Unit = {},
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (zh) "设置" else "Settings", style = MaterialTheme.typography.headlineSmall)
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (zh) "隐私与调试" else "Privacy and diagnostics", style = MaterialTheme.typography.titleMedium)
                SettingSwitch(if (zh) "匿名使用统计" else "Anonymous usage statistics", state.statsEnabled, actions.onStats)
                SettingSwitch(if (zh) "显示请求检查器" else "Show request inspector", state.requestInspectionEnabled, actions.onRequestInspection)
                Text(if (zh) "API 密钥不会进入导出文件或请求检查器。" else "API keys never enter exports or the request inspector.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
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
private fun SelectorRow(label: String, value: String, onOpen: () -> Unit, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        BoxedSelector(value, onOpen, menu)
    }
}

@Composable
private fun BoxedSelector(value: String, onOpen: () -> Unit, menu: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = onOpen) { Text(value) }
        menu()
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
