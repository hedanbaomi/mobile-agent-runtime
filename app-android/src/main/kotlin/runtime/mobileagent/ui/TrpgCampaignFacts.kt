// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.trpg.TrpgCheck
import runtime.mobileagent.domain.trpg.TrpgCheckRequest
import runtime.mobileagent.domain.trpg.TrpgCheckStatus
import runtime.mobileagent.domain.trpg.TrpgCampaign
import runtime.mobileagent.domain.trpg.TrpgCommand
import runtime.mobileagent.domain.trpg.TrpgDiceMode
import runtime.mobileagent.domain.trpg.TrpgFact
import runtime.mobileagent.domain.trpg.TrpgFactStatus
import runtime.mobileagent.domain.trpg.TrpgOperation
import runtime.mobileagent.domain.trpg.TrpgPreferences
import runtime.mobileagent.domain.trpg.TrpgResponseStyle
import runtime.mobileagent.domain.trpg.TrpgRuleset
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgStateValidation
import runtime.mobileagent.domain.trpg.TrpgViewRole
import runtime.mobileagent.domain.trpg.TrpgVisibility

/* ---------------------------------- facts --------------------------------- */

@Composable
internal fun FactsTab(
    facts: List<TrpgFact>, writable: Boolean, chinese: Boolean,
    onSource: (String) -> Unit, onEdit: (TrpgFact) -> Unit, onRemove: (TrpgFact) -> Unit, onCreate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "事实（${facts.size}）" else "Facts (${facts.size})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onCreate, enabled = writable,
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.fact.create")) {
            Text(if (chinese) "新增事实" else "New fact")
        }
    }
    if (facts.isEmpty()) Text(if (chinese) "暂无事实" else "No facts yet", style = MaterialTheme.typography.bodySmall)
    facts.forEach { fact ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fact.text, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(if (chinese) factStatusZh(fact.status) else fact.status.name,
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                if (fact.sourceMessageId != null) {
                    TextButton(onClick = { fact.sourceMessageId?.let(onSource) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.fact.source.${fact.id}")) {
                        Text(if (chinese) "查看来源" else "View source")
                    }
                } else if (fact.sourceNote.isNotBlank()) {
                    Text("${if (chinese) "备注" else "Note"}: ${fact.sourceNote}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onEdit(fact) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.fact.edit.${fact.id}")) {
                        Text(if (chinese) "修正" else "Edit")
                    }
                    TextButton(onClick = { onRemove(fact) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.fact.remove.${fact.id}")) {
                        Text(if (chinese) "删除" else "Delete")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrpgFactEditor(
    initial: TrpgFact, recentMessages: List<Message>, chinese: Boolean, enabled: Boolean,
    onDismiss: () -> Unit, onSubmit: suspend (TrpgFact) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id) { mutableStateOf(false) }
    var text by remember(initial.id) { mutableStateOf(initial.text) }
    var status by remember(initial.id) { mutableStateOf(initial.status) }
    var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
    var sourceId by remember(initial.id) { mutableStateOf(initial.sourceMessageId) }
    var note by remember(initial.id) { mutableStateOf(initial.sourceNote) }
    var localError by remember(initial.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trpg.campaign.fact.editor"),
        title = { Text(if (chinese) "事实" else "Fact") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it.take(2048) }, enabled = enabled, minLines = 2, maxLines = 5,
                    label = { Text(if (chinese) "事实内容 (≤2048)" else "Fact text (≤2048)") },
                    modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.fact.text"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrpgFactStatus.entries.forEach { option ->
                        FilterChip(selected = status == option, onClick = { status = option }, enabled = enabled,
                            label = { Text(if (chinese) factStatusZh(option) else option.name) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = visibility == TrpgVisibility.PLAYER, onClick = { visibility = TrpgVisibility.PLAYER },
                        enabled = enabled, label = { Text(if (chinese) "玩家可见" else "Player visible") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = visibility == TrpgVisibility.GAME_MASTER, onClick = { visibility = TrpgVisibility.GAME_MASTER },
                        enabled = enabled, label = { Text(if (chinese) "主持秘密" else "GM secret") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                Text(if (chinese) "来源（选择真实消息或仅备注）" else "Source (a real message or a note)",
                    style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = sourceId == null, onClick = { sourceId = null }, enabled = enabled,
                        label = { Text(if (chinese) "仅备注" else "Note only") }, modifier = Modifier.heightIn(min = 48.dp))
                    recentMessages.takeLast(8).forEach { message ->
                        FilterChip(selected = sourceId == message.id, onClick = { sourceId = message.id }, enabled = enabled,
                            label = { Text("#${message.role.name.lowercase().take(4)} ${message.text.take(24)}", maxLines = 1) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.fact.source.option.${message.id}"))
                    }
                }
                OutlinedTextField(note, { note = it.take(512) }, enabled = enabled, singleLine = true,
                    label = { Text(if (chinese) "来源备注 (≤512)" else "Source note (≤512)") },
                    modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.fact.note"))
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (text.isBlank()) {
                    localError = if (chinese) "事实不能为空" else "Fact text is required"; return@Button
                }
                val updated = initial.copy(text = text.trim(), status = status, visibility = visibility,
                    sourceMessageId = sourceId, sourceNote = note.trim())
                scope.launch {
                    submitting = true
                    try { onSubmit(updated) } finally { submitting = false }
                }
            }, enabled = enabled && !submitting,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.fact.apply")) {
                Text(if (chinese) "应用" else "Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

/* ---------------------------------- recap --------------------------------- */

@Composable
internal fun RecapTab(
    session: TrpgSession, draft: TrpgState, facts: List<TrpgFact>, recentMessages: List<Message>,
    chinese: Boolean, onSource: (String) -> Unit, onOpenJournal: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "续团回顾" else "Recap", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpenJournal, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.journal")) {
            Text(if (chinese) "打开日志" else "Open journal")
        }
    }
    Text(if (chinese) "当前场景" else "Current scene", style = MaterialTheme.typography.labelMedium)
    Text(draft.scene.ifBlank { if (chinese) "（未填写）" else "(unset)" }, style = MaterialTheme.typography.bodyMedium)
    Text(if (chinese) "已确认且可见的事实" else "Confirmed, visible facts", style = MaterialTheme.typography.labelMedium)
    val confirmed = facts.filter { it.status == TrpgFactStatus.CONFIRMED }
    if (confirmed.isEmpty()) Text(if (chinese) "暂无" else "None", style = MaterialTheme.typography.bodySmall)
    confirmed.take(12).forEach { fact -> Text("• ${fact.text}", style = MaterialTheme.typography.bodySmall) }
    Text(if (chinese) "最近消息（原文）" else "Recent messages (verbatim)", style = MaterialTheme.typography.labelMedium)
    recentMessages.takeLast(6).forEach { message ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${message.role.name.lowercase()}: ${message.text.take(120)}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { onSource(message.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (chinese) "查看" else "Open")
            }
        }
    }
}

internal fun factStatusZh(status: TrpgFactStatus): String = when (status) {
    TrpgFactStatus.PROPOSED -> "待确认"
    TrpgFactStatus.CONFIRMED -> "已确认"
    TrpgFactStatus.CORRECTED -> "已修正"
}
