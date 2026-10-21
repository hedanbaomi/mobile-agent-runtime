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

internal enum class TrpgPanel { SHEET, DICE, PARTY, CAMPAIGN, STORY, CHECKPOINTS, EXPORT, JOURNAL, ATLAS, MEDIA }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrpgChatChrome(
    activeSession: TrpgSession, hudSession: TrpgSession, focusedActor: Boolean,
    writable: Boolean, saving: Boolean, archived: Boolean, chinese: Boolean,
    preferExpanded: Boolean, notice: String?, conflictMsg: String?, pendingRoll: TrpgManualRollEntry?,
    rollHistory: List<TrpgManualRollEntry>, onExpanded: (Boolean) -> Unit,
    onOpen: (TrpgPanel) -> Unit, onRetryRoll: (TrpgManualRollEntry) -> Unit,
    onAdjustResource: (TrpgResource, Int) -> Unit, onApplyStarter: (TrpgRuleset) -> Unit,
    onInsertRoll: (String) -> Unit,
) {
    val state = activeSession.state
    val palette = rememberTrpgPalette()
    val keyboardVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var moreOpen by remember(activeSession.conversationId) { mutableStateOf(false) }

    Surface(
        color = palette.boardSurface,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("trpg.chat.bar"),
    ) {
        BoxWithConstraints {
        val bodyHeight = (maxHeight * 0.25f).coerceAtMost(160.dp)
        val hasRoomForInlineData = maxHeight >= 360.dp
        val hudExpanded = preferExpanded && !keyboardVisible && hasRoomForInlineData
        val sheetPanel = if (!focusedActor) TrpgPanel.SHEET else TrpgPanel.PARTY
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val toggleLabel = if (!hasRoomForInlineData && !keyboardVisible) {
                    if (chinese) "查看数据" else "View stats"
                } else if (hudExpanded) {
                    if (chinese) "收起数据 ▴" else "Hide stats ▴"
                } else {
                    if (chinese) "展开数据 ▾" else "Show stats ▾"
                }
                TextButton(
                    onClick = {
                        val next = !hudExpanded
                        if (next && keyboardVisible) {
                            keyboard?.hide()
                            focus.clearFocus()
                        }
                        if (hasRoomForInlineData || keyboardVisible) {
                            onExpanded(next)
                        } else {
                            onOpen(sheetPanel)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("trpg.chat.toggle_hud").semantics {
                            stateDescription = if (hudExpanded) {
                                if (chinese) "已展开" else "Expanded"
                            } else {
                                if (chinese) "已折叠" else "Collapsed"
                            }
                        },
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(toggleLabel, style = MaterialTheme.typography.labelMedium)
                        if (hasRoomForInlineData) Text(buildTrpgHeadline(hudSession.state, chinese), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("trpg.chat.scene"))
                    }
                }
                TextButton(
                    onClick = { onOpen(TrpgPanel.DICE) },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.testTag("trpg.chat.open_dice"),
                ) {
                    Text(if (chinese) "骰盘" else "Dice", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = { onOpen(sheetPanel) },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.testTag("trpg.chat.open_sheet"),
                ) {
                    Text(if (focusedActor) { if (chinese) "人物" else "Actor" } else if (chinese) "状态板" else "Sheet", style = MaterialTheme.typography.labelMedium)
                }
                Box {
                    TextButton(onClick = { moreOpen = true }, contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.chat.more")) {
                        Text(if (chinese) "团务" else "More", style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text(if (chinese) "角色、物品与回合" else "Party, inventory and turns") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.PARTY) }, modifier = Modifier.testTag("trpg.chat.open_party"))
                        DropdownMenuItem(text = { Text(if (chinese) "开团、检定与回顾" else "Setup, checks and recap") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.CAMPAIGN) }, modifier = Modifier.testTag("trpg.chat.open_campaign"))
                        DropdownMenuItem(text = { Text(if (chinese) "战役、任务与线索" else "Campaign, quests and clues") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.STORY) }, modifier = Modifier.testTag("trpg.chat.open_story"))
                        DropdownMenuItem(text = { Text(if (chinese) "检查点与分支" else "Checkpoints and branches") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.CHECKPOINTS) }, modifier = Modifier.testTag("trpg.chat.open_checkpoints"))
                        DropdownMenuItem(text = { Text(if (chinese) "地点与地图" else "Locations and map") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.ATLAS) }, modifier = Modifier.testTag("trpg.chat.open_atlas"))
                        DropdownMenuItem(text = { Text(if (chinese) "语音与氛围" else "Voice and ambience") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.MEDIA) }, modifier = Modifier.testTag("trpg.chat.open_media"))
                        DropdownMenuItem(text = { Text(if (chinese) "导出玩家资料" else "Export player record") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.EXPORT) },
                            modifier = Modifier.testTag("trpg.chat.export_player"))
                        DropdownMenuItem(text = { Text(if (chinese) "结算日志与撤销" else "Journal and undo") },
                            onClick = { moreOpen = false; onOpen(TrpgPanel.JOURNAL) }, modifier = Modifier.testTag("trpg.chat.open_journal"))
                    }
                }
            }
            val visibleChecks = if (state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER) state.campaign.checks
                else runtime.mobileagent.domain.trpg.TrpgProjection.player(state).campaign.checks
            val pendingChecks = visibleChecks.count { it.status == TrpgCheckStatus.PENDING }
            if (pendingChecks > 0 && !keyboardVisible) TextButton(onClick = { onOpen(TrpgPanel.CAMPAIGN) },
                modifier = Modifier.fillMaxWidth().testTag("trpg.chat.pending_checks")) {
                Text(if (chinese) "$pendingChecks 项检定等待掷骰" else "$pendingChecks checks waiting for a roll")
            }

            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            conflictMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, modifier = Modifier.testTag("trpg.chat.error"))
            }
            pendingRoll?.let { roll ->
                TextButton(onClick = { onRetryRoll(roll) }, enabled = writable && !saving,
                    modifier = Modifier.testTag("trpg.chat.retry_roll")) {
                    Text(if (chinese) "重试保存骰点" else "Retry saving roll")
                }
            }
            if (hudExpanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = bodyHeight).verticalScroll(rememberScrollState())
                    .testTag("trpg.chat.hud_body")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(trpgRulesetTitle(state.ruleset, chinese), style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f).testTag("trpg.chat.ruleset"))
                        Text("#${activeSession.revision}", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("trpg.chat.revision"))
                    }
                    TrpgExpandedHudBody(
                        session = hudSession,
                        palette = palette,
                        writable = writable && !saving,
                        canInsertDraft = !archived,
                        chinese = chinese,
                        latestRoll = rollHistory.firstOrNull(),
                        onAdjustResource = onAdjustResource,
                        onApplyStarter = onApplyStarter,
                        onInsertRoll = onInsertRoll,
                    )
                }
            }
        }
        }
    }

}
