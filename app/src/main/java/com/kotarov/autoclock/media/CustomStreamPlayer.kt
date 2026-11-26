package com.kotarov.autoclock.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.URI

/** One user-selected direct HTTP(S) audio stream. No idle player or persistent audio route. */
class CustomStreamPlayer(context: Context) {
    private val app = context.applicationContext
    private val localBookmarks = app.getSharedPreferences("audio_bookmarks", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var fallback: NetworkAudioPlayer? = null
    private var prepareTimeout: Runnable? = null
    private var generation = 0L
    private val keepAlive = PhoneAudioKeepAlive()
    private var ownsMode = false
    private var ownsDevice = false
    private var routeWatch: (() -> Unit)? = null
    private var requested: Triple<String, Boolean, Boolean>? = null
    data class SeekState(val position: Int, val duration: Int)
    private var savedSeek: Pair<String, SeekState>? = null
    private var textLanguage: String? = null
    private var reader: TextFileReader? = null
    private var savedBookTimeline: Pair<String, TextReading.Timeline>? = null
    private var failed = false
    private var suspendedMedia = false
    private var reading = false
    private var speed = 1f
    private var prepared = false
    private var seekable = false
    private var changed: () -> Unit = {}

    /** Never report a stale saved seek position as currently playing media. */
    fun activePositionMs(url: String?): Int? {
        if (url == null || requested?.first != url || !prepared || !seekable || suspendedMedia) return null
        fallback?.let { return it.position() }
        val media = player ?: return null
        return runCatching { media.currentPosition }.getOrNull()?.takeIf { it >= 0 }
    }

    fun readingProgress(url: String?): String? =
        if (reading && requested?.first == url) reader?.progress() else null

    fun seekState(url: String?): SeekState? {
        if (url == null) return null
        val media = player
        if (requested?.first == url) {
            // An active request must establish its own timeline. Never reuse a
            // previous finite duration while the new connection is live/loading.
            if (!prepared || !seekable) return null
            fallback?.let { return it.seekState() }
            if (media == null) return null
            return runCatching { SeekState(media.currentPosition, media.duration) }.getOrNull()
                ?.takeIf { PlaybackTimeline.known(it.position, it.duration) }
        }
        return savedSeek?.takeIf { it.first == url }?.second
            ?.takeIf { PlaybackTimeline.known(it.position, it.duration) }
    }

    fun isPlaying(): Boolean = if (reading) reader?.progress() != null
        else fallback?.isPlaying() ?: runCatching { player?.isPlaying == true }.getOrDefault(false)

    internal fun bookTimeline(url: String?): TextReading.Timeline? {
        if (url == null) return null
        val active = reader?.timeline(url)
        if (active != null) return active
        // During a fresh load, wait until the new document length is known.
        if (requested?.first == url) return null
        return savedBookTimeline?.takeIf { it.first == url }?.second
    }

    fun seekTextTo(url: String?, positionMs: Long): Boolean =
        url != null && reader?.seekEstimatedTime(url, positionMs) == true

    fun setSpeed(value: Float, sourceUrl: String?) {
        speed = PlaybackSpeed.normalize(value)
        // A pending source switch must not change the outgoing file or restart its TTS.
        if (sourceUrl == null || requested?.first != sourceUrl) return
        if (reading) reader?.setSpeed(speed)
        if (prepared && seekable && !suspendedMedia) fallback?.setSpeed(speed)
        val media = player
        if (media != null && prepared && seekable && !suspendedMedia && isPlaying() &&
            kotlin.math.abs(actualMediaSpeed() - speed) > 0.001f) applyMediaSpeed(media)
    }

    fun actualMediaSpeed(): Float = if (seekable) fallback?.speed() ?: runCatching { player?.playbackParams?.speed ?: 1f }.getOrDefault(1f) else 1f

    private fun applyMediaSpeed(media: MediaPlayer) {
        runCatching {
            if (seekable) media.playbackParams = media.playbackParams.setSpeed(speed).setPitch(1f)
        }.onFailure { Log.w("AutoClock", "Playback speed unavailable") }
    }

    fun canSkipText(url: String?): Boolean = url != null && reader?.canSkip(url) == true

    fun skipText(url: String?, deltaMs: Int): Boolean =
        url != null && reader?.skipBy(url, deltaMs) == true

    fun seekBy(url: String?, deltaMs: Int): Boolean {
        val current = seekState(url) ?: return false
        return seekTo(url, current.position.toLong() + deltaMs)
    }

    fun seekTo(url: String?, position: Long): Boolean {
        val current = seekState(url) ?: return false
        val target = position.coerceIn(0L, current.duration.toLong()).toInt()
        val media = player
        if (prepared && seekable && requested?.first == url && media != null) {
            if (runCatching { media.seekTo(target) }.isFailure) return false
        }
        if (prepared && seekable && requested?.first == url && fallback != null) {
            if (fallback?.seek(target) != true) return false
        }
        if (url != null) {
            savedSeek = url to current.copy(position = target)
            if (validLocal(url)) localBookmarks.edit().putInt(url, target).apply()
        }
        return true
    }

    fun sync(url: String?, phone: Boolean, sharedPhoneChannel: Boolean = false,
             textLanguage: String? = null, onChanged: () -> Unit = {}, onError: (String) -> Unit) {
        changed = onChanged
        val next = url?.takeIf { valid(it) || validLocal(it) }?.let { Triple(it, phone, sharedPhoneChannel) }
        if (requested == next && this.textLanguage == textLanguage) {
            if (suspendedMedia && next != null) {
                suspendedMedia = false
                try {
                    player?.let { it.start(); applyMediaSpeed(it) }
                    fallback?.let { if (seekable) it.setSpeed(speed); it.play() }
                    changed()
                } catch (_: Exception) { fail(generation, "Playback failed", onError) }
            }
            return
        }
        val reuse = next != null && textLanguage != null && reader?.matches(next.first, textLanguage) == true
        stop(keepReader = reuse)
        if (next == null) return
        this.textLanguage = textLanguage
        requested = next
        val token = generation
        if (sharedPhoneChannel && phone && Build.VERSION.SDK_INT >= 31) {
            open(token, next.first, true, onError)
            return
        }
        if (!phone || Build.VERSION.SDK_INT < 31) {
            open(token, next.first, false, onError)
            return
        }
        try {
            if (audio.mode != AudioManager.MODE_NORMAL) {
                fail(token, "Phone audio busy", onError)
                return
            }
            val devices = audio.availableCommunicationDevices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            val current = audio.communicationDevice
            val device = devices.firstOrNull { it.id == current?.id } ?: devices.singleOrNull()
            if (device == null) {
                open(token, next.first, false, onError)
                return
            }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsMode = true
            ownsDevice = audio.setCommunicationDevice(device)
            if (!ownsDevice) {
                releaseRoute()
                open(token, next.first, false, onError)
                return
            }
            if (!keepAlive.start()) {
                releaseRoute()
                open(token, next.first, false, onError)
                return
            }
            awaitRoute(token, next.first, device.id, SystemClock.elapsedRealtime() + 5000L, onError)
        } catch (e: Exception) {
            releaseRoute()
            open(token, next.first, false, onError)
        }
    }

    private fun awaitRoute(token: Long, url: String, id: Int, deadline: Long,
                           onError: (String) -> Unit) {
        if (token != generation || Build.VERSION.SDK_INT < 31) return
        if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
            fail(token, "Phone audio interrupted", onError)
        } else if (audio.communicationDevice?.id == id) {
            handler.postDelayed({
                if (token == generation) {
                    if (routeActive(id)) {
                        open(token, url, true, onError)
                        watchRoute(token, id, onError)
                    } else fail(token, "Phone audio interrupted", onError)
                }
            }, 700L)
        } else if (SystemClock.elapsedRealtime() >= deadline) {
            releaseRoute()
            open(token, url, false, onError)
        } else handler.postDelayed({ awaitRoute(token, url, id, deadline, onError) }, 200L)
    }

