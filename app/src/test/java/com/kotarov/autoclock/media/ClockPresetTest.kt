package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockPresetTest {
    @Test fun threeFamiliesHaveExactlyTheRequestedVariantCounts() {
        assertEquals(3, ClockFamily.entries.size)
        assertEquals(3, ClockPreset.options(ClockFamily.ANALOG).size)
        assertEquals(2, ClockPreset.options(ClockFamily.DIGITAL).size)
        assertEquals(2, ClockPreset.options(ClockFamily.WEATHER).size)
    }

    @Test fun everyModeHasUniqueStableIdAndQueue() {
        assertEquals(7, ClockPreset.entries.map { it.id }.toSet().size)
        assertEquals(7, ClockPreset.entries.map { it.queueId }.toSet().size)
        ClockPreset.entries.forEach { assertEquals(it, ClockPreset.fromId(it.id)) }
    }

    @Test fun timeIsThePrimaryDisplayOnDigitalClock() {
        assertTrue(!ClockPreset.DIGITAL_TIME.usesWeather)
        assertTrue(ClockPreset.DIGITAL_TIME_WEATHER.usesWeather)
        assertEquals(ClockFamily.DIGITAL, ClockPreset.DIGITAL_TIME_WEATHER.family)
    }

    @Test fun mainWeatherCanHaveSmallerTimeOrNoTime() {
        assertEquals(ClockFamily.WEATHER, ClockPreset.WEATHER_ONLY.family)
        assertEquals(ClockFamily.WEATHER, ClockPreset.WEATHER_WITH_TIME.family)
        assertTrue(ClockPreset.WEATHER_ONLY.usesWeather)
        assertTrue(ClockPreset.WEATHER_WITH_TIME.usesWeather)
    }

    @Test fun selectionPersistsOneVariantForEachFamily() {
        assertEquals(
            ClockPreset.ANALOG_WEATHER,
            ClockPreset.selected("analog", "weather", "time_weather", "with_time")
        )
        assertEquals(
            ClockPreset.DIGITAL_TIME_WEATHER,
            ClockPreset.selected("digital", "weather", "time_weather", "with_time")
        )
        assertEquals(
            ClockPreset.WEATHER_WITH_TIME,
            ClockPreset.selected("weather", "weather", "time_weather", "with_time")
        )
    }

    @Test fun migratesLegacyLayouts() {
        assertEquals(
            ClockPreset.DIGITAL_TIME,
            ClockPreset.selected(null, null, null, null, "digital")
        )
        assertEquals(
            ClockPreset.ANALOG_TIME_WEATHER,
            ClockPreset.selected(null, null, null, null, "both")
        )
        assertEquals(
            ClockPreset.WEATHER_ONLY,
            ClockPreset.selected(null, null, null, null, "weather")
        )
    }
}
