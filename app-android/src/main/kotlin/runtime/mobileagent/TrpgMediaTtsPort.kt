// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Platform TextToSpeech adapter.
 *
 * Only an installed, locale-matching voice that declares
 * `isNetworkConnectionRequired == false` is accepted. The adapter never calls
 * `setLanguage` for a missing language, because that could trigger a voice
 * download or a network-backed engine. The application context is retained and
 * [shutdown] releases the engine, so no Activity is leaked.
 */
internal class TrpgMediaAndroidTtsPort(context: Context, private val chinese: Boolean) : TrpgMediaSpeechPort {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initializing = false

    override var listener: TrpgMediaSpeechListener? = null

    override val maxInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()

    override fun initialize() {
        if (engine != null || initializing) return
        initializing = true
        val created = runCatching {
            TextToSpeech(appContext) { status -> main.post { finishInitialize(status) } }
        }.getOrNull()
        if (created == null) {
            initializing = false
            listener?.onInitialized(false, false)
            return
        }
        engine = created
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { listener?.onStart(utteranceId.orEmpty()) }
            }

            override fun onDone(utteranceId: String?) {
                main.post { listener?.onDone(utteranceId.orEmpty()) }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) {
                main.post { listener?.onError(utteranceId.orEmpty(), null) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { listener?.onError(utteranceId.orEmpty(), "tts_error_$errorCode") }
            }
        })
    }

    private fun finishInitialize(status: Int) {
        initializing = false
        val tts = engine
        if (tts == null) {
            listener?.onInitialized(false, false)
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            listener?.onInitialized(false, false)
            return
        }
        val target = if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        val voice = tts.voices.orEmpty().firstOrNull { candidate ->
            !candidate.isNetworkConnectionRequired &&
                candidate.locale.language == target.language &&
                (candidate.locale.country.isEmpty() || target.country.isEmpty() ||
                    candidate.locale.country == target.country)
        }
        if (voice == null) {
            listener?.onInitialized(false, false)
            return
        }
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            listener?.onInitialized(false, false)
            return
        }
        listener?.onInitialized(true, true)
    }

    override fun speak(utteranceId: String, text: String, rate: Float): Boolean {
        val tts = engine ?: return false
        if (tts.setSpeechRate(rate) != TextToSpeech.SUCCESS) return false
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        runCatching { engine?.stop() }
    }

    override fun shutdown() {
        val tts = engine
        engine = null
        initializing = false
        listener = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
    }
}
