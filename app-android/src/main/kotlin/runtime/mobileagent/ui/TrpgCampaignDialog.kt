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

/* ------------------------------- pure helpers ------------------------------- */

/** Player view hides GM-secret checks; the table host sees every check. */
internal fun visibleTrpgChecks(checks: List<TrpgCheck>, gameMasterView: Boolean): List<TrpgCheck> =
    if (gameMasterView) checks else checks.filter { it.request.visibility == TrpgVisibility.PLAYER }

/** Player view hides GM-secret facts. */
internal fun visibleTrpgFacts(facts: List<TrpgFact>, gameMasterView: Boolean): List<TrpgFact> =
    if (gameMasterView) facts else facts.filter { it.visibility == TrpgVisibility.PLAYER }

/** Pinned resources are a small, ordered subset of the real resources (at most [limit]). */
internal fun togglePinnedResource(current: List<String>, name: String, limit: Int = 8): List<String> = when {
    name in current -> current - name
    current.size >= limit -> current
    else -> current + name
}

/**
 * Keeps local draft fields (text/resources/preferences) while adopting the authoritative campaign
 * facts/checks, so commands and a later save do not overwrite each other.
 */
internal fun mergeCampaignForSave(draft: TrpgState, authoritative: TrpgState): TrpgState =
    draft.copy(campaign = authoritative.campaign.copy(preferences = draft.campaign.preferences))

/**
 * The part of the state this dialog may edit directly: the six legacy root fields plus campaign
 * preferences. Facts/checks are command-owned and excluded so command results never look like
 * local root edits (and an external root edit cannot be silently overwritten by a stale draft).
 */
internal fun editableTrpgProjection(state: TrpgState): TrpgState =
    state.copy(campaign = TrpgCampaign(preferences = state.campaign.preferences))

/** A save may use the current revision only while the host's editable projection equals baseline. */
internal fun canSaveTrpgDraft(baseline: TrpgState, current: TrpgState): Boolean =
    editableTrpgProjection(baseline) == editableTrpgProjection(current)

internal fun campaignFailure(chinese: Boolean): String =
    if (chinese) "团务操作失败，草稿已保留。" else "Campaign operation failed; your draft was kept."

