package fr.guill.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Petite enveloppe autour de la synthèse vocale d'Android. */
class Speaker(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    @Volatile var speaking = false
        private set

    /** Appelé (sur le fil principal) quand la voix commence ou s'arrête. */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    /** Appelé si la synthèse vocale n'est pas disponible. */
    var onUnavailable: (() -> Unit)? = null

    fun say(text: String) {
        val t = tts
        if (t != null && ready) {
            speak(t, text)
            return
        }
        pending = text
        if (t != null) return // initialisation en cours
        tts = TextToSpeech(ctx) init@{ status ->
            val engine = tts ?: return@init
            if (status != TextToSpeech.SUCCESS) {
                main.post { onUnavailable?.invoke() }
                return@init
            }
            ready = true
            engine.language = Locale.FRENCH
            engine.setPitch(0.9f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = setSpeaking(true)
                override fun onDone(utteranceId: String?) = setSpeaking(false)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = setSpeaking(false)
            })
            pending?.let { speak(engine, it) }
            pending = null
        }
    }

    fun stop() {
        tts?.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        speaking = false
    }

    private fun speak(t: TextToSpeech, text: String) {
        setSpeaking(true)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.nanoTime())
    }

    private fun setSpeaking(v: Boolean) {
        speaking = v
        main.post { onSpeakingChanged?.invoke(v) }
    }
}
