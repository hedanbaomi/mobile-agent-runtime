// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.Message

/** Receives the already projected source; raw GM tool payloads never enter this surface. */
@Composable internal fun TrpgSourceDialog(message: Message, chinese: Boolean, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (chinese) "来源记录" else "Source record") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text(message.createdAt, style = MaterialTheme.typography.labelSmall)
            Text(message.text)
        } }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (chinese) "关闭" else "Close")
        } })
}
