package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockMediaLabelsTest {
    private val time = LocalDateTime.of(2026, 11, 23, 11, 49)

    private fun weather(
        place: String = "Станция София (LBSF)",
        code: Int = 3
    ) = WeatherStore.Snapshot(
        temperatureC = 14.2,
        weatherCode = code,
        observedAtMs = 10L,
        isDay = true,
        originLabel = place,
        sourceLabel = "NOAA · измерване METAR",
        reportTimeMs = 10L,
        autoSelected = true
    )

    @Test fun plainAnalogShowsOnlyDigitalTimeInMediaText() {
        val lines = ClockMediaLabels.forClock(ClockPreset.ANALOG_PURE, time, null)
        assertEquals("11:49", lines.title)
        assertEquals(ClockMediaLabels.EMPTY, lines.subtitle)
        assertFalse(lines.title.contains("Аналогов"))
    }

    @Test fun analogWithWeatherShowsCloudAndCityWithoutStationName() {
        for (preset in listOf(ClockPreset.ANALOG_WEATHER, ClockPreset.ANALOG_TIME_WEATHER)) {
            val lines = ClockMediaLabels.forClock(preset, time, weather())
            assertEquals("11:49", lines.title)
            assertEquals("☁ София", lines.subtitle)
            assertFalse(lines.subtitle.contains("Станция"))
            assertFalse(lines.subtitle.contains("LBSF"))
            assertFalse(lines.subtitle.contains("NOAA"))
        }
    }

    @Test fun digitalAndWeatherFamiliesAlsoAvoidTechnicalStationNames() {
        assertEquals("☁ София",
            ClockMediaLabels.forClock(ClockPreset.DIGITAL_TIME_WEATHER, time, weather()).subtitle)
        val weatherOnly = ClockMediaLabels.forClock(ClockPreset.WEATHER_ONLY, time, weather())
        assertEquals("☁ 14°C", weatherOnly.title)
        assertEquals("☁ София", weatherOnly.subtitle)
    }

    @Test fun manualCitiesAndNoDataRemainReadable() {
        assertEquals("☁ Варна",
            ClockMediaLabels.forClock(ClockPreset.ANALOG_WEATHER, time,
                weather(place = "Варна")).subtitle)
        val missing = ClockMediaLabels.forClock(ClockPreset.ANALOG_WEATHER, time, null)
        assertEquals("11:49", missing.title)
        assertEquals("☁ Няма данни", missing.subtitle)
    }

    @Test fun pureDigitalModeHasNoExtraSubtitle() {
        val lines = ClockMediaLabels.forClock(ClockPreset.DIGITAL_TIME, time, null)
        assertEquals("11:49", lines.title)
        assertEquals(ClockMediaLabels.EMPTY, lines.subtitle)
    }
}
