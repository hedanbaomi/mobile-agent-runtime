// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import runtime.mobileagent.feature.settings.R as SettR

@Composable
fun AboutScreen(
    versionName: String,
    gitRevision: String,
    statsEnabled: Boolean = false,
    onStats: (Boolean) -> Unit = {},
    language: String = "zh-CN",
) {
    val context = LocalContext.current
    val chinese = !language.equals("en-US", true)
    var agpl by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf(ThirdPartyNoticesUiState()) }
    LaunchedEffect(Unit) {
        agpl = ThirdPartyNoticeAssets.loadAgplText(context).getOrElse {
            if (chinese) "无法读取 AGPL-3.0-only 文本。" else "AGPL-3.0-only text is unavailable."
        }
    }
    LaunchedEffect(notices.opened, notices.selectedComponentId, notices.components.size, notices.error) {
        if (!notices.opened || notices.loading || notices.error != null) return@LaunchedEffect
        if (notices.components.isEmpty() && notices.overview.isBlank()) {
            notices = notices.copy(loading = true)
            notices = ThirdPartyNoticeAssets.loadCatalog(context).copy(opened = true)
            return@LaunchedEffect
        }
        val selected = notices.components.firstOrNull { it.id == notices.selectedComponentId }
        if (selected != null && notices.selectedLicenseText == null) {
            notices = notices.copy(loading = true)
            val loaded = ThirdPartyNoticeAssets.loadComponentText(context, selected)
            notices = loaded.fold(
                onSuccess = { text -> notices.copy(loading = false, selectedLicenseText = text, error = null) },
                onFailure = { failure -> notices.copy(loading = false, error = aboutNoticeError(failure, chinese)) },
            )
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("mobileAgentRuntime", style = MaterialTheme.typography.headlineSmall)
        Text(if (chinese) "版本 $versionName ($gitRevision)" else "Version $versionName ($gitRevision)")
        Text(if (chinese) "许可证：AGPL-3.0-only" else "License: AGPL-3.0-only")
        Text(if (chinese) "源码：https://github.com/hedanbaomi/mobile-agent-runtime" else "Source: https://github.com/hedanbaomi/mobile-agent-runtime")
        Text(
            if (chinese) {
                "本软件仅依据 GNU Affero General Public License 3.0 发布。API 密钥保留在本设备；公告服务独立于模型服务商。"
            } else {
                "This is free software under GNU Affero General Public License version 3.0 only. API keys stay on this device. The announcement service is independent of your model provider."
            },
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(stringResource(SettR.string.sett_anonymous_metrics), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(SettR.string.sett_metrics_help), style = MaterialTheme.typography.bodySmall)
        Switch(checked = statsEnabled, onCheckedChange = onStats)
        OutlinedButton(onClick = { notices = notices.copy(opened = true, error = null) }, modifier = Modifier.padding(top = 12.dp)) {
            Text(if (chinese) "第三方声明" else "Third-party notices")
        }
        Text(agpl ?: if (chinese) "正在读取 AGPL-3.0-only 文本…" else "Loading AGPL-3.0-only text…", modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
    if (notices.opened) {
        ThirdPartyNoticesDialog(
            state = notices,
            chinese = chinese,
            onSelect = { id ->
                notices = notices.copy(
                    opened = true,
                    selectedComponentId = id,
                    selectedLicenseText = null,
                    error = null,
                )
            },
            onClose = { notices = notices.copy(opened = false) },
        )
    }
}

private fun aboutNoticeError(failure: Throwable, chinese: Boolean): String {
    val fallback = if (chinese) "第三方声明文件读取失败。" else "The third-party notice could not be read."
    return failure.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(256)?.ifBlank { fallback } ?: fallback
}
