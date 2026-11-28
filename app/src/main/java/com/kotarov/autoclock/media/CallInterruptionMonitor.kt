package com.kotarov.autoclock.media

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.util.concurrent.Executor

/** No polling and no call log or number access. All callbacks run on the service handler. */
internal class CallInterruptionMonitor(
    context: Context, private val handler: Handler, private val changed: (Boolean) -> Unit
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val telecom = app.getSystemService(TelecomManager::class.java)
    private var active = false
    private var registered = false
    private var removeMode: (() -> Unit)? = null
    private var broadcastBusy = false
    private var reported = false
    private val settle = Runnable {
        if (active) {
            val busy = isCallNow()
            if (busy != reported) {
                reported = busy
                changed(busy)
            }
        }
    }
    private fun permitted(): Boolean =
        app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun aggregateBusy(): Boolean =
        permitted() && runCatching { telecom?.isInCall == true }.getOrDefault(false)

    fun isCallNow(): Boolean {
        val mode = audio?.mode
        // MODE_IN_COMMUNICATION is also used by our own HFP keepalive; do not treat it as a call.
        return broadcastBusy || aggregateBusy() || mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active || intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING, TelephonyManager.EXTRA_STATE_OFFHOOK -> broadcastBusy = true
                TelephonyManager.EXTRA_STATE_IDLE -> broadcastBusy = false
                else -> return
            }
            update()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhone() {
        if (registered || !permitted()) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                app.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter, null, handler)
            true
        }.getOrDefault(false)
    }

    fun start(): Boolean {
        if (active) return reported
        active = true
        broadcastBusy = false
        registerPhone()
        if (Build.VERSION.SDK_INT >= 31 && audio != null) {
            removeMode = runCatching {
                val manager = requireNotNull(audio)
                val listener = AudioManager.OnModeChangedListener { update() }
                manager.addOnModeChangedListener(Executor { task -> handler.post(task) }, listener)
                val cancel: () -> Unit = { manager.removeOnModeChangedListener(listener) }
                cancel
            }.getOrNull()
        }
        reported = isCallNow()
        return reported
    }

    fun refreshPermission() {
        if (!active) return
        registerPhone()
        update()
    }

    private fun update() {
        if (!active) return
        handler.removeCallbacks(settle)
        if (isCallNow()) {
            if (!reported) {
                reported = true
                changed(true)
            }
            // An IDLE broadcast can precede Telecom's final state; verify once after settling.
            if (!broadcastBusy) handler.postDelayed(settle, 1200L)
        } else if (reported) {
            // Give the car/phone audio route time to return to media before reopening it.
            handler.postDelayed(settle, 1200L)
        }
    }

    fun stop() {
        active = false
        handler.removeCallbacks(settle)
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        runCatching { removeMode?.invoke() }
        removeMode = null
        broadcastBusy = false
        reported = false
    }

    companion object {
        const val PERMISSION_REFRESH = "call_permission_refresh_v118"
    }
}
