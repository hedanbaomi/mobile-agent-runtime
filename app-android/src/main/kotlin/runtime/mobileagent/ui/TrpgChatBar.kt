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
internal fun TrpgChatBar(
    container: AppContainer,
    conversationId: String?,
    streaming: Boolean,
    archived: Boolean,
    refreshTick: Int,
    chinese: Boolean,
    onOpenConversation: (String) -> Unit = {},
    onInsertDraft: (String) -> Unit,
) {
    if (conversationId == null) return
    var session by remember(conversationId) { mutableStateOf<TrpgSession?>(null) }
    // A display preference, not campaign state. IME suppression must not overwrite it.
    var preferExpanded by remember(container.uiPreferences) {
        mutableStateOf(container.uiPreferences.getBoolean(TRPG_HUD_EXPANDED, false))
    }
    var sheetOpen by remember(conversationId) { mutableStateOf(false) }
    var diceOpen by remember(conversationId) { mutableStateOf(false) }
    var journalOpen by remember(conversationId) { mutableStateOf(false) }
    var campaignOpen by remember(conversationId) { mutableStateOf(false) }
    var partyOpen by remember(conversationId) { mutableStateOf(false) }
    var storyOpen by remember(conversationId) { mutableStateOf(false) }
    var atlasOpen by remember(conversationId) { mutableStateOf(false) }
    var mediaOpen by remember(conversationId) { mutableStateOf(false) }
    var checkpointsOpen by remember(conversationId) { mutableStateOf(false) }
    var checkpoints by remember(conversationId) { mutableStateOf<List<runtime.mobileagent.domain.trpg.TrpgCheckpoint>>(emptyList()) }
    var exportPreview by remember(conversationId) { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var recentMessages by remember(conversationId) { mutableStateOf<List<Message>>(emptyList()) }
    var sourceMessage by remember(conversationId) { mutableStateOf<Message?>(null) }
    var saving by remember(conversationId) { mutableStateOf(false) }
    var conflictMsg by remember(conversationId) { mutableStateOf<String?>(null) }
    var notice by remember(conversationId) { mutableStateOf<String?>(null) }
    var rollHistory by remember(conversationId) { mutableStateOf<List<TrpgManualRollEntry>>(emptyList()) }
    var events by remember(conversationId) { mutableStateOf<List<TrpgEvent>>(emptyList()) }
    var latestTurn by remember(conversationId) { mutableStateOf<List<TrpgEvent>>(emptyList()) }
    var undoableId by remember(conversationId) { mutableStateOf<String?>(null) }
    var hasEarlierEvents by remember(conversationId) { mutableStateOf(false) }
    var pendingRoll by remember(conversationId) { mutableStateOf<TrpgManualRollEntry?>(null) }
    var pendingEdit by remember(conversationId) { mutableStateOf<Triple<Long, TrpgState, String>?>(null) }
    val scope = rememberCoroutineScope()
    val writable = !streaming && !archived
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Cannot open selected document")
                    output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                }
                notice = if (chinese) "玩家资料已保存。" else "Player record saved."
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {
                conflictMsg = if (chinese) "导出未完成，请重新选择文件重试。" else "Export failed. Choose a file and retry."
            }
        }
    }

    fun applyView(view: TrpgJournalView) {
        session = view.session
        events = view.events
        latestTurn = view.latestTurn
        undoableId = view.undoableEventId
        hasEarlierEvents = view.events.size == 50
        val shownEvents = if (view.session.state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER) view.events
            else view.events.mapNotNull(TrpgProjection::eventForPlayer)
        rollHistory = listOfNotNull(pendingRoll) + shownEvents.filter { it.kind == TrpgEventKind.ROLL }
            .map { TrpgManualRollEntry(it.title, it.detail, id = it.id) }
    }

    LaunchedEffect(conversationId, streaming, refreshTick, journalOpen, campaignOpen, partyOpen, storyOpen, checkpointsOpen, atlasOpen, mediaOpen) {
        try {
            val loaded = withContext(Dispatchers.IO) { loadTrpgSessionIfEligible(container, conversationId) }
            if (loaded == null) session = null else applyView(withContext(Dispatchers.IO) { container.trpg.journalView(conversationId) })
            if (loaded != null && (campaignOpen || storyOpen || mediaOpen)) recentMessages = withContext(Dispatchers.IO) {
                container.conversations.messagePage(conversationId, limit = 20).messages.map(::trpgMessageForDisplay)
            }
            if (loaded != null && checkpointsOpen) checkpoints = withContext(Dispatchers.IO) { container.trpgCheckpoints.list(conversationId) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            session = null
        }
    }

    val activeSession = session ?: return
    val state = activeSession.state
    val focusActor = state.campaign.party.actors.singleOrNull { it.id == state.campaign.preferences.focusActorId &&
        (it.visibility == TrpgVisibility.PLAYER || state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER) }
    val hudSession = if (focusActor == null) activeSession else activeSession.copy(state = state.copy(
        playerName = focusActor.name, characterSheet = focusActor.description, resources = focusActor.resources,
    ))

    val mutationUi = TrpgSessionUiCallbacks({ session = it }, ::applyView, { conflictMsg = it }, { saving = it })

    fun persistState(expectedRevision: Long, nextState: TrpgState, closeSheetOnSuccess: Boolean) {
        if (!writable || saving) return
        val action = pendingEdit?.takeIf { it.first == expectedRevision && it.second == nextState }
            ?: Triple(expectedRevision, nextState, java.util.UUID.randomUUID().toString()).also { pendingEdit = it }
        launchTrpgUiMutation(scope, container, conversationId, chinese, mutationUi,
            onCommitted = { pendingEdit = null; if (closeSheetOnSuccess) sheetOpen = false }) {
            container.trpg.update(conversationId, expectedRevision, nextState, action.third)
        }
    }

    fun persistRoll(entry: TrpgManualRollEntry) {
        if (!writable || saving) return
        pendingRoll = entry
        launchTrpgUiMutation(scope, container, conversationId, chinese, mutationUi, onCommitted = { pendingRoll = null }) {
            container.trpg.recordRoll(conversationId, actionId = entry.id, title = entry.summary, detail = entry.detail)
        }
    }

    suspend fun mutateCampaign(action: () -> TrpgSession): TrpgSession {
        check(writable && !saving) { "Campaign is not writable" }
        return mutateTrpgUi(container, conversationId, chinese, mutationUi, action = action)
    }

    TrpgChatChrome(activeSession, hudSession, focusActor != null, writable, saving, archived, chinese,
        preferExpanded, notice, conflictMsg, pendingRoll, rollHistory,
        onExpanded = { next -> preferExpanded = next; container.uiPreferences.edit().putBoolean(TRPG_HUD_EXPANDED, next).apply() },
        onOpen = { panel -> when (panel) {
            TrpgPanel.SHEET -> sheetOpen = true
            TrpgPanel.DICE -> diceOpen = true
            TrpgPanel.PARTY -> partyOpen = true
            TrpgPanel.CAMPAIGN -> campaignOpen = true
            TrpgPanel.STORY -> storyOpen = true
            TrpgPanel.CHECKPOINTS -> checkpointsOpen = true
            TrpgPanel.JOURNAL -> journalOpen = true
            TrpgPanel.EXPORT -> exportPreview = TrpgPlayerExport.encode(activeSession, Utc.nowIso())
            TrpgPanel.ATLAS -> atlasOpen = true
            TrpgPanel.MEDIA -> mediaOpen = true
        } }, onRetryRoll = ::persistRoll,
                        onAdjustResource = { res, delta ->
                            if (focusActor != null) {
                                scope.launch { try {
                                    mutateCampaign { container.trpg.command(conversationId, activeSession.revision, java.util.UUID.randomUUID().toString(),
                                        TrpgCommand(TrpgOperation.PARTY, party = TrpgPartyCommand(TrpgPartyOperation.ADJUST_RESOURCE,
                                            id = focusActor.id, resource = res.name, amount = delta))) }
                                } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Error retained above. */ } }
                            } else {
                                val nextResources = state.resources.map { item -> if (item.name == res.name) adjustTrpgResource(item, delta) else item }
                                persistState(activeSession.revision, state.copy(resources = nextResources), false)
                            }
                        },
                        onApplyStarter = { ruleset ->
                            runCatching {
                                val starter = trpgStarterState(ruleset, hudSession.state, chinese)
                                if (focusActor == null) starter else state.copy(ruleset = ruleset, campaign = state.campaign.copy(
                                    party = state.campaign.party.copy(actors = state.campaign.party.actors.map {
                                        if (it.id == focusActor.id) it.copy(resources = starter.resources) else it
                                    })))
                            }.onSuccess { persistState(activeSession.revision, it, false) }
                                .onFailure { conflictMsg = it.message }
                        },
        onInsertRoll = onInsertDraft,
    )

    fun showSource(messageId: String) {
        scope.launch {
            sourceMessage = withContext(Dispatchers.IO) {
                container.conversations.message(messageId)?.takeIf { it.conversationId == conversationId }?.let(::trpgMessageForDisplay)
            }
            if (sourceMessage == null) conflictMsg = if (chinese) "来源消息已不可用。" else "Source message is no longer available."
        }
    }
    if (atlasOpen) TrpgAtlasDialog(activeSession, state.campaign.atlas, writable, saving, conflictMsg, chinese,
        onCommand = { revision, command -> mutateCampaign {
            container.trpg.command(conversationId, revision, java.util.UUID.randomUUID().toString(),
                TrpgCommand(TrpgOperation.ATLAS, atlas = command))
        } }, onDismiss = { atlasOpen = false })
    if (mediaOpen) TrpgMediaDialog(conversationId,
        recentMessages.lastOrNull { it.role == runtime.mobileagent.domain.MessageRole.ASSISTANT && it.text.isNotBlank() }?.text.orEmpty(),
        !archived, chinese, onInsertDraft, onDismiss = { mediaOpen = false })
    if (storyOpen) TrpgStoryDialog(activeSession, state.campaign.story, writable, saving, conflictMsg, chinese, recentMessages,
        onCommand = { revision, command -> mutateCampaign {
            container.trpg.command(conversationId, revision, java.util.UUID.randomUUID().toString(),
                TrpgCommand(TrpgOperation.STORY, story = command))
        } }, onSource = ::showSource, onDismiss = { storyOpen = false })
    if (checkpointsOpen) TrpgCheckpointsDialog(activeSession, checkpoints, writable, !streaming, saving, conflictMsg, chinese,
        onCreate = { revision, name, id ->
            var created: runtime.mobileagent.domain.trpg.TrpgCheckpoint? = null
            mutateCampaign {
                created = container.trpgCheckpoints.create(conversationId, revision, name, id)
                container.trpg.get(conversationId)
            }
            checkpoints = (checkpoints + checkNotNull(created)).distinctBy { it.checkpointId }
            checkNotNull(created)
        },
        onBranch = { checkpointId, title, newId ->
            check(!streaming && !saving)
            saving = true
            try {
                withContext(Dispatchers.IO) {
                    val existing = container.conversations.get(newId)
                    if (existing == null) container.trpgCheckpoints.branch(conversationId, checkpointId, title, newId).id
                    else {
                        val origin = container.trpg.get(newId).state.campaign.origin
                        require(origin?.sourceConversationId == conversationId && origin.checkpointId == checkpointId && existing.title == title)
                        newId
                    }
                }
            } finally { saving = false }
        }, onOpenConversation = onOpenConversation, onDismiss = { checkpointsOpen = false })

    exportPreview?.let { content -> TrpgExportDialog(content, chinese,
        onSave = { pendingExport = content; exportPreview = null; exportLauncher.launch("trpg-player.json") },
        onDismiss = { exportPreview = null }) }
    if (partyOpen) TrpgPartyDialog(activeSession, writable, saving, conflictMsg, chinese,
        onCommand = { revision, command -> mutateCampaign {
            container.trpg.command(conversationId, revision, java.util.UUID.randomUUID().toString(), command)
        } },
        onFocusActor = { revision, id -> mutateCampaign {
            val current = container.trpg.get(conversationId)
            require(current.revision == revision)
            require(id == null || current.state.campaign.party.actors.any { it.id == id })
            container.trpg.update(conversationId, revision, current.state.copy(campaign = current.state.campaign.copy(
                preferences = current.state.campaign.preferences.copy(focusActorId = id))))
        } }, onDismiss = { partyOpen = false })

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
            onRollRecorded = { entry ->
                rollHistory = listOf(entry) + rollHistory.take(5)
                if (writable) persistRoll(entry) else conflictMsg = if (chinese) "临时骰点：当前会话只读，结果未保存。" else "Temporary roll: the conversation is read-only; result not saved."
            },
            onInsertDraft = { text -> onInsertDraft(text); diceOpen = false },
            onAppendNotes = { text ->
                runCatching { appendTrpgNote(state, text) }
                    .onSuccess { persistState(activeSession.revision, it, false) }
                    .onFailure { conflictMsg = if (chinese) "笔记未写入：已超过笔记或状态容量上限。" else
                        "Note was not saved: notes or state capacity exceeded." }
            },
            onDismiss = { diceOpen = false },
            rollingEnabled = !saving && pendingRoll == null,
            onRetrySave = pendingRoll?.let { roll -> { persistRoll(roll) } },
            showDetails = state.campaign.preferences.showRollDetails,
        )
    }
    val shownEvents = if (state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER) events else events.mapNotNull(TrpgProjection::eventForPlayer)
    val shownTurn = if (state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER) latestTurn else latestTurn.mapNotNull(TrpgProjection::eventForPlayer)
    if (journalOpen) TrpgJournalDialog(shownEvents, undoableId, writable, saving, chinese, conflictMsg, hasEarlierEvents,
        onUndo = { target ->
            if (writable && !saving) {
                saving = true
                val actionId = java.util.UUID.randomUUID().toString()
                scope.launch {
                    try {
                        val view = withContext(Dispatchers.IO) {
                            container.trpg.undo(conversationId, activeSession.revision, target, actionId)
                            container.trpg.journalView(conversationId)
                        }
                        applyView(view)
                        conflictMsg = null
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) {
                        conflictMsg = if (chinese) "未撤销：状态已变化、正在运行或存储失败。请重新打开日志后重试。" else "Undo failed: state changed, a run is active, or storage failed. Reopen the journal and retry."
                    } finally { saving = false }
                }
            }
        },
        onLoadEarlier = {
            saving = true
            scope.launch {
                try {
                    val page = withContext(Dispatchers.IO) { container.trpg.events(conversationId, beforeRevision = events.lastOrNull()?.revision ?: Long.MAX_VALUE) }
                    events = (events + page).distinctBy { it.id }
                    hasEarlierEvents = page.size == 50
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { conflictMsg = if (chinese) "读取日志失败，可重试。" else "Could not load history. Retry."
                } finally { saving = false }
            }
        }, onDismiss = { journalOpen = false }, latestTurn = shownTurn)
    if (campaignOpen) TrpgCampaignDialog(
        session = activeSession, writable = writable, saving = saving, error = conflictMsg, chinese = chinese,
        recentMessages = recentMessages,
        onSave = { revision, next -> mutateCampaign { container.trpg.update(conversationId, revision, next) } },
        onCommand = { revision, command -> mutateCampaign { container.trpg.command(conversationId, revision, java.util.UUID.randomUUID().toString(), command) } },
        onRollCheck = { revision, checkId -> mutateCampaign { container.trpg.rollCheck(conversationId, revision, checkId, java.util.UUID.randomUUID().toString()) } },
        onSource = ::showSource,
        onInsertDraft = { text -> if (!archived) { onInsertDraft(text); campaignOpen = false } },
        onDismiss = { campaignOpen = false },
        onOpenJournal = { campaignOpen = false; journalOpen = true },
    )
    sourceMessage?.let { TrpgSourceDialog(it, chinese) { sourceMessage = null } }
}

internal const val TRPG_HUD_EXPANDED = "trpg-hud-expanded"
