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

/* --------------------------------- checks --------------------------------- */

@Composable
internal fun ChecksTab(
    checks: List<TrpgCheck>, writable: Boolean, chinese: Boolean,
    notes: Map<String, String>,
    onNote: (String, String) -> Unit,
    onRoll: (String) -> Unit,
    onResolve: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit,
    onInsert: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "检定（${checks.size}）" else "Checks (${checks.size})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onCreate, enabled = writable,
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.check.create")) {
            Text(if (chinese) "新建检定" else "New check")
        }
    }
    if (checks.isEmpty()) Text(if (chinese) "暂无检定" else "No checks yet", style = MaterialTheme.typography.bodySmall)
    checks.forEach { check ->
        val request = check.request
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(request.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (chinese) statusZh(check.status) else check.status.name,
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                Text(
                    if (request.cocSkill != null) "CoC ${request.cocSkill}" else "${request.expression}${request.dc?.let { " · DC $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                check.roll?.let { roll ->
                    Text(roll.summary, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("trpg.campaign.check.summary.${request.id}"))
                }
                if (check.resolution.isNotBlank()) Text(check.resolution, style = MaterialTheme.typography.bodySmall)
                when (check.status) {
                    TrpgCheckStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { onRoll(request.id) }, enabled = writable,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.check.roll.${request.id}")) {
                            Text(if (chinese) "掷骰" else "Roll")
                        }
                        TextButton(onClick = { onCancel(request.id) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (chinese) "取消" else "Cancel")
                        }
                    }
                    TrpgCheckStatus.ROLLED -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = notes[request.id].orEmpty(),
                            onValueChange = { onNote(request.id, it.take(2048)) },
                            enabled = writable, singleLine = true,
                            label = { Text(if (chinese) "结算说明（必填）" else "Resolution note (required)") },
                            modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.check.note.${request.id}"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { onResolve(request.id) },
                                enabled = writable && notes[request.id].orEmpty().isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.check.resolve.${request.id}")) {
                                Text(if (chinese) "结算" else "Resolve")
                            }
                            TextButton(onClick = {
                                val text = buildString {
                                    append(if (chinese) "【检定】" else "[Check] "); append(request.title)
                                    check.roll?.summary?.let { append("：").append(it) }
                                    notes[request.id]?.takeIf { it.isNotBlank() }?.let { append("；").append(it) }
                                }
                                onInsert(text)
                            }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.check.insert.${request.id}")) {
                                Text(if (chinese) "填入输入" else "To input")
                            }
                        }
                    }
                    else -> TextButton(onClick = { onArchive(request.id) }, enabled = writable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.check.archive.${request.id}")) {
                        Text(if (chinese) "归档检定（保留日志）" else "Archive check (keep journal)")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrpgCheckEditor(
    initial: TrpgCheckRequest, chinese: Boolean, enabled: Boolean,
    onDismiss: () -> Unit, onSubmit: suspend (TrpgCheckRequest) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id) { mutableStateOf(false) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var coc by remember(initial.id) { mutableStateOf(initial.cocSkill != null) }
    var expression by remember(initial.id) { mutableStateOf(if (initial.cocSkill != null) "1d100" else initial.expression) }
    var dc by remember(initial.id) { mutableStateOf(initial.dc?.toString().orEmpty()) }
    var skill by remember(initial.id) { mutableStateOf(initial.cocSkill?.toString() ?: "60") }
    var bonus by remember(initial.id) { mutableIntStateOf(initial.bonusDice) }
    var penalty by remember(initial.id) { mutableIntStateOf(initial.penaltyDice) }
    var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
    var localError by remember(initial.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trpg.campaign.check.editor"),
        title = { Text(if (chinese) "新建检定" else "New check") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it.take(128) }, enabled = enabled, singleLine = true,
                    label = { Text(if (chinese) "检定名称" else "Title") }, modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.check.title"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !coc, onClick = { coc = false }, enabled = enabled,
                        label = { Text(if (chinese) "标准表达式" else "Expression") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = coc, onClick = { coc = true }, enabled = enabled,
                        label = { Text(if (chinese) "CoC 技能" else "CoC skill") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                if (!coc) {
                    OutlinedTextField(expression, { expression = it.take(256) }, enabled = enabled, singleLine = true,
                        label = { Text(if (chinese) "表达式（如 2d20kh1+5）" else "Expression (e.g. 2d20kh1+5)") },
                        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.check.expression"))
                    OutlinedTextField(dc, { dc = it }, enabled = enabled, singleLine = true,
                        label = { Text(if (chinese) "DC（可空）" else "DC (optional)") },
                        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.check.dc"))
                } else {
                    OutlinedTextField(skill, { skill = it }, enabled = enabled, singleLine = true,
                        label = { Text(if (chinese) "技能值 1..100" else "Skill 1..100") },
                        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.check.skill"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (chinese) "奖励骰" else "Bonus", modifier = Modifier.weight(1f))
                        (0..2).forEach { count ->
                            FilterChip(selected = bonus == count && penalty == 0, onClick = { bonus = count; penalty = 0 },
                                enabled = enabled, label = { Text("$count") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (chinese) "惩罚骰" else "Penalty", modifier = Modifier.weight(1f))
                        (0..2).forEach { count ->
                            FilterChip(selected = penalty == count && bonus == 0, onClick = { penalty = count; bonus = 0 },
                                enabled = enabled, label = { Text("$count") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = visibility == TrpgVisibility.PLAYER, onClick = { visibility = TrpgVisibility.PLAYER },
                        enabled = enabled, label = { Text(if (chinese) "玩家可见" else "Player visible") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = visibility == TrpgVisibility.GAME_MASTER, onClick = { visibility = TrpgVisibility.GAME_MASTER },
                        enabled = enabled, label = { Text(if (chinese) "主持秘密" else "GM secret") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedDc = dc.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                if (dc.trim().isNotEmpty() && parsedDc == null) {
                    localError = if (chinese) "DC 必须是整数" else "DC must be an integer"; return@Button
                }
                val parsedSkill = skill.trim().toIntOrNull()
                if (coc && parsedSkill == null) {
                    localError = if (chinese) "技能必须是 1..100" else "Skill must be 1..100"; return@Button
                }
                val request = TrpgCheckRequest(
                    id = initial.id,
                    title = title.trim(),
                    expression = if (coc) "1d100" else expression.trim(),
                    dc = if (coc) null else parsedDc,
                    cocSkill = if (coc) parsedSkill else null,
                    bonusDice = if (coc) bonus else 0,
                    penaltyDice = if (coc) penalty else 0,
                    visibility = visibility,
                )
                scope.launch {
                    submitting = true
                    try { onSubmit(request) } finally { submitting = false }
                }
            }, enabled = enabled && !submitting,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.check.create.confirm")) {
                Text(if (chinese) "创建" else "Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chinese) "取消" else "Cancel") } },
    )
}

internal fun statusZh(status: TrpgCheckStatus): String = when (status) {
    TrpgCheckStatus.PENDING -> "待掷骰"
    TrpgCheckStatus.ROLLED -> "已掷骰"
    TrpgCheckStatus.RESOLVED -> "已结算"
    TrpgCheckStatus.CANCELLED -> "已取消"
}

