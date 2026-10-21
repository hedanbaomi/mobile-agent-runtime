// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Engine port so the queue and lifecycle rules can be checked without a device voice. */
interface TrpgMediaSpeechPort {
    var listener: TrpgMediaSpeechListener?
    val maxInputLength: Int
    fun initialize()
    fun speak(utteranceId: String, text: String, rate: Float): Boolean
    fun stop()
    fun shutdown()
}

interface TrpgMediaSpeechListener {
    fun onInitialized(available: Boolean, offlineVoice: Boolean)
    fun onStart(utteranceId: String) {}
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String?, detail: String?)
}

enum class TrpgMediaSpeechStatus { IDLE, INITIALIZING, READY, SPEAKING, UNAVAILABLE, FAILED, CLOSED }

data class TrpgMediaSpeechState(
    val status: TrpgMediaSpeechStatus = TrpgMediaSpeechStatus.IDLE,
    val offlineVoice: Boolean = false,
    val text: String? = null,
    val chunkCount: Int = 0,
    val chunkIndex: Int = 0,
    val error: String? = null,
)

/**
 * Splits user-visible text into explicit, ordered utterances. Splits prefer a
 * sentence boundary in the second half of the window and never cut a surrogate
 * pair. Every piece is at most [maxLength] characters.
 */
object TrpgMediaSpeechText {
    private val BOUNDARIES = setOf('.', '!', '?', ';', '\n', '。', '！', '？', '；')

    fun chunks(text: String, maxLength: Int): List<String> {
        require(maxLength > 0) { "maxLength must be positive" }
        val source = text.trim()
        if (source.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        var start = 0
        while (start < source.length) {
            var end = minOf(start + maxLength, source.length)
            if (end < source.length) {
                if (end > start + 1 && Character.isHighSurrogate(source[end - 1])) end -= 1
                val minimum = start + maxOf(1, (end - start) / 2)
                val boundary = (end - 1 downTo minimum).firstOrNull { source[it] in BOUNDARIES }
                if (boundary != null) end = boundary + 1
            }
            val piece = source.substring(start, end).trim()
            if (piece.isNotEmpty()) result += piece
            start = end
        }
        return result
    }
}

/**
 * Offline-only read-aloud controller.
 *
 * The engine must finish initialization before [start] is accepted. Progress is
 * driven by [TrpgMediaSpeechListener.onDone], never by a successful enqueue.
 * [close] stops and shuts the engine down once; every later callback is
 * ignored, so a late init or completion can never revive a closed controller.
 */
class TrpgMediaSpeechController(private val port: TrpgMediaSpeechPort) : TrpgMediaSpeechListener {
    private val mutable = MutableStateFlow(TrpgMediaSpeechState())
    val state: StateFlow<TrpgMediaSpeechState> = mutable.asStateFlow()

    private val prefix = "trpg-media-${INSTANCES.incrementAndGet()}"
    private var open = true
    private var initialized = false
    private var generation = 0L
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var rate = 1f

    fun initialize() {
        if (!open) return
        if (mutable.value.status != TrpgMediaSpeechStatus.IDLE) return
        mutable.value = mutable.value.copy(status = TrpgMediaSpeechStatus.INITIALIZING, error = null)
        port.listener = this
        runCatching { port.initialize() }.onFailure { unavailable() }
    }

    fun start(text: String, rate: Float = 1f): Boolean {
        if (!open) return false
        if (mutable.value.status != TrpgMediaSpeechStatus.READY) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            mutable.value = mutable.value.copy(error = "empty_text")
            return false
        }
        val parts = TrpgMediaSpeechText.chunks(trimmed, port.maxInputLength.coerceAtLeast(1))
        if (parts.isEmpty()) return false
        generation += 1
        chunks = parts
        index = 0
        this.rate = rate.coerceIn(0.5f, 2.0f)
        mutable.value = mutable.value.copy(
            status = TrpgMediaSpeechStatus.SPEAKING,
            text = trimmed,
            chunkCount = parts.size,
            chunkIndex = 0,
            error = null,
        )
        if (!speakCurrent()) fail("speak_failed")
        return mutable.value.status == TrpgMediaSpeechStatus.SPEAKING
    }

    fun stop() {
        if (!open) return
        generation += 1
        chunks = emptyList()
        index = 0
        runCatching { port.stop() }
        val current = mutable.value
        if (current.status == TrpgMediaSpeechStatus.SPEAKING || current.status == TrpgMediaSpeechStatus.FAILED) {
            val next = if (initialized) TrpgMediaSpeechStatus.READY else TrpgMediaSpeechStatus.IDLE
            mutable.value = current.copy(
                status = next,
                text = null,
                chunkCount = 0,
                chunkIndex = 0,
                error = null,
            )
        }
    }

    fun close() {
        if (!open) return
        open = false
        generation += 1
        chunks = emptyList()
        index = 0
        runCatching { port.stop() }
        runCatching { port.shutdown() }
        port.listener = null
        mutable.value = mutable.value.copy(
            status = TrpgMediaSpeechStatus.CLOSED,
            text = null,
            chunkCount = 0,
            chunkIndex = 0,
            error = null,
        )
    }

    override fun onInitialized(available: Boolean, offlineVoice: Boolean) {
        if (!open) return
        if (mutable.value.status != TrpgMediaSpeechStatus.INITIALIZING) return
        initialized = available
        if (!available) {
            unavailable()
            return
        }
        if (!offlineVoice) {
            mutable.value = mutable.value.copy(
                status = TrpgMediaSpeechStatus.UNAVAILABLE,
                offlineVoice = false,
                error = "offline_voice_unavailable",
            )
            return
        }
        mutable.value = mutable.value.copy(
            status = TrpgMediaSpeechStatus.READY,
            offlineVoice = true,
            error = null,
        )
    }

    override fun onDone(utteranceId: String) {
        if (!open) return
        if (mutable.value.status != TrpgMediaSpeechStatus.SPEAKING) return
        if (utteranceId != utteranceId(index)) return
        index += 1
        if (index < chunks.size) {
            mutable.value = mutable.value.copy(chunkIndex = index)
            if (!speakCurrent()) fail("speak_failed")
            return
        }
        chunks = emptyList()
        index = 0
        mutable.value = mutable.value.copy(
            status = TrpgMediaSpeechStatus.READY,
            text = null,
            chunkCount = 0,
            chunkIndex = 0,
            error = null,
        )
    }

    override fun onError(utteranceId: String?, detail: String?) {
        if (!open) return
        if (mutable.value.status != TrpgMediaSpeechStatus.SPEAKING) return
        if (utteranceId != utteranceId(index)) return
        fail(detail ?: "tts_error")
    }

    private fun speakCurrent(): Boolean =
        runCatching { port.speak(utteranceId(index), chunks[index], rate) }.getOrDefault(false)

    private fun utteranceId(at: Int): String = "$prefix-$generation-$at"

    private fun fail(reason: String) {
        generation += 1
        chunks = emptyList()
        index = 0
        runCatching { port.stop() }
        mutable.value = mutable.value.copy(
            status = TrpgMediaSpeechStatus.FAILED,
            text = null,
            chunkCount = 0,
            chunkIndex = 0,
            error = reason,
        )
    }

    private fun unavailable() {
        initialized = false
        mutable.value = mutable.value.copy(
            status = TrpgMediaSpeechStatus.UNAVAILABLE,
            error = "tts_unavailable",
        )
    }

    private companion object {
        val INSTANCES = AtomicInteger()
    }
}
