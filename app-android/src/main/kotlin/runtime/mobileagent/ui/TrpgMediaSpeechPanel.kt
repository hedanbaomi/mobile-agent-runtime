// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import runtime.mobileagent.TrpgMediaPolicy
import runtime.mobileagent.TrpgMediaSpeechGate
import runtime.mobileagent.trpgMediaText

/** User-visible state of the optional native recognition panel. */
internal sealed interface TrpgMediaSpeechUi {
    data object Idle : TrpgMediaSpeechUi
    data object Recognized : TrpgMediaSpeechUi
    data object Empty : TrpgMediaSpeechUi
    data object Cancelled : TrpgMediaSpeechUi
    data object Unavailable : TrpgMediaSpeechUi
    data object Inserted : TrpgMediaSpeechUi
}

private data class TrpgMediaSpeechRequest(val token: Long, val conversationId: String)

/**
 * Native `ACTION_RECOGNIZE_SPEECH` launcher.
 *
 * The app does not hold RECORD_AUDIO or run a background SpeechRecognizer; the
 * system recognition activity owns the microphone permission and UI. We only
 * ask for offline preference and say so in the copy, because
 * `EXTRA_PREFER_OFFLINE` may be ignored by the installed recognizer. A result
 * fills an editable draft only: no automatic send, run, or insertion into
 * another conversation.
 */
@Composable
internal fun TrpgMediaSpeechPanel(
    conversationId: String,
    chinese: Boolean,
    canInsertDraft: Boolean,
    onInsertDraft: (String) -> Unit,
) {
    val context = LocalContext.current
    val gate = remember(conversationId) { TrpgMediaSpeechGate() }
    var draft by remember(conversationId) { mutableStateOf("") }
    var status by remember(conversationId) { mutableStateOf<TrpgMediaSpeechUi>(TrpgMediaSpeechUi.Idle) }
    var request by remember(conversationId) { mutableStateOf<TrpgMediaSpeechRequest?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val active = request
        request = null
        if (active == null || active.conversationId != conversationId) {
            return@rememberLauncherForActivityResult
        }
        if (result.resultCode == Activity.RESULT_CANCELED) {
            gate.cancel()
            status = TrpgMediaSpeechUi.Cancelled
            return@rememberLauncherForActivityResult
        }
        val candidates = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val accepted = gate.accept(conversationId, active.token, candidates)
        if (accepted == null) {
            status = TrpgMediaSpeechUi.Empty
            return@rememberLauncherForActivityResult
        }
        draft = accepted
        status = TrpgMediaSpeechUi.Recognized
    }

    fun startRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (chinese) "zh-CN" else "en-US")
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                trpgMediaText(chinese, "请说话；结果只会填入草稿", "Speak; the result only fills the draft"),
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            status = TrpgMediaSpeechUi.Unavailable
            return
        }
        val token = gate.begin(conversationId)
        if (token == null) return
        request = TrpgMediaSpeechRequest(token, conversationId)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            gate.cancel()
            request = null
            status = TrpgMediaSpeechUi.Unavailable
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = trpgMediaText(chinese, "语音输入", "Voice input"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("trpg.media.speech.title"),
        )
        Text(
            text = trpgMediaText(
                chinese,
                "会请求设备离线识别；设备语音服务是否联网由其自身决定，仍可能联网。本应用不直接录音，也不申请录音权限。",
                "Offline recognition is requested; the device speech service may still use the network. This app does not record audio itself and holds no recording permission.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { startRecognition() },
                enabled = request == null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.speech.start"),
            ) {
                Text(trpgMediaText(chinese, "开始语音输入", "Start voice input"))
            }
            TextButton(
                onClick = {
                    draft = ""
                    status = TrpgMediaSpeechUi.Idle
                },
                enabled = draft.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.speech.clear"),
            ) {
                Text(trpgMediaText(chinese, "清空草稿", "Clear draft"))
            }
        }
        Text(
            text = trpgMediaSpeechStatus(chinese, status),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("trpg.media.speech.status"),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(TrpgMediaPolicy.MAX_SPEECH_DRAFT_CHARS) },
            label = { Text(trpgMediaText(chinese, "识别的文字（草稿）", "Recognized text (draft)")) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("trpg.media.speech.draft"),
        )
        TextButton(
            onClick = {
                onInsertDraft(draft.trim())
                status = TrpgMediaSpeechUi.Inserted
            },
            enabled = canInsertDraft && draft.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.speech.insert"),
        ) {
            Text(trpgMediaText(chinese, "插入输入草稿", "Insert into draft"))
        }
        if (!canInsertDraft) {
            Text(
                text = trpgMediaText(chinese, "当前会话只读，不能插入草稿。", "This conversation is read-only; drafts cannot be inserted."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun trpgMediaSpeechStatus(chinese: Boolean, status: TrpgMediaSpeechUi): String = when (status) {
    TrpgMediaSpeechUi.Idle -> trpgMediaText(
        chinese,
        "点击开始后由系统语音界面识别，结果只会填入草稿。",
        "The system speech UI opens after you tap start; the result only fills the draft.",
    )
    TrpgMediaSpeechUi.Recognized -> trpgMediaText(
        chinese,
        "已填入草稿，请检查后再插入；不会自动发送或运行。",
        "Draft filled. Review it before inserting; nothing is sent or run automatically.",
    )
    TrpgMediaSpeechUi.Empty -> trpgMediaText(
        chinese,
        "未识别到可用文字，未写入草稿。",
        "No usable text was recognized; the draft was not changed.",
    )
    TrpgMediaSpeechUi.Cancelled -> trpgMediaText(
        chinese,
        "已取消语音输入，未写入草稿。",
        "Voice input was cancelled; the draft was not changed.",
    )
    TrpgMediaSpeechUi.Unavailable -> trpgMediaText(
        chinese,
        "设备没有可用的语音识别界面。本应用不会直接录音。",
        "No speech recognition UI is available on this device. This app does not record audio itself.",
    )
    TrpgMediaSpeechUi.Inserted -> trpgMediaText(
        chinese,
        "已插入输入草稿，请检查后自行发送。",
        "Inserted into the composer draft. Review it, then send it yourself.",
    )
}
