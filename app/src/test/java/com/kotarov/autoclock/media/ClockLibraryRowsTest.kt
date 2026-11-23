package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.ForecastStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockLibraryRowsTest {
    private val zone = ZoneId.of("Europe/Sofia")
    // 23 Nov 2026 at 23:40 Sofia time: crossing midnight is intentional.
    private val now = LocalDateTime.of(2026, 11, 23, 23, 40)
        .atZone(zone).toEpochSecond()
    private val first = ((now / 3600L) + 1L) * 3600L
    private val hours = (0..7).map { offset ->
        val epoch = first + offset * 3600L
        ForecastStore.Hour(
            epochSeconds = epoch,
            localDateTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(epoch), zone),
            code = if (offset == 0) 61 else 2,
            temperatureC = 9.5 + offset,
            precipitationPercent = 20 + offset
        )
    }

    @Test fun sixNextHoursAreVisibleAcrossMidnight() {
        val rows = ClockLibraryRows.create(hours, now)
        assertEquals(6, rows.size)
        assertEquals(6, rows.map { it.id }.toSet().size)
        assertEquals("00:00 · 10°C", rows[0].title)
        assertEquals("05:00 · 15°C", rows[5].title)
        assertEquals("Дъжд · Валежи 20%", rows[0].subtitle)
        assertFalse(rows[0].title.contains("☂"))
        assertTrue(rows.all { it.forecast != null })
        assertEquals(first, rows[0].epochSeconds)
    }

    @Test fun advancingTimeShiftsNextSixHours() {
        val later = ClockLibraryRows.create(hours, now + 3600L)
        assertEquals("01:00 · 11°C", later[0].title)
    }

    @Test fun missingDataNeverInventsWeather() {
        val rows = ClockLibraryRows.create(emptyList(), now)
        assertEquals(6, rows.size)
        assertEquals("00:00 · Няма прогноза", rows[0].title)
        assertTrue(rows.all { it.forecast == null && it.subtitle.contains("заредени") })
    }

    @Test fun knownIdsAreTheSixHoursOnly() {
        val rows = ClockLibraryRows.create(hours, now)
        rows.forEach { assertTrue(ClockLibraryRows.isKnownId(it.id)) }
        assertFalse(ClockLibraryRows.isKnownId("autoclock_forecast_hour_7"))
        assertFalse(ClockLibraryRows.isKnownId("autoclock_forecast_day_1"))
        assertFalse(ClockLibraryRows.isKnownId(ClockLibraryRows.CLOCK_ID))
        assertFalse(ClockLibraryRows.isKnownId(null))
    }

    @Test fun nextSixHoursExcludeNowAndEarlier() {
        assertEquals(hours.take(6), ForecastStore.nextSixHours(hours, now))
        assertEquals(hours.drop(1).take(6),
            ForecastStore.nextSixHours(hours, first))
    }
}
