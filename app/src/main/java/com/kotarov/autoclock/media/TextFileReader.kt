package com.kotarov.autoclock.media

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One active document, bounded memory, one utterance in flight.
 * A temporary UTF-16 cache provides character offsets without retaining a whole book in RAM.
 * Short audio interruptions keep the prepared cache and engine; explicit Stop frees both. */
internal class TextFileReader(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("text_bookmarks", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>()) { runnable ->
        Thread(runnable, "AutoClockText").apply { isDaemon = true }
    }
    private data class Document(val file: File, val length: Int, val sample: String, val cached: Boolean = false) {
        fun release() { if (!cached) file.delete() }
    }
    private data class Chunk(val start: Int, val end: Int, val body: String)
    private var document: Document? = null
    private var job: Future<*>? = null
    private var loadSignal: CancellationSignal? = null
    private var engine: TextToSpeech? = null
    private var speechSpeed = 1f
    private var ready = false
    private var paused = false
    private var token = 0L
    private var readSerial = 0L
    private var url: String? = null
    private var languageChoice: String? = null
    private var offset = 0
    private var utteranceStart = 0
    private var utteranceEnd = 0
    private var activeId: String? = null
    private var sequence = 0L
    private var lastSaved = -1
    private var lastCheckpoint = 0L
    private var lastPercent = -1
    private var lastProgressReport = Long.MIN_VALUE
    private var changed: () -> Unit = {}
    private var finished: ((String?) -> Unit)? = null
    private val timeout = Runnable { finish("Text-to-speech timed out") }

    fun matches(source: String, language: String): Boolean =
        ready && document != null && url == source && languageChoice == language

    fun progress(): String? = document?.takeIf { ready && !paused }?.let {
        "${TextReading.percent(offset, it.length)}%"
    }

    fun timeline(source: String): TextReading.Timeline? = document?.takeIf { ready && url == source }
        ?.let { TextReading.timeline(offset, it.length) }

    /** Relative speech seek uses text offsets. */
    fun canSkip(source: String): Boolean = ready && document != null && url == source

    fun skipBy(source: String, deltaMs: Int): Boolean {
        val doc = document ?: return false
        if (!canSkip(source)) return false
        return seekOffset(TextReading.skipOffset(offset, doc.length, deltaMs))
    }

    fun seekEstimatedTime(source: String, positionMs: Long): Boolean {
        val doc = document ?: return false
        if (!canSkip(source)) return false
        return seekOffset(TextReading.offsetAtTime(positionMs, doc.length))
    }

    private fun seekOffset(target: Int): Boolean {
        if (target == offset) return true
        activeId = null
        cancelJob()
        runCatching { engine?.stop() }
        offset = target
        checkpoint(force = true)
        lastPercent = -1
        reportProgress(force = true)
        if (!paused) next(alignWord = true)
        return true
    }

    fun setSpeed(value: Float) {
        val speed = PlaybackSpeed.normalize(value)
        if (speed == speechSpeed) return
        speechSpeed = speed
        val tts = engine ?: return
        if (!ready) return
        if (tts.setSpeechRate(speed) != TextToSpeech.SUCCESS) return
        if (!paused) {
            activeId = null
            cancelJob()
            runCatching { tts.stop() }
            checkpoint(force = true)
            next(alignWord = true)
        }
    }

    fun start(source: String, language: String, phone: Boolean,
              onChanged: () -> Unit, onFinished: (String?) -> Unit) {
        if (matches(source, language) && paused) {
            changed = onChanged
            finished = onFinished
            paused = false
            lastPercent = -1
            val tts = engine ?: run { finish("Text-to-speech unavailable"); return }
            try {
                setRoute(tts, phone)
                next(alignWord = true)
            } catch (_: Exception) { finish("Text-to-speech unavailable") }
            return
        }
        stop()
        url = source
        languageChoice = language
        changed = onChanged
        finished = onFinished
        val generation = token
        val signal = CancellationSignal()
        loadSignal = signal
        handler.postDelayed(timeout, 60_000L)
        job = worker.submit {
            val result = runCatching { prepare(source, signal) }
            handler.post {
                if (generation != token) {
                    result.getOrNull()?.release()
                } else {
                    job = null
                    loadSignal = null
                    handler.removeCallbacks(timeout)
                    val doc = result.getOrNull()
                    if (doc == null) finish(result.exceptionOrNull()?.message?.takeIf { it.startsWith("PDF needs") } ?: "Document unavailable, protected, too large or unreadable")
                    else {
                        document = doc
                        if (doc.length == 0) finish("No readable text found (scanned PDFs need OCR)")
                        else {
                            offset = prefs.getInt(source, 0).coerceIn(0, doc.length)
                            lastSaved = offset
                            lastCheckpoint = SystemClock.elapsedRealtime()
                            initialize(generation,
                                TextReading.language(doc.sample, language, Locale.getDefault().language), phone)
                        }
                    }
                }
            }
        }
    }

    private fun prepare(source: String, signal: CancellationSignal): Document {
        val dir = File(app.cacheDir, "text-reading").apply { mkdirs() }
        // Remove orphaned caches after a process kill; never touch a fresh active cache.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        dir.listFiles()?.filter { it.name.startsWith("book-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
        val file = File.createTempFile("book-", ".utf16", dir)
        try {
            var length = 0
            val sample = StringBuilder(4000)
            val uri = Uri.parse(source)
            val name = runCatching {
                app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull().orEmpty()
            val format = BookFormat.detect(name, app.contentResolver.getType(uri)) ?: BookFormat.PLAIN
            val backendKey = "pdf_backend:" + source
            val previousBackend = prefs.getString(backendKey, null)
            // Keep the extraction algorithm stable for bookmarks created by
            // previous versions: different engines can produce different offsets.
            val tryNative = format == BookFormat.PDF && PdfBookExtractor.nativeAvailable() &&
                (!LegacyPdfExtractor.available || previousBackend == "native" || (previousBackend == null && !prefs.contains(source)))
            if (format == BookFormat.PDF && !tryNative && !LegacyPdfExtractor.available)
                error("PDF needs Android 15 or Android 12–14 with S Extension 13; install Universal")
            val sourceKey = BookDiskCache.fingerprint(app, uri, signal)
            fun cacheKey(native: Boolean) = sourceKey?.let {
                BookDiskCache.key(it, format.name + if (format == BookFormat.PDF) (if (native) ":native" else ":pdfbox") else ":v2")
            }
            cacheKey(tryNative)?.let { key ->
                BookDiskCache.read(dir, key)?.let { hit ->
                    file.delete()
                    return Document(hit.file, hit.length, hit.sample, true)
                }
            }
            fun extract(native: Boolean) {
                signal.throwIfCanceled()
                length = 0
                sample.setLength(0)
                if (format == BookFormat.PDF && !native)
                    LegacyPdfExtractor.init(app)
                val afd = app.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: error("No file")
                afd.use { descriptor ->
                    descriptor.createInputStream().use { input ->
                        file.outputStream().buffered().writer(Charsets.UTF_16BE).use { output ->
                            BookExtractor.extract(format, input, dir, {
                                signal.throwIfCanceled()
                                if (Thread.currentThread().isInterrupted) error("Cancelled")
                            }, nativePdf = native) { text ->
                                require(length.toLong() + text.length <= TextReading.MAX_CHARS)
                                length += text.length
                                if (sample.length < 4000)
                                    sample.append(text, 0, minOf(text.length, 4000 - sample.length))
                                output.write(text)
                            }
                        }
                    }
                }
            }
            var nativeUsed = tryNative
            try {
                extract(nativeUsed)
            } catch (e: Exception) {
                // Fall back only for a new PDF, before committing a backend
                // choice; never reinterpret an existing native bookmark.
                if (!nativeUsed || previousBackend == "native" || !LegacyPdfExtractor.available) throw e
                signal.throwIfCanceled()
                if (Thread.currentThread().isInterrupted) throw e
                nativeUsed = false
                extract(false)
            }
            if (format == BookFormat.PDF) {
                signal.throwIfCanceled()
                prefs.edit().putString(backendKey, if (nativeUsed) "native" else "pdfbox").apply()
            }
            signal.throwIfCanceled()
            // The cache is a fixed-width character index, not a synthesized audio file.
            require(file.length() == length.toLong() * 2L)
            val cached = cacheKey(nativeUsed)?.let { BookDiskCache.store(dir, it, file) }
            return if (cached != null) Document(cached, length, sample.toString(), true)
                else Document(file, length, sample.toString())
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    private fun initialize(generation: Long, language: String, phone: Boolean) {
        handler.postDelayed(timeout, 15_000L)
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

    private fun setRoute(tts: TextToSpeech, phone: Boolean) {
        tts.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(if (phone) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
            setRoute(tts, phone)
            tts.setSpeechRate(speechSpeed)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    handler.post {
                        if (generation == token && !paused && activeId == utteranceId) {
                            offset = (utteranceStart + start).coerceIn(utteranceStart, utteranceEnd)
                            checkpoint()
                            reportProgress()
                        }
                    }
                }
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (generation == token && !paused && activeId == utteranceId) {
                            handler.removeCallbacks(timeout)
                            activeId = null
                            offset = utteranceEnd
                            checkpoint()
                            reportProgress()
                            next()
                        }
                    }
                }
                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) = failed(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = failed(utteranceId)
                private fun failed(id: String?) {
                    handler.post {
                        if (generation == token && !paused && activeId == id) finish("Text-to-speech failed")
                    }
                }
            })
            ready = true
            reportProgress(force = true)
            next()
        } catch (_: Exception) { finish("Text-to-speech unavailable") }
    }

    /** Read only a small window on a worker; skipped whitespace never fills RAM. */
    private fun readChunk(doc: Document, position: Int, alignWord: Boolean): Chunk? =
        RandomAccessFile(doc.file, "r").use { input ->
            var start = position
            if (alignWord && start in 1 until doc.length) {
                val from = (start - 64).coerceAtLeast(0)
                val count = minOf(start - from + 1, doc.length - from)
                input.seek(from.toLong() * 2L)
                val around = CharArray(count) { input.readChar() }
                start = from + TextReading.wordStart(String(around), start - from)
            }
            while (start < doc.length) {
                if (Thread.currentThread().isInterrupted) error("Cancelled")
                input.seek(start.toLong() * 2L)
                if (start > 0 && Character.isLowSurrogate(input.readChar())) start--
                input.seek(start.toLong() * 2L)
                val count = minOf(4096, doc.length - start)
                val bytes = ByteArray(count * 2)
                input.readFully(bytes)
                val chars = CharArray(count)
                ByteBuffer.wrap(bytes).asCharBuffer().get(chars)
                val body = String(chars)
                val first = body.indexOfFirst { !it.isWhitespace() }
                if (first < 0) {
                    start += count
                    continue
                }
                val end = TextReading.chunkEnd(body, first)
                return@use Chunk(start + first, start + end, body.substring(first, end))
            }
            null
        }

    private fun next(alignWord: Boolean = false) {
        val doc = document ?: return
        if (!ready || paused) return
        if (offset >= doc.length) { finish(null); return }
        val generation = token
        val serial = ++readSerial
        val position = offset
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 15_000L)
        job = worker.submit {
            val result = runCatching { readChunk(doc, position, alignWord) }
            handler.post {
                if (generation == token && serial == readSerial && !paused) {
                    job = null
                    handler.removeCallbacks(timeout)
                    if (result.isFailure) finish("Document read failed")
                    else {
                        val chunk = result.getOrNull()
                        if (chunk == null) finish(null)
                        else speakChunk(chunk)
                    }
                }
            }
        }
    }

    private fun speakChunk(chunk: Chunk) {
        val tts = engine ?: return
        offset = chunk.start
        utteranceStart = chunk.start
        utteranceEnd = chunk.end
        val id = "autoclock-text-$token-${++sequence}"
        activeId = id
        reportProgress()
        handler.postDelayed(timeout, 120_000L)
        try {
            if (tts.speak(chunk.body, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
                finish("Text-to-speech failed")
        } catch (_: Exception) { finish("Text-to-speech failed") }
    }

    private fun reportProgress(force: Boolean = false) {
        val doc = document ?: return
        val percent = TextReading.percent(offset, doc.length)
        val now = SystemClock.elapsedRealtime()
        // Event driven; do not start a periodic timer. At most one position
        // callback per second, except explicit seek/start/end.
        if (!force && lastProgressReport != Long.MIN_VALUE && now - lastProgressReport < 1000L && offset < doc.length) return
        lastPercent = percent
        lastProgressReport = now
        changed()
    }

    private fun checkpoint(force: Boolean = false) {
        val source = url ?: return
        if (document == null || offset == lastSaved) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCheckpoint < 45_000L) return
        prefs.edit().putInt(source, offset).apply()
        lastSaved = offset
        lastCheckpoint = now
    }

    private fun cancelJob() {
        readSerial++
        runCatching { loadSignal?.cancel() }
        loadSignal = null
        job?.cancel(true)
        job = null
        worker.purge()
        handler.removeCallbacks(timeout)
    }

    fun pause() {
        if (paused) return
        if (!ready) { stop(); return }
        checkpoint(force = true)
        paused = true
        activeId = null
        cancelJob()
        finished = null
        changed = {}
        runCatching { engine?.stop() }
    }

    private fun finish(error: String?) {
        val callback = finished
        if (error == null) offset = 0
        stop()
        callback?.invoke(error)
    }

    fun stop() {
        checkpoint(force = true)
        token++
        cancelJob()
        activeId = null
        finished = null
        changed = {}
        val old = engine
        engine = null
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.shutdown() }
        }
        document?.release()
        document = null
        ready = false
        paused = false
        url = null
        languageChoice = null
        offset = 0
        lastSaved = -1
        lastPercent = -1
        lastProgressReport = Long.MIN_VALUE
    }

    fun close() {
        stop()
        worker.shutdownNow()
    }
}