    private fun routeActive(id: Int): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching {
            audio.mode == AudioManager.MODE_IN_COMMUNICATION &&
                audio.communicationDevice?.id == id
        }.getOrDefault(false)

    private fun watchRoute(token: Long, id: Int, onError: (String) -> Unit) {
        if (token != generation) return
        routeWatch = PhoneRouteWatch.start(audio, handler) {
            if (token == generation && !routeActive(id)) fail(token, "Phone audio interrupted", onError)
        }
        if (routeWatch == null) monitor(token, id, onError)
    }

    private fun monitor(token: Long, id: Int, onError: (String) -> Unit) {
        handler.postDelayed({
            if (token == generation) {
                if (routeActive(id)) monitor(token, id, onError)
                else fail(token, "Phone audio interrupted", onError)
            }
        }, 1000L)
    }

    private fun open(token: Long, url: String, phone: Boolean, onError: (String) -> Unit) {
        if (token != generation) return
        val language = textLanguage
        if (language != null) {
            reading = true
            val currentReader = reader ?: TextFileReader(app).also { reader = it }
            currentReader.setSpeed(speed)
            currentReader.start(url, language, phone, changed) { error ->
                if (token == generation) {
                    if (error == null) {
                        savedBookTimeline = null
                        stop()
                        onError("Media ended")
                    } else fail(token, error, onError)
                }
            }
            return
        }
        if (!validLocal(url) && NetworkAudioPlayer.preferred(url)) {
            openFallback(token, url, phone, onError)
            return
        }
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (phone) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            val timeout = Runnable { nativeFailed(token, url, phone, "Stream connection timed out", onError) }
            prepareTimeout = timeout
            media.setOnPreparedListener {
                if (token == generation && player === it) {
                    handler.removeCallbacks(timeout)
                    prepareTimeout = null
                    runCatching {
                        prepared = true
                        seekable = it.duration > 0
                        if (!seekable && savedSeek?.first == url) savedSeek = null
                        val position = savedSeek?.takeIf { p -> p.first == url }?.second?.position
                            ?: if (validLocal(url)) localBookmarks.getInt(url, 0) else 0
                        if (seekable && position > 0 && position < it.duration) it.seekTo(position)
                        it.start()
                        if (speed != 1f) applyMediaSpeed(it)
                        changed()
                    }.onFailure { fail(token, "Playback failed", onError) }
                }
            }
            media.setOnInfoListener { _, what, _ ->
                if (token == generation && what == MediaPlayer.MEDIA_INFO_NOT_SEEKABLE) {
                    seekable = false
                    if (savedSeek?.first == url) savedSeek = null
                    changed()
                }
                false
            }
            media.setOnSeekCompleteListener { if (token == generation) changed() }
            media.setOnErrorListener { _, _, _ ->
                if (player === media) nativeFailed(token, url, phone, "Stream unavailable", onError)
                true
            }
            media.setOnCompletionListener {
                if (token == generation && (validLocal(url) || seekable)) {
                    savedSeek = null
                    if (validLocal(url)) localBookmarks.edit().remove(url).apply()
                    requested = null
                    stop()
                    onError("Media ended")
                } else fail(token, "Stream ended", onError)
            }
            if (validLocal(url)) media.setDataSource(app, android.net.Uri.parse(url))
            else if (URI(url).scheme.equals("https", true)) {
                // Prevent HTTPS streams from forwarding private URL parameters to HTTP.
                media.setDataSource(app, android.net.Uri.parse(url), mapOf("android-allow-cross-domain-redirect" to "0"))
            } else media.setDataSource(url)
            handler.postDelayed(timeout, 20000L)
            media.prepareAsync()
        } catch (e: Exception) {
            // Do not log the URL, which might contain a private access token.
            Log.w("AutoClock", "Stream could not be opened")
            nativeFailed(token, url, phone, "Stream unavailable", onError)
        }
    }

    private fun nativeFailed(token: Long, url: String, phone: Boolean, message: String, onError: (String) -> Unit) {
        if (token != generation) return
        if (!prepared && !validLocal(url) && fallback == null) {
            prepareTimeout?.let { handler.removeCallbacks(it) }
            prepareTimeout = null
            val old = player
            player = null
            runCatching { old?.release() }
            // Keep the existing route and silent keepalive intact during the one fallback.
            openFallback(token, url, phone, onError)
        } else fail(token, message, onError)
    }

    private fun openFallback(token: Long, url: String, phone: Boolean, onError: (String) -> Unit) {
        if (token != generation) return
        try {
            val engine = NetworkAudioPlayer(app, phone)
            fallback = engine
            val timeout = Runnable {
                if (fallback === engine) fail(token, "Stream connection timed out", onError)
            }
            prepareTimeout = timeout
            handler.postDelayed(timeout, 20_000L)
            engine.open(url, onReady = {
                if (token == generation && fallback === engine) {
                    handler.removeCallbacks(timeout)
                    prepareTimeout = null
                    val first = !prepared
                    prepared = true
                    seekable = engine.seekState() != null
                    if (first && seekable) {
                        val pos = savedSeek?.takeIf { it.first == url }?.second?.position ?: 0
                        if (pos > 0) engine.seek(pos)
                        engine.setSpeed(speed)
                    }
                    if (!seekable && savedSeek?.first == url) savedSeek = null
                    changed()
                }
            }, onChanged = {
                if (token == generation && fallback === engine && prepared) changed()
            }, onEnded = {
                if (token == generation && fallback === engine) {
                    val finite = seekable
                    savedSeek = null
                    requested = null
                    stop()
                    onError(if (finite) "Media ended" else "Stream ended")
                }
            }, onError = {
                if (token == generation && fallback === engine) fail(token, "Stream unavailable (AAC compatible player)", onError)
            })
        } catch (_: Exception) {
            fail(token, "Stream unavailable (AAC compatible player)", onError)
        }
    }

    private fun fail(token: Long, message: String, onError: (String) -> Unit) {
        if (token != generation) return
        // Keep the failed request until explicit Play or configuration change;
        // a weather refresh must never trigger an infinite reconnect loop.
        val previous = requested
        val language = textLanguage
        stop()
        requested = previous
        textLanguage = language
        failed = true
        onError(message)
    }

    fun retryFailed() { if (failed) stop() }

    fun suspendForNotice() {
        if (failed || suspendedMedia) return
        val media = player
        if (fallback != null && prepared && requested?.second == false && fallback?.pause() == true) {
            suspendedMedia = true
            return
        }
        // A normal media output can stay prepared while a short notice plays.
        // HFP must be released before the notice can claim the phone route.
        if (media != null && prepared && requested?.second == false &&
            runCatching { media.pause() }.isSuccess) {
            suspendedMedia = true
            return
        }
        stop(keepReader = true)
    }

    fun close() {
        stop()
        reader?.close()
        reader = null
    }

    fun stop(keepReader: Boolean = false) {
        generation++
        handler.removeCallbacksAndMessages(null)
        prepareTimeout = null
        requested?.first?.let { source ->
            reader?.timeline(source)?.let { savedBookTimeline = source to it }
        }
        if (keepReader) reader?.pause() else reader?.stop()
        failed = false
        suspendedMedia = false
        reading = false
        textLanguage = null
        val previous = requested
        requested = null
        val old = player
        val alternate = fallback
        if (alternate != null && previous != null && prepared && seekable) {
            alternate.seekState()?.let { savedSeek = previous.first to it }
        }
        fallback = null
        alternate?.release()
        if (old != null && previous != null && prepared && seekable) {
            runCatching { SeekState(old.currentPosition, old.duration) }.getOrNull()
                ?.takeIf { it.duration > 0 }?.let {
                    savedSeek = previous.first to it
                    if (validLocal(previous.first)) localBookmarks.edit().putInt(previous.first,
                        it.position.takeIf { pos -> pos < it.duration } ?: 0).apply()
                }
        }
        prepared = false
        seekable = false
        player = null
        if (old != null) runCatching { old.release() }
        releaseRoute()
    }

    private fun releaseRoute() {
        routeWatch?.invoke()
        routeWatch = null
        keepAlive.stop()
        if (Build.VERSION.SDK_INT >= 31 && ownsDevice) runCatching { audio.clearCommunicationDevice() }
        ownsDevice = false
        if (ownsMode) runCatching { audio.mode = AudioManager.MODE_NORMAL }
        ownsMode = false
    }

    companion object {
        const val KEY_ENABLED = "custom_stream_enabled"
        const val KEY_URL = "custom_stream_url"
        fun validLocal(url: String): Boolean = runCatching {
            val uri = android.net.Uri.parse(url)
            uri.scheme == "content" && !uri.authority.isNullOrBlank()
        }.getOrDefault(false)
        fun valid(url: String): Boolean = runCatching {
            val uri = URI(url)
            val secure = uri.scheme.equals("https", true)
            val publicHttp = uri.scheme.equals("http", true) && uri.rawQuery == null
            (secure || publicHttp) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)
    }
}
