package com.kotarov.autoclock.weather

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ForecastRingTest {
    private val zone = ZoneId.of("Europe/Sofia")
    private val start = LocalDateTime.of(2026, 11, 24, 8, 0).atZone(zone).toEpochSecond()
    private val hours = (-1..11).map { i ->
        val epoch = start + i * 3600L
        ForecastStore.Hour(epoch, LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone),
            2, 9.0 + i, null)
    }

    @Test fun currentPlusNineAt0848() {
        assertEquals((8..17).toList(), ForecastStore.ringHours(hours, start + 48 * 60)
            .map { it.localDateTime.hour })
    }

    @Test fun shiftsAt0900() {
        assertEquals((9..18).toList(), ForecastStore.ringHours(hours, start + 3600)
            .map { it.localDateTime.hour })
    }

    @Test fun missingHourIsNotReplacedByAnEleventhSlot() {
        val missing = hours.filter { it.localDateTime.hour != 10 }
        assertEquals(listOf(8, 9, 11, 12, 13, 14, 15, 16, 17), ForecastStore.ringHours(missing, start + 60)
            .map { it.localDateTime.hour })
    }

    private val observation = WeatherStore.Snapshot(
        temperatureC = 0.0, weatherCode = 0, observedAtMs = start * 1000,
        isDay = true, originLabel = "Sofia", sourceLabel = "NOAA METAR",
        reportTimeMs = start * 1000, autoSelected = true
    )

    @Test fun actualObservationOverridesModelOnlyInCurrentSlot() {
        val ring = ForecastStore.ringWithObservation(hours, start + 48 * 60, observation, zone)
        assertEquals(10, ring.size)
        assertEquals(0, ring.first().code)
        assertEquals("0°C", ring.first().temperatureText)
        assertEquals(8, ring.first().localDateTime.hour)
        assertEquals(2, ring[1].code)
        assertEquals(10.0, ring[1].temperatureC, 0.0)
    }

    @Test fun observedSunReplacesCloudAtCurrentHourEvenAfterHourChange() {
        val ring = ForecastStore.ringWithObservation(hours, start + 3600 + 60, observation, zone)
        assertEquals(9, ring.first().localDateTime.hour)
        assertEquals(0, ring.first().code)
        assertEquals(0.0, ring.first().temperatureC, 0.0)
        assertEquals(1, ring.count { it.localDateTime.hour == 9 })
        assertEquals(10, ring[1].localDateTime.hour)
        assertEquals(2, ring[1].code)
    }

    @Test fun observationWorksEvenWithoutForecast() {
        val ring = ForecastStore.ringWithObservation(emptyList(), start + 60, observation, zone)
        assertEquals(1, ring.size)
        assertEquals(8, ring.single().localDateTime.hour)
    }

    @Test fun absentObservationKeepsModel() {
        assertEquals(ForecastStore.ringHours(hours, start + 60),
            ForecastStore.ringWithObservation(hours, start + 60, null, zone))
    }

    @Test fun pauseListRemainsSixFutureHours() {
        assertEquals((9..14).toList(), ForecastStore.nextSixHours(hours, start + 60)
            .map { it.localDateTime.hour })
    }
}
