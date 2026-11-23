package com.kotarov.autoclock.media

/**
 * Play shows the selected analog/digital clock. Pause shows the weather-only
 * artwork without modifying the clock selected for the next Play.
 *
 * WEATHER remains an internal ClockPreset renderer for Pause and legacy IDs,
 * but is deliberately not available as a primary phone/Android Auto choice.
 */
object PlaybackDisplay {
    val selectableFamilies = listOf(ClockFamily.ANALOG, ClockFamily.DIGITAL)

    fun clockFamily(family: ClockFamily?): ClockFamily =
        when (family) {
            ClockFamily.DIGITAL -> ClockFamily.DIGITAL
            else -> ClockFamily.ANALOG
        }

    fun visiblePreset(selectedClock: ClockPreset, playing: Boolean): ClockPreset =
        if (playing) selectedClock else ClockPreset.WEATHER_ONLY
}
