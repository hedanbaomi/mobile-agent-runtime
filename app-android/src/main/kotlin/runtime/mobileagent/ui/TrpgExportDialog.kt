// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun TrpgExportDialog(content: String, chinese: Boolean, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (chinese) "导出玩家资料" else "Export player record") },
        text = { Column {
            Text(if (chinese) "已过滤标为主持人可见的结构化资料。请核对自由文本；此文件不含聊天原文，也不代替完整备份。"
                else "GM-only structured records are filtered. Review free text before sharing. This file contains no transcript and is not a complete backup.",
                style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(content, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()).testTag("trpg.export.preview"), style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.export.save")) {
            Text(if (chinese) "选择位置并保存" else "Choose file and save")
        } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.export.close")) { Text(if (chinese) "取消" else "Cancel") } })
}
