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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.trpg.TrpgStory
import runtime.mobileagent.domain.trpg.TrpgStoryChapter
import runtime.mobileagent.domain.trpg.TrpgStorySession
import runtime.mobileagent.domain.trpg.TrpgStoryValidation

/* Campaign organization: campaign name, chapters, sessions and their relationship. */

@Composable
internal fun StoryOrganizationTab(
    story: TrpgStory,
    nameDraft: String,
    writable: Boolean,
    chinese: Boolean,
    onName: (String) -> Unit,
    onRename: () -> Unit,
    onCreateChapter: () -> Unit,
    onEditChapter: (TrpgStoryChapter) -> Unit,
    onRemoveChapter: (TrpgStoryChapter) -> Unit,
    onActivateChapter: (TrpgStoryChapter) -> Unit,
    onCreateSession: (String?) -> Unit,
    onEditSession: (TrpgStorySession) -> Unit,
    onRemoveSession: (TrpgStorySession) -> Unit,
    onActivateSession: (TrpgStorySession) -> Unit,
) {
    SectionTitle(if (chinese) "战役" else "Campaign")
    OutlinedTextField(
        value = nameDraft, onValueChange = onName, enabled = writable, singleLine = true,
        label = { Text(if (chinese) "战役名 (≤128)" else "Campaign name (≤128)") },
        modifier = Modifier.fillMaxWidth().testTag("trpg.story.name"),
    )
    TextButton(
        onClick = onRename,
        enabled = writable && nameDraft.isNotBlank() && nameDraft.trim() != story.campaignName,
        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.name.apply"),
    ) { Text(if (chinese) "重命名战役" else "Rename campaign") }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "章节（${story.chapters.size}/${TrpgStoryValidation.MAX_CHAPTERS}）"
            else "Chapters (${story.chapters.size}/${TrpgStoryValidation.MAX_CHAPTERS})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onCreateChapter, enabled = writable,
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.chapter.create")) {
            Text(if (chinese) "新建章节" else "New chapter")
        }
    }
    if (story.chapters.isEmpty()) {
        Text(if (chinese) "暂无章节；可先新建章节，或直接创建未归章场次。" else "No chapters yet; create one or add an unassigned session.",
            style = MaterialTheme.typography.bodySmall)
    }

    story.chapters.forEach { chapter ->
        val active = story.activeChapterId == chapter.id
        StoryCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(chapter.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (active) Text(if (chinese) "当前" else "Current", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(if (chinese) "可见性：${storyVisibilityLabel(chapter.visibility, true)}"
                else "Visibility: ${storyVisibilityLabel(chapter.visibility, false)}",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            if (chapter.summary.isNotBlank()) Text(chapter.summary, style = MaterialTheme.typography.bodySmall,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!active) StoryAction(if (chinese) "设为当前章节" else "Set current", "trpg.story.chapter.${chapter.id}.activate", writable, onActivateChapter, chapter)
                StoryAction(if (chinese) "编辑" else "Edit", "trpg.story.chapter.${chapter.id}.edit", writable, onEditChapter, chapter)
                StoryAction(if (chinese) "删除" else "Delete", "trpg.story.chapter.${chapter.id}.remove", writable, onRemoveChapter, chapter)
                StoryAction(if (chinese) "新建场次" else "New session", "trpg.story.chapter.${chapter.id}.session.create", writable, { onCreateSession(it.id) }, chapter)
            }
            val sessions = story.sessions.filter { it.chapterId == chapter.id }
            if (sessions.isEmpty()) {
                Text(if (chinese) "本章暂无场次" else "No sessions in this chapter", style = MaterialTheme.typography.bodySmall)
            }
            sessions.forEach { entry ->
                SessionCard(entry, story.activeSessionId == entry.id, writable, chinese,
                    onEditSession, onRemoveSession, onActivateSession)
            }
        }
    }

    val unassigned = story.sessions.filter { it.chapterId == null }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (chinese) "未归章场次（${unassigned.size}）" else "Unassigned sessions (${unassigned.size})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { onCreateSession(null) }, enabled = writable,
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.session.create.unassigned")) {
            Text(if (chinese) "新建场次" else "New session")
        }
    }
    if (unassigned.isEmpty()) Text(if (chinese) "暂无" else "None", style = MaterialTheme.typography.bodySmall)
    unassigned.forEach { entry ->
        SessionCard(entry, story.activeSessionId == entry.id, writable, chinese,
            onEditSession, onRemoveSession, onActivateSession)
    }
    Text(if (chinese) "设置场次会同步其所属章节；切换到另一章节会清除不匹配的当前场次。"
        else "Setting a session syncs its chapter; switching chapters clears a mismatched current session.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SessionCard(
    entry: TrpgStorySession,
    active: Boolean,
    writable: Boolean,
    chinese: Boolean,
    onEdit: (TrpgStorySession) -> Unit,
    onRemove: (TrpgStorySession) -> Unit,
    onActivate: (TrpgStorySession) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (active) Text(if (chinese) "当前场次" else "Current session",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(if (chinese) "可见性：${storyVisibilityLabel(entry.visibility, true)}"
                else "Visibility: ${storyVisibilityLabel(entry.visibility, false)}",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            if (entry.recap.isNotBlank()) Text(entry.recap, style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!active) StoryAction(if (chinese) "设为当前" else "Set current",
                    "trpg.story.session.${entry.id}.activate", writable, onActivate, entry)
                StoryAction(if (chinese) "编辑" else "Edit", "trpg.story.session.${entry.id}.edit", writable, onEdit, entry)
                StoryAction(if (chinese) "删除" else "Delete", "trpg.story.session.${entry.id}.remove", writable, onRemove, entry)
            }
        }
    }
}
