package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockLibraryRowsTest {
    private val today = LocalDate.of(2026, 11, 23)
    private val days = (0..3).map { offset ->
        ForecastStore.Day(
            today.plusDays(offset.toLong()),
            code = if (offset == 1) 61 else 2,
            maxC = 10.5 + offset,
            minC = 2.5 + offset,
            precipitationPercent = 20 + offset
        )
    }

    @Test fun threeRowsReferToTomorrowAndNextTwoDays() {
        val rows = ClockLibraryRows.create(days, today)
        assertEquals(3, rows.size)
        assertEquals(listOf(today.plusDays(1), today.plusDays(2), today.plusDays(3)),
            rows.map { it.date })
        assertEquals(3, rows.map { it.id }.toSet().size)
        assertTrue(rows[0].title.contains("↑12°"))
        assertTrue(rows[0].title.contains("↓4°"))
        assertTrue(rows[0].subtitle.contains("Валежи до 21%"))
    }

    @Test fun missingForecastNeverInventsTemperatures() {
        val rows = ClockLibraryRows.create(emptyList(), today)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.forecast == null && it.title.contains("Няма прогноза") })
    }

    @Test fun idMatchingIsLimitedToThreeDays() {
        ClockLibraryRows.create(days, today).forEach {
            assertTrue(ClockLibraryRows.isKnownId(it.id))
        }
        assertFalse(ClockLibraryRows.isKnownId("autoclock_metrics"))
        assertFalse(ClockLibraryRows.isKnownId(ClockLibraryRows.CLOCK_ID))
        assertFalse(ClockLibraryRows.isKnownId(null))
    }
}
