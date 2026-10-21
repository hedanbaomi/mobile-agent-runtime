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

/* --------------------------------- surface --------------------------------- */

@Composable
internal fun TrpgPartyDialog(
    session: TrpgSession,
    writable: Boolean,
    saving: Boolean,
    error: String?,
    chinese: Boolean,
    onCommand: suspend (Long, TrpgCommand) -> TrpgSession,
    onFocusActor: suspend (Long, String?) -> TrpgSession,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val party = session.state.campaign.party
    val gameMasterView = session.state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER
    var busy by remember(session.conversationId) { mutableStateOf(false) }
    var localError by remember(session.conversationId) { mutableStateOf<String?>(null) }
    var tab by remember(session.conversationId) { mutableIntStateOf(0) }
    var editingActor by remember(session.conversationId) { mutableStateOf<TrpgActor?>(null) }
    var editingItem by remember(session.conversationId) { mutableStateOf<TrpgItem?>(null) }
    var consumingItem by remember(session.conversationId) { mutableStateOf<TrpgItem?>(null) }
    var editingCondition by remember(session.conversationId) { mutableStateOf<TrpgCondition?>(null) }
    var initiativeOpen by remember(session.conversationId) { mutableStateOf(false) }
    // Editors keep the revision they were opened at; a conflict must not auto-rebase onto newer state.
    var actorRevision by remember(session.conversationId) { mutableStateOf(0L) }
    var itemRevision by remember(session.conversationId) { mutableStateOf(0L) }
    var consumeRevision by remember(session.conversationId) { mutableStateOf(0L) }
    var conditionRevision by remember(session.conversationId) { mutableStateOf(0L) }
    var initiativeRevision by remember(session.conversationId) { mutableStateOf(0L) }
    var editorReloadToken by remember(session.conversationId) { mutableIntStateOf(0) }
    val blocked = busy || saving
    val editable = writable && !blocked

    suspend fun submitAt(revision: Long, command: TrpgCommand): Boolean {
        if (!editable) return false
        busy = true
        return try {
            onCommand(revision, command)
            localError = null
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: partyFailure(chinese)
            false
        } finally {
            busy = false
        }
    }

    suspend fun submit(command: TrpgCommand): Boolean = submitAt(session.revision, command)

    suspend fun focus(actorId: String?): Boolean {
        if (!editable) return false
        busy = true
        return try {
            onFocusActor(session.revision, actorId)
            localError = null
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: partyFailure(chinese)
            false
        } finally {
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!blocked) onDismiss() },
        modifier = Modifier.testTag("trpg.party.dialog"),
        title = { Text(if (chinese) "队伍与团务" else "Party & table", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                val problem = error ?: localError
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp).testTag("trpg.party.error"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PartyTab(0, tab, chinese, "角色队伍", "Party") { tab = it }
                    PartyTab(1, tab, chinese, "物品装备", "Items") { tab = it }
                    PartyTab(2, tab, chinese, "回合条件", "Turn") { tab = it }
                }
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        0 -> ActorsTab(
                            party = party, actors = visiblePartyActors(party, gameMasterView),
                            writable = editable, chinese = chinese,
                            focusActorId = session.state.campaign.preferences.focusActorId,
                            inEncounter = party.encounter.order.isNotEmpty(),
                            onEdit = { actorRevision = session.revision; editingActor = it },
                            onCreate = { actorRevision = session.revision; editingActor = TrpgActor(id = UUID.randomUUID().toString(), name = "") },
                            onRest = { actor, rest -> scope.launch { submit(partyCommand(TrpgPartyOperation.REST, id = actor.id, rest = rest)) } },
                            onFocus = { actorId -> scope.launch { focus(actorId) } },
                            onRemove = { actor -> scope.launch { submit(partyCommand(TrpgPartyOperation.REMOVE_ACTOR, id = actor.id)) } },
                            onQuickCheck = { actor, skill ->
                                scope.launch {
                                    submit(TrpgCommand(TrpgOperation.REQUEST_CHECK, check = TrpgCheckRequest(
                                        id = UUID.randomUUID().toString(), title = skill.name,
                                        expression = skill.expression, dc = skill.dc, cocSkill = skill.cocSkill,
                                        actorId = actor.id, visibility = actor.visibility,
                                    )))
                                }
                            },
                        )
                        1 -> ItemsTab(
                            party = party, items = visiblePartyItems(party, gameMasterView),
                            writable = editable, chinese = chinese,
                            onEdit = { itemRevision = session.revision; editingItem = it },
                            onCreate = { itemRevision = session.revision; editingItem = TrpgItem(id = UUID.randomUUID().toString(), name = "") },
                            onEquip = { item, equipped -> scope.launch { submit(partyCommand(TrpgPartyOperation.EQUIP_ITEM, id = item.id, equipped = equipped)) } },
                            onConsume = { consumeRevision = session.revision; consumingItem = it },
                            onRemove = { item -> scope.launch { submit(partyCommand(TrpgPartyOperation.REMOVE_ITEM, id = item.id)) } },
                        )
                        else -> TurnTab(
                            party = party, chinese = chinese, gameMasterView = gameMasterView,
                            writable = editable,
                            onEditCondition = { conditionRevision = session.revision; editingCondition = it },
                            onCreateCondition = { conditionRevision = session.revision; editingCondition = TrpgCondition(id = UUID.randomUUID().toString(), actorId = visiblePartyActors(party, gameMasterView).firstOrNull()?.id.orEmpty(), name = "") },
                            onRemoveCondition = { condition -> scope.launch { submit(partyCommand(TrpgPartyOperation.REMOVE_CONDITION, id = condition.id)) } },
                            onStart = { initiativeRevision = session.revision; initiativeOpen = true },
                            onNext = { scope.launch { submit(partyCommand(TrpgPartyOperation.NEXT_TURN)) } },
                            onEnd = { scope.launch { submit(partyCommand(TrpgPartyOperation.END_ENCOUNTER)) } },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.close")) {
                Text(if (chinese) "关闭" else "Close")
            }
        },
        dismissButton = null,
    )

    editingActor?.let { actor ->
        PartyActorEditor(initial = actor, chinese = chinese, enabled = editable,
            revision = actorRevision, reloadToken = editorReloadToken,
            onReloadLatest = {
                val current = session.state.campaign.party.actors.singleOrNull { it.id == actor.id }
                if (current == null) { localError = if (chinese) "角色已不存在" else "Actor no longer exists"; editingActor = null }
                else { editingActor = current; actorRevision = session.revision; editorReloadToken++ }
            },
            onDismiss = { editingActor = null },
            onSubmit = { updated ->
                val ok = submitAt(actorRevision, partyCommand(TrpgPartyOperation.UPSERT_ACTOR, actor = updated))
                if (ok) editingActor = null
                ok
            })
    }
    editingItem?.let { item ->
        PartyItemEditor(initial = item, party = party, gameMasterView = gameMasterView, chinese = chinese, enabled = editable,
            revision = itemRevision, reloadToken = editorReloadToken,
            onReloadLatest = {
                val current = session.state.campaign.party.items.singleOrNull { it.id == item.id }
                if (current == null) { localError = if (chinese) "物品已不存在" else "Item no longer exists"; editingItem = null }
                else { editingItem = current; itemRevision = session.revision; editorReloadToken++ }
            },
            onDismiss = { editingItem = null },
            onSubmit = { updated ->
                val ok = submitAt(itemRevision, partyCommand(TrpgPartyOperation.UPSERT_ITEM, item = updated))
                if (ok) editingItem = null
                ok
            })
    }
    consumingItem?.let { item ->
        PartyConsumeDialog(item = item, chinese = chinese, enabled = editable,
            revision = consumeRevision, reloadToken = editorReloadToken,
            onReloadLatest = {
                val current = session.state.campaign.party.items.singleOrNull { it.id == item.id }
                if (current == null) { localError = if (chinese) "物品已不存在" else "Item no longer exists"; consumingItem = null }
                else { consumingItem = current; consumeRevision = session.revision; editorReloadToken++ }
            },
            onDismiss = { consumingItem = null },
            onSubmit = { count ->
                val ok = submitAt(consumeRevision, partyCommand(TrpgPartyOperation.CONSUME_ITEM, id = item.id, amount = count))
                if (ok) consumingItem = null
                ok
            })
    }
    editingCondition?.let { condition ->
        PartyConditionEditor(initial = condition, party = party, gameMasterView = gameMasterView, chinese = chinese, enabled = editable,
            revision = conditionRevision, reloadToken = editorReloadToken,
            onReloadLatest = {
                val current = session.state.campaign.party.conditions.singleOrNull { it.id == condition.id }
                if (current == null) { localError = if (chinese) "条件已不存在" else "Condition no longer exists"; editingCondition = null }
                else { editingCondition = current; conditionRevision = session.revision; editorReloadToken++ }
            },
            onDismiss = { editingCondition = null },
            onSubmit = { updated ->
                val ok = submitAt(conditionRevision, partyCommand(TrpgPartyOperation.UPSERT_CONDITION, condition = updated))
                if (ok) editingCondition = null
                ok
            })
    }
    if (initiativeOpen) {
        PartyInitiativeEditor(party = party, gameMasterView = gameMasterView, chinese = chinese, enabled = editable,
            revision = initiativeRevision, reloadToken = editorReloadToken,
            onReloadLatest = { initiativeRevision = session.revision; editorReloadToken++ },
            onDismiss = { initiativeOpen = false },
            onSubmit = { order ->
                val ok = submitAt(initiativeRevision, partyCommand(TrpgPartyOperation.START_ENCOUNTER, initiative = order))
                if (ok) initiativeOpen = false
                ok
            })
    }
}

@Composable
private fun PartyTab(index: Int, selected: Int, chinese: Boolean, zh: String, en: String, onSelect: (Int) -> Unit) {
    FilterChip(selected = selected == index, onClick = { onSelect(index) },
        label = { Text(if (chinese) zh else en) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.party.tab.$index"))
}

