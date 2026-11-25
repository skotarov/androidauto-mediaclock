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

/** One user-selected direct HTTPS stream. No idle player or persistent audio route. */
class CustomStreamPlayer(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var generation = 0L
    private var ownsMode = false
    private var ownsDevice = false
    private var requested: Pair<String, Boolean>? = null

    fun sync(url: String?, phone: Boolean, onError: (String) -> Unit) {
        val next = url?.takeIf { valid(it) }?.let { it to phone }
        if (requested == next) return
        stop()
        if (next == null) return
        requested = next
        val token = generation
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
                        monitor(token, id, onError)
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
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (phone) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            val timeout = Runnable { fail(token, "Stream connection timed out", onError) }
            media.setOnPreparedListener {
                if (token == generation && player === it) {
                    handler.removeCallbacks(timeout)
                    runCatching { it.start() }.onFailure { fail(token, "Stream playback failed", onError) }
                }
            }
            media.setOnErrorListener { _, _, _ ->
                fail(token, "Stream unavailable", onError)
                true
            }
            media.setOnCompletionListener { fail(token, "Stream ended", onError) }
            media.setDataSource(url)
            handler.postDelayed(timeout, 20000L)
            media.prepareAsync()
        } catch (e: Exception) {
            // Do not log the URL, which might contain a private access token.
            Log.w("AutoClock", "Stream could not be opened")
            fail(token, "Stream unavailable", onError)
        }
    }

    private fun fail(token: Long, message: String, onError: (String) -> Unit) {
        if (token != generation) return
        // Keep the failed request until explicit Play or configuration change;
        // a weather refresh must never trigger an infinite reconnect loop.
        val previous = requested
        stop()
        requested = previous
        onError(message)
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)
        requested = null
        val old = player
        player = null
        if (old != null) runCatching { old.release() }
        releaseRoute()
    }

    private fun releaseRoute() {
        if (Build.VERSION.SDK_INT >= 31 && ownsDevice) runCatching { audio.clearCommunicationDevice() }
        ownsDevice = false
        if (ownsMode) runCatching { audio.mode = AudioManager.MODE_NORMAL }
        ownsMode = false
    }

    companion object {
        const val KEY_ENABLED = "custom_stream_enabled"
        const val KEY_URL = "custom_stream_url"
        fun valid(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)
    }
}
