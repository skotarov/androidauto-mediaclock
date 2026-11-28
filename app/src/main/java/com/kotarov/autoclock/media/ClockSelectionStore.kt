package com.kotarov.autoclock.media

import android.content.SharedPreferences

object ClockSelectionStore {
    private const val MIGRATED = "display_four_modes_v52"

    fun migrateSimplifiedIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATED, false)) return
        val legacy = ClockPreset.fromId(prefs.getString("display_mode", null))
        val family = ClockFamily.fromId(prefs.getString(ClockPreset.KEY_FAMILY, null))
            ?: legacy.family
        val weather = if (prefs.contains(ClockPreset.KEY_ADD_WEATHER))
            prefs.getBoolean(ClockPreset.KEY_ADD_WEATHER, false)
        else prefs.getBoolean("analog_add_weather", false) ||
            prefs.getBoolean("digital_add_weather", false) ||
            prefs.getString("analog_detail", null) in listOf("weather", "time_weather") ||
            prefs.getString("digital_detail", null) == "time_weather" || legacy.usesWeather
        prefs.edit().putString(ClockPreset.KEY_FAMILY, family.id)
            .putBoolean(ClockPreset.KEY_ADD_WEATHER, weather)
            .putBoolean(MIGRATED, true).apply()
    }

    fun weatherEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(ClockPreset.KEY_ADD_WEATHER, false)

    fun setWeatherEnabled(prefs: SharedPreferences, enabled: Boolean) {
        if (weatherEnabled(prefs) != enabled)
            prefs.edit().putBoolean(ClockPreset.KEY_ADD_WEATHER, enabled).apply()
    }

    fun current(prefs: SharedPreferences): ClockPreset =
        forFamily(prefs, PlaybackDisplay.clockFamily(
            ClockFamily.fromId(prefs.getString(ClockPreset.KEY_FAMILY, null))))

    fun forFamily(prefs: SharedPreferences, family: ClockFamily): ClockPreset =
        PlaybackDisplay.forFamily(family, weatherEnabled(prefs))
}
