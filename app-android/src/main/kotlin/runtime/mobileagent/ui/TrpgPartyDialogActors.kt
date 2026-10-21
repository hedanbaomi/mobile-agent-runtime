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

/* --------------------------------- actors ---------------------------------- */

@Composable
internal fun ActorsTab(
    party: TrpgParty, actors: List<TrpgActor>, writable: Boolean, chinese: Boolean,
    focusActorId: String?, inEncounter: Boolean,
    onEdit: (TrpgActor) -> Unit, onCreate: () -> Unit, onRest: (TrpgActor, TrpgRest) -> Unit,
    onFocus: (String?) -> Unit, onRemove: (TrpgActor) -> Unit, onQuickCheck: (TrpgActor, TrpgSkill) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "角色（${actors.size}）" else "Actors (${actors.size})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onCreate, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.actor.create")) {
            Text(if (chinese) "新建角色" else "New actor")
        }
    }
    if (actors.isEmpty()) Text(if (chinese) "暂无角色" else "No actors", style = MaterialTheme.typography.bodySmall)
    actors.forEach { actor ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("trpg.party.actor.${actor.id}")) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(actor.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (actor.visibility == TrpgVisibility.GAME_MASTER) (if (chinese) "主持" else "GM") else actorKindText(actor.kind, chinese),
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                if (actor.description.isNotBlank()) Text(actor.description.take(160), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (actor.resources.isNotEmpty()) {
                    Text(actor.resources.joinToString(" · ") { "${it.name} ${it.current}${it.maximum?.let { m -> "/$m" }.orEmpty()}" },
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    actor.skills.forEach { skill ->
                        OutlinedButton(onClick = { onQuickCheck(actor, skill) }, enabled = writable,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.skill.${actor.id}.${skill.id}")) {
                            Text("${skill.name}${skill.cocSkill?.let { " $it" } ?: skill.dc?.let { " DC$it" } ?: ""}")
                        }
                    }
                }
                if (actor.recovery.isNotEmpty()) {
                    Text((if (chinese) "休息预览：" else "Rest preview: ") + restPreview(actor, TrpgRest.SHORT, chinese),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { onRest(actor, TrpgRest.SHORT) }, enabled = writable && !inEncounter,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.rest.short.${actor.id}")) {
                        Text(if (chinese) "短休" else "Short rest")
                    }
                    Button(onClick = { onRest(actor, TrpgRest.LONG) }, enabled = writable && !inEncounter,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.rest.long.${actor.id}")) {
                        Text(if (chinese) "长休" else "Long rest")
                    }
                    TextButton(onClick = { onEdit(actor) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.actor.edit.${actor.id}")) {
                        Text(if (chinese) "编辑" else "Edit")
                    }
                    TextButton(onClick = { onFocus(if (focusActorId == actor.id) null else actor.id) }, enabled = writable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.focus.${actor.id}")) {
                        Text(if (focusActorId == actor.id) (if (chinese) "回到状态板" else "Unfocus") else (if (chinese) "设为HUD人物" else "Show in HUD"))
                    }
                    TextButton(onClick = { onRemove(actor) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.actor.remove.${actor.id}")) {
                        Text(if (chinese) "删除" else "Remove")
                    }
                }
                if (inEncounter) Text(if (chinese) "战斗中不能休息；先结束遭遇。" else "Cannot rest during an encounter.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
