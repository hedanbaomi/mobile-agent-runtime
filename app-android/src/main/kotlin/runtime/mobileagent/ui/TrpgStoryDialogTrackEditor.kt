// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.trpg.TrpgStoryCommand
import runtime.mobileagent.domain.trpg.TrpgStoryOperation
import runtime.mobileagent.domain.trpg.TrpgTrack
import runtime.mobileagent.domain.trpg.TrpgTrackStatus

/* Track editor: raw text/number drafts with explicit submit validation. */

@Composable
internal fun TrpgTrackEditor(
    initial: TrpgTrack,
    chinese: Boolean,
    enabled: Boolean,
    recentMessages: List<Message>,
    expectedRevision: Long,
    acceptedRevision: Long,
    externalNewer: Boolean,
    onReloadLatest: () -> Unit,
    onSource: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: suspend (TrpgStoryCommand) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id) { mutableStateOf(false) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var status by remember(initial.id) { mutableStateOf(initial.status) }
    var progress by remember(initial.id) { mutableStateOf(initial.progress.toString()) }
    var goal by remember(initial.id) { mutableStateOf(initial.goal.toString()) }
    var sourceId by remember(initial.id) { mutableStateOf(initial.sourceMessageId) }
    var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
    var localError by remember(initial.id) { mutableStateOf<String?>(null) }
    val conflict = externalNewer || storyEditorSubmitRevision(expectedRevision, acceptedRevision) == null
    val kindLabel = storyTrackKindLabel(initial.kind, chinese)
    StoryEditorFrame(
        tag = "trpg.story.track.editor",
        title = if (initial.title.isBlank()) (if (chinese) "新建$kindLabel" else "New ${kindLabel.lowercase()}") else
            (if (chinese) "编辑$kindLabel" else "Edit ${kindLabel.lowercase()}"),
        chinese = chinese, enabled = enabled, submitting = submitting, conflict = conflict,
        localError = localError, expectedRevision = expectedRevision, acceptedRevision = acceptedRevision,
        onReloadLatest = onReloadLatest, onDismiss = onDismiss, confirmLabel = if (chinese) "保存" else "Save",
        onConfirm = {
            val draft = StoryTrackDraft(initial.id, initial.kind, title, description, status, progress, goal,
                sourceId, visibility)
            val problem = when {
                conflict -> storyConflict(chinese)
                else -> validateStoryTrackDraft(draft, chinese)
            }
            if (problem != null) {
                localError = problem
            } else {
                val track = checkNotNull(buildStoryTrack(draft))
                scope.launch {
                    submitting = true
                    try {
                        if (onSubmit(TrpgStoryCommand(TrpgStoryOperation.UPSERT_TRACK, track = track))) onDismiss()
                    } finally { submitting = false }
                }
            }
        },
    ) {
        OutlinedTextField(title, { title = it }, enabled = enabled && !submitting, singleLine = true,
            label = { Text(if (chinese) "标题 (≤128)" else "Title (≤128)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.track.editor.title"))
        OutlinedTextField(description, { description = it }, enabled = enabled && !submitting,
            minLines = 3, maxLines = 6,
            label = { Text(if (chinese) "说明 (≤2048)" else "Description (≤2048)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.track.editor.description"))
        Text(if (chinese) "状态" else "Status", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TrpgTrackStatus.entries.forEach { option ->
                FilterChip(selected = status == option, onClick = { status = option }, enabled = enabled && !submitting,
                    label = { Text(storyStatusLabel(option, chinese)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("trpg.story.track.editor.status.${option.name}"))
            }
        }
        OutlinedTextField(goal, { goal = it }, enabled = enabled && !submitting, singleLine = true,
            label = { Text(if (chinese) "目标 1..1000" else "Goal 1..1000") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.track.editor.goal"))
        OutlinedTextField(progress, { progress = it }, enabled = enabled && !submitting, singleLine = true,
            label = { Text(if (chinese) "进度 0..目标（可直接输入）" else "Progress 0..goal (direct input)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.track.editor.progress"))
        Text(if (chinese) "数字允许空或负号继续编辑；提交时才校验，不做截断或改写。"
            else "Empty or a leading minus stays editable; validation happens on submit without clamping.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (chinese) "来源（真实最近消息或空）" else "Source (a real recent message or none)",
            style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = sourceId == null, onClick = { sourceId = null }, enabled = enabled && !submitting,
                label = { Text(if (chinese) "无来源" else "No source") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.track.editor.source.none"))
            recentMessages.takeLast(8).forEach { message ->
                FilterChip(selected = sourceId == message.id, onClick = { sourceId = message.id },
                    enabled = enabled && !submitting,
                    label = { Text("#${message.role.name.lowercase().take(4)} ${message.text.take(24)}", maxLines = 1) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("trpg.story.track.editor.source.option.${message.id}"))
            }
        }
        val savedSource = sourceId
        if (savedSource != null && recentMessages.none { it.id == savedSource }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (chinese) "已保存来源：$savedSource" else "Saved source: $savedSource",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSource(savedSource) }, enabled = enabled && !submitting,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.track.editor.source.open")) {
                    Text(if (chinese) "看原文" else "Open")
                }
            }
        }
        VisibilityChips(visibility, enabled && !submitting, chinese) { visibility = it }
    }
}
