package com.kotarov.autoclock.media

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

/** On-demand offline speech. All state is owned by the main thread.
 * A generation token invalidates delayed starts and asynchronous engine callbacks. */
class NoticeSpeech(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var generation = 0L
    private var onFinished: (() -> Unit)? = null

    fun speak(body: String, delayMs: Long, phoneChannel: Boolean = false,
              finished: () -> Unit = {}) {
        stop()
        onFinished = finished
        val token = generation
        val text = NoticeSpeechText.format(body)
        if (text.isBlank()) { stop(); return }
        handler.postDelayed({
            if (token == generation) initialize(token, text, phoneChannel)
        }, delayMs)
    }

    private fun initialize(token: Long, text: String, phoneChannel: Boolean) {
        try {
            // The callback may be synchronous on error or arrive on another thread.
            engine = TextToSpeech(app) { status ->
                handler.post {
                    if (token == generation) {
                        val tts = engine
                        if (status != TextToSpeech.SUCCESS || tts == null) {
                            stop()
                        } else {
                            start(token, tts, text, phoneChannel)
                        }
                    }
                }
            }
            // Also bounds stuck initialization, missing progress callbacks and long speech.
            handler.postDelayed({ if (token == generation) stop() }, 12000L)
        } catch (e: Exception) {
            Log.w("AutoClock", "Speech engine unavailable", e)
            stop()
        }
    }

    private fun start(token: Long, tts: TextToSpeech, text: String, phoneChannel: Boolean) {
        try {
            val voice = tts.voices.orEmpty().filter {
                it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.sortedWith(compareByDescending<android.speech.tts.Voice> {
                it.locale.country == "US" || it.locale.country == "GB"
            }.thenByDescending { it.quality }.thenBy { it.latency }).firstOrNull()
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                Log.w("AutoClock", "No installed offline English speech voice")
                stop()
                return
            }
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (phoneChannel) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            val id = "autoclock-notice-$token"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                private fun finish(utteranceId: String?) {
                    if (utteranceId == id) handler.post {
                        if (token == generation) stop()
                    }
                }
            })
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) stop()
        } catch (e: Exception) {
            Log.w("AutoClock", "Notice speech unavailable", e)
            stop()
        }
    }

    fun stop() {
        generation++
        val finished = onFinished
        onFinished = null
        handler.removeCallbacksAndMessages(null)
        val old = engine
        engine = null
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.shutdown() }
        }
        finished?.invoke()
    }
}
