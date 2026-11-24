package com.kotarov.autoclock.media

/** Both transport states display the selected clock family.
 * Playing adds weather; paused removes it. Legacy WEATHER maps to analog. */
object PlaybackDisplay {
    val selectableFamilies = listOf(ClockFamily.ANALOG, ClockFamily.DIGITAL)

    fun clockFamily(family: ClockFamily?): ClockFamily =
        if (family == ClockFamily.DIGITAL) ClockFamily.DIGITAL else ClockFamily.ANALOG

    fun visiblePreset(selectedClock: ClockPreset, playing: Boolean): ClockPreset =
        forFamily(selectedClock.family, playing)

    fun forFamily(family: ClockFamily, weather: Boolean): ClockPreset =
        when (clockFamily(family)) {
            ClockFamily.DIGITAL -> if (weather) ClockPreset.DIGITAL_TIME_WEATHER else ClockPreset.DIGITAL_TIME
            ClockFamily.ANALOG -> if (weather) ClockPreset.ANALOG_WEATHER else ClockPreset.ANALOG_PURE
        }
}
