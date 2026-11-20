package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockPresetTest {
    @Test fun eachFamilyHasFourCheckboxStatesIncludingPure() {
        assertEquals(3, ClockFamily.entries.size)
        for (family in ClockFamily.entries) {
            assertEquals(4, ClockPreset.options(family).size)
            assertEquals(4, (listOf(false, true).flatMap { a ->
                listOf(false, true).map { b -> ClockPreset.fromAddons(family, a, b) }
            }).toSet().size)
        }
    }

    @Test fun pureModesHaveNoExtraContent() {
        val analog = ClockPreset.fromAddons(ClockFamily.ANALOG)
        assertEquals(ClockPreset.ANALOG_PURE, analog)
        assertTrue(analog.hasAnalog)
        assertFalse(analog.hasDigitalTime)
        assertFalse(analog.usesWeather)

        val digital = ClockPreset.fromAddons(ClockFamily.DIGITAL)
        assertEquals(ClockPreset.DIGITAL_TIME, digital)
        assertFalse(digital.hasAnalog)
        assertTrue(digital.hasDigitalTime)
        assertFalse(digital.usesWeather)

        val weather = ClockPreset.fromAddons(ClockFamily.WEATHER)
        assertEquals(ClockPreset.WEATHER_ONLY, weather)
        assertFalse(weather.hasAnalog)
        assertFalse(weather.hasDigitalTime)
        assertTrue(weather.usesWeather)
    }

    @Test fun checkboxesAreIndependentForEachFamily() {
        assertEquals(ClockPreset.ANALOG_TIME,
            ClockPreset.fromAddons(ClockFamily.ANALOG, first = true))
        assertEquals(ClockPreset.ANALOG_WEATHER,
            ClockPreset.fromAddons(ClockFamily.ANALOG, second = true))
        assertEquals(ClockPreset.ANALOG_TIME_WEATHER,
            ClockPreset.fromAddons(ClockFamily.ANALOG, first = true, second = true))

        assertEquals(ClockPreset.DIGITAL_ANALOG,
            ClockPreset.fromAddons(ClockFamily.DIGITAL, first = true))
        assertEquals(ClockPreset.DIGITAL_TIME_WEATHER,
            ClockPreset.fromAddons(ClockFamily.DIGITAL, second = true))
        assertEquals(ClockPreset.DIGITAL_ANALOG_WEATHER,
            ClockPreset.fromAddons(ClockFamily.DIGITAL, first = true, second = true))

        assertEquals(ClockPreset.WEATHER_ANALOG,
            ClockPreset.fromAddons(ClockFamily.WEATHER, first = true))
        assertEquals(ClockPreset.WEATHER_WITH_TIME,
            ClockPreset.fromAddons(ClockFamily.WEATHER, second = true))
        assertEquals(ClockPreset.WEATHER_ANALOG_TIME,
            ClockPreset.fromAddons(ClockFamily.WEATHER, first = true, second = true))
    }

    @Test fun allTwelveCombinationsHaveStableUniqueIds() {
        assertEquals(12, ClockPreset.entries.size)
        assertEquals(12, ClockPreset.entries.map { it.id }.toSet().size)
        assertEquals(12, ClockPreset.entries.map { it.queueId }.toSet().size)
        ClockPreset.entries.forEach { assertEquals(it, ClockPreset.fromId(it.id)) }
    }

    @Test fun oldMediaIdsHaveSafeMappings() {
        assertEquals(ClockPreset.ANALOG_PURE, ClockPreset.fromId("analog"))
        assertEquals(ClockPreset.DIGITAL_TIME, ClockPreset.fromId("digital"))
        assertEquals(ClockPreset.WEATHER_ONLY, ClockPreset.fromId("weather"))
        assertEquals(ClockPreset.ANALOG_TIME_WEATHER, ClockPreset.fromId("both"))
    }
}
