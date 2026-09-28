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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

const val SOURCE_REPOSITORY_URL = "https://github.com/hedanbaomi/mobile-agent-runtime"

/** Version, licensing and source information reached from Settings. */
@Composable
fun AboutScreen(
    state: SettingsUiState,
    actions: SettingsActions = SettingsActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
) {
    val zh = state.language.equals("zh-CN", true) || state.language.equals("system", true)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showVersion by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var agplText by remember(state.licenseText) { mutableStateOf(state.licenseText) }
    LaunchedEffect(showLicense, state.licenseText) {
        if (showLicense && agplText == null) {
            agplText = ThirdPartyNoticeAssets.loadAgplText(context).getOrElse {
                if (zh) "无法读取 AGPL-3.0-only 文本。" else "AGPL-3.0-only text is unavailable."
            }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("about.screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPageTitle) Text(if (zh) "关于" else "About", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("mobileAgentRuntime", style = MaterialTheme.typography.titleMedium)
                Text("${state.versionName} (${state.gitRevision})", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (zh) "数据库 schema ${state.schemaVersion} · 构建 ${state.buildTimeUtc}"
                    else "DB schema ${state.schemaVersion} · built ${state.buildTimeUtc}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("AGPL-3.0-only", style = MaterialTheme.typography.bodySmall)
                Text(SOURCE_REPOSITORY_URL, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = actions.onOpenSource, modifier = Modifier.testTag("about.github")) {
                    Text(if (zh) "打开 GitHub 源码仓库" else "Open GitHub source repository")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showVersion = true }) { Text(if (zh) "查看版本信息" else "Version details") }
                    OutlinedButton(onClick = { showLicense = true }) { Text(if (zh) "查看许可证" else "License") }
                }
                OutlinedButton(onClick = actions.onOpenThirdPartyNotices) {
                    Text(if (zh) "第三方声明" else "Third-party notices")
                }
                if (state.noticeCount > 0) TextButton(onClick = actions.onOpenAnnouncements) {
                    Text(if (zh) "公告 (${state.noticeCount})" else "News (${state.noticeCount})")
                }
                OutlinedButton(onClick = actions.onCheckUpdates) { Text(if (zh) "检查更新" else "Check updates") }
                if (state.updateState.isNotBlank()) Text(state.updateState, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (showVersion) {
        AlertDialog(
            onDismissRequest = { showVersion = false },
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
            confirmButton = { Button(onClick = { showVersion = false }) { Text(if (zh) "关闭" else "Close") } },
            dismissButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.diagnosticText)) }) {
                    Text(if (zh) "复制诊断" else "Copy diagnostics")
                }
            },
        )
    }
    if (showLicense) {
        val license = agplText ?: if (zh) "正在读取 AGPL-3.0-only 文本…" else "Loading AGPL-3.0-only text…"
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("AGPL-3.0-only") },
            text = { Text(license, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { Button(onClick = { showLicense = false }) { Text(if (zh) "关闭" else "Close") } })
    }
    if (state.thirdPartyNotices.opened) {
        ThirdPartyNoticesDialog(state = state.thirdPartyNotices, chinese = zh,
            onSelect = actions.onSelectThirdPartyNotice, onClose = actions.onCloseThirdPartyNotices)
    }
}
