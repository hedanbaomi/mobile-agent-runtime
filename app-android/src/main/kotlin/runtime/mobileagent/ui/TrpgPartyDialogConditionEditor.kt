// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import runtime.mobileagent.domain.trpg.TrpgActor
import runtime.mobileagent.domain.trpg.TrpgActorKind
import runtime.mobileagent.domain.trpg.TrpgCheckRequest
import runtime.mobileagent.domain.trpg.TrpgCommand
import runtime.mobileagent.domain.trpg.TrpgCondition
import runtime.mobileagent.domain.trpg.TrpgEncounter
import runtime.mobileagent.domain.trpg.TrpgInitiative
import runtime.mobileagent.domain.trpg.TrpgItem
import runtime.mobileagent.domain.trpg.TrpgOperation
import runtime.mobileagent.domain.trpg.TrpgParty
import runtime.mobileagent.domain.trpg.TrpgPartyCommand
import runtime.mobileagent.domain.trpg.TrpgPartyOperation
import runtime.mobileagent.domain.trpg.TrpgPartyValidation
import runtime.mobileagent.domain.trpg.TrpgRecovery
import runtime.mobileagent.domain.trpg.TrpgResource
import runtime.mobileagent.domain.trpg.TrpgResourceDelta
import runtime.mobileagent.domain.trpg.TrpgRest
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgSkill
import runtime.mobileagent.domain.trpg.TrpgViewRole
import runtime.mobileagent.domain.trpg.TrpgVisibility

@Composable
internal fun PartyConditionEditor(
    initial: TrpgCondition, party: TrpgParty, gameMasterView: Boolean, chinese: Boolean, enabled: Boolean,
    revision: Long, reloadToken: Int, onReloadLatest: () -> Unit,
    onDismiss: () -> Unit, onSubmit: suspend (TrpgCondition) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id, reloadToken) { mutableStateOf(false) }
    var condition by remember(initial.id, reloadToken) { mutableStateOf(initial) }
    var roundsText by remember(initial.id, reloadToken) { mutableStateOf(initial.remainingRounds?.toString().orEmpty()) }
    var localError by remember(initial.id, reloadToken) { mutableStateOf<String?>(null) }
    val canEdit = enabled && !submitting
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.testTag("trpg.party.condition.editor"),
        title = { Text(if (chinese) "条件" else "Condition") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (chinese) "角色" else "Actor", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    party.actors.filter { gameMasterView || it.visibility == TrpgVisibility.PLAYER }.forEach { actor ->
                        FilterChip(selected = condition.actorId == actor.id, onClick = { condition = condition.copy(actorId = actor.id) },
                            enabled = canEdit, label = { Text(actor.name) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedTextField(condition.name, { condition = condition.copy(name = it.take(128)) }, enabled = canEdit, singleLine = true,
                    label = { Text(if (chinese) "名称" else "Name") }, modifier = Modifier.fillMaxWidth().testTag("trpg.party.condition.name"))
                OutlinedTextField(condition.note, { condition = condition.copy(note = it.take(2048)) }, enabled = canEdit, minLines = 2, maxLines = 4,
                    label = { Text(if (chinese) "备注" else "Note") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(roundsText, { roundsText = it.take(10) }, enabled = canEdit, singleLine = true,
                    label = { Text(if (chinese) "剩余轮数（可空）" else "Remaining rounds (optional)") }, modifier = Modifier.fillMaxWidth().testTag("trpg.party.condition.rounds"))
                Text(if (chinese) "休息结束策略" else "Ends on rest", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TrpgRest.entries.forEach { rest ->
                        FilterChip(selected = condition.endsOnRest == rest, onClick = { condition = condition.copy(endsOnRest = rest) },
                            enabled = canEdit, label = { Text(if (chinese) restText(rest) else rest.name.lowercase()) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = condition.visibility == TrpgVisibility.PLAYER, onClick = { condition = condition.copy(visibility = TrpgVisibility.PLAYER) },
                        enabled = canEdit, label = { Text(if (chinese) "玩家可见" else "Player") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = condition.visibility == TrpgVisibility.GAME_MASTER, onClick = { condition = condition.copy(visibility = TrpgVisibility.GAME_MASTER) },
                        enabled = canEdit, label = { Text(if (chinese) "主持秘密" else "GM") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (localError != null) {
                    TextButton(onClick = onReloadLatest, enabled = !submitting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.condition.reload")) {
                        Text(if (chinese) "载入最新" else "Reload latest")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (condition.name.isBlank()) { localError = if (chinese) "名称不能为空" else "Name is required"; return@Button }
                if (condition.actorId.isBlank()) { localError = if (chinese) "请选择角色" else "Choose an actor"; return@Button }
                val rounds = roundsText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                if (roundsText.trim().isNotEmpty() && (rounds == null || rounds < 0)) {
                    localError = if (chinese) "轮数必须是非负整数" else "Rounds must be a non-negative integer"; return@Button
                }
                val updated = condition.copy(remainingRounds = rounds)
                scope.launch {
                    submitting = true
                    try {
                        if (!onSubmit(updated)) {
                            localError = if (chinese) "提交失败，草稿已保留；可载入最新后重试。" else
                                "Submit failed; draft kept. Reload latest and retry."
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        localError = failure.message
                    } finally { submitting = false }
                }
            }, enabled = canEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.condition.apply")) {
                Text(if (chinese) "应用" else "Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chinese) "取消" else "Cancel") } },
    )
}
