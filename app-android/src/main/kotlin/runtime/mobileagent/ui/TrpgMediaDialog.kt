// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.TrpgMediaAndroidAudioPort
import runtime.mobileagent.TrpgMediaAndroidImageLoader
import runtime.mobileagent.TrpgMediaAndroidTtsPort
import runtime.mobileagent.TrpgMediaAudioController
import runtime.mobileagent.TrpgMediaAudioFiles
import runtime.mobileagent.TrpgMediaAudioRejection
import runtime.mobileagent.TrpgMediaImageLoader
import runtime.mobileagent.TrpgMediaImageResult
import runtime.mobileagent.TrpgMediaPolicy
import runtime.mobileagent.TrpgMediaSelection
import runtime.mobileagent.TrpgMediaSelectionStore
import runtime.mobileagent.TrpgMediaSharedPreferencesStore
import runtime.mobileagent.TrpgMediaSpeechController
import runtime.mobileagent.trpgMediaText

/**
 * Optional native voice input, offline read-aloud, and user-picked ambience
 * audio and scene image for one TRPG conversation.
 *
 * Everything here is off by default and local to the device. The dialog never
 * sends a URI or file content to a model, never records audio itself, never
 * auto-plays a picked or remembered file, and never includes these picks in
 * campaign backups or exports.
 */
@Composable
internal fun TrpgMediaDialog(
    conversationId: String,
    readAloudText: String,
    canInsertDraft: Boolean,
    chinese: Boolean,
    onInsertDraft: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember(appContext) { TrpgMediaSharedPreferencesStore(appContext) }
    val speech = remember(conversationId, chinese) {
        TrpgMediaSpeechController(TrpgMediaAndroidTtsPort(appContext, chinese))
    }
    val audio = remember(conversationId) {
        TrpgMediaAudioController(TrpgMediaAndroidAudioPort(appContext))
    }
    val images = remember(appContext) { TrpgMediaAndroidImageLoader(appContext) }
    TrpgMediaDialogScaffold(
        conversationId = conversationId,
        readAloudText = readAloudText,
        canInsertDraft = canInsertDraft,
        chinese = chinese,
        onInsertDraft = onInsertDraft,
        onDismiss = onDismiss,
        speech = speech,
        audio = audio,
        store = store,
        images = images,
    )
}

