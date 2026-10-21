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

/* ---------------------------------- items ---------------------------------- */

@Composable
internal fun ItemsTab(
    party: TrpgParty, items: List<TrpgItem>, writable: Boolean, chinese: Boolean,
    onEdit: (TrpgItem) -> Unit, onCreate: () -> Unit,
    onEquip: (TrpgItem, Boolean) -> Unit, onConsume: (TrpgItem) -> Unit, onRemove: (TrpgItem) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "物品（${items.size}）" else "Items (${items.size})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onCreate, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.create")) {
            Text(if (chinese) "新建物品" else "New item")
        }
    }
    if (items.isEmpty()) Text(if (chinese) "暂无物品" else "No items", style = MaterialTheme.typography.bodySmall)
    items.forEach { item ->
        val owner = party.actors.singleOrNull { it.id == item.ownerId }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("trpg.party.item.${item.id}")) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("×${item.quantity}${if (item.consumable) " · C" else ""}${if (item.equipped) " · E" else ""}",
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                Text((owner?.name ?: (if (chinese) "队伍仓库" else "Party stash")) + item.effects.joinToString("") { " · ${it.resource}${if (it.amount >= 0) "+" else ""}${it.amount}" },
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onEdit(item) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.edit.${item.id}")) {
                        Text(if (chinese) "编辑" else "Edit")
                    }
                    if (item.ownerId != null) TextButton(onClick = { onEquip(item, !item.equipped) }, enabled = writable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.equip.${item.id}")) {
                        Text(if (item.equipped) (if (chinese) "卸下" else "Unequip") else (if (chinese) "装备" else "Equip"))
                    }
                    if (item.consumable) TextButton(onClick = { onConsume(item) }, enabled = writable && item.quantity > 0,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.consume.${item.id}")) {
                        Text(if (chinese) "消耗" else "Consume")
                    }
                    TextButton(onClick = { onRemove(item) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.item.remove.${item.id}")) {
                        Text(if (chinese) "删除" else "Remove")
                    }
                }
            }
        }
    }
}
