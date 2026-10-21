// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import runtime.mobileagent.serialization.TrpgPlayerExport
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.AppContainer
import runtime.mobileagent.trpgMessageForDisplay
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.trpg.TrpgProjection
import runtime.mobileagent.domain.trpg.TrpgCommand
import runtime.mobileagent.domain.trpg.TrpgOperation
import runtime.mobileagent.domain.trpg.TrpgPartyCommand
import runtime.mobileagent.domain.trpg.TrpgPartyOperation
import runtime.mobileagent.domain.trpg.TrpgResource
import runtime.mobileagent.domain.trpg.TrpgRuleset
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgEvent
import runtime.mobileagent.domain.trpg.TrpgEventKind
import runtime.mobileagent.domain.trpg.TrpgJournalView
import runtime.mobileagent.domain.trpg.TrpgCheckStatus
import runtime.mobileagent.domain.trpg.TrpgViewRole
import runtime.mobileagent.domain.trpg.TrpgVisibility

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrpgExpandedHudBody(
    session: TrpgSession,
    palette: TrpgTabletopPalette,
    writable: Boolean,
    canInsertDraft: Boolean,
    chinese: Boolean,
    latestRoll: TrpgManualRollEntry?,
    onAdjustResource: (TrpgResource, Int) -> Unit,
    onApplyStarter: (TrpgRuleset) -> Unit,
    onInsertRoll: (String) -> Unit,
) {
    val state = session.state
    if (state.resources.isEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (chinese) "数值框架预设：" else "Framework:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilterChip(
                selected = false,
                onClick = { onApplyStarter(TrpgRuleset.DND_5E) },
                enabled = writable,
                label = { Text(if (chinese) "⚔ D&D 通用框架" else "⚔ D&D Universal", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("trpg.preset.dnd5e"),
            )
            FilterChip(
                selected = false,
                onClick = { onApplyStarter(TrpgRuleset.COC_7) },
                enabled = writable,
                label = { Text(if (chinese) "🐙 CoC 通用框架" else "🐙 CoC Universal", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("trpg.preset.coc7"),
            )
            FilterChip(
                selected = false,
                onClick = { onApplyStarter(TrpgRuleset.CUSTOM) },
                enabled = writable,
                label = { Text(if (chinese) "📖 世界书自定义" else "📖 Lorebook Custom", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("trpg.preset.custom"),
            )
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.resources.sortedBy { resource ->
                state.campaign.preferences.pinnedResources.indexOf(resource.name).let { if (it < 0) Int.MAX_VALUE else it }
            }.forEach { res ->
                TrpgResourceGaugeChip(res, palette, writable, onAdjustResource)
            }
        }
    }

    latestRoll?.let { roll ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = roll.summary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("trpg.chat.last_roll"),
            )
            OutlinedButton(
                onClick = { onInsertRoll(roll.summary) },
                enabled = canInsertDraft,
                modifier = Modifier.testTag("trpg.chat.insert_last_roll"),
            ) {
                Text(if (chinese) "填入输入" else "Insert", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrpgResourceGaugeChip(
    res: TrpgResource,
    palette: TrpgTabletopPalette,
    writable: Boolean,
    onAdjust: (TrpgResource, Int) -> Unit,
) {
    val visual = trpgResourceVisual(res.name, palette)
    Surface(
        color = palette.headerSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, visual.accent.copy(alpha = 0.45f)),
        modifier = Modifier.testTag("trpg.resource.card.${res.name}"),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${visual.icon} ${res.name}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { onAdjust(res, -1) },
                    enabled = writable,
                    modifier = Modifier.testTag("trpg.resource.dec.${res.name}"),
                ) { Text("-", style = MaterialTheme.typography.labelSmall) }
                Text(
                    text = "${res.current}${res.maximum?.let { "/$it" }.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = visual.accent,
                )
                TextButton(
                    onClick = { onAdjust(res, 1) },
                    enabled = writable,
                    modifier = Modifier.testTag("trpg.resource.inc.${res.name}"),
                ) { Text("+", style = MaterialTheme.typography.labelSmall) }
            }
            val max = res.maximum
            if (max != null && max > 0) {
                val ratio = (res.current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { ratio },
                    color = visual.accent,
                    trackColor = palette.boardSurface,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

internal fun loadTrpgSessionIfEligible(container: AppContainer, conversationId: String): TrpgSession? {
    val conversation = container.conversations.get(conversationId) ?: return null
    val resolved = container.agents.resolveSnapshot(conversation.snapshotId)
    if (resolved.snapshot.mode != AgentMode.TRPG) return null
    return container.trpg.get(conversationId)
}

internal fun buildTrpgHeadline(state: TrpgState, chinese: Boolean): String {
    val actor = state.playerName.ifBlank { if (chinese) "未命名角色" else "Unnamed PC" }
    val scene = state.scene.ifBlank { if (chinese) "待推进场景" else "Unset scene" }
    return "$actor · 📍 $scene"
}

internal fun appendTrpgRollToDraft(current: String, rollLine: String): String =
    if (current.isBlank()) rollLine else "$current\n$rollLine"
