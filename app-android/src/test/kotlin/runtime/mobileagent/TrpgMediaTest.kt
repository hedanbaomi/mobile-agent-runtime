// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrpgMediaTest {
    private class FakeSpeechPort(override val maxInputLength: Int = 6) : TrpgMediaSpeechPort {
        override var listener: TrpgMediaSpeechListener? = null
        val spoken = mutableListOf<Pair<String, String>>()
        var stops = 0
        var shutdowns = 0
        var speakResult = true

        override fun initialize() = Unit

        fun emitInitialized(available: Boolean, offlineVoice: Boolean) {
            listener?.onInitialized(available, offlineVoice)
        }

        override fun speak(utteranceId: String, text: String, rate: Float): Boolean {
            spoken += utteranceId to text
            return speakResult
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
        val prepared = mutableListOf<Long>()
        var starts = 0
        var pauses = 0
        var stops = 0
        var releases = 0
        var focusRequests = 0
        var abandons = 0
        var appliedVolume = 1f
        var appliedLooping = false

        override fun prepare(uri: String, token: Long) {
            prepared += token
            appliedVolume = 1f
            appliedLooping = false
        }

        override fun start() {
            starts += 1
        }

        override fun pause() {
            pauses += 1
        }

        override fun stop() {
            stops += 1
        }

        override fun release() {
            releases += 1
        }

        override fun setVolume(volume: Float) { appliedVolume = volume }

        override fun setLooping(looping: Boolean) { appliedLooping = looping }

        override fun requestFocus(): Boolean {
            focusRequests += 1
            return true
        }

        override fun abandonFocus() {
            abandons += 1
        }
    }

    @Test
    fun speechGateBindsOneResultToItsConversationAndToken() {
        val gate = TrpgMediaSpeechGate()
        val token = gate.begin("conv-a")!!
        assertNull(gate.begin("conv-a"), "a second launch must wait for the first result")
        assertNull(gate.accept("conv-b", token, listOf("other conversation")), "cross-conversation result is rejected")
        assertEquals("hello", gate.accept("conv-a", token, listOf("", " hello ")))
        assertNull(gate.accept("conv-a", token, listOf("again")), "one request produces at most one draft")
    }

    @Test fun lateSpeechErrorCannotStopANewerUtterance() {
        val port = FakeSpeechPort()
        val controller = TrpgMediaSpeechController(port)
        controller.initialize()
        port.emitInitialized(true, true)
        assertTrue(controller.start("First"))
        val oldId = port.spoken.last().first
        controller.stop()
        assertTrue(controller.start("Second"))
        val currentId = port.spoken.last().first
        val stops = port.stops
        controller.onError(oldId, "old failure")
        controller.onError(null, "unbound failure")
        assertEquals(stops, port.stops)
        assertEquals(TrpgMediaSpeechStatus.SPEAKING, controller.state.value.status)
        controller.onError(currentId, "current failure")
        assertEquals(stops + 1, port.stops)
        assertEquals(TrpgMediaSpeechStatus.FAILED, controller.state.value.status)
    }

    @Test fun audioPreparationAndRestartApplyUserVolumeAndLooping() {
        val port = FakeAudioPort()
        val controller = TrpgMediaAudioController(port)
        controller.select("content://audio/one")
        controller.setVolume(0f)
        controller.setLooping(true)
        repeat(2) {
            controller.play()
            controller.onPrepared(port.prepared.last())
            assertEquals(0f, port.appliedVolume)
            assertTrue(port.appliedLooping)
            assertEquals(TrpgMediaAudioStatus.PLAYING, controller.state.value.status)
            controller.stop()
        }
    }

    @Test
    fun speechGateIgnoresCancelledEmptyAndStaleResults() {
        val gate = TrpgMediaSpeechGate()
        val empty = gate.begin("a")!!
        assertNull(gate.accept("a", empty, listOf("   ")))
        val cancelled = gate.begin("a")!!
        gate.cancel("a")
        assertNull(gate.accept("a", cancelled, listOf("late")))
        val stale = gate.begin("a")!!
        val fresh = gate.begin("a")
        assertNull(fresh, "a pending request blocks another begin")
        assertEquals("fresh", gate.accept("a", stale, listOf("fresh")))
    }

    @Test
    fun speechGateBoundsDraftLength() {
        val gate = TrpgMediaSpeechGate()
        val token = gate.begin("a")!!
        val accepted = gate.accept("a", token, listOf("x".repeat(TrpgMediaPolicy.MAX_SPEECH_DRAFT_CHARS + 50)))
        assertEquals(TrpgMediaPolicy.MAX_SPEECH_DRAFT_CHARS, accepted!!.length)
    }

    @Test
    fun selectionStoreIsPerConversationAndClearingKeepsTheOriginalFile() {
        val store = TrpgMediaInMemoryStore()
        val original = File.createTempFile("trpg-media", ".ogg")
        try {
            store.save("conv-a", TrpgMediaSelection(audioUri = "content://audio/a"))
            store.save("conv-b", TrpgMediaSelection(imageUri = "content://image/b"))
            assertEquals("content://audio/a", store.load("conv-a").audioUri)
            store.clear("conv-a")
            assertNull(store.load("conv-a").audioUri)
            assertEquals("content://image/b", store.load("conv-b").imageUri)
            assertTrue(original.exists(), "clearing a preference must not delete the user file")
        } finally {
            original.delete()
        }
    }

    @Test
    fun selectionRejectsNonContentAndInvalidConversationUris() {
        val store = TrpgMediaInMemoryStore()
        assertThrows(IllegalArgumentException::class.java) {
            store.save("conv-a", TrpgMediaSelection(audioUri = "file:///sdcard/secret.ogg"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save("conv-a", TrpgMediaSelection(imageUri = "https://example.com/a.png"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(" ", TrpgMediaSelection(audioUri = "content://audio/a"))
        }
        assertTrue(TrpgMediaPolicy.isAllowedContentUri("content://audio/a"))
        assertFalse(TrpgMediaPolicy.isAllowedContentUri("file:///sdcard/a.ogg"))
        assertFalse(TrpgMediaPolicy.isAllowedContentUri("content://bad uri"))
        assertFalse(TrpgMediaPolicy.isAllowedContentUri(null))
        assertNull(TrpgMediaSelections.sanitize(TrpgMediaSelection(audioUri = "file:///x")).audioUri)
    }

    @Test
    fun imageAdmissionRejectsOversizeStreamsAndSamplesBothEdges() {
        val oversized = ByteArrayInputStream(ByteArray((TrpgMediaPolicy.MAX_IMAGE_BYTES + 1).toInt()))
        assertTrue(TrpgMediaImageBudget.boundedRead(oversized) is TrpgMediaImageBytes.TooLarge)
        val loaded = TrpgMediaImageBudget.boundedRead(ByteArrayInputStream(ByteArray(64)))
        assertTrue(loaded is TrpgMediaImageBytes.Loaded)
        assertEquals(64, (loaded as TrpgMediaImageBytes.Loaded).bytes.size)
        assertEquals(4, TrpgMediaImageBudget.inSampleSize(8000, 4000))
        assertEquals(2, TrpgMediaImageBudget.inSampleSize(4097, 500))
        assertEquals(1, TrpgMediaImageBudget.inSampleSize(2048, 2048))
        assertEquals(1, TrpgMediaImageBudget.inSampleSize(0, 100))
        assertTrue(TrpgMediaImageBudget.isWithinPixelBudget(2048, 2048))
        assertFalse(TrpgMediaImageBudget.isWithinPixelBudget(2048, 2049))
        assertFalse(TrpgMediaImageBudget.isWithinPixelBudget(0, 100))
    }

    @Test
    fun speechChunksStayWithinTheLimitAndPreserveEveryCodePoint() {
        val source = "第一句。第二句。第三句。"
        val chunks = TrpgMediaSpeechText.chunks(source, 5)
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 5 })
        assertEquals(listOf("第一句。", "第二句。", "第三句。"), chunks)

        val emoji = "😀😀😀"
        val emojiChunks = TrpgMediaSpeechText.chunks(emoji, 3)
        assertEquals(emoji, emojiChunks.joinToString(""))
        assertTrue(emojiChunks.all { it.isNotEmpty() })
        assertTrue(TrpgMediaSpeechText.chunks("   ", 10).isEmpty())
    }

    @Test
    fun readAloudRequiresInitializationAndAnOfflineVoice() {
        val port = FakeSpeechPort()
        val controller = TrpgMediaSpeechController(port)
        assertFalse(controller.start("hello"), "speech before initialization must be refused")
        controller.initialize()
        assertEquals(TrpgMediaSpeechStatus.INITIALIZING, controller.state.value.status)
        assertFalse(controller.start("hello"))
        port.emitInitialized(true, offlineVoice = false)
        assertEquals(TrpgMediaSpeechStatus.UNAVAILABLE, controller.state.value.status)
        assertFalse(controller.start("hello"))
        assertTrue(port.spoken.isEmpty(), "a network-required voice is never used")

        val missingPort = FakeSpeechPort()
        val missing = TrpgMediaSpeechController(missingPort)
        missing.initialize()
        missingPort.emitInitialized(available = false, offlineVoice = false)
        assertEquals(TrpgMediaSpeechStatus.UNAVAILABLE, missing.state.value.status)
        assertTrue(missingPort.spoken.isEmpty())
    }

    @Test
    fun readAloudQueueProgressComesFromCallbacksAndStopBlocksLateCallbacks() {
        val port = FakeSpeechPort(maxInputLength = 4)
        val controller = TrpgMediaSpeechController(port)
        controller.initialize()
        port.emitInitialized(true, true)
        assertTrue(controller.start("abcdefghij", 1.2f))
        assertEquals(TrpgMediaSpeechStatus.SPEAKING, controller.state.value.status)
        assertEquals(listOf("abcd"), port.spoken.map { it.second }, "enqueue alone is not completion")

        controller.onDone("wrong-id")
        assertEquals(listOf("abcd"), port.spoken.map { it.second }, "unknown utterance ids are ignored")
        controller.onDone(port.spoken.last().first)
        assertEquals(listOf("abcd", "efgh"), port.spoken.map { it.second })
        controller.onDone(port.spoken.last().first)
        assertEquals(listOf("abcd", "efgh", "ij"), port.spoken.map { it.second })
        controller.onDone(port.spoken.last().first)
        assertEquals(TrpgMediaSpeechStatus.READY, controller.state.value.status)

        assertTrue(controller.start("abcdefghij"))
        assertEquals(4, port.spoken.size)
        val staleId = port.spoken.last().first
        controller.stop()
        assertEquals(TrpgMediaSpeechStatus.READY, controller.state.value.status)
        controller.onDone(staleId)
        assertEquals(4, port.spoken.size, "a completion after stop must not restart speech")
    }

    @Test
    fun readAloudCloseIgnoresLateInitAndCompletionAndShutsDownOnce() {
        val port = FakeSpeechPort()
        val controller = TrpgMediaSpeechController(port)
        controller.initialize()
        val lateListener = port.listener
        controller.close()
        assertEquals(TrpgMediaSpeechStatus.CLOSED, controller.state.value.status)
        assertEquals(1, port.shutdowns)
        assertNull(port.listener)

        lateListener?.onInitialized(true, true)
        lateListener?.onDone("trpg-media-1")
        assertEquals(TrpgMediaSpeechStatus.CLOSED, controller.state.value.status)
        assertTrue(port.spoken.isEmpty(), "a late callback cannot revive a closed controller")
        assertFalse(controller.start("hello"))
        controller.close()
        assertEquals(1, port.shutdowns, "close is idempotent")
    }

    @Test
    fun readAloudEnqueueFailureStopsTheEngine() {
        val port = FakeSpeechPort()
        port.speakResult = false
        val controller = TrpgMediaSpeechController(port)
        controller.initialize()
        port.emitInitialized(true, true)
        assertFalse(controller.start("hello"))
        assertEquals(TrpgMediaSpeechStatus.FAILED, controller.state.value.status)
        assertEquals(1, port.stops)
    }

    @Test
    fun ambienceSelectionNeverPreparesOrPlaysUntilTheUserPressesPlay() {
        val port = FakeAudioPort()
        val controller = TrpgMediaAudioController(port)
        assertTrue(controller.select("content://audio/one"))
        assertEquals(TrpgMediaAudioStatus.STAGED, controller.state.value.status)
        assertTrue(port.prepared.isEmpty())
        assertEquals(0, port.starts)
        assertEquals(0, port.focusRequests)

        controller.onPrepared(0L)
        assertEquals(TrpgMediaAudioStatus.STAGED, controller.state.value.status)

        controller.play()
        assertEquals(TrpgMediaAudioStatus.PREPARING, controller.state.value.status)
        assertEquals(1, port.prepared.size)
        controller.onPrepared(port.prepared.single())
        assertEquals(TrpgMediaAudioStatus.PLAYING, controller.state.value.status)
        assertEquals(1, port.starts)
        assertEquals(1, port.focusRequests)
    }

    @Test
    fun latePreparedCallbacksAfterStopOrCloseCannotRestartPlayback() {
        val port = FakeAudioPort()
        val controller = TrpgMediaAudioController(port)
        controller.select("content://audio/one")
        controller.play()
        val firstToken = port.prepared.single()
        controller.stop()
        assertEquals(TrpgMediaAudioStatus.STAGED, controller.state.value.status)
        controller.onPrepared(firstToken)
        assertEquals(TrpgMediaAudioStatus.STAGED, controller.state.value.status)
        assertEquals(0, port.starts)

        controller.play()
        val secondToken = port.prepared.last()
        controller.close()
        assertEquals(TrpgMediaAudioStatus.CLOSED, controller.state.value.status)
        controller.onPrepared(secondToken)
        assertEquals(TrpgMediaAudioStatus.CLOSED, controller.state.value.status)
        assertEquals(0, port.starts)
        assertTrue(port.releases >= 2)
        controller.close()
        assertTrue(port.releases >= 2)
    }

    @Test
    fun playbackFailureStopsReleasesAndAbandonsFocusOnlyOnce() {
        val port = FakeAudioPort()
        val controller = TrpgMediaAudioController(port)
        controller.select("content://audio/one")
        controller.play()
        val token = port.prepared.single()
        controller.onPrepared(token)
        assertEquals(TrpgMediaAudioStatus.PLAYING, controller.state.value.status)
        val stopsBefore = port.stops
        val releasesBefore = port.releases

        controller.onError(token, "decoder")
        assertEquals(TrpgMediaAudioStatus.ERROR, controller.state.value.status)
        assertEquals(stopsBefore + 1, port.stops)
        assertEquals(releasesBefore + 1, port.releases)
        assertEquals(1, port.abandons)

        controller.onError(token, "duplicate")
        assertEquals(stopsBefore + 1, port.stops)
        assertEquals(releasesBefore + 1, port.releases)
        assertEquals(1, port.abandons)
    }

    @Test
    fun focusLossPausesWithoutAutoResumeAndCompletionStaysReady() {
        val port = FakeAudioPort()
        val controller = TrpgMediaAudioController(port)
        controller.select("content://audio/one")
        controller.play()
        val token = port.prepared.single()
        controller.onPrepared(token)
        controller.onFocusLost()
        assertEquals(TrpgMediaAudioStatus.PAUSED, controller.state.value.status)
        assertEquals(1, port.pauses)
        assertEquals(1, port.abandons)
        assertEquals(1, port.starts)

        controller.play()
        assertEquals(2, port.starts)
        controller.onCompletion(token)
        assertEquals(TrpgMediaAudioStatus.READY, controller.state.value.status)
        assertEquals(2, port.starts, "completion must not restart playback")
    }

    @Test
    fun invalidAudioSelectionAndUnusableFilesAreRejectedWithTheFileUntouched() {
        val port = FakeAudioPort()
        val controller = TrpgMediaAudioController(port)
        assertFalse(controller.select("file:///sdcard/ambience.mp3"))
        assertEquals(TrpgMediaAudioStatus.ERROR, controller.state.value.status)
        assertTrue(port.prepared.isEmpty())

        assertNull(TrpgMediaAudioFiles.rejection("audio/mpeg", 1024))
        assertNull(TrpgMediaAudioFiles.rejection("application/ogg", null))
        assertEquals(TrpgMediaAudioRejection.NOT_AUDIO, TrpgMediaAudioFiles.rejection("video/mp4", 1024))
        assertEquals(
            TrpgMediaAudioRejection.TOO_LARGE,
            TrpgMediaAudioFiles.rejection("audio/mpeg", TrpgMediaPolicy.MAX_AUDIO_BYTES + 1),
        )
        assertEquals(
            TrpgMediaAudioRejection.TOO_LARGE,
            TrpgMediaAudioFiles.rejection(null, TrpgMediaPolicy.MAX_AUDIO_BYTES + 1),
        )
    }
}
