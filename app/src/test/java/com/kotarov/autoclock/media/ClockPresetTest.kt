package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class ClockPresetTest {
    @Test fun fourModesHaveStableIds() {
        assertEquals(4, ClockPreset.entries.size)
        assertEquals(4, ClockPreset.entries.map { it.id }.toSet().size)
        ClockPreset.entries.forEach { assertEquals(it, ClockPreset.fromId(it.id)) }
    }
    @Test fun oldIdsMapToSupportedModes() {
        assertEquals(ClockPreset.ANALOG_PURE, ClockPreset.fromId("analog_time"))
        assertEquals(ClockPreset.ANALOG_WEATHER, ClockPreset.fromId("analog_time_weather"))
        assertEquals(ClockPreset.DIGITAL_TIME, ClockPreset.fromId("digital_analog"))
        assertEquals(ClockPreset.DIGITAL_TIME_WEATHER, ClockPreset.fromId("digital_analog_weather"))
        assertEquals(ClockPreset.ANALOG_WEATHER, ClockPreset.fromId("weather"))
    }
}
