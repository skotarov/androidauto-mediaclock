package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDisplayTest {
    @Test fun onlyAnalogAndDigitalCanBeSelectedAsClockModes() {
        assertEquals(
            listOf(ClockFamily.ANALOG, ClockFamily.DIGITAL),
            PlaybackDisplay.selectableFamilies
        )
        assertFalse(PlaybackDisplay.selectableFamilies.contains(ClockFamily.WEATHER))
    }

    @Test fun playKeepsExactSelectedClockIncludingItsAddons() {
        listOf(
            ClockPreset.ANALOG_PURE,
            ClockPreset.ANALOG_WEATHER,
            ClockPreset.ANALOG_TIME_WEATHER,
            ClockPreset.DIGITAL_TIME,
            ClockPreset.DIGITAL_TIME_WEATHER
        ).forEach {
            assertEquals(it, PlaybackDisplay.visiblePreset(it, playing = true))
        }
    }

    @Test fun pauseAlwaysShowsWeatherWithoutAnAddedClock() {
        for (selected in listOf(ClockPreset.ANALOG_PURE, ClockPreset.DIGITAL_TIME_WEATHER)) {
            val paused = PlaybackDisplay.visiblePreset(selected, playing = false)
            assertEquals(ClockPreset.WEATHER_ONLY, paused)
            assertTrue(paused.usesWeather)
            assertFalse(paused.hasAnalog)
            assertFalse(paused.hasDigitalTime)
        }
    }

    @Test fun legacyWeatherSelectionRevertsToAnalog() {
        assertEquals(ClockFamily.ANALOG, PlaybackDisplay.clockFamily(ClockFamily.WEATHER))
        assertEquals(ClockFamily.ANALOG, PlaybackDisplay.clockFamily(null))
        assertEquals(ClockFamily.DIGITAL, PlaybackDisplay.clockFamily(ClockFamily.DIGITAL))
    }
}
