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
internal fun PartyItemEditor(
    initial: TrpgItem, party: TrpgParty, gameMasterView: Boolean, chinese: Boolean, enabled: Boolean,
    revision: Long, reloadToken: Int, onReloadLatest: () -> Unit,
    onDismiss: () -> Unit, onSubmit: suspend (TrpgItem) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id, reloadToken) { mutableStateOf(false) }
    var item by remember(initial.id, reloadToken) { mutableStateOf(initial) }
    var ownerId by remember(initial.id, reloadToken) { mutableStateOf(initial.ownerId) }
    var effects by remember(initial.id, reloadToken) { mutableStateOf(initial.effects) }
    var effectAmounts by remember(initial.id, reloadToken) { mutableStateOf(initial.effects.map { it.amount.toString() }) }
    var quantityText by remember(initial.id, reloadToken) { mutableStateOf(initial.quantity.toString()) }
    var localError by remember(initial.id, reloadToken) { mutableStateOf<String?>(null) }
    val canEdit = enabled && !submitting
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.testTag("trpg.party.item.editor"),
        title = { Text(if (chinese) "物品编辑" else "Item") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(item.name, { item = item.copy(name = it.take(128)) }, enabled = canEdit, singleLine = true,
                    label = { Text(if (chinese) "名称" else "Name") }, modifier = Modifier.fillMaxWidth().testTag("trpg.party.item.name"))
                OutlinedTextField(item.description, { item = item.copy(description = it.take(2048)) }, enabled = canEdit, minLines = 2, maxLines = 4,
                    label = { Text(if (chinese) "描述" else "Description") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(quantityText, { value -> quantityText = value.take(10) }, enabled = canEdit, singleLine = true,
                    label = { Text(if (chinese) "数量" else "Quantity") }, modifier = Modifier.fillMaxWidth().testTag("trpg.party.item.quantity"))
                Text(if (chinese) "所属" else "Owner", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = ownerId == null, onClick = { ownerId = null }, enabled = canEdit,
                        label = { Text(if (chinese) "队伍仓库" else "Party stash") }, modifier = Modifier.heightIn(min = 48.dp))
                    party.actors.filter { gameMasterView || it.visibility == TrpgVisibility.PLAYER }.forEach { actor ->
                        FilterChip(selected = ownerId == actor.id, onClick = { ownerId = actor.id }, enabled = canEdit,
                            label = { Text(actor.name) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (chinese) "已装备（需有主且有余量）" else "Equipped (needs owner & stock)", modifier = Modifier.weight(1f))
                    Switch(checked = item.equipped, onCheckedChange = { item = item.copy(equipped = it) }, enabled = canEdit)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (chinese) "消耗品" else "Consumable", modifier = Modifier.weight(1f))
                    Switch(checked = item.consumable, onCheckedChange = { item = item.copy(consumable = it) }, enabled = canEdit)
                }
                Text(if (chinese) "可见性" else "Visibility", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = item.visibility == TrpgVisibility.PLAYER, onClick = { item = item.copy(visibility = TrpgVisibility.PLAYER) },
                        enabled = canEdit, label = { Text(if (chinese) "玩家可见" else "Player") }, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.visibility.player"))
                    FilterChip(selected = item.visibility == TrpgVisibility.GAME_MASTER, onClick = { item = item.copy(visibility = TrpgVisibility.GAME_MASTER) },
                        enabled = canEdit, label = { Text(if (chinese) "主持秘密" else "GM") }, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.visibility.gm"))
                }
                Text(if (chinese) "资源效果（仅消耗品）" else "Resource effects (consumables)", style = MaterialTheme.typography.labelMedium)
                effects.forEachIndexed { index, effect ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(effect.resource, { value -> effects = effects.toMutableList().also { it[index] = effect.copy(resource = value.take(64)) } },
                            enabled = canEdit, singleLine = true, label = { Text(if (chinese) "资源" else "Resource") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(effectAmounts.getOrElse(index) { "" }, { value ->
                            effectAmounts = effectAmounts.toMutableList().also { it[index] = value }
                        }, enabled = canEdit, singleLine = true, label = { Text("Δ") }, modifier = Modifier.weight(0.6f).testTag("trpg.party.item.effect.amount.${effect.resource}"))
                        TextButton(onClick = {
                            effects = effects.filterIndexed { i, _ -> i != index }
                            effectAmounts = effectAmounts.filterIndexed { i, _ -> i != index }
                        }, enabled = canEdit,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("✕") }
                    }
                }
                TextButton(onClick = {
                    if (effects.size < 16) {
                        effects = effects + TrpgResourceDelta("", 0)
                        effectAmounts = effectAmounts + "0"
                    }
                },
                    enabled = canEdit && effects.size < 16, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (chinese) "+ 效果" else "+ Effect")
                }
                localError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (localError != null) {
                    TextButton(onClick = onReloadLatest, enabled = !submitting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.reload")) {
                        Text(if (chinese) "载入最新" else "Reload latest")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val quantity = quantityText.toIntOrNull()
                if (quantity == null || quantity !in 0..1_000_000) {
                    localError = if (chinese) "数量必须是 0..1000000" else "Quantity must be 0..1000000"; return@Button
                }
                val parsedEffects = effects.mapIndexed { index, effect ->
                    val text = effectAmounts.getOrElse(index) { "" }.trim()
                    val amount = text.toIntOrNull()
                    if (text.isEmpty() || amount == null) {
                        localError = if (chinese) "效果 Δ 必须是整数（可负）" else "Effect delta must be an integer (negative allowed)"
                        return@Button
                    }
                    effect.copy(amount = amount)
                }
                val updated = item.copy(ownerId = ownerId, quantity = quantity, effects = parsedEffects, equipped = item.equipped && ownerId != null && quantity > 0)
                scope.launch {
                    submitting = true
                    try {
                        try {
                            TrpgPartyValidation.validate(TrpgParty(items = listOf(updated), actors = party.actors))
                        } catch (failure: Exception) {
                            localError = failure.message
                            return@launch
                        }
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
            }, enabled = canEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.apply")) {
                Text(if (chinese) "应用" else "Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (chinese) "取消" else "Cancel") } },
    )
}
