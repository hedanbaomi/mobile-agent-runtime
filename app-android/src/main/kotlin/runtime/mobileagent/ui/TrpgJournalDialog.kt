// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.trpg.TrpgEvent
import runtime.mobileagent.domain.trpg.TrpgEventKind
import runtime.mobileagent.domain.trpg.trpgTurnResourceChanges

@Composable
internal fun TrpgJournalDialog(
    events: List<TrpgEvent>, undoableId: String?, writable: Boolean, busy: Boolean,
    chinese: Boolean, error: String?, hasMore: Boolean,
    onUndo: (String) -> Unit, onLoadEarlier: () -> Unit, onDismiss: () -> Unit,
    latestTurn: List<TrpgEvent> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trpg.journal.dialog"),
        title = { Text(if (chinese) "跑团日志" else "Campaign journal") },
        text = {
            Column {
                Text(if (chinese) "骰点与结算会自动保存。撤销保留记录；只撤销最近一次状态变更。"
                    else "Rolls and settlements are saved. Undo keeps history and reverses only the latest state change.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (latestTurn.isNotEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("trpg.journal.turn_summary")) {
                            Text(if (chinese) "最近一轮 · ${latestTurn.size} 次结算" else "Latest turn · ${latestTurn.size} settlements", style = MaterialTheme.typography.titleSmall)
                            trpgTurnResourceChanges(latestTurn).forEach { change -> Text("${change.displayName}: ${change.before ?: "—"} → ${change.after ?: "—"}") }
                            Text(latestTurn.flatMap { it.changedFields }.distinct().joinToString(" · ") { trpgFieldLabel(it, chinese) })
                        }
                    }
                    if (events.isEmpty()) item { Text(if (chinese) "尚无记录" else "No events yet") }
                    items(events, key = { it.id }) { event ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().testTag("trpg.journal.event.${event.revision}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("#${event.revision} · ${trpgEventTitle(event, chinese)}", style = MaterialTheme.typography.titleSmall)
                                Text(event.createdAt, style = MaterialTheme.typography.labelSmall)
                                if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.bodySmall)
                                event.resources.forEach { change ->
                                    Text("${change.displayName}: ${change.before ?: "—"} → ${change.after ?: "—"}")
                                }
                                if (event.changedFields.isNotEmpty()) Text(event.changedFields.joinToString(" · ") { trpgFieldLabel(it, chinese) },
                                    style = MaterialTheme.typography.bodySmall)
                                if (event.id == undoableId) OutlinedButton(
                                    onClick = { onUndo(event.id) }, enabled = writable && !busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.journal.undo"),
                                ) { Text(if (chinese) "撤销此变更" else "Undo this change") }
                            }
                        }
                    }
                    if (hasMore) item {
                        TextButton(onClick = onLoadEarlier, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (chinese) "查看更早记录" else "Load earlier events")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (chinese) "关闭" else "Close") } },
    )
}

internal fun trpgEventTitle(event: TrpgEvent, chinese: Boolean): String = when (event.kind) {
    TrpgEventKind.ROLL -> event.title
    TrpgEventKind.EDIT -> if (chinese) "手动更新状态" else "State edited"
    TrpgEventKind.TOOL -> if (chinese) "主持结算" else "GM settlement"
    TrpgEventKind.UNDO -> if (chinese) "已撤销状态变更" else "State change undone"
    TrpgEventKind.CHECKPOINT -> if (chinese) "保存检查点" else "Checkpoint saved"
    TrpgEventKind.RESTORE -> if (chinese) "恢复检查点" else "Checkpoint restored"
}

private fun trpgFieldLabel(field: String, chinese: Boolean): String = if (!chinese) field else when (field) {
    "ruleset" -> "规则"
    "playerName" -> "玩家"
    "characterSheet" -> "角色卡"
    "scene" -> "场景"
    "notes" -> "笔记"
    "resources" -> "资源"
    else -> "团务资料"
}
