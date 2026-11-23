package com.kotarov.autoclock.media

import android.content.SharedPreferences

/** Persist analog/digital choices; weather-only is the Pause display. */
object ClockSelectionStore {
    private const val KEY_SCHEMA_V13 = "display_simplified_v13"
    private const val KEY_ANALOG_DEFAULT_OFF_V20 = "analog_digital_default_off_v20"

    /**
     * New installs show the clean analog dial without digital digits.
     *
     * In v0.13-v0.19 the migration forcibly checked "Add digital time"
     * even if the setting had never been explicitly selected. A one-time
     * v0.20 correction clears that legacy default on upgrade. Afterward
     * manual checkbox changes are preserved on all future launches.
     */
    fun migrateSimplifiedIfNeeded(prefs: SharedPreferences) {
        val wasSimplified = prefs.getBoolean(KEY_SCHEMA_V13, false)
        migrateIfNeeded(prefs)
        if (!wasSimplified) {
            prefs.edit()
                // Keep intentional migrated choices; do not force digits on.
                .putBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL,
                    prefs.getBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL, false))
                .putBoolean(ClockPreset.KEY_DIGITAL_ADD_ANALOG, false)
                .putBoolean(ClockPreset.KEY_WEATHER_ADD_ANALOG, false)
                .putBoolean(KEY_SCHEMA_V13, true)
                .apply()
        }
        if (!prefs.getBoolean(KEY_ANALOG_DEFAULT_OFF_V20, false)) {
            val editor = prefs.edit()
                .putBoolean(KEY_ANALOG_DEFAULT_OFF_V20, true)
            if (wasSimplified) {
                // Previous releases enabled digits by default. Reset once,
                // then preserve the user's explicit choice from this version.
                editor.putBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL, false)
            }
            editor.apply()
        }
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
        val family = PlaybackDisplay.clockFamily(
            ClockFamily.fromId(prefs.getString(ClockPreset.KEY_FAMILY, null))
        )
        return forFamily(prefs, family)
    }

    fun forFamily(prefs: SharedPreferences, family: ClockFamily): ClockPreset =
        when (family) {
            ClockFamily.ANALOG ->
                ClockPreset.fromAddons(
                    ClockFamily.ANALOG,
                    first = prefs.getBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL, false),
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
            .putBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL,
                if (family == ClockFamily.ANALOG) preset.hasDigitalTime
                else prefs.getBoolean(ClockPreset.KEY_ANALOG_ADD_DIGITAL, false))
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
