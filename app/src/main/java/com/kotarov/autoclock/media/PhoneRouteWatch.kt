package com.kotarov.autoclock.media

import android.media.AudioManager
import android.os.Build
import android.os.Handler
import java.util.concurrent.Executor

/** Prefer Android's route/mode callbacks to a permanent polling timer. */
internal object PhoneRouteWatch {
    fun start(audio: AudioManager, handler: Handler, changed: () -> Unit): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < 31) return null
        val executor = Executor { task -> handler.post(task) }
        val device = AudioManager.OnCommunicationDeviceChangedListener { changed() }
        val mode = AudioManager.OnModeChangedListener { changed() }
        try {
            audio.addOnCommunicationDeviceChangedListener(executor, device)
            try {
                audio.addOnModeChangedListener(executor, mode)
            } catch (e: Exception) {
                runCatching { audio.removeOnCommunicationDeviceChangedListener(device) }
                return null
            }
        } catch (_: Exception) { return null }
        return {
            runCatching { audio.removeOnCommunicationDeviceChangedListener(device) }
            runCatching { audio.removeOnModeChangedListener(mode) }
            Unit
        }
    }
}
