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

/* --------------------------------- setup ---------------------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetupTab(session: TrpgSession, draft: TrpgState, writable: Boolean, chinese: Boolean, onChange: (TrpgState) -> Unit) {
    Text(if (chinese) "开团向导（仅点击“应用框架”才补入核心数值）" else "Setup (framework is added only on explicit tap)",
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = draft.playerName,
        onValueChange = { onChange(draft.copy(playerName = it.take(128))) },
        enabled = writable, singleLine = true,
        label = { Text(if (chinese) "角色名 (≤128)" else "Character name (≤128)") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.player"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrpgRuleset.entries.forEach { ruleset ->
            FilterChip(selected = draft.ruleset == ruleset, onClick = { onChange(draft.copy(ruleset = ruleset)) },
                enabled = writable, label = { Text(trpgRulesetTitle(ruleset, chinese)) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    OutlinedButton(
        onClick = {
            runCatching { trpgStarterState(draft.ruleset, draft, chinese) }.onSuccess(onChange)
        },
        enabled = writable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trpg.campaign.applyFramework"),
    ) { Text(if (chinese) "应用通用框架（保留自定义数值）" else "Apply universal framework (keep custom stats)") }
    OutlinedTextField(
        value = draft.campaign.preferences.ruleEdition,
        onValueChange = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(ruleEdition = it.take(128))))) },
        enabled = writable, singleLine = true,
        label = { Text(if (chinese) "规则版本（文本）" else "Rule edition (text)") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.ruleEdition"),
    )
    OutlinedTextField(
        value = draft.scene,
        onValueChange = { onChange(draft.copy(scene = it.take(4096))) },
        enabled = writable, minLines = 2, maxLines = 4,
        label = { Text(if (chinese) "当前场景" else "Current scene") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.scene"),
    )
    OutlinedTextField(
        value = draft.characterSheet,
        onValueChange = { onChange(draft.copy(characterSheet = it.take(8192))) },
        enabled = writable, minLines = 3, maxLines = 8,
        label = { Text(if (chinese) "角色卡 (≤8192)" else "Character sheet (≤8192)") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.sheet"),
    )
    OutlinedTextField(
        value = draft.campaign.preferences.tone,
        onValueChange = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(tone = it.take(512))))) },
        enabled = writable, minLines = 2, maxLines = 4,
        label = { Text(if (chinese) "风格 tone (≤512)" else "Tone (≤512)") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.tone"),
    )
    OutlinedTextField(
        value = draft.campaign.preferences.boundaries,
        onValueChange = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(boundaries = it.take(2048))))) },
        enabled = writable, minLines = 2, maxLines = 5,
        label = { Text(if (chinese) "边界 boundaries (≤2048)" else "Boundaries (≤2048)") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.campaign.boundaries"),
    )
    Text(if (chinese) "视图与掷骰" else "View & dice", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = draft.campaign.preferences.viewRole == TrpgViewRole.PLAYER,
            onClick = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(viewRole = TrpgViewRole.PLAYER)))) },
            enabled = writable, label = { Text(if (chinese) "玩家视图" else "Player view") }, modifier = Modifier.heightIn(min = 48.dp))
        FilterChip(selected = draft.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER,
            onClick = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(viewRole = TrpgViewRole.GAME_MASTER)))) },
            enabled = writable, label = { Text(if (chinese) "主持视图" else "GM view") }, modifier = Modifier.heightIn(min = 48.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = draft.campaign.preferences.diceMode == TrpgDiceMode.PLAYER,
            onClick = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(diceMode = TrpgDiceMode.PLAYER)))) },
            enabled = writable, label = { Text(if (chinese) "玩家掷骰" else "Player rolls") }, modifier = Modifier.heightIn(min = 48.dp))
        FilterChip(selected = draft.campaign.preferences.diceMode == TrpgDiceMode.AUTOMATIC,
            onClick = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(diceMode = TrpgDiceMode.AUTOMATIC)))) },
            enabled = writable, label = { Text(if (chinese) "自动掷骰" else "Automatic rolls") }, modifier = Modifier.heightIn(min = 48.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrpgResponseStyle.entries.forEach { style ->
            FilterChip(selected = draft.campaign.preferences.responseStyle == style,
                onClick = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = draft.campaign.preferences.copy(responseStyle = style)))) },
                enabled = writable, label = {
                    Text(if (chinese) when (style) {
                        TrpgResponseStyle.BALANCED -> "均衡"
                        TrpgResponseStyle.CONCISE -> "简洁"
                        TrpgResponseStyle.DESCRIPTIVE -> "详细"
                    } else style.name.lowercase().replaceFirstChar { it.uppercase() })
                }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

/* ------------------------------- preferences ------------------------------ */

@Composable
internal fun PreferencesTab(pref: TrpgPreferences, draft: TrpgState, writable: Boolean, chinese: Boolean, onChange: (TrpgState) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "显示掷骰明细" else "Show roll details", modifier = Modifier.weight(1f))
        Switch(checked = pref.showRollDetails,
            onCheckedChange = { onChange(draft.copy(campaign = draft.campaign.copy(preferences = pref.copy(showRollDetails = it)))) },
            enabled = writable, modifier = Modifier.testTag("trpg.campaign.showRollDetails"))
    }
    Text(if (chinese) "钉选数值（最多 8 个）" else "Pinned stats (max 8)",
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    if (draft.resources.isEmpty()) Text(if (chinese) "暂无资源" else "No resources", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.resources.forEach { resource ->
            FilterChip(
                selected = resource.name in pref.pinnedResources,
                onClick = {
                    onChange(draft.copy(campaign = draft.campaign.copy(
                        preferences = pref.copy(pinnedResources = togglePinnedResource(pref.pinnedResources, resource.name)),
                    )))
                },
                enabled = writable,
                label = { Text("${resource.name} ${resource.current}") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.pin.${resource.name}"),
            )
        }
    }
    Text(if (chinese) "已钉选 ${pref.pinnedResources.size}/8" else "Pinned ${pref.pinnedResources.size}/8",
        style = MaterialTheme.typography.bodySmall)
    Text(if (chinese) "其余选项（风格、边界、视图、掷骰、篇幅）在“开团”页。" else "Tone/boundaries/view/dice/length live on the Setup tab.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

