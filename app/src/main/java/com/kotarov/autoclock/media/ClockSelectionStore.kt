package com.kotarov.autoclock.media

import android.content.SharedPreferences

/** Persist independent checkbox choices for each of the three radio styles. */
object ClockSelectionStore {
    fun migrateIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean(ClockPreset.KEY_SCHEMA_V11, false)) return

        val analog = prefs.getString(ClockPreset.KEY_OLD_ANALOG_DETAIL, null)
        val digital = prefs.getString(ClockPreset.KEY_OLD_DIGITAL_DETAIL, null)
        val weather = prefs.getString(ClockPreset.KEY_OLD_WEATHER_DETAIL, null)
        val oldMode = prefs.getString("display_mode", null)
        val oldPreset = ClockPreset.fromId(oldMode)

        // Prefer explicit v0.10 saved details, then the older selected mode.
        val analogDetail = analog ?: if (oldPreset.family == ClockFamily.ANALOG) {
            when (oldPreset) {
                ClockPreset.ANALOG_TIME -> "time"
                ClockPreset.ANALOG_TIME_WEATHER -> "time_weather"
                ClockPreset.ANALOG_WEATHER -> "weather"
                else -> null
            }
        } else null
        val digitalDetail = digital ?: if (oldPreset.family == ClockFamily.DIGITAL) {
            if (oldPreset.usesWeather) "time_weather" else "time"
        } else null
        val weatherDetail = weather ?: if (oldPreset.family == ClockFamily.WEATHER) {
            if (oldPreset.hasDigitalTime) "with_time" else "only"
        } else null

        val family = prefs.getString(ClockPreset.KEY_FAMILY, null)
            ?: oldPreset.family.id

        prefs.edit()
            .putString(ClockPreset.KEY_FAMILY, family)
            .putBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL,
                analogDetail == "time" || analogDetail == "time_weather")
            .putBoolean(ClockPreset.KEY_ANALOG_ADD_WEATHER,
                analogDetail == "weather" || analogDetail == "time_weather")
            .putBoolean(ClockPreset.KEY_DIGITAL_ADD_ANALOG, false)
            .putBoolean(ClockPreset.KEY_DIGITAL_ADD_WEATHER, digitalDetail == "time_weather")
            .putBoolean(ClockPreset.KEY_WEATHER_ADD_ANALOG, false)
            .putBoolean(ClockPreset.KEY_WEATHER_ADD_DIGITAL, weatherDetail == "with_time")
            .putBoolean(ClockPreset.KEY_SCHEMA_V11, true)
            .apply()
    }

    fun current(prefs: SharedPreferences): ClockPreset {
        val family = ClockFamily.fromId(prefs.getString(ClockPreset.KEY_FAMILY, null))
            ?: ClockFamily.ANALOG
        return forFamily(prefs, family)
    }

    fun forFamily(prefs: SharedPreferences, family: ClockFamily): ClockPreset =
        ClockPreset.fromAddons(
            family,
            prefs.getBoolean(ClockPreset.firstKey(family), false),
            prefs.getBoolean(ClockPreset.secondKey(family), false)
        )

    fun choose(prefs: SharedPreferences, preset: ClockPreset) {
        val family = preset.family
        val first = when (family) {
            ClockFamily.ANALOG -> preset.hasDigitalTime
            ClockFamily.DIGITAL, ClockFamily.WEATHER -> preset.hasAnalog
        }
        val second = when (family) {
            ClockFamily.ANALOG, ClockFamily.DIGITAL -> preset.usesWeather
            ClockFamily.WEATHER -> preset.hasDigitalTime
        }
        prefs.edit()
            .putString(ClockPreset.KEY_FAMILY, family.id)
            .putBoolean(ClockPreset.firstKey(family), first)
            .putBoolean(ClockPreset.secondKey(family), second)
            .apply()
    }
}
