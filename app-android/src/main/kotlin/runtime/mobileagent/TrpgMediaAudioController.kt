// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Player/focus port; the real adapter wraps MediaPlayer and AudioFocusRequest. */
interface TrpgMediaAudioPort {
    var listener: TrpgMediaAudioListener?
    fun prepare(uri: String, token: Long)
    fun start()
    fun pause()
    fun stop()
    fun release()
    fun setVolume(volume: Float)
    fun setLooping(looping: Boolean)
    fun requestFocus(): Boolean
    fun abandonFocus()
}

interface TrpgMediaAudioListener {
    fun onPrepared(token: Long)
    fun onCompletion(token: Long)
    fun onError(token: Long, detail: String?)
    fun onFocusLost()
}

enum class TrpgMediaAudioStatus { IDLE, STAGED, PREPARING, READY, PLAYING, PAUSED, ERROR, CLOSED }

data class TrpgMediaAudioState(
    val status: TrpgMediaAudioStatus = TrpgMediaAudioStatus.IDLE,
    val uri: String? = null,
    val volume: Float = 1f,
    val looping: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * Ambience controller with an explicit user gesture for every start.
 *
 * Selecting or restoring a pick only stages the URI: it never opens, prepares
 * or plays the file. Prepared, completion, error and focus callbacks are all
 * token-guarded, so a callback from a replaced or closed session is ignored.
 * [stop] and [close] release the player and abandon audio focus; after
 * [close] no callback can start playback again.
 */
class TrpgMediaAudioController(private val port: TrpgMediaAudioPort) : TrpgMediaAudioListener {
    private val mutable = MutableStateFlow(TrpgMediaAudioState())
    val state: StateFlow<TrpgMediaAudioState> = mutable.asStateFlow()

    private var open = true
    private var token = 0L
    private var playWhenReady = false
    private var focusHeld = false

    init {
        port.listener = this
    }

    fun select(uri: String?): Boolean {
        if (!open) return false
        if (uri != null && !TrpgMediaPolicy.isAllowedContentUri(uri)) {
            mutable.value = mutable.value.copy(status = TrpgMediaAudioStatus.ERROR, error = "invalid_uri")
            return false
        }
        token += 1
        playWhenReady = false
        stopPlayback()
        val next = if (uri == null) TrpgMediaAudioStatus.IDLE else TrpgMediaAudioStatus.STAGED
        mutable.value = TrpgMediaAudioState(
            status = next,
            uri = uri,
            volume = mutable.value.volume,
            looping = mutable.value.looping,
        )
        return true
    }

    fun play() {
        if (!open) return
        val current = mutable.value
        when (current.status) {
            TrpgMediaAudioStatus.STAGED, TrpgMediaAudioStatus.ERROR -> {
                val uri = current.uri
                if (uri == null) return
                beginPrepare(uri, current)
            }
            TrpgMediaAudioStatus.READY, TrpgMediaAudioStatus.PAUSED -> startPlayback(current)
            TrpgMediaAudioStatus.PREPARING -> playWhenReady = true
            else -> Unit
        }
    }

    fun pause() {
        if (!open) return
        if (mutable.value.status != TrpgMediaAudioStatus.PLAYING) return
        runCatching { port.pause() }
        abandonFocus()
        mutable.value = mutable.value.copy(status = TrpgMediaAudioStatus.PAUSED)
    }

    fun stop() {
        if (!open) return
        token += 1
        playWhenReady = false
        stopPlayback()
        val next = if (mutable.value.uri == null) TrpgMediaAudioStatus.IDLE else TrpgMediaAudioStatus.STAGED
        mutable.value = mutable.value.copy(status = next, error = null, notice = null)
    }

    fun close() {
        if (!open) return
        open = false
        token += 1
        playWhenReady = false
        stopPlayback()
        port.listener = null
        mutable.value = mutable.value.copy(status = TrpgMediaAudioStatus.CLOSED, error = null, notice = null)
    }

    fun setVolume(volume: Float) {
        if (!open) return
        val next = volume.coerceIn(0f, 1f)
        runCatching { port.setVolume(next) }
        mutable.value = mutable.value.copy(volume = next)
    }

    fun setLooping(looping: Boolean) {
        if (!open) return
        runCatching { port.setLooping(looping) }
        mutable.value = mutable.value.copy(looping = looping)
    }

    override fun onPrepared(token: Long) {
        if (!open || token != this.token) return
        if (mutable.value.status != TrpgMediaAudioStatus.PREPARING) return
        runCatching {
            port.setVolume(mutable.value.volume)
            port.setLooping(mutable.value.looping)
        }.onFailure { onError(token, it.message); return }
        mutable.value = mutable.value.copy(status = TrpgMediaAudioStatus.READY)
        if (playWhenReady) {
            playWhenReady = false
            startPlayback(mutable.value)
        }
    }

    override fun onCompletion(token: Long) {
        if (!open || token != this.token) return
        if (mutable.value.status != TrpgMediaAudioStatus.PLAYING) return
        playWhenReady = false
        abandonFocus()
        mutable.value = mutable.value.copy(status = TrpgMediaAudioStatus.READY)
    }

    override fun onError(token: Long, detail: String?) {
        if (!open || token != this.token) return
        this.token += 1
        playWhenReady = false
        runCatching { port.stop() }
        runCatching { port.release() }
        abandonFocus()
        mutable.value = mutable.value.copy(status = TrpgMediaAudioStatus.ERROR, error = detail ?: "playback_failed")
    }

    override fun onFocusLost() {
        if (!open) return
        if (mutable.value.status != TrpgMediaAudioStatus.PLAYING) return
        runCatching { port.pause() }
        abandonFocus()
        mutable.value = mutable.value.copy(status = TrpgMediaAudioStatus.PAUSED, notice = "focus_lost")
    }

    private fun beginPrepare(uri: String, current: TrpgMediaAudioState) {
        token += 1
        playWhenReady = true
        mutable.value = current.copy(status = TrpgMediaAudioStatus.PREPARING, error = null, notice = null)
        val preparedToken = token
        runCatching { port.prepare(uri, preparedToken) }.onFailure { onError(preparedToken, it.message) }
    }

    private fun startPlayback(current: TrpgMediaAudioState) {
        if (!focusHeld) focusHeld = runCatching { port.requestFocus() }.getOrDefault(false)
        if (!focusHeld) {
            mutable.value = current.copy(status = TrpgMediaAudioStatus.PAUSED, notice = "focus_denied")
            return
        }
        runCatching { port.start() }.onFailure {
            onError(token, it.message)
            return
        }
        mutable.value = current.copy(status = TrpgMediaAudioStatus.PLAYING, error = null, notice = null)
    }

    private fun stopPlayback() {
        runCatching { port.stop() }
        runCatching { port.release() }
        abandonFocus()
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        runCatching { port.abandonFocus() }
    }
}
