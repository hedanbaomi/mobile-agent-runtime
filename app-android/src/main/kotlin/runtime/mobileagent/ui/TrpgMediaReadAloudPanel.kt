// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import runtime.mobileagent.TrpgMediaPolicy
import runtime.mobileagent.TrpgMediaSpeechState
import runtime.mobileagent.TrpgMediaSpeechStatus
import runtime.mobileagent.trpgMediaText

private val TRPG_MEDIA_SPEEDS = listOf(0.75f to "0.75x", 1f to "1x", 1.25f to "1.25x", 1.5f to "1.5x")

/**
 * Read-aloud controls for the text the user can see.
 *
 * The text is previewed and editable before any speech starts; only an
 * installed offline voice is used and a missing voice is reported instead of
 * downloading or falling back to the network. Progress comes from the speech
 * callback, not from the enqueue result, and stop is explicit.
 */
@Composable
internal fun TrpgMediaReadAloudPanel(
    chinese: Boolean,
    text: String,
    speed: Float,
    state: TrpgMediaSpeechState,
    onTextChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = trpgMediaText(chinese, "朗读", "Read aloud"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("trpg.media.tts.title"),
        )
        Text(
            text = trpgMediaText(
                chinese,
                "只朗读下面这段可见文字。使用本机离线语音；找不到离线语音时会说明不可用，不会下载语音包或改用联网语音。",
                "Reads only the visible text below, using an installed offline voice. If none exists it says so, without downloading voices or using a network voice.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { onTextChange(it.take(TrpgMediaPolicy.MAX_READ_ALOUD_CHARS)) },
            label = { Text(trpgMediaText(chinese, "要朗读的文字（可修改）", "Text to read (editable)")) },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().testTag("trpg.media.tts.text"),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = onPlay,
                enabled = state.status == TrpgMediaSpeechStatus.READY && text.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.tts.play"),
            ) {
                Text(trpgMediaText(chinese, "播放", "Play"))
            }
            TextButton(
                onClick = onStop,
                enabled = state.status == TrpgMediaSpeechStatus.SPEAKING,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.tts.stop"),
            ) {
                Text(trpgMediaText(chinese, "停止", "Stop"))
            }
        }
        Text(
            text = trpgMediaReadAloudStatus(chinese, state),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("trpg.media.tts.status"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = trpgMediaText(chinese, "速度", "Speed"),
                style = MaterialTheme.typography.bodySmall,
            )
            TRPG_MEDIA_SPEEDS.forEach { (value, label) ->
                FilterChip(
                    selected = speed == value,
                    onClick = { onSpeedChange(value) },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

private fun trpgMediaReadAloudStatus(chinese: Boolean, state: TrpgMediaSpeechState): String = when (state.status) {
    TrpgMediaSpeechStatus.IDLE -> trpgMediaText(
        chinese,
        "尚未初始化本机语音。",
        "The local speech engine has not been initialized yet.",
    )
    TrpgMediaSpeechStatus.INITIALIZING -> trpgMediaText(
        chinese,
        "正在检查本机离线语音…",
        "Checking the installed offline voice…",
    )
    TrpgMediaSpeechStatus.READY -> trpgMediaText(
        chinese,
        "本机离线语音已就绪。只读取上方文字，可随时停止。",
        "Offline voice ready. Only the text above is read; stop at any time.",
    )
    TrpgMediaSpeechStatus.SPEAKING -> trpgMediaText(
        chinese,
        "正在朗读第 ${state.chunkIndex + 1}/${state.chunkCount} 段，可随时停止。",
        "Reading part ${state.chunkIndex + 1} of ${state.chunkCount}; stop at any time.",
    )
    TrpgMediaSpeechStatus.UNAVAILABLE -> trpgMediaText(
        chinese,
        "没有匹配的离线语音，朗读不可用。不会下载语音包，也不会改用联网语音。",
        "No matching offline voice is installed, so read-aloud is unavailable. Nothing is downloaded and no network voice is used.",
    )
    TrpgMediaSpeechStatus.FAILED -> trpgMediaText(
        chinese,
        "本机语音引擎报错，朗读已停止；不会自动重试或联网。",
        "The local speech engine failed and reading stopped. Nothing is retried over the network.",
    )
    TrpgMediaSpeechStatus.CLOSED -> ""
}
