// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.trpg.TrpgStoryChapter
import runtime.mobileagent.domain.trpg.TrpgStoryCommand
import runtime.mobileagent.domain.trpg.TrpgStoryOperation
import runtime.mobileagent.domain.trpg.TrpgStorySession
import runtime.mobileagent.domain.trpg.TrpgStoryValidation

/* Chapter and session editors. Each captures the revision it opened on. */

@Composable
internal fun TrpgChapterEditor(
    initial: TrpgStoryChapter,
    chinese: Boolean,
    enabled: Boolean,
    expectedRevision: Long,
    acceptedRevision: Long,
    externalNewer: Boolean,
    onReloadLatest: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: suspend (TrpgStoryCommand) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id) { mutableStateOf(false) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var summary by remember(initial.id) { mutableStateOf(initial.summary) }
    var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
    var localError by remember(initial.id) { mutableStateOf<String?>(null) }
    val conflict = externalNewer || storyEditorSubmitRevision(expectedRevision, acceptedRevision) == null
    TrpgEditorFrame(
        subTagPrefix = "trpg.story.editor",
        tag = "trpg.story.chapter.editor",
        title = if (initial.title.isBlank()) (if (chinese) "新建章节" else "New chapter") else
            (if (chinese) "编辑章节" else "Edit chapter"),
        chinese = chinese, enabled = enabled, submitting = submitting, conflict = conflict,
        localError = localError, expectedRevision = expectedRevision, acceptedRevision = acceptedRevision,
        onReloadLatest = onReloadLatest, onDismiss = onDismiss, confirmLabel = if (chinese) "保存" else "Save",
        onConfirm = {
            if (conflict) {
                localError = storyConflict(chinese)
            } else if (title.isBlank()) {
                localError = if (chinese) "章节标题不能为空。" else "A chapter title is required."
            } else if (title.trim().length > TrpgStoryValidation.MAX_TITLE) {
                localError = if (chinese) "标题超过 128 字符。" else "The title exceeds 128 characters."
            } else if (summary.length > TrpgStoryValidation.MAX_SUMMARY) {
                localError = if (chinese) "摘要超过 2048 字符。" else "The summary exceeds 2048 characters."
            } else {
                scope.launch {
                    submitting = true
                    try {
                        val command = TrpgStoryCommand(TrpgStoryOperation.UPSERT_CHAPTER,
                            chapter = initial.copy(title = title.trim(), summary = summary, visibility = visibility))
                        if (onSubmit(command)) onDismiss()
                    } finally { submitting = false }
                }
            }
        },
    ) {
        OutlinedTextField(title, { title = it.take(TrpgStoryValidation.MAX_TITLE) }, enabled = enabled && !submitting,
            singleLine = true, label = { Text(if (chinese) "章节标题 (≤128)" else "Chapter title (≤128)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.chapter.editor.title"))
        OutlinedTextField(summary, { summary = it.take(TrpgStoryValidation.MAX_SUMMARY) },
            enabled = enabled && !submitting, minLines = 3, maxLines = 6,
            label = { Text(if (chinese) "摘要 (≤2048)" else "Summary (≤2048)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.chapter.editor.summary"))
        TrpgVisibilityChips(visibility, enabled && !submitting, chinese) { visibility = it }
    }
}

@Composable
internal fun TrpgSessionEditor(
    initial: TrpgStorySession,
    chapters: List<TrpgStoryChapter>,
    chinese: Boolean,
    enabled: Boolean,
    expectedRevision: Long,
    acceptedRevision: Long,
    externalNewer: Boolean,
    onReloadLatest: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: suspend (TrpgStoryCommand) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id) { mutableStateOf(false) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var recap by remember(initial.id) { mutableStateOf(initial.recap) }
    var chapterId by remember(initial.id) { mutableStateOf(initial.chapterId) }
    var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
    var localError by remember(initial.id) { mutableStateOf<String?>(null) }
    val conflict = externalNewer || storyEditorSubmitRevision(expectedRevision, acceptedRevision) == null
    TrpgEditorFrame(
        subTagPrefix = "trpg.story.editor",
        tag = "trpg.story.session.editor",
        title = if (initial.title.isBlank()) (if (chinese) "新建场次" else "New session") else
            (if (chinese) "编辑场次" else "Edit session"),
        chinese = chinese, enabled = enabled, submitting = submitting, conflict = conflict,
        localError = localError, expectedRevision = expectedRevision, acceptedRevision = acceptedRevision,
        onReloadLatest = onReloadLatest, onDismiss = onDismiss, confirmLabel = if (chinese) "保存" else "Save",
        onConfirm = {
            if (conflict) {
                localError = storyConflict(chinese)
            } else if (title.isBlank()) {
                localError = if (chinese) "场次标题不能为空。" else "A session title is required."
            } else if (title.trim().length > TrpgStoryValidation.MAX_TITLE) {
                localError = if (chinese) "标题超过 128 字符。" else "The title exceeds 128 characters."
            } else if (recap.length > TrpgStoryValidation.MAX_SUMMARY) {
                localError = if (chinese) "回顾超过 2048 字符。" else "The recap exceeds 2048 characters."
            } else if (chapterId != null && chapters.none { it.id == chapterId }) {
                localError = if (chinese) "所选章节不存在，请载入最新。" else "The selected chapter is missing; reload the latest."
            } else {
                scope.launch {
                    submitting = true
                    try {
                        val command = TrpgStoryCommand(TrpgStoryOperation.UPSERT_SESSION, session = initial.copy(
                            title = title.trim(), recap = recap, chapterId = chapterId, visibility = visibility))
                        if (onSubmit(command)) onDismiss()
                    } finally { submitting = false }
                }
            }
        },
    ) {
        OutlinedTextField(title, { title = it.take(TrpgStoryValidation.MAX_TITLE) }, enabled = enabled && !submitting,
            singleLine = true, label = { Text(if (chinese) "场次标题 (≤128)" else "Session title (≤128)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.session.editor.title"))
        OutlinedTextField(recap, { recap = it.take(TrpgStoryValidation.MAX_SUMMARY) },
            enabled = enabled && !submitting, minLines = 3, maxLines = 6,
            label = { Text(if (chinese) "回顾 (≤2048)" else "Recap (≤2048)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.story.session.editor.recap"))
        Text(if (chinese) "所属章节" else "Chapter", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = chapterId == null, onClick = { chapterId = null }, enabled = enabled && !submitting,
                label = { Text(if (chinese) "未归章" else "Unassigned") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.session.editor.chapter.none"))
            chapters.forEach { chapter ->
                FilterChip(selected = chapterId == chapter.id, onClick = { chapterId = chapter.id },
                    enabled = enabled && !submitting,
                    label = { Text(chapter.title, maxLines = 1) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("trpg.story.session.editor.chapter.${chapter.id}"))
            }
        }
        TrpgVisibilityChips(visibility, enabled && !submitting, chinese) { visibility = it }
    }
}