/* --------------------------------- surface --------------------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrpgCampaignDialog(
    session: TrpgSession,
    writable: Boolean,
    saving: Boolean,
    error: String?,
    chinese: Boolean,
    recentMessages: List<Message>,
    onSave: suspend (Long, TrpgState) -> TrpgSession,
    onCommand: suspend (Long, TrpgCommand) -> TrpgSession,
    onRollCheck: suspend (Long, String) -> TrpgSession,
    onSource: (String) -> Unit,
    onInsertDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val conversationId = session.conversationId
    val scope = rememberCoroutineScope()
    // Draft identity is the conversation; the baseline is the authoritative state the draft was
    // built from. Only an explicit reload replaces the draft.
    var draft by remember(conversationId) { mutableStateOf(session.state) }
    var baseline by remember(conversationId) { mutableStateOf(session.state) }
    var externalNewer by remember(conversationId) { mutableStateOf(false) }
    var busy by remember(conversationId) { mutableStateOf(false) }
    var tab by remember(conversationId) { mutableIntStateOf(0) }
    var localError by remember(conversationId) { mutableStateOf<String?>(null) }
    var editingCheck by remember(conversationId) { mutableStateOf<TrpgCheckRequest?>(null) }
    var editingFact by remember(conversationId) { mutableStateOf<TrpgFact?>(null) }
    var editingFactRevision by remember(conversationId) { mutableLongStateOf(session.revision) }
    var resolutionNotes by remember(conversationId) { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(session.revision) {
        // External edits to the editable root/preferences are never adopted into the draft.
        if (editableTrpgProjection(session.state) != editableTrpgProjection(baseline)) externalNewer = true
    }

    val gameMasterView = draft.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER
    val blocked = busy || saving
    val editable = writable && !blocked
    val visibleState = if (gameMasterView) session.state else runtime.mobileagent.domain.trpg.TrpgProjection.player(session.state)
    val checks = visibleState.campaign.checks
    val facts = visibleState.campaign.facts
    val pref = draft.campaign.preferences

    // Only facts/checks are adopted from command results; root fields and preferences stay the
    // baseline this draft was built on. A command that also changed them is an external change.
    suspend fun adoptCommandResult(returned: TrpgSession) {
        val rootChanged = editableTrpgProjection(returned.state) != editableTrpgProjection(baseline)
        baseline = baseline.copy(campaign = baseline.campaign.copy(
            facts = returned.state.campaign.facts,
            checks = returned.state.campaign.checks,
        ))
        if (rootChanged) externalNewer = true
    }

    suspend fun runCommand(command: TrpgCommand, expectedRevision: Long = session.revision): Boolean {
        if (!editable) return false
        busy = true
        return try {
            adoptCommandResult(onCommand(expectedRevision, command))
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: campaignFailure(chinese)
            false
        } finally {
            busy = false
        }
    }

    suspend fun runRoll(id: String): Boolean {
        if (!editable) return false
        busy = true
        return try {
            adoptCommandResult(onRollCheck(session.revision, id))
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: campaignFailure(chinese)
            false
        } finally {
            busy = false
        }
    }

    suspend fun saveDraft(): Boolean {
        if (!editable) return false
        if (editableTrpgProjection(session.state) != editableTrpgProjection(baseline)) {
            externalNewer = true
            localError = if (chinese) "根字段或偏好已被其他写入更新；草稿保留，请先载入最新。" else
                "Root fields or preferences changed elsewhere; your draft is kept — reload latest first."
            return false
        }
        val merged = mergeCampaignForSave(draft, session.state)
        val valid = runCatching { TrpgStateValidation.validate(merged) }
            .onFailure { localError = it.message ?: campaignFailure(chinese) }
            .isSuccess
        if (!valid) return false
        busy = true
        return try {
            val returned = onSave(session.revision, merged)
            val submitted = editableTrpgProjection(merged)
            val accepted = editableTrpgProjection(returned.state)
            if (accepted != submitted) {
                adoptCommandResult(returned)
                localError = if (chinese) "宿主接受的根字段/偏好与提交不一致；草稿保留。" else
                    "Host accepted different root fields/preferences; your draft is kept."
                externalNewer = true
                false
            } else {
                baseline = returned.state
                draft = returned.state
                externalNewer = false
                localError = null
                true
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: campaignFailure(chinese)
            false
        } finally {
            busy = false
        }
    }

    suspend fun createCheck(request: TrpgCheckRequest): Boolean {
        val valid = runCatching { runtime.mobileagent.domain.trpg.TrpgCampaignValidation.validate(request) }
            .onFailure { localError = it.message }.isSuccess
        if (!valid) return false
        val accepted = runCommand(TrpgCommand(TrpgOperation.REQUEST_CHECK, check = request))
        if (accepted) editingCheck = null
        return accepted
    }

    suspend fun applyFact(fact: TrpgFact): Boolean {
        val valid = runCatching { runtime.mobileagent.domain.trpg.TrpgCampaignValidation.validate(fact) }
            .onFailure { localError = it.message }.isSuccess
        if (!valid) return false
        val accepted = runCommand(TrpgCommand(TrpgOperation.UPSERT_FACT, fact = fact), editingFactRevision)
        if (accepted) editingFact = null
        return accepted
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trpg.campaign.dialog"),
        title = { Text(if (chinese) "团务" else "Campaign", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                val problem = error ?: localError
                if (problem != null) {
                    Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp).testTag("trpg.campaign.error"))
                }
                if (externalNewer) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (chinese) "会话已有更新 #${session.revision}；草稿未变。" else "Session changed to #${session.revision}; your draft is kept.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                draft = session.state
                                baseline = session.state
                                externalNewer = false
                                localError = null
                            }, enabled = !blocked,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.reload")) {
                                Text(if (chinese) "载入最新" else "Reload")
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CampaignTab(0, tab, chinese, "开团", "Setup") { tab = it }
                    CampaignTab(1, tab, chinese, "检定", "Checks") { tab = it }
                    CampaignTab(2, tab, chinese, "事实", "Facts") { tab = it }
                    CampaignTab(3, tab, chinese, "回顾", "Recap") { tab = it }
                    CampaignTab(4, tab, chinese, "偏好", "Prefs") { tab = it }
                }
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        0 -> SetupTab(session, draft, editable, chinese) { draft = it }
                        1 -> ChecksTab(
                            checks = checks, writable = editable, chinese = chinese,
                            notes = resolutionNotes,
                            onNote = { id, text -> resolutionNotes = resolutionNotes + (id to text) },
                            onRoll = { id -> scope.launch { runRoll(id) } },
                            onResolve = { id -> scope.launch { runCommand(TrpgCommand(TrpgOperation.RESOLVE_CHECK, id = id, note = resolutionNotes[id])) } },
                            onCancel = { id -> scope.launch { runCommand(TrpgCommand(TrpgOperation.CANCEL_CHECK, id = id)) } },
                            onArchive = { id -> scope.launch { runCommand(TrpgCommand(TrpgOperation.ARCHIVE_CHECK, id = id)) } },
                            onInsert = { text -> onInsertDraft(text) },
                            onCreate = { editingCheck = TrpgCheckRequest(id = UUID.randomUUID().toString(), title = "") },
                        )
                        2 -> FactsTab(
                            facts = facts, writable = editable, chinese = chinese,
                            onSource = onSource,
                            onEdit = { editingFactRevision = session.revision; editingFact = it },
                            onRemove = { scope.launch { runCommand(TrpgCommand(TrpgOperation.REMOVE_FACT, id = it.id)) } },
                            onCreate = { editingFactRevision = session.revision; editingFact = TrpgFact(id = UUID.randomUUID().toString(), text = "") },
                        )
                        3 -> RecapTab(session, draft, facts, recentMessages, chinese, onSource, onOpenJournal)
                        else -> PreferencesTab(pref, draft, editable, chinese) { draft = it }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { scope.launch { saveDraft() } },
                enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.save"),
            ) { Text(if (chinese) "保存团务" else "Save campaign") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.close")) {
                Text(if (chinese) "关闭" else "Close")
            }
        },
    )

    editingCheck?.let { request ->
        TrpgCheckEditor(
            initial = request,
            chinese = chinese,
            enabled = editable,
            onDismiss = { editingCheck = null },
            onSubmit = { created -> createCheck(created) },
        )
    }
    editingFact?.let { fact ->
        TrpgFactEditor(
            initial = fact,
            recentMessages = recentMessages,
            chinese = chinese,
            enabled = editable,
            onDismiss = { editingFact = null },
            onSubmit = { updated -> applyFact(updated) },
        )
    }
}

@Composable
private fun CampaignTab(index: Int, selected: Int, chinese: Boolean, zh: String, en: String, onSelect: (Int) -> Unit) {
    FilterChip(
        selected = selected == index,
        onClick = { onSelect(index) },
        label = { Text(if (chinese) zh else en) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.campaign.tab.$index"),
    )
}

