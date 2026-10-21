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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import runtime.mobileagent.domain.trpg.TrpgTrack
import runtime.mobileagent.domain.trpg.TrpgTrackKind
import runtime.mobileagent.domain.trpg.TrpgTrackStatus

/* Quests, clues and clocks: creation, status, progress and source message. */

@Composable
internal fun TracksTab(
    story: TrpgStory,
    kind: TrpgTrackKind?,
    writable: Boolean,
    chinese: Boolean,
    onSource: (String) -> Unit,
    onCreate: (TrpgTrackKind) -> Unit,
    onEdit: (TrpgTrack) -> Unit,
    onRemove: (TrpgTrack) -> Unit,
    onStatus: (TrpgTrack, TrpgTrackStatus) -> Unit,
    onAdvance: ((TrpgTrack, Int) -> Unit)? = null,
) {
    val sections = if (kind == null) listOf(TrpgTrackKind.QUEST, TrpgTrackKind.CLUE) else listOf(kind)
    sections.forEach { section ->
        val tracks = story.tracks.filter { it.kind == section }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${storyTrackKindLabel(section, chinese)}（${tracks.size}）",
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { onCreate(section) }, enabled = writable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.track.create.${section.name}")) {
                Text(if (chinese) "新建${storyTrackKindLabel(section, true)}" else "New ${storyTrackKindLabel(section, false).lowercase()}")
            }
        }
        if (tracks.isEmpty()) {
            Text(if (chinese) "暂无" else "None", style = MaterialTheme.typography.bodySmall)
        }
        tracks.forEach { track ->
            StoryCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${track.progress}/${track.goal}", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("trpg.story.track.${track.id}.progress"))
                }
                Text("${storyTrackKindLabel(track.kind, chinese)} · ${storyStatusLabel(track.status, chinese)}" +
                    " · ${storyVisibilityLabel(track.visibility, chinese)}",
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                if (track.description.isNotBlank()) Text(track.description, style = MaterialTheme.typography.bodySmall,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (track.sourceMessageId != null) {
                    TextButton(onClick = { track.sourceMessageId?.let(onSource) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.track.${track.id}.source")) {
                        Text(if (chinese) "查看来源" else "View source")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrpgTrackStatus.entries.forEach { status ->
                        FilterChip(
                            selected = track.status == status,
                            onClick = { onStatus(track, status) },
                            enabled = writable,
                            label = { Text(storyStatusLabel(status, chinese)) },
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("trpg.story.track.${track.id}.status.${status.name}"),
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onAdvance != null && track.kind == TrpgTrackKind.CLOCK) {
                        TextButton(onClick = { onAdvance(track, -1) }, enabled = writable && track.progress > 0,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.track.${track.id}.minus")) {
                            Text("−1")
                        }
                        TextButton(onClick = { onAdvance(track, 1) }, enabled = writable && track.progress < track.goal,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.story.track.${track.id}.plus")) {
                            Text("+1")
                        }
                    }
                    StoryAction(if (chinese) "编辑" else "Edit", "trpg.story.track.${track.id}.edit", writable, onEdit, track)
                    StoryAction(if (chinese) "删除" else "Delete", "trpg.story.track.${track.id}.remove", writable, onRemove, track)
                }
            }
        }
    }
    Text(if (chinese) "进度直接输入在编辑器中；数字不会被截断或改写。" else
        "Enter progress directly in the editor; numbers are never clamped or rewritten.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