@Composable
internal fun TrpgMediaDialogScaffold(
    conversationId: String,
    readAloudText: String,
    canInsertDraft: Boolean,
    chinese: Boolean,
    onInsertDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    speech: TrpgMediaSpeechController,
    audio: TrpgMediaAudioController,
    store: TrpgMediaSelectionStore,
    images: TrpgMediaImageLoader,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val speechState by speech.state.collectAsState()
    val audioState by audio.state.collectAsState()

    var selection by remember(conversationId) { mutableStateOf(TrpgMediaSelection()) }
    var audioName by remember(conversationId) { mutableStateOf<String?>(null) }
    var audioMessage by remember(conversationId) { mutableStateOf<String?>(null) }
    var imageLoading by remember(conversationId) { mutableStateOf(false) }
    var imageResult by remember(conversationId) { mutableStateOf<TrpgMediaImageResult?>(null) }
    var imageNotice by remember(conversationId) { mutableStateOf<String?>(null) }
    var readText by remember(conversationId) { mutableStateOf(readAloudText) }
    var speed by remember(conversationId) { mutableStateOf(1f) }

    fun closeDialog() {
        speech.close()
        audio.close()
        onDismiss()
    }

    LaunchedEffect(readAloudText) { readText = readAloudText }
    LaunchedEffect(speech) { speech.initialize() }
    LaunchedEffect(conversationId, store, audio) {
        val saved = runCatching { store.load(conversationId) }.getOrDefault(TrpgMediaSelection())
        selection = saved
        audio.select(saved.audioUri)
    }
    LaunchedEffect(selection.imageUri, images) {
        imageResult = null
        val uri = selection.imageUri
        if (uri == null) {
            imageLoading = false
            return@LaunchedEffect
        }
        imageLoading = true
        imageResult = try {
            images.load(uri)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            TrpgMediaImageResult.Failed("unreadable")
        }
        imageLoading = false
    }
    DisposableEffect(lifecycleOwner, speech, audio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                speech.stop()
                audio.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            speech.close()
            audio.close()
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            audioMessage = trpgMediaText(chinese, "已取消选择音频。", "Audio selection cancelled.")
            return@rememberLauncherForActivityResult
        }
        audioMessage = null
        val chosen = uri.toString()
        scope.launch {
            val persisted = trpgMediaPersistRead(context, uri)
            val picked = withContext(Dispatchers.IO) { trpgMediaAudioInfo(context, uri) }
            val rejected = TrpgMediaAudioFiles.rejection(picked.mimeType, picked.sizeBytes)
            if (rejected != null) {
                audioMessage = when (rejected) {
                    TrpgMediaAudioRejection.NOT_AUDIO ->
                        trpgMediaText(chinese, "请选择音频文件。", "Choose an audio file.")
                    TrpgMediaAudioRejection.TOO_LARGE ->
                        trpgMediaText(chinese, "音频超过 256 MiB，未选择。", "Audio is over 256 MiB; not selected.")
                }
                return@launch
            }
            if (!audio.select(chosen)) {
                audioMessage = trpgMediaText(chinese, "无法使用所选音频。", "The selected audio cannot be used.")
                return@launch
            }
            val next = selection.withAudio(chosen)
            selection = next
            audioName = picked.displayName
            if (persisted) {
                runCatching { store.save(conversationId, next) }
            } else {
                audioMessage = trpgMediaText(
                    chinese,
                    "系统未授予持久访问权限：本次可用，重开应用后需重新选择。",
                    "No persistent access was granted: usable now, choose again after restart.",
                )
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            imageNotice = trpgMediaText(chinese, "已取消选择图片。", "Image selection cancelled.")
            return@rememberLauncherForActivityResult
        }
        imageNotice = null
        val chosen = uri.toString()
        scope.launch {
            val persisted = trpgMediaPersistRead(context, uri)
            val mimeType = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.getType(uri) }.getOrNull()
            }
            if (mimeType != null && !mimeType.startsWith("image/")) {
                imageNotice = trpgMediaText(chinese, "请选择图片文件。", "Choose an image file.")
                return@launch
            }
            val next = selection.withImage(chosen)
            selection = next
            if (persisted) {
                runCatching { store.save(conversationId, next) }
            } else {
                imageNotice = trpgMediaText(
                    chinese,
                    "系统未授予持久访问权限：本次可预览，重开应用后需重新选择。",
                    "No persistent access was granted: preview now, choose again after restart.",
                )
            }
        }
    }

    Dialog(
        onDismissRequest = { closeDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .testTag("trpg.media.dialog"),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = trpgMediaText(chinese, "语音与场景", "Voice and scene"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { closeDialog() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.close"),
                    ) {
                        Text(trpgMediaText(chinese, "关闭", "Close"))
                    }
                }
                HorizontalDivider()
                TrpgMediaSpeechPanel(conversationId, chinese, canInsertDraft, onInsertDraft)
                HorizontalDivider()
                TrpgMediaReadAloudPanel(
                    chinese = chinese,
                    text = readText,
                    speed = speed,
                    state = speechState,
                    onTextChange = { readText = it },
                    onSpeedChange = { speed = it },
                    onPlay = { speech.start(readText, speed) },
                    onStop = { speech.stop() },
                )
                HorizontalDivider()
                TrpgMediaAudioPanel(
                    chinese = chinese,
                    uri = selection.audioUri,
                    name = audioName,
                    message = audioMessage,
                    state = audioState,
                    onChoose = { audioLauncher.launch(arrayOf("audio/*")) },
                    onPlay = { audio.play() },
                    onPause = { audio.pause() },
                    onStop = { audio.stop() },
                    onVolume = { audio.setVolume(it) },
                    onLooping = { audio.setLooping(it) },
                    onClear = {
                        val next = selection.withAudio(null)
                        selection = next
                        audio.select(null)
                        audioName = null
                        audioMessage = null
                        runCatching { store.save(conversationId, next) }
                    },
                )
                HorizontalDivider()
                TrpgMediaImagePanel(
                    chinese = chinese,
                    uri = selection.imageUri,
                    loading = imageLoading,
                    result = imageResult,
                    message = imageNotice,
                    onChoose = { imageLauncher.launch(arrayOf("image/*")) },
                    onClear = {
                        val next = selection.withImage(null)
                        selection = next
                        imageResult = null
                        imageNotice = null
                        runCatching { store.save(conversationId, next) }
                    },
                )
                HorizontalDivider()
                Text(
                    text = trpgMediaText(
                        chinese,
                        "本机语音、音频与图片选择只保存在这台设备，不随团务备份转移，也不会上传或写入模型提示。清除选择只撤销本应用的偏好，不会删除原文件。",
                        "These local voice, audio and image picks stay on this device. They are not part of campaign backups, uploads or model prompts. Clearing a pick only forgets this app's preference; the original file is never deleted.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("trpg.media.local_note"),
                )
            }
        }
    }
}

private data class TrpgMediaPickedAudio(
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
)

private fun trpgMediaPersistRead(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    true
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

private fun trpgMediaAudioInfo(context: Context, uri: Uri): TrpgMediaPickedAudio {
    val resolver = context.contentResolver
    var displayName: String? = null
    var sizeBytes: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) sizeBytes = cursor.getLong(sizeColumn)
                }
            }
    }
    return TrpgMediaPickedAudio(
        displayName = displayName,
        mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
        sizeBytes = sizeBytes,
    )
}
