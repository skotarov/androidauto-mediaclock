package com.kotarov.autoclock.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Owns the temporary HFP request only while a notice is being played.
 * No phone call, microphone access or persistent Bluetooth connection. */
class NoticeAudio(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val chime = NoticeSoundPlayer()
    private val speech = NoticeSpeech(context)
    private var generation = 0L
    private var finished: (() -> Unit)? = null
    private var ownsMode = false
    private var ownsDevice = false

    fun play(sound: NoticeSound, text: String, phoneChannel: Boolean,
             sharedPhoneChannel: Boolean = false,
             onFinished: () -> Unit = {}) {
        stop()
        finished = onFinished
        val token = generation
        if (sharedPhoneChannel && phoneChannel && Build.VERSION.SDK_INT >= 31) {
            // The continuous mode owns the route; never claim or release it here.
            playOnRoute(token, sound, text, true)
            return
        }
        if (!phoneChannel || Build.VERSION.SDK_INT < 31) {
            playOnRoute(token, sound, text, false)
            return
        }
        // Never compete with an ongoing call or another communication session.
        if (audio.mode != AudioManager.MODE_NORMAL) { complete(); return }
        try {
            val devices = audio.availableCommunicationDevices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            // Prefer the already selected Bluetooth device. Do not choose
            // arbitrarily between multiple headsets.
            val current = audio.communicationDevice
            val device = devices.firstOrNull { it.id == current?.id }
                ?: devices.singleOrNull()
            if (device == null) {
                playOnRoute(token, sound, text, false)
                return
            }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsMode = true
            ownsDevice = audio.setCommunicationDevice(device)
            if (!ownsDevice) {
                fallback(token, sound, text)
                return
            }
            val deadline = SystemClock.elapsedRealtime() + 5000L
            awaitDevice(token, device.id, deadline, sound, text)
        } catch (e: Exception) {
            Log.w("AutoClock", "Bluetooth phone channel unavailable", e)
            fallback(token, sound, text)
        }
    }

    private fun awaitDevice(token: Long, id: Int, deadline: Long,
                            sound: NoticeSound, text: String) {
        if (token != generation || Build.VERSION.SDK_INT < 31) return
        try {
            if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
                complete()
                return
            }
            if (audio.communicationDevice?.id == id) {
                // Give the car time to unmute its telephone input.
                handler.postDelayed({
                    if (token == generation) {
                        if (routeActive(id)) {
                            playOnRoute(token, sound, text, true)
                            monitor(token, id)
                        } else fallback(token, sound, text)
                    }
                }, 700L)
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                fallback(token, sound, text)
            } else {
                handler.postDelayed({ awaitDevice(token, id, deadline, sound, text) }, 200L)
            }
        } catch (e: Exception) {
            fallback(token, sound, text)
        }
    }

    private fun routeActive(id: Int): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching {
            audio.mode == AudioManager.MODE_IN_COMMUNICATION &&
                audio.communicationDevice?.id == id
        }.getOrDefault(false)

    private fun monitor(token: Long, id: Int) {
        handler.postDelayed({
            if (token == generation) {
                if (routeActive(id)) monitor(token, id) else complete()
            }
        }, 250L)
    }

    private fun fallback(token: Long, sound: NoticeSound, text: String) {
        if (token != generation) return
        releaseRoute()
        if (audio.mode == AudioManager.MODE_NORMAL) playOnRoute(token, sound, text, false)
        else complete()
    }

    private fun playOnRoute(token: Long, sound: NoticeSound, text: String, phone: Boolean) {
        if (token != generation) return
        chime.play(sound, phone)
        speech.speak(text,
            if (sound == NoticeSound.SILENT) 0L else sound.durationMs.toLong() + 250L,
            phone) {
            if (token == generation) complete()
        }
        // Independent safety timeout even if the TTS engine never calls back.
        handler.postDelayed({ if (token == generation) complete() }, 15000L)
    }

    private fun complete() {
        val callback = finished
        stop()
        callback?.invoke()
    }

    fun stop() {
        finished = null
        generation++
        handler.removeCallbacksAndMessages(null)
        chime.stop()
        speech.stop()
        releaseRoute()
    }

    private fun releaseRoute() {
        if (Build.VERSION.SDK_INT >= 31 && ownsDevice) {
            runCatching { audio.clearCommunicationDevice() }
        }
        ownsDevice = false
        if (ownsMode) {
            // MODE_NORMAL relinquishes our communication-mode request.
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
        }
        ownsMode = false
    }

    companion object {
        const val KEY_PHONE_CHANNEL = "notice_bluetooth_phone_channel"
    }
}
