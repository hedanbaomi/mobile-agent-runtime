// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import runtime.mobileagent.feature.chat.ChatUiState

@Composable
internal fun ArchivedConversationsDialog(state: ChatUiState, chinese: Boolean,
    onClose: () -> Unit, onSelect: (String) -> Unit, onRestore: (String) -> Unit, onLoadMore: () -> Unit) {
    AlertDialog(onDismissRequest = onClose,
        title = { Text(if (chinese) "已归档的对话" else "Archived conversations") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp).testTag("settings.archive.list")) {
                val archived = state.archivedSessions
                if (archived.isEmpty()) item { Text(if (chinese) "暂无已归档的对话。" else "No archived conversations.") }
                items(archived.size, key = { archived[it].id }) { index ->
                    val session = archived[index]
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        TextButton(onClick = { onSelect(session.id) }, modifier = Modifier.testTag("settings.archive.view.${session.id}")) { Text(session.title) }
                        Text(session.timeLabel, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onRestore(session.id) }, modifier = Modifier.testTag("settings.archive.restore.${session.id}")) {
                            Text(if (chinese) "恢复" else "Restore")
                        }
                    }
                }
                if (state.hasMoreArchivedSessions) item {
                    TextButton(onClick = onLoadMore, modifier = Modifier.testTag("settings.archive.loadMore")) {
                        Text(if (chinese) "加载更多已归档对话" else "Load more archived conversations")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text(if (chinese) "关闭" else "Close") } })
}
