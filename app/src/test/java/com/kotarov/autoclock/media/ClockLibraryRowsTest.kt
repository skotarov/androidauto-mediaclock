package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockLibraryRowsTest {
    private val time = LocalDateTime.of(2026, 11, 23, 13, 48)
    private val observation = WeatherStore.Snapshot(
        temperatureC = 10.9, weatherCode = 3, observedAtMs = 100L,
        isDay = true, originLabel = "Станция София (LBSF)",
        sourceLabel = "NOAA · измерване METAR", reportTimeMs = 100L,
        autoSelected = true, pressureHpa = 1014.4, elevationMeters = 548.6
    )

    @Test fun exactlyThreeDistinctItemsWithFormattedWeatherAndElevation() {
        val rows = ClockLibraryRows.create(time, ClockPreset.ANALOG_PURE, observation)
        assertEquals(3, rows.size)
        assertEquals(3, rows.map { it.id }.toSet().size)
        assertEquals("13:48", rows[0].title)
        assertEquals("Аналогов часовник", rows[0].subtitle)
        assertTrue(rows[1].title.startsWith("☁ София · 11°C"))
        assertFalse(rows[1].title.contains("Станция"))
        assertEquals("Налягане: 1014 hPa", rows[2].title)
        assertEquals("Надм. височина (станция): 549 м", rows[2].subtitle)
        assertEquals(listOf(
            ClockLibraryRows.Icon.CLOCK, ClockLibraryRows.Icon.WEATHER,
            ClockLibraryRows.Icon.METRICS
        ), rows.map { it.icon })
    }

    @Test fun oneMinuteLaterClockTextChangesWithoutRefetchingWeather() {
        val later = ClockLibraryRows.create(time.plusMinutes(1),
            ClockPreset.DIGITAL_TIME, observation)
        assertEquals("13:49", later[0].title)
        assertEquals("Цифров часовник", later[0].subtitle)
        assertEquals("Налягане: 1014 hPa", later[2].title)
    }

    @Test fun missingDataIsNeverInvented() {
        val rows = ClockLibraryRows.create(time, ClockPreset.ANALOG_PURE, null)
        assertTrue(rows[1].title.contains("няма данни"))
        assertEquals("Налягане: няма данни", rows[2].title)
        assertEquals("Надм. височина (терен): няма данни", rows[2].subtitle)
    }

    @Test fun knownIdsMatchOnlyThreeRows() {
        ClockLibraryRows.create(time, ClockPreset.ANALOG_PURE, observation)
            .forEach { assertTrue(ClockLibraryRows.isKnownId(it.id)) }
        assertFalse(ClockLibraryRows.isKnownId("family_analog"))
        assertFalse(ClockLibraryRows.isKnownId(null))
    }
}
