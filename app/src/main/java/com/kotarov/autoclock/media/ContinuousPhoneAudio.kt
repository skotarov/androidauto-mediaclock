package com.kotarov.autoclock.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Experimental hands-free connection, owned by the active car media session.
 * Loops silent PCM; never opens a microphone or attempts to override a phone call. */
class ContinuousPhoneAudio(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val keepAlive = PhoneAudioKeepAlive()
    private var ownsMode = false
    private var ownsDevice = false
    private var targetId: Int? = null
    private var enabled = false
    private var ready = false
    private var notify: ((Boolean, String) -> Unit)? = null
    private var removeListeners: (() -> Unit)? = null

    val active: Boolean get() = ready

    fun start(onChange: (Boolean, String) -> Unit) {
        if (enabled) return
        enabled = true
        notify = onChange
        if (Build.VERSION.SDK_INT < 31) {
            end("Requires Android 12")
            return
        }
        try {
            if (audio.mode != AudioManager.MODE_NORMAL) {
                end("Phone audio busy")
                return
            }
            val devices = audio.availableCommunicationDevices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            val current = audio.communicationDevice
            val device = devices.firstOrNull { it.id == current?.id } ?: devices.singleOrNull()
            if (device == null) {
                end("No unique Bluetooth phone device")
                return
            }
            targetId = device.id
            val executor = java.util.concurrent.Executor { command -> handler.post(command) }
            val routeListener = AudioManager.OnCommunicationDeviceChangedListener {
                if (enabled && ownsDevice) {
                    if (matches()) markReady()
                    else if (ready) end("Phone audio disconnected")
                }
            }
            val modeListener = AudioManager.OnModeChangedListener { mode ->
                if (enabled && ownsMode && mode != AudioManager.MODE_IN_COMMUNICATION)
                    end("Phone audio interrupted")
            }
            audio.addOnCommunicationDeviceChangedListener(executor, routeListener)
            removeListeners = { audio.removeOnCommunicationDeviceChangedListener(routeListener) }
            audio.addOnModeChangedListener(executor, modeListener)
            removeListeners = {
                runCatching { audio.removeOnCommunicationDeviceChangedListener(routeListener) }
                runCatching { audio.removeOnModeChangedListener(modeListener) }
            }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsMode = true
            ownsDevice = audio.setCommunicationDevice(device)
            if (!ownsDevice) {
                end("Phone channel unavailable")
                return
            }
            if (!keepAlive.start()) {
                end("Phone audio keepalive unavailable")
                return
            }
            onChange(false, "Connecting…")
            handler.postDelayed({
                if (enabled && !ready) {
                    if (matches()) markReady() else end("Phone channel timed out")
                }
            }, 5000L)
            if (matches()) markReady()
        } catch (e: Exception) {
            Log.w("AutoClock", "Continuous phone audio unavailable", e)
            end("Phone channel unavailable")
        }
    }

    private fun matches(): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching {
            audio.mode == AudioManager.MODE_IN_COMMUNICATION &&
                audio.communicationDevice?.id == targetId
        }.getOrDefault(false)

    private fun markReady() {
        if (ready || !enabled) return
        ready = true
        notify?.invoke(true, "Connected")
    }

    private fun end(status: String) {
        val callback = notify
        stop()
        callback?.invoke(false, status)
    }

    fun stop() {
        enabled = false
        ready = false
        notify = null
        handler.removeCallbacksAndMessages(null)
        removeListeners?.let { runCatching { it() } }
        removeListeners = null
        keepAlive.stop()
        if (Build.VERSION.SDK_INT >= 31 && ownsDevice) runCatching { audio.clearCommunicationDevice() }
        ownsDevice = false
        if (ownsMode) runCatching { audio.mode = AudioManager.MODE_NORMAL }
        ownsMode = false
        targetId = null
    }

    companion object {
        const val KEY_STATUS = "continuous_phone_audio_status"
    }
}
