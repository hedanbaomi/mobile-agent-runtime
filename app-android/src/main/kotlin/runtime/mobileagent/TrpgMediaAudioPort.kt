// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * MediaPlayer + AudioFocusRequest adapter (minSdk 26 has the focus API).
 *
 * Player work is serialized on the main looper: `prepareAsync` never blocks a
 * caller and lifecycle calls are ordered with the prepare they cancel. Every
 * playback gesture requests focus; stopping, completing, failing or closing
 * abandons it. The adapter holds the application context only.
 */
internal class TrpgMediaAndroidAudioPort(context: Context) : TrpgMediaAudioPort {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    override var listener: TrpgMediaAudioListener? = null

    override fun prepare(uri: String, token: Long) {
        main.post { prepareOnMain(uri, token) }
    }

    private fun prepareOnMain(uri: String, token: Long) {
        releaseOnMain()
        val created = MediaPlayer()
        created.setAudioAttributes(attributes)
        created.setOnPreparedListener { prepared ->
            if (player === prepared) listener?.onPrepared(token)
        }
        created.setOnCompletionListener { completed ->
            if (player === completed) listener?.onCompletion(token)
        }
        created.setOnErrorListener { failed, what, extra ->
            if (player === failed) {
                listener?.onError(token, "media_error_${what}_$extra")
                true
            } else {
                false
            }
        }
        player = created
        try {
            created.setDataSource(appContext, Uri.parse(uri))
            created.prepareAsync()
        } catch (failure: Exception) {
            releaseOnMain()
            listener?.onError(token, failure.message)
        }
    }

    override fun start() {
        main.post { runCatching { player?.start() } }
    }

    override fun pause() {
        main.post { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
    }

    override fun stop() {
        main.post { runCatching { player?.stop() } }
    }

    override fun release() {
        main.post { releaseOnMain() }
    }

    private fun releaseOnMain() {
        val current = player
        player = null
        if (current != null) {
            runCatching { current.reset() }
            runCatching { current.release() }
        }
    }

    override fun setVolume(volume: Float) {
        main.post { runCatching { player?.setVolume(volume, volume) } }
    }

    override fun setLooping(looping: Boolean) {
        main.post { runCatching { player?.isLooping = looping } }
    }

    override fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> main.post { onFocusChange(change) } }
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = if (granted) request else null
        return granted
    }

    override fun abandonFocus() {
        val request = focusRequest
        focusRequest = null
        if (request != null) runCatching { audioManager.abandonAudioFocusRequest(request) }
    }

    private fun onFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            listener?.onFocusLost()
        }
    }
}
