// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import runtime.mobileagent.MainActivity
import runtime.mobileagent.updates.AppUpdateCoordinator
import runtime.mobileagent.updates.UpdatePhase

@Composable
internal fun AppUpdateDialog(updates: AppUpdateCoordinator, chinese: Boolean) {
    val state by updates.state.collectAsState()
    if (!state.prompt) return
    val context = LocalContext.current
    val release = state.release
    AlertDialog(
        modifier = Modifier.testTag("app-update-dialog"),
        onDismissRequest = updates::dismiss,
        title = { Text(if (release != null) "${if (chinese) "新版本" else "New version"} ${release.version}" else if (chinese) "检查更新" else "Check updates") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.message)
                if (release != null) {
                    Text("GitHub · ${(release.size / (1024.0 * 1024)).toInt()} MB")
                    if (state.phase == UpdatePhase.DOWNLOADING) {
                        val progress = state.downloaded.toFloat() / release.size
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%")
                    }
                    if (!state.busy && release.notes.isNotBlank()) Text(release.notes)
                    if (state.compatible) Text(if (chinese) "下载后验证安装包，再由系统确认安装。" else "The download is verified before Android asks you to install.")
                }
            }
        },
        confirmButton = {
            if (release != null && state.compatible && !state.busy) {
                TextButton(onClick = { (context as? MainActivity)?.downloadAndInstallUpdate(updates) }) {
                    Text(if (state.phase == UpdatePhase.READY) { if (chinese) "安装更新" else "Install update" }
                        else if (chinese) "下载并安装" else "Download and install")
                }
            } else if (state.phase == UpdatePhase.ERROR && !state.busy) {
                TextButton(onClick = { updates.check(manual = true) }) { Text(if (chinese) "重试" else "Retry") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (state.phase == UpdatePhase.DOWNLOADING) updates.cancelDownload()
                updates.dismiss()
            }) { Text(if (state.phase == UpdatePhase.DOWNLOADING) { if (chinese) "取消下载" else "Cancel download" }
                else if (chinese) "稍后" else "Later") }
        },
    )
}
