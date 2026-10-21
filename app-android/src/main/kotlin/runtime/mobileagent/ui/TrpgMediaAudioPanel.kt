// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import runtime.mobileagent.TrpgMediaAudioState
import runtime.mobileagent.TrpgMediaAudioStatus
import runtime.mobileagent.trpgMediaText

/**
 * User-picked ambience audio.
 *
 * A stored or newly picked URI is staged only; playback always needs the
 * explicit play gesture. The file is handed to the local player as a content
 * URI, never uploaded, and is not part of campaign backup or model context.
 */
@Composable
internal fun TrpgMediaAudioPanel(
    chinese: Boolean,
    uri: String?,
    name: String?,
    message: String?,
    state: TrpgMediaAudioState,
    onChoose: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    onLooping: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val hasSelection = uri != null
    val playEnabled = hasSelection && state.status in setOf(
        TrpgMediaAudioStatus.STAGED,
        TrpgMediaAudioStatus.READY,
        TrpgMediaAudioStatus.PAUSED,
        TrpgMediaAudioStatus.ERROR,
    )
    val stopEnabled = hasSelection && state.status in setOf(
        TrpgMediaAudioStatus.STAGED,
        TrpgMediaAudioStatus.PREPARING,
        TrpgMediaAudioStatus.READY,
        TrpgMediaAudioStatus.PLAYING,
        TrpgMediaAudioStatus.PAUSED,
        TrpgMediaAudioStatus.ERROR,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = trpgMediaText(chinese, "氛围音频", "Ambience audio"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("trpg.media.audio.title"),
        )
        Text(
            text = trpgMediaText(
                chinese,
                "仅由你从本机选择，不自动播放，不随团务备份转移。播放占用音频焦点，来电或其他应用播放时会自动暂停且不会自动恢复。",
                "Only a local file you choose. It never autoplays and is not part of campaign backups. Playback takes audio focus and pauses when another app or a call needs it, without auto-resuming.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onChoose,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.audio.choose"),
            ) {
                Text(trpgMediaText(chinese, "选择本机音频", "Choose local audio"))
            }
            TextButton(
                onClick = onClear,
                enabled = hasSelection,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.audio.clear"),
            ) {
                Text(trpgMediaText(chinese, "清除选择", "Clear selection"))
            }
        }
        if (hasSelection) {
            val label = if (name.isNullOrBlank()) trpgMediaText(chinese, "本机音频", "Local audio") else name
            Text(
                text = trpgMediaText(chinese, "已选择：$label", "Selected: $label"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trpg.media.audio.selected"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onPlay,
                enabled = playEnabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.audio.play"),
            ) {
                Text(trpgMediaText(chinese, "播放", "Play"))
            }
            TextButton(
                onClick = onPause,
                enabled = state.status == TrpgMediaAudioStatus.PLAYING,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.audio.pause"),
            ) {
                Text(trpgMediaText(chinese, "暂停", "Pause"))
            }
            TextButton(
                onClick = onStop,
                enabled = stopEnabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.audio.stop"),
            ) {
                Text(trpgMediaText(chinese, "停止", "Stop"))
            }
        }
        Text(
            text = trpgMediaAudioStatus(chinese, state),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("trpg.media.audio.status"),
        )
        if (state.notice == "focus_lost") {
            Text(
                text = trpgMediaText(chinese, "音频焦点被其他应用占用，已暂停。", "Audio focus was taken by another app; playback paused."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trpg.media.audio.focus_lost"),
            )
        } else if (state.notice == "focus_denied") {
            Text(
                text = trpgMediaText(chinese, "无法获取音频焦点，未开始播放。", "Could not acquire audio focus; playback did not start."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trpg.media.audio.focus_denied"),
            )
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trpg.media.audio.message"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trpgMediaText(chinese, "音量", "Volume"),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = state.volume,
                onValueChange = onVolume,
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("trpg.media.audio.volume")
                    .semantics { contentDescription = trpgMediaText(chinese, "音量", "Volume") },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.looping,
                onCheckedChange = onLooping,
                modifier = Modifier.testTag("trpg.media.audio.loop"),
            )
            Text(
                text = trpgMediaText(chinese, "循环播放", "Loop"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = trpgMediaText(
                chinese,
                "超过 256 MiB 或无法读取的音频会被拒绝；访问授权失效时会提示重新选择。",
                "Audio over 256 MiB or unreadable audio is rejected; a lost grant asks you to pick the file again.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun trpgMediaAudioStatus(chinese: Boolean, state: TrpgMediaAudioState): String = when (state.status) {
    TrpgMediaAudioStatus.IDLE -> ""
    TrpgMediaAudioStatus.STAGED -> trpgMediaText(
        chinese,
        "已选择（未播放）。按播放才会准备并开始。",
        "Selected (not playing). Press play to prepare and start.",
    )
    TrpgMediaAudioStatus.PREPARING -> trpgMediaText(
        chinese,
        "正在准备音频…",
        "Preparing audio…",
    )
    TrpgMediaAudioStatus.READY -> trpgMediaText(
        chinese,
        "已就绪（未播放）。",
        "Ready (not playing).",
    )
    TrpgMediaAudioStatus.PLAYING -> trpgMediaText(
        chinese,
        "正在播放。",
        "Playing.",
    )
    TrpgMediaAudioStatus.PAUSED -> trpgMediaText(
        chinese,
        "已暂停。",
        "Paused.",
    )
    TrpgMediaAudioStatus.ERROR -> trpgMediaText(
        chinese,
        "无法播放所选音频：文件可能已移动，或访问授权已失效。",
        "The selected audio cannot be played: the file may have moved or the access grant expired.",
    )
    TrpgMediaAudioStatus.CLOSED -> ""
}
