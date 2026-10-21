// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.TrpgMediaDialogScaffold

/**
 * Compose coverage for the media dialog with fake ports: no microphone, no
 * recognizer activity and no audio output are started. Real voice acoustics
 * remain a device acceptance item.
 */
@RunWith(AndroidJUnit4::class)
class TrpgMediaDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun nativeOfflineSpeechInitializationFinishesAndClosesWithoutSpeaking() {
        val controller = TrpgMediaSpeechController(TrpgMediaAndroidTtsPort(compose.activity.applicationContext, chinese = false))
        try {
            compose.runOnIdle { controller.initialize() }
            compose.waitUntil(20_000) { controller.state.value.status in setOf(TrpgMediaSpeechStatus.READY, TrpgMediaSpeechStatus.UNAVAILABLE) }
            if (controller.state.value.status == TrpgMediaSpeechStatus.READY) assertTrue(controller.state.value.offlineVoice)
        } finally { compose.runOnIdle { controller.close() } }
        assertEquals(TrpgMediaSpeechStatus.CLOSED, controller.state.value.status)
    }

    @Test fun nativeMediaPlayerDecodesUserContentUriAndCompletesSilently() {
        val context = compose.activity.applicationContext
        val directory = java.io.File(context.filesDir, "app-updates").apply { mkdirs() }
        val file = java.io.File(directory, "trpg-test-${java.util.UUID.randomUUID()}.wav")
        val pcmBytes = 16_000
        val data = java.nio.ByteBuffer.allocate(44 + pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort()).putInt(8_000).putInt(16_000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(pcmBytes)
        file.writeBytes(data.array())
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.app-updates", file)
        val controller = TrpgMediaAudioController(TrpgMediaAndroidAudioPort(context))
        try {
            compose.runOnIdle { controller.select(uri.toString()); controller.setVolume(0f); controller.play() }
            compose.waitUntil(10_000) { controller.state.value.status in setOf(TrpgMediaAudioStatus.READY, TrpgMediaAudioStatus.ERROR) }
            assertEquals(TrpgMediaAudioStatus.READY, controller.state.value.status)
            assertEquals(0f, controller.state.value.volume)
            compose.runOnIdle { controller.stop() }
            assertEquals(TrpgMediaAudioStatus.STAGED, controller.state.value.status)
        } finally {
            compose.runOnIdle { controller.close() }
            compose.waitForIdle()
            file.delete()
        }
    }

    private class FakeSpeechPort(private val ready: Boolean) : TrpgMediaSpeechPort {
        override var listener: TrpgMediaSpeechListener? = null
        override val maxInputLength: Int = 12
        val spoken = mutableListOf<String>()
        var shutdowns = 0
        var stops = 0

        override fun initialize() {
            listener?.onInitialized(ready, ready)
        }

        override fun speak(utteranceId: String, text: String, rate: Float): Boolean {
            spoken += text
            return true
        }

        override fun stop() {
            stops += 1
        }

        override fun shutdown() {
            shutdowns += 1
        }
    }

    private class FakeAudioPort : TrpgMediaAudioPort {
        override var listener: TrpgMediaAudioListener? = null
        val prepared = mutableListOf<String>()
        var lastUri: String? = null
        var starts = 0
        var stops = 0
        var releases = 0

        override fun prepare(uri: String, token: Long) {
            prepared += uri
            lastUri = uri
            listener?.onPrepared(token)
        }

        override fun start() {
            starts += 1
        }

        override fun pause() = Unit

        override fun stop() {
            stops += 1
        }

        override fun release() {
            releases += 1
        }

        override fun setVolume(volume: Float) = Unit

        override fun setLooping(looping: Boolean) = Unit

        override fun requestFocus(): Boolean = true

        override fun abandonFocus() = Unit
    }

    private class FakeImageLoader(private val result: TrpgMediaImageResult) : TrpgMediaImageLoader {
        override suspend fun load(uri: String): TrpgMediaImageResult = result
    }

    private fun show(
        speech: TrpgMediaSpeechController,
        audio: TrpgMediaAudioController,
        store: TrpgMediaSelectionStore,
        images: TrpgMediaImageLoader,
        onDismiss: () -> Unit = {},
        onInsertDraft: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                TrpgMediaDialogScaffold(
                    conversationId = CONVERSATION,
                    readAloudText = "初始文字",
                    canInsertDraft = true,
                    chinese = true,
                    onInsertDraft = onInsertDraft,
                    onDismiss = onDismiss,
                    speech = speech,
                    audio = audio,
                    store = store,
                    images = images,
                )
            }
        }
    }

    @Test
    fun unavailableOfflineVoiceIsReportedAndCannotPlay() {
        val speech = FakeSpeechPort(ready = false)
        val controller = TrpgMediaSpeechController(speech)
        show(controller, TrpgMediaAudioController(FakeAudioPort()), TrpgMediaInMemoryStore(), FakeImageLoader(TrpgMediaImageResult.Failed("none")))
        compose.waitForIdle()
        compose.onNodeWithTag("trpg.media.tts.status").assertTextContains("没有匹配", substring = true)
        compose.onNodeWithTag("trpg.media.tts.play").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(speech.spoken.isEmpty()) }
    }

    @Test
    fun readAloudPreviewIsEditableAndCloseStopsTheEngine() {
        val speech = FakeSpeechPort(ready = true)
        val controller = TrpgMediaSpeechController(speech)
        var dismissed = false
        show(
            controller,
            TrpgMediaAudioController(FakeAudioPort()),
            TrpgMediaInMemoryStore(),
            FakeImageLoader(TrpgMediaImageResult.Failed("none")),
            onDismiss = { dismissed = true },
        )
        compose.onNodeWithTag("trpg.media.tts.text").performTextReplacement("换过的文字")
        compose.onNodeWithTag("trpg.media.tts.play").performClick()
        compose.runOnIdle { assertEquals(listOf("换过的文字"), speech.spoken) }
        compose.onNodeWithTag("trpg.media.tts.stop").assertIsDisplayed()
        compose.onNodeWithTag("trpg.media.close").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertEquals(1, speech.shutdowns)
        }
    }

    @Test
    fun restoredAudioIsNotPreparedUntilPlayAndImagePreviewCanBeCleared() {
        val store = TrpgMediaInMemoryStore().apply {
            save(CONVERSATION, TrpgMediaSelection(audioUri = "content://audio/saved", imageUri = "content://image/saved"))
        }
        val audioPort = FakeAudioPort()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        show(
            TrpgMediaSpeechController(FakeSpeechPort(ready = true)),
            TrpgMediaAudioController(audioPort),
            store,
            FakeImageLoader(TrpgMediaImageResult.Ready(bitmap, 8, 8)),
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("trpg.media.image.preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(audioPort.prepared.isEmpty())
            assertEquals(0, audioPort.starts)
        }
        compose.onNodeWithTag("trpg.media.audio.play").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, audioPort.prepared.size)
            assertEquals("content://audio/saved", audioPort.lastUri)
        }
        compose.onNodeWithTag("trpg.media.image.clear").performScrollTo().performClick()
        compose.onNodeWithTag("trpg.media.image.preview").assertDoesNotExist()
        compose.runOnIdle { assertNull(store.stored(CONVERSATION)?.imageUri) }
    }

    @Test
    fun oversizedImageIsRejectedWithoutPreview() {
        val store = TrpgMediaInMemoryStore().apply {
            save(CONVERSATION, TrpgMediaSelection(imageUri = "content://image/huge"))
        }
        show(
            TrpgMediaSpeechController(FakeSpeechPort(ready = true)),
            TrpgMediaAudioController(FakeAudioPort()),
            store,
            FakeImageLoader(TrpgMediaImageResult.RejectedTooLarge(TrpgMediaPolicy.MAX_IMAGE_BYTES)),
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("trpg.media.image.error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("trpg.media.image.error").assertTextContains("8 MiB", substring = true)
        compose.onNodeWithTag("trpg.media.image.preview").assertDoesNotExist()
    }

    @Test
    fun speechPanelOnlyInsertsAnExplicitDraftAndExplainsTheOfflineRequest() {
        var inserted = ""
        show(
            TrpgMediaSpeechController(FakeSpeechPort(ready = true)),
            TrpgMediaAudioController(FakeAudioPort()),
            TrpgMediaInMemoryStore(),
            FakeImageLoader(TrpgMediaImageResult.Failed("none")),
            onInsertDraft = { inserted = it },
        )
        compose.onNodeWithTag("trpg.media.speech.status").assertTextContains("系统语音界面", substring = true)
        compose.onNodeWithTag("trpg.media.speech.draft").performTextReplacement("手动草稿")
        compose.onNodeWithTag("trpg.media.speech.insert").performClick()
        compose.runOnIdle { assertEquals("手动草稿", inserted) }
    }

    @Test
    fun removingTheDialogFromCompositionClosesBothEngines() {
        val speech = FakeSpeechPort(ready = true)
        val audioPort = FakeAudioPort()
        val speechController = TrpgMediaSpeechController(speech)
        val audioController = TrpgMediaAudioController(audioPort)
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) {
                MaterialTheme {
                    TrpgMediaDialogScaffold(
                        conversationId = CONVERSATION,
                        readAloudText = "初始文字",
                        canInsertDraft = true,
                        chinese = true,
                        onInsertDraft = {},
                        onDismiss = {},
                        speech = speechController,
                        audio = audioController,
                        store = TrpgMediaInMemoryStore(),
                        images = FakeImageLoader(TrpgMediaImageResult.Failed("none")),
                    )
                }
            }
        }
        compose.waitForIdle()
        visible = false
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, speech.shutdowns)
            assertTrue(audioPort.releases >= 1)
        }
    }

    private companion object {
        const val CONVERSATION = "conv-media"
    }
}
