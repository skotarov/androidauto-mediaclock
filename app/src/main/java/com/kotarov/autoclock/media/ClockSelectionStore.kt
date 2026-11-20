package com.kotarov.autoclock.media

import android.content.SharedPreferences

/** Persist independent checkbox choices for each of the three radio styles. */
object ClockSelectionStore {
    private const val KEY_SCHEMA_V13 = "display_simplified_v13"

    /**
     * Migration from older flat layouts. Previously forced analogue digits
     * remain checked for existing installs, but can now be switched off to
     * get a genuinely clean analogue face.
     */
    fun migrateSimplifiedIfNeeded(prefs: SharedPreferences) {
        migrateIfNeeded(prefs)
        if (prefs.getBoolean(KEY_SCHEMA_V13, false)) return
        prefs.edit()
            .putBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL, true)
            .putBoolean(ClockPreset.KEY_DIGITAL_ADD_ANALOG, false)
            .putBoolean(ClockPreset.KEY_WEATHER_ADD_ANALOG, false)
            .putBoolean(KEY_SCHEMA_V13, true)
            .apply()
    }
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
        when (family) {
            ClockFamily.ANALOG ->
                ClockPreset.fromAddons(
                    ClockFamily.ANALOG,
                    first = prefs.getBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL, true),
                    second = prefs.getBoolean(ClockPreset.KEY_ANALOG_ADD_WEATHER, false)
                )
            ClockFamily.DIGITAL ->
                if (prefs.getBoolean(ClockPreset.KEY_DIGITAL_ADD_WEATHER, false))
                    ClockPreset.DIGITAL_TIME_WEATHER
                else ClockPreset.DIGITAL_TIME
            ClockFamily.WEATHER ->
                if (prefs.getBoolean(ClockPreset.KEY_WEATHER_ADD_DIGITAL, false))
                    ClockPreset.WEATHER_WITH_TIME
                else ClockPreset.WEATHER_ONLY
        }

    fun choose(prefs: SharedPreferences, preset: ClockPreset) {
        val family = preset.family
        prefs.edit()
            .putString(ClockPreset.KEY_FAMILY, family.id)
            .putBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL, preset.hasDigitalTime)
            .putBoolean(ClockPreset.KEY_ANALOG_ADD_WEATHER,
                if (family == ClockFamily.ANALOG) preset.usesWeather
                else prefs.getBoolean(ClockPreset.KEY_ANALOG_ADD_WEATHER, false))
            .putBoolean(ClockPreset.KEY_DIGITAL_ADD_ANALOG, false)
            .putBoolean(ClockPreset.KEY_DIGITAL_ADD_WEATHER,
                if (family == ClockFamily.DIGITAL) preset.usesWeather
                else prefs.getBoolean(ClockPreset.KEY_DIGITAL_ADD_WEATHER, false))
            .putBoolean(ClockPreset.KEY_WEATHER_ADD_ANALOG, false)
            .putBoolean(ClockPreset.KEY_WEATHER_ADD_DIGITAL,
                if (family == ClockFamily.WEATHER) preset.hasDigitalTime
                else prefs.getBoolean(ClockPreset.KEY_WEATHER_ADD_DIGITAL, false))
            .apply()
    }
}
