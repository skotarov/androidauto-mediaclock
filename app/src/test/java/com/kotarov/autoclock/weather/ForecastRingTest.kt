package com.kotarov.autoclock.weather

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ForecastRingTest {
    private val zone = ZoneId.of("Europe/Sofia")
    private val start = LocalDateTime.of(2026, 11, 24, 8, 0).atZone(zone).toEpochSecond()
    private val hours = (-1..8).map { i ->
        val epoch = start + i * 3600L
        ForecastStore.Hour(epoch, LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone),
            2, 9.0 + i, null)
    }

    @Test fun currentPlusSixAt0848() {
        assertEquals((8..14).toList(), ForecastStore.ringHours(hours, start + 48 * 60)
            .map { it.localDateTime.hour })
    }

    @Test fun shiftsAt0900() {
        assertEquals((9..15).toList(), ForecastStore.ringHours(hours, start + 3600)
            .map { it.localDateTime.hour })
    }

    @Test fun missingHourIsNotReplacedByAnEighthSlot() {
        val missing = hours.filter { it.localDateTime.hour != 10 }
        assertEquals(listOf(8, 9, 11, 12, 13, 14), ForecastStore.ringHours(missing, start + 60)
            .map { it.localDateTime.hour })
    }

    @Test fun pauseListRemainsSixFutureHours() {
        assertEquals((9..14).toList(), ForecastStore.nextSixHours(hours, start + 60)
            .map { it.localDateTime.hour })
    }
}
