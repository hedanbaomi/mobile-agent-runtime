// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.AppContainer
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.trpg.TrpgResource
import runtime.mobileagent.domain.trpg.TrpgRuleset
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrpgChatBar(
    container: AppContainer,
    conversationId: String?,
    streaming: Boolean,
    archived: Boolean,
    refreshTick: Int,
    chinese: Boolean,
    onInsertDraft: (String) -> Unit,
) {
    if (conversationId == null) return
    var session by remember(conversationId) { mutableStateOf<TrpgSession?>(null) }
    var hudExpanded by remember(conversationId) { mutableStateOf(true) }
    var sheetOpen by remember(conversationId) { mutableStateOf(false) }
    var diceOpen by remember(conversationId) { mutableStateOf(false) }
    var saving by remember(conversationId) { mutableStateOf(false) }
    var conflictMsg by remember(conversationId) { mutableStateOf<String?>(null) }
    var rollHistory by remember(conversationId) { mutableStateOf<List<TrpgManualRollEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val palette = rememberTrpgPalette()
    val writable = !streaming && !archived

    LaunchedEffect(conversationId, streaming, refreshTick) {
        try {
            val loaded = withContext(Dispatchers.IO) { loadTrpgSessionIfEligible(container, conversationId) }
            session = loaded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            session = null
        }
    }

    val activeSession = session ?: return
    val state = activeSession.state

    fun persistState(expectedRevision: Long, nextState: TrpgState, closeSheetOnSuccess: Boolean) {
        if (!writable || saving) return
        saving = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    container.trpg.update(conversationId, expectedRevision, nextState)
                }
                session = updated
                conflictMsg = null
                if (closeSheetOnSuccess) sheetOpen = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                val latest = try {
                    withContext(Dispatchers.IO) { container.trpg.get(conversationId) }
                } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                if (latest != null) session = latest
                conflictMsg = if (chinese) {
                    "状态未保存（最新 #${latest?.revision ?: "?"}）：版本冲突、活动运行或存储错误。草稿已保留，可载入最新状态后重试。"
                } else {
                    "State was not saved (latest #${latest?.revision ?: "?"}): revision conflict, active run or storage error. Draft retained; reload and retry."
                }
            } finally {
                saving = false
            }
        }
    }

    Surface(
        color = palette.boardSurface,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("trpg.chat.bar"),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    color = palette.crestContainer,
                    contentColor = palette.crestContent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("trpg.chat.ruleset"),
                ) {
                    Text(
                        text = trpgRulesetTitle(state.ruleset, chinese),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = buildTrpgHeadline(state, chinese),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("trpg.chat.scene"),
                )
                Text(
                    text = "#${activeSession.revision}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trpg.chat.revision"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { diceOpen = true },
                    modifier = Modifier.testTag("trpg.chat.open_dice"),
                ) {
                    Text(if (chinese) "🎲 骰盘" else "🎲 Dice", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = { sheetOpen = true },
                    modifier = Modifier.testTag("trpg.chat.open_sheet"),
                ) {
                    Text(if (chinese) "📜 状态板" else "📜 Sheet", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = { hudExpanded = !hudExpanded },
                    modifier = Modifier.testTag("trpg.chat.toggle_hud"),
                ) {
                    Text(if (hudExpanded) "▴" else "▾")
                }
            }

            conflictMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, modifier = Modifier.testTag("trpg.chat.error"))
            }
            if (hudExpanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                    .testTag("trpg.chat.hud_body")) {
                    TrpgExpandedHudBody(
                        session = activeSession,
                        palette = palette,
                        writable = writable && !saving,
                        canInsertDraft = !archived,
                        chinese = chinese,
                        latestRoll = rollHistory.firstOrNull(),
                        onAdjustResource = { res, delta ->
                            val nextResources = state.resources.map { item ->
                                if (item.name == res.name) {
                                    adjustTrpgResource(item, delta)
                                } else {
                                    item
                                }
                            }
                            persistState(activeSession.revision, state.copy(resources = nextResources), false)
                        },
                        onApplyStarter = { ruleset ->
                            runCatching { trpgStarterState(ruleset, state, chinese) }
                                .onSuccess { persistState(activeSession.revision, it, false) }
                                .onFailure { conflictMsg = it.message }
                        },
                        onInsertRoll = onInsertDraft,
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        TrpgStateSheetDialog(
            session = activeSession,
            writable = writable,
            saving = saving,
            conflictMessage = conflictMsg,
            chinese = chinese,
            onSave = { expectedRev, next -> persistState(expectedRev, next, true) },
            onReloadLatest = {
                val latest = withContext(Dispatchers.IO) { container.trpg.get(conversationId) }
                session = latest
                conflictMsg = null
                latest
            },
            onDismiss = { sheetOpen = false },
        )
    }

    if (diceOpen) {
        TrpgDiceRollerDialog(
            defaultRuleset = state.ruleset,
            writable = writable && !saving,
            canInsertDraft = !archived,
            saveError = conflictMsg,
            chinese = chinese,
            history = rollHistory,
            onRollRecorded = { entry -> rollHistory = listOf(entry) + rollHistory.take(5) },
            onInsertDraft = { text -> onInsertDraft(text); diceOpen = false },
            onAppendNotes = { text ->
                runCatching { appendTrpgNote(state, text) }
                    .onSuccess { persistState(activeSession.revision, it, false) }
                    .onFailure { conflictMsg = if (chinese) "笔记未写入：已超过笔记或状态容量上限。" else
                        "Note was not saved: notes or state capacity exceeded." }
            },
            onDismiss = { diceOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrpgExpandedHudBody(
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
            state.resources.forEach { res ->
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

private fun loadTrpgSessionIfEligible(container: AppContainer, conversationId: String): TrpgSession? {
    val conversation = container.conversations.get(conversationId) ?: return null
    val resolved = container.agents.resolveSnapshot(conversation.snapshotId)
    if (resolved.snapshot.mode != AgentMode.TRPG) return null
    return container.trpg.get(conversationId)
}

private fun buildTrpgHeadline(state: TrpgState, chinese: Boolean): String {
    val actor = state.playerName.ifBlank { if (chinese) "未命名角色" else "Unnamed PC" }
    val scene = state.scene.ifBlank { if (chinese) "待推进场景" else "Unset scene" }
    return "$actor · 📍 $scene"
}

internal fun appendTrpgRollToDraft(current: String, rollLine: String): String =
    if (current.isBlank()) rollLine else "$current\n$rollLine"
