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

    @Test fun currentPlusFiveAt0848() {
        assertEquals((8..13).toList(), ForecastStore.ringHours(hours, start + 48 * 60)
            .map { it.localDateTime.hour })
    }

    @Test fun shiftsAt0900() {
        assertEquals((9..14).toList(), ForecastStore.ringHours(hours, start + 3600)
            .map { it.localDateTime.hour })
    }

    @Test fun missingHourIsNotReplacedByASeventhSlot() {
        val missing = hours.filter { it.localDateTime.hour != 10 }
        assertEquals(listOf(8, 9, 11, 12, 13), ForecastStore.ringHours(missing, start + 60)
            .map { it.localDateTime.hour })
    }

    private val observation = WeatherStore.Snapshot(
        temperatureC = 0.0, weatherCode = 0, observedAtMs = start * 1000,
        isDay = true, originLabel = "Sofia", sourceLabel = "NOAA METAR",
        reportTimeMs = start * 1000, autoSelected = true
    )

    @Test fun actualObservationOverridesModelOnlyInCurrentSlot() {
        val ring = ForecastStore.ringWithObservation(hours, start + 48 * 60, observation, zone)
        assertEquals(6, ring.size)
        assertEquals(8, ring.first().localDateTime.hour)
        assertEquals(0, ring.first().code)
        assertEquals("0°C", ring.first().temperatureText)
        assertEquals(9, ring[1].localDateTime.hour)
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

    @Test fun opacityIsRelativeToCurrentHourNotListIndex() {
        (0..3).forEach { assertEquals(255, ForecastStore.ringAlpha(it)) }
        assertEquals(115, ForecastStore.ringAlpha(4))
        assertEquals(51, ForecastStore.ringAlpha(5))
    }

    @Test fun midnightStartsAtZero() {
        val midnight = LocalDateTime.of(2026, 11, 24, 0, 0).atZone(zone).toEpochSecond()
        val data = (-1..10).map { i ->
            val epoch = midnight + i * 3600L
            ForecastStore.Hour(epoch, LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone),
                2, 9.0, null)
        }
        val ring = ForecastStore.ringHours(data, midnight + 60, zone)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), ring.map { it.localDateTime.hour })
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
        assertEquals(listOf(0, 3, 5), offsets)
    }

    @Test fun overcastDoesNotChangeAtNightAndMissingDataRestartsIcons() {
        val data = hours.filter { it.epochSeconds != start + 2 * 3600L }
            .map { it.copy(code = 3, isDay = it.epochSeconds <= start) }
        assertEquals(setOf(start, start + 3 * 3600L),
            ForecastStore.ringIconEpochs(data, start))
    }

    @Test fun iconAndRoundedTemperatureChangesAreIndependent() {
        val data = hours.filter { it.epochSeconds in (start - 3600L)..(start + 5 * 3600L) }
            .map { h ->
                val offset = ((h.epochSeconds - start) / 3600L).toInt()
                h.copy(code = if (offset < 2) 3 else 61,
                    temperatureC = when (offset) {
                        -1, 0 -> 9.0
                        1, 2 -> 10.0
                        3 -> 10.4
                        else -> 11.0
                    }, isDay = true)
            }
        val changes = ForecastStore.ringChanges(data, start)
        assertEquals(setOf(start, start + 2 * 3600L), changes.iconEpochs)
        assertEquals(setOf(start, start + 3600L, start + 4 * 3600L),
            changes.temperatureEpochs)
    }

    @Test fun aMissingHourRestartsBothIndependentValues() {
        val data = hours.filter { it.epochSeconds != start + 2 * 3600L }
            .map { it.copy(code = 3, temperatureC = 9.0, isDay = true) }
        val changes = ForecastStore.ringChanges(data, start)
        val expected = setOf(start, start + 3 * 3600L)
        assertEquals(expected, changes.iconEpochs)
        assertEquals(expected, changes.temperatureEpochs)
    }

    @Test fun pastAndSeventhSlotNeverDrawEvenWhenDifferent() {
        val data = hours.map { h ->
            h.copy(code = when (h.epochSeconds) {
                start - 3600L -> 0
                start + 6 * 3600L -> 95
                else -> 3
            }, temperatureC = if (h.epochSeconds == start) 9.0 else 10.0, isDay = true)
        }
        val changes = ForecastStore.ringChanges(data, start)
        assertEquals(setOf(start), changes.iconEpochs)
        assertEquals(setOf(start, start + 3600L), changes.temperatureEpochs)
        val past = data.first { it.epochSeconds < start }
        val empty = ForecastStore.ringChanges(listOf(past), start)
        assertEquals(emptySet<Long>(), empty.iconEpochs)
        assertEquals(emptySet<Long>(), empty.temperatureEpochs)
    }

    @Test fun pauseListRemainsSixFutureHours() {
        assertEquals((9..14).toList(), ForecastStore.nextSixHours(hours, start + 60)
            .map { it.localDateTime.hour })
    }
}
