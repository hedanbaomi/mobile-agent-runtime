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

/* ------------------------------- turn/conditions ---------------------------- */

@Composable
internal fun TurnTab(
    party: TrpgParty, chinese: Boolean, gameMasterView: Boolean, writable: Boolean,
    onEditCondition: (TrpgCondition) -> Unit, onCreateCondition: () -> Unit,
    onRemoveCondition: (TrpgCondition) -> Unit, onStart: () -> Unit, onNext: () -> Unit, onEnd: () -> Unit,
) {
    val conditions = visiblePartyConditions(party, gameMasterView)
    Text(partyTurnLabel(party, gameMasterView, chinese), style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("trpg.party.turn"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = onStart, enabled = writable && party.encounter.order.isEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.encounter.start")) {
            Text(if (chinese) "开始先攻" else "Start encounter")
        }
        Button(onClick = onNext, enabled = writable && party.encounter.order.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.encounter.next")) {
            Text(if (chinese) "下一回合" else "Next turn")
        }
        TextButton(onClick = onEnd, enabled = writable && party.encounter.order.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.encounter.end")) {
            Text(if (chinese) "结束先攻" else "End encounter")
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "条件（${conditions.size}）" else "Conditions (${conditions.size})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onCreateCondition, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.condition.create")) {
            Text(if (chinese) "新增条件" else "New condition")
        }
    }
    conditions.forEach { condition ->
        val actor = party.actors.singleOrNull { it.id == condition.actorId }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("trpg.party.condition.${condition.id}")) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${actor?.name ?: condition.actorId} · ${condition.name}", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val remaining = condition.remainingRounds
                    Text(if (remaining == null) (if (chinese) "持续" else "ongoing") else if (remaining <= 0) (if (chinese) "已到期" else "expired") else (if (chinese) "$remaining 轮" else "$remaining rounds"),
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                if (condition.note.isNotBlank()) Text(condition.note.take(160), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onEditCondition(condition) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.condition.edit.${condition.id}")) {
                        Text(if (chinese) "编辑" else "Edit")
                    }
                    TextButton(onClick = { onRemoveCondition(condition) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.condition.remove.${condition.id}")) {
                        Text(if (chinese) "移除" else "Remove")
                    }
                }
            }
        }
    }
}
