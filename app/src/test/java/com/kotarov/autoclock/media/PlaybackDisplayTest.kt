package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackDisplayTest {
    @Test fun onlyAnalogAndDigitalCanBeSelectedAsClockModes() {
        assertEquals(listOf(ClockFamily.ANALOG, ClockFamily.DIGITAL),
            PlaybackDisplay.selectableFamilies)
    }

    @Test fun playAddsWeatherRegardlessOfPreviousPreset() {
        assertEquals(ClockPreset.ANALOG_WEATHER,
            PlaybackDisplay.visiblePreset(ClockPreset.ANALOG_PURE, true))
        assertEquals(ClockPreset.DIGITAL_TIME_WEATHER,
            PlaybackDisplay.visiblePreset(ClockPreset.DIGITAL_TIME, true))
    }

    @Test fun pauseRemovesWeatherButKeepsClockFamily() {
        assertEquals(ClockPreset.ANALOG_PURE,
            PlaybackDisplay.visiblePreset(ClockPreset.ANALOG_WEATHER, false))
        assertEquals(ClockPreset.DIGITAL_TIME,
            PlaybackDisplay.visiblePreset(ClockPreset.DIGITAL_TIME_WEATHER, false))
    }

    @Test fun togglingWeatherNeverSelectsWeatherOnlyArtwork() {
        ClockPreset.entries.forEach { selected ->
            listOf(true, false).forEach { playing ->
                val visible = PlaybackDisplay.visiblePreset(selected, playing)
                assertNotEquals(ClockFamily.WEATHER, visible.family)
                assertEquals(playing, visible.usesWeather)
                assertEquals(PlaybackDisplay.clockFamily(selected.family), visible.family)
            }
        }
    }

    @Test fun legacyWeatherSelectionRevertsToAnalog() {
        assertEquals(ClockFamily.ANALOG, PlaybackDisplay.clockFamily(ClockFamily.WEATHER))
        assertEquals(ClockFamily.ANALOG, PlaybackDisplay.clockFamily(null))
    }
}
