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

/* ------------------------------- pure helpers ------------------------------- */

internal fun visiblePartyActors(party: TrpgParty, gameMasterView: Boolean): List<TrpgActor> =
    if (gameMasterView) party.actors else party.actors.filter { it.visibility == TrpgVisibility.PLAYER }

internal fun visiblePartyItems(party: TrpgParty, gameMasterView: Boolean): List<TrpgItem> {
    val hiddenOwners = party.actors.filter { it.visibility == TrpgVisibility.GAME_MASTER }.map { it.id }.toSet()
    return party.items.filter { item ->
        gameMasterView || (item.visibility == TrpgVisibility.PLAYER && item.ownerId !in hiddenOwners)
    }
}

internal fun visiblePartyConditions(party: TrpgParty, gameMasterView: Boolean): List<TrpgCondition> {
    val hiddenActors = party.actors.filter { it.visibility == TrpgVisibility.GAME_MASTER }.map { it.id }.toSet()
    return party.conditions.filter { condition ->
        gameMasterView || (condition.visibility == TrpgVisibility.PLAYER && condition.actorId !in hiddenActors)
    }
}

/** Player-facing turn label; hidden participants collapse to "主持人回合" without counts/scores. */
internal fun partyTurnLabel(party: TrpgParty, gameMasterView: Boolean, chinese: Boolean): String {
    val encounter = party.encounter
    if (encounter.order.isEmpty()) return if (chinese) "未开始先攻" else "No encounter"
    val currentId = encounter.order.getOrNull(encounter.turn)?.actorId
    val actor = party.actors.singleOrNull { it.id == currentId }
    val visible = actor != null && (gameMasterView || actor.visibility == TrpgVisibility.PLAYER)
    val name = if (visible) actor!!.name else if (chinese) "主持人回合" else "GM's turn"
    return if (chinese) "第 ${encounter.round} 轮 · 当前：$name" else "Round ${encounter.round} · $name"
}

internal fun restPreview(actor: TrpgActor, rest: TrpgRest, chinese: Boolean): String {
    if (rest == TrpgRest.NONE || actor.recovery.isEmpty()) return if (chinese) "未配置恢复" else "No recovery configured"
    return actor.recovery.joinToString(" · ") { policy ->
        val resource = actor.resources.singleOrNull { it.name == policy.resource }
        val full = if (rest == TrpgRest.SHORT) policy.shortFull else policy.longFull
        val amount = if (rest == TrpgRest.SHORT) policy.shortAmount else policy.longAmount
        when {
            full -> if (chinese) "${policy.resource} 全满" else "${policy.resource} full"
            amount == 0 -> if (chinese) "${policy.resource} +0" else "${policy.resource} +0"
            else -> "${policy.resource} +$amount" + (resource?.maximum?.let { m -> " ≤$m" } ?: "")
        }
    }
}

internal fun partyCommand(operation: TrpgPartyOperation, id: String? = null, actor: TrpgActor? = null,
    item: TrpgItem? = null, condition: TrpgCondition? = null, resource: String? = null, amount: Int? = null,
    equipped: Boolean? = null, initiative: List<TrpgInitiative>? = null, rest: TrpgRest? = null): TrpgCommand =
    TrpgCommand(TrpgOperation.PARTY, party = TrpgPartyCommand(operation, id, actor, item, condition, resource, amount, equipped, initiative, rest))

internal fun partyFailure(chinese: Boolean): String =
    if (chinese) "队伍操作失败，草稿已保留。" else "Party operation failed; input kept."

internal fun restText(rest: TrpgRest): String = when (rest) {
    TrpgRest.NONE -> "不因休息结束"
    TrpgRest.SHORT -> "短休结束"
    TrpgRest.LONG -> "长休结束"
}

private val ACTOR_KIND_ZH = mapOf(TrpgActorKind.PLAYER to "玩家", TrpgActorKind.COMPANION to "同伴", TrpgActorKind.NPC to "NPC")
internal fun actorKindText(kind: TrpgActorKind, chinese: Boolean): String =
    if (chinese) ACTOR_KIND_ZH.getValue(kind) else kind.name.lowercase()
