// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgStory
import runtime.mobileagent.domain.trpg.TrpgStoryChapter
import runtime.mobileagent.domain.trpg.TrpgStoryCommand
import runtime.mobileagent.domain.trpg.TrpgStoryOperation
import runtime.mobileagent.domain.trpg.TrpgStoryReducer
import runtime.mobileagent.domain.trpg.TrpgStorySession
import runtime.mobileagent.domain.trpg.TrpgStoryValidation
import runtime.mobileagent.domain.trpg.TrpgTrack
import runtime.mobileagent.domain.trpg.TrpgTrackKind
import runtime.mobileagent.domain.trpg.TrpgViewRole

/* Story dialog surface: revision-guarded command execution and tab dispatch. */

@Composable
internal fun TrpgStoryDialog(
    session: TrpgSession,
    story: TrpgStory,
    writable: Boolean,
    saving: Boolean,
    error: String?,
    chinese: Boolean,
    recentMessages: List<Message>,
    onCommand: suspend (Long, TrpgStoryCommand) -> TrpgSession,
    onSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val conversationId = session.conversationId
    val scope = rememberCoroutineScope()
    var viewStory by remember(conversationId) { mutableStateOf(story) }
    var storyAtBase by remember(conversationId) { mutableStateOf(story) }
    var baseRevision by remember(conversationId) { mutableLongStateOf(session.revision) }
    var externalNewer by remember(conversationId) { mutableStateOf(false) }
    var busy by remember(conversationId) { mutableStateOf(false) }
    var tab by remember(conversationId) { mutableIntStateOf(0) }
    var localError by remember(conversationId) { mutableStateOf<String?>(null) }
    var nameDraft by remember(conversationId) { mutableStateOf(story.campaignName) }
    var editorRevision by remember(conversationId) { mutableLongStateOf(session.revision) }
    var editingChapter by remember(conversationId) { mutableStateOf<TrpgStoryChapter?>(null) }
    var editingSession by remember(conversationId) { mutableStateOf<TrpgStorySession?>(null) }
    var editingTrack by remember(conversationId) { mutableStateOf<TrpgTrack?>(null) }

    LaunchedEffect(session.revision, story, busy) {
        if (busy) return@LaunchedEffect
        val editorOpen = editingChapter != null || editingSession != null || editingTrack != null
        val sync = reconcileStory(storyAtBase, baseRevision, story, session.revision, editorOpen)
        if (sync.conflict) {
            externalNewer = true
        } else if (sync.story != storyAtBase || sync.baseRevision != baseRevision) {
            viewStory = sync.story
            storyAtBase = sync.story
            baseRevision = sync.baseRevision
            nameDraft = sync.story.campaignName
            externalNewer = false
        }
    }

    val blocked = busy || saving
    val editable = writable && !blocked
    val gameMasterView = session.state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER
    val visibleStory = storyForRole(viewStory, gameMasterView)

    fun reloadLatest() {
        if (session.revision >= baseRevision) {
            viewStory = story
            storyAtBase = story
            baseRevision = session.revision
            nameDraft = story.campaignName
            externalNewer = false
            localError = null
        } else {
            localError = if (chinese) "宿主尚未提供更新的故事，请稍后重试。" else
                "The host has not published a newer story yet; retry shortly."
        }
        // An explicit reload discards editor drafts on purpose.
        editingChapter = null
        editingSession = null
        editingTrack = null
    }

    suspend fun runCommand(expectedRevision: Long, command: TrpgStoryCommand): Boolean {
        if (!editable) return false
        if (externalNewer || session.revision > baseRevision) {
            externalNewer = true
            localError = storyConflict(chinese)
            return false
        }
        if (expectedRevision != baseRevision) {
            externalNewer = true
            localError = storyConflict(chinese)
            return false
        }
        val next = try {
            TrpgStoryReducer.apply(viewStory, command)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: storyFailure(chinese)
            return false
        }
        busy = true
        return try {
            val returned = onCommand(expectedRevision, command)
            viewStory = next
            storyAtBase = next
            baseRevision = returned.revision
            nameDraft = next.campaignName
            externalNewer = false
            localError = null
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: storyFailure(chinese)
            false
        } finally {
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!blocked) onDismiss() },
        modifier = Modifier.testTag("trpg.story.dialog"),
        title = { Text(if (chinese) "故事、任务与时钟" else "Story, quests & clocks", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                val problem = error ?: localError
                if (problem != null) {
                    Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp).testTag("trpg.story.error"))
                }
                if (externalNewer) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (chinese) "故事已有更新 #${session.revision}；草稿未覆盖。"
                                else "The story changed to #${session.revision}; the draft is kept.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { reloadLatest() }, enabled = !blocked,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.reload")) {
                                Text(if (chinese) "载入最新" else "Reload")
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StoryTab(0, tab, chinese, "战役组织", "Story") { tab = it }
                    StoryTab(1, tab, chinese, "任务与线索", "Quests & clues") { tab = it }
                    StoryTab(2, tab, chinese, "时钟", "Clocks") { tab = it }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tab) {
                        0 -> StoryOrganizationTab(
                            story = visibleStory, nameDraft = nameDraft, writable = editable, chinese = chinese,
                            onName = { nameDraft = it.take(TrpgStoryValidation.MAX_CAMPAIGN_NAME) },
                            onRename = {
                                scope.launch {
                                    runCommand(baseRevision, TrpgStoryCommand(
                                        TrpgStoryOperation.RENAME_CAMPAIGN, campaignName = nameDraft.trim()))
                                }
                            },
                            onCreateChapter = {
                                editorRevision = baseRevision
                                editingChapter = TrpgStoryChapter(UUID.randomUUID().toString(), "")
                            },
                            onEditChapter = { chapter -> editorRevision = baseRevision; editingChapter = chapter },
                            onRemoveChapter = { chapter ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.REMOVE_CHAPTER, id = chapter.id)) }
                            },
                            onActivateChapter = { chapter ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.SET_ACTIVE_CHAPTER, chapterId = chapter.id)) }
                            },
                            onCreateSession = { chapterId ->
                                editorRevision = baseRevision
                                editingSession = TrpgStorySession(UUID.randomUUID().toString(), chapterId, "")
                            },
                            onEditSession = { entry -> editorRevision = baseRevision; editingSession = entry },
                            onRemoveSession = { entry ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.REMOVE_SESSION, id = entry.id)) }
                            },
                            onActivateSession = { entry ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.SET_ACTIVE_SESSION, sessionId = entry.id)) }
                            },
                        )
                        1 -> TracksTab(
                            story = visibleStory, kind = null, writable = editable, chinese = chinese,
                            onSource = onSource,
                            onCreate = { kind ->
                                editorRevision = baseRevision
                                editingTrack = TrpgTrack(UUID.randomUUID().toString(), kind, "", goal = 1)
                            },
                            onEdit = { track -> editorRevision = baseRevision; editingTrack = track },
                            onRemove = { track ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.REMOVE_TRACK, id = track.id)) }
                            },
                            onStatus = { track, status ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.UPSERT_TRACK, track = track.copy(status = status))) }
                            },
                        )
                        else -> TracksTab(
                            story = visibleStory, kind = TrpgTrackKind.CLOCK, writable = editable, chinese = chinese,
                            onSource = onSource,
                            onCreate = { kind ->
                                editorRevision = baseRevision
                                editingTrack = TrpgTrack(UUID.randomUUID().toString(), kind, "", goal = 6)
                            },
                            onEdit = { track -> editorRevision = baseRevision; editingTrack = track },
                            onRemove = { track ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.REMOVE_TRACK, id = track.id)) }
                            },
                            onStatus = { track, status ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.UPSERT_TRACK, track = track.copy(status = status))) }
                            },
                            onAdvance = { track, delta ->
                                scope.launch { runCommand(baseRevision, TrpgStoryCommand(
                                    TrpgStoryOperation.ADVANCE_TRACK, id = track.id, delta = delta)) }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, enabled = !blocked,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.close")) {
                Text(if (chinese) "关闭" else "Close")
            }
        },
    )

    editingChapter?.let { initial ->
        TrpgChapterEditor(
            initial = initial, chinese = chinese, enabled = editable,
            expectedRevision = editorRevision, acceptedRevision = baseRevision, externalNewer = externalNewer,
            onReloadLatest = { reloadLatest() },
            onDismiss = { editingChapter = null },
            onSubmit = { command -> runCommand(editorRevision, command) },
        )
    }
    editingSession?.let { initial ->
        TrpgSessionEditor(
            initial = initial, chapters = visibleStory.chapters, chinese = chinese, enabled = editable,
            expectedRevision = editorRevision, acceptedRevision = baseRevision, externalNewer = externalNewer,
            onReloadLatest = { reloadLatest() },
            onDismiss = { editingSession = null },
            onSubmit = { command -> runCommand(editorRevision, command) },
        )
    }
    editingTrack?.let { initial ->
        TrpgTrackEditor(
            initial = initial, chinese = chinese, enabled = editable, recentMessages = recentMessages,
            expectedRevision = editorRevision, acceptedRevision = baseRevision, externalNewer = externalNewer,
            onReloadLatest = { reloadLatest() },
            onSource = onSource,
            onDismiss = { editingTrack = null },
            onSubmit = { command -> runCommand(editorRevision, command) },
        )
    }
}
