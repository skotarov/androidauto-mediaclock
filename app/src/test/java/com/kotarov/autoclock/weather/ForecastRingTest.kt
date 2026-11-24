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

    @Test fun previousCurrentPlusNineAt0848() {
        assertEquals((7..17).toList(), ForecastStore.ringHours(hours, start + 48 * 60)
            .map { it.localDateTime.hour })
    }

    @Test fun shiftsAt0900() {
        assertEquals((8..18).toList(), ForecastStore.ringHours(hours, start + 3600)
            .map { it.localDateTime.hour })
    }

    @Test fun missingHourIsNotReplacedByATwelfthSlot() {
        val missing = hours.filter { it.localDateTime.hour != 10 }
        assertEquals(listOf(7, 8, 9, 11, 12, 13, 14, 15, 16, 17), ForecastStore.ringHours(missing, start + 60)
            .map { it.localDateTime.hour })
    }

    private val observation = WeatherStore.Snapshot(
        temperatureC = 0.0, weatherCode = 0, observedAtMs = start * 1000,
        isDay = true, originLabel = "Sofia", sourceLabel = "NOAA METAR",
        reportTimeMs = start * 1000, autoSelected = true
    )

    @Test fun actualObservationOverridesModelOnlyInCurrentSlot() {
        val ring = ForecastStore.ringWithObservation(hours, start + 48 * 60, observation, zone)
        assertEquals(11, ring.size)
        assertEquals(7, ring.first().localDateTime.hour)
        assertEquals(2, ring.first().code)
        assertEquals(0, ring[1].code)
        assertEquals("0°C", ring[1].temperatureText)
        assertEquals(8, ring[1].localDateTime.hour)
        assertEquals(2, ring[2].code)
        assertEquals(10.0, ring[2].temperatureC, 0.0)
    }

    @Test fun observedSunReplacesCloudAtCurrentHourEvenAfterHourChange() {
        val ring = ForecastStore.ringWithObservation(hours, start + 3600 + 60, observation, zone)
        assertEquals(8, ring.first().localDateTime.hour)
        assertEquals(9, ring[1].localDateTime.hour)
        assertEquals(0, ring[1].code)
        assertEquals(0.0, ring[1].temperatureC, 0.0)
        assertEquals(1, ring.count { it.localDateTime.hour == 9 })
        assertEquals(10, ring[2].localDateTime.hour)
        assertEquals(2, ring[2].code)
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

    @Test fun opacityIsRelativeToCurrentHourNotListIndex() {
        assertEquals(115, ForecastStore.ringAlpha(-1))
        (0..3).forEach { assertEquals(255, ForecastStore.ringAlpha(it)) }
        (4..8).forEach { assertEquals(115, ForecastStore.ringAlpha(it)) }
        assertEquals(51, ForecastStore.ringAlpha(9))
    }

    @Test fun midnightIncludesPreviousDayAt23() {
        val midnight = LocalDateTime.of(2026, 11, 24, 0, 0).atZone(zone).toEpochSecond()
        val data = (-1..10).map { i ->
            val epoch = midnight + i * 3600L
            ForecastStore.Hour(epoch, LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone),
                2, 9.0, null)
        }
        val ring = ForecastStore.ringHours(data, midnight + 60, zone)
        assertEquals(listOf(23, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9), ring.map { it.localDateTime.hour })
    }

    @Test fun repeatedGlyphsAreHiddenButChangesAndCurrentRemain() {
        val data = hours.filter { it.epochSeconds <= start + 9 * 3600L }.map { h ->
            val offset = ((h.epochSeconds - start) / 3600L).toInt()
            h.copy(code = when (offset) {
                -1, 0 -> 1
                1, 2 -> 2
                3 -> 61
                4 -> 63
                5, 6 -> 3
                else -> 0
            }, isDay = offset < 8)
        }
        val offsets = ForecastStore.ringIconEpochs(data, start).sorted()
            .map { ((it - start) / 3600L).toInt() }
        assertEquals(listOf(-1, 0, 3, 5, 7, 8), offsets)
    }

    @Test fun overcastDoesNotChangeAtNightAndMissingDataRestartsIcons() {
        val data = hours.filter { it.epochSeconds != start + 2 * 3600L }
            .map { it.copy(code = 3, isDay = it.epochSeconds <= start) }
        assertEquals(setOf(start - 3600L, start, start + 3 * 3600L),
            ForecastStore.ringIconEpochs(data, start))
    }

    @Test fun pauseListRemainsSixFutureHours() {
        assertEquals((9..14).toList(), ForecastStore.nextSixHours(hours, start + 60)
            .map { it.localDateTime.hour })
    }
}
