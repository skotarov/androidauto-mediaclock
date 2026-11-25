package com.kotarov.autoclock.media

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.PushbackInputStream
import java.util.Locale

/** One active document, one utterance in flight. No audio files or network TTS. */
internal class TextFileReader(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("text_bookmarks", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var worker: Thread? = null
    private var token = 0L
    private var url: String? = null
    private var text: String? = null
    private var offset = 0
    private var utteranceStart = 0
    private var utteranceEnd = 0
    private var activeId: String? = null
    private var sequence = 0L
    private var changed: () -> Unit = {}
    private var finished: ((String?) -> Unit)? = null
    private val timeout = Runnable { finish("Text-to-speech timed out") }

    fun progress(): String? = text?.let { "${TextReading.percent(offset, it.length)}%" }

    fun start(source: String, language: String, phone: Boolean,
              onChanged: () -> Unit, onFinished: (String?) -> Unit) {
        stop()
        url = source
        changed = onChanged
        finished = onFinished
        val generation = token
        worker = Thread({
            val result = runCatching { load(source) }
            handler.post {
                if (generation == token) {
                    worker = null
                    val body = result.getOrNull()
                    if (body == null) finish("TXT unavailable or too large (2 million characters max)")
                    else if (body.isBlank()) finish("Text file is empty")
                    else {
                        text = body
                        offset = prefs.getInt(source, 0).coerceIn(0, body.length - 1)
                        if (offset > 0 && Character.isLowSurrogate(body[offset])) offset--
                        initialize(generation, TextReading.language(body, language, Locale.getDefault().language), phone)
                    }
                }
            }
        }, "AutoClockText").apply { isDaemon = true; start() }
    }

    private fun load(source: String): String {
        val input = app.contentResolver.openInputStream(Uri.parse(source)) ?: error("No file")
        return PushbackInputStream(input, 3).use { stream ->
            val prefix = ByteArray(3)
            var n = 0
            while (n < prefix.size) {
                val read = stream.read(prefix, n, prefix.size - n)
                if (read <= 0) break
                n += read
            }
            val b = prefix.map { it.toInt() and 255 }
            val (charset, skip) = when {
                n >= 3 && b == listOf(239, 187, 191) -> Charsets.UTF_8 to 3
                n >= 2 && b[0] == 255 && b[1] == 254 -> Charsets.UTF_16LE to 2
                n >= 2 && b[0] == 254 && b[1] == 255 -> Charsets.UTF_16BE to 2
                else -> Charsets.UTF_8 to 0
            }
            if (n > skip) stream.unread(prefix, skip, n - skip)
            stream.reader(charset).use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) error("Cancelled")
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(body.length + count <= TextReading.MAX_CHARS)
                    body.append(buffer, 0, count)
                }
                body.toString()
            }
        }
    }

    private fun initialize(generation: Long, language: String, phone: Boolean) {
        handler.postDelayed(timeout, 15000)
        try {
            engine = TextToSpeech(app) { status ->
                handler.post {
                    if (generation == token) {
                        val tts = engine
                        if (status != TextToSpeech.SUCCESS || tts == null) finish("Text-to-speech unavailable")
                        else configure(tts, generation, language, phone)
                    }
                }
            }
        } catch (_: Exception) { finish("Text-to-speech unavailable") }
    }

    private fun configure(tts: TextToSpeech, generation: Long, language: String, phone: Boolean) {
        handler.removeCallbacks(timeout)
        try {
            val voice = tts.voices.orEmpty().filter {
                it.locale.language == language && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.sortedWith(compareByDescending<android.speech.tts.Voice> {
                it.locale == Locale.getDefault()
            }.thenByDescending { it.quality }.thenBy { it.latency }).firstOrNull()
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                finish("Install an offline TTS voice for " + language)
                return
            }
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (phone) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    handler.post {
                        if (generation == token && activeId == utteranceId)
                            offset = (utteranceStart + start).coerceIn(utteranceStart, utteranceEnd)
                    }
                }
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (generation == token && activeId == utteranceId) {
                            handler.removeCallbacks(timeout)
                            offset = utteranceEnd
                            checkpoint()
                            next()
                        }
                    }
                }
                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) = failed(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = failed(utteranceId)
                private fun failed(id: String?) {
                    handler.post {
                        if (generation == token && activeId == id) finish("Text-to-speech failed")
                    }
                }
            })
            next()
        } catch (_: Exception) { finish("Text-to-speech unavailable") }
    }

    private fun next() {
        val body = text ?: return
        val tts = engine ?: return
        while (offset < body.length && body[offset].isWhitespace()) offset++
        if (offset >= body.length) { finish(null); return }
        utteranceStart = offset
        utteranceEnd = TextReading.chunkEnd(body, offset)
        val id = "autoclock-text-$token-${++sequence}"
        activeId = id
        changed()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 120000)
        if (tts.speak(body.substring(utteranceStart, utteranceEnd), TextToSpeech.QUEUE_FLUSH, null, id)
            != TextToSpeech.SUCCESS) finish("Text-to-speech failed")
    }

    private fun checkpoint() {
        val source = url ?: return
        if (text != null) prefs.edit().putInt(source, offset).apply()
    }

    private fun finish(error: String?) {
        val callback = finished
        if (error == null) offset = 0
        stop()
        callback?.invoke(error)
    }

    fun stop() {
        checkpoint()
        token++
        handler.removeCallbacksAndMessages(null)
        worker?.interrupt()
        worker = null
        activeId = null
        finished = null
        changed = {}
        val old = engine
        engine = null
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.shutdown() }
        }
        url = null
        text = null
        offset = 0
    }
}
