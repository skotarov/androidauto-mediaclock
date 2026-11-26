package com.kotarov.autoclock.media

import android.content.SharedPreferences

enum class PhoneAudioMode(val id: String, val label: String) {
    OFF("off", "Off"),
    ALERTS("alerts", "Alerts only"),
    CONTINUOUS("continuous", "Continuous (test)");

    companion object {
        const val KEY = "bluetooth_phone_audio_mode"
        fun toggle(prefs: SharedPreferences) {
            val next = if (read(prefs) == OFF) ALERTS else OFF
            prefs.edit().putString(KEY, next.id).apply()
        }
        fun read(prefs: SharedPreferences): PhoneAudioMode =
            when (prefs.getString(KEY, null)) {
                OFF.id -> OFF
                ALERTS.id, CONTINUOUS.id -> ALERTS
                else -> if (prefs.getBoolean(NoticeAudio.KEY_PHONE_CHANNEL, false)) ALERTS else OFF
            }
    }
}
