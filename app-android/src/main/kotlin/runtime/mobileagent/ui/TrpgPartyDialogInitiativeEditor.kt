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
internal fun PartyInitiativeEditor(
    party: TrpgParty, gameMasterView: Boolean, chinese: Boolean, enabled: Boolean,
    revision: Long, reloadToken: Int, onReloadLatest: () -> Unit,
    onDismiss: () -> Unit, onSubmit: suspend (List<TrpgInitiative>) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    val candidates = party.actors.filter { gameMasterView || it.visibility == TrpgVisibility.PLAYER }
    var selected by remember(reloadToken) { mutableStateOf(candidates.map { it.id }.toSet()) }
    var scores by remember(reloadToken) { mutableStateOf(candidates.associate { it.id to "10" }) }
    var localError by remember(reloadToken) { mutableStateOf<String?>(null) }
    val canEdit = enabled && !submitting
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.testTag("trpg.party.initiative.editor"),
        title = { Text(if (chinese) "先攻" else "Initiative") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { actor ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = actor.id in selected, onClick = {
                            selected = if (actor.id in selected) selected - actor.id else selected + actor.id
                        }, enabled = canEdit, label = { Text(actor.name) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        OutlinedTextField(scores[actor.id].orEmpty(), { value -> scores = scores + (actor.id to value.take(10)) },
                            enabled = canEdit, singleLine = true, label = { Text("Score") }, modifier = Modifier.weight(0.7f).testTag("trpg.party.initiative.score.${actor.id}"))
                    }
                }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (localError != null) {
                    TextButton(onClick = onReloadLatest, enabled = !submitting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.initiative.reload")) {
                        Text(if (chinese) "载入最新" else "Reload latest")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val order = candidates.filter { it.id in selected }.mapNotNull { actor ->
                    scores[actor.id]?.toIntOrNull()?.let { TrpgInitiative(actor.id, it) }
                }
                if (order.size != selected.size) { localError = if (chinese) "先攻分数必须是整数" else "Scores must be integers"; return@Button }
                if (order.isEmpty()) { localError = if (chinese) "至少选择一个角色" else "Choose at least one actor"; return@Button }
                scope.launch {
                    submitting = true
                    try {
                        if (!onSubmit(order)) {
                            localError = if (chinese) "提交失败，草稿已保留；可载入最新后重试。" else
                                "Submit failed; draft kept. Reload latest and retry."
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        localError = failure.message
                    } finally { submitting = false }
                }
            }, enabled = canEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.initiative.apply")) {
                Text(if (chinese) "开始" else "Start")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chinese) "取消" else "Cancel") } },
    )
}
