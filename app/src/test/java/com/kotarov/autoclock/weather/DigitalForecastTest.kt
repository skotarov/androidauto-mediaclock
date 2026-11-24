package com.kotarov.autoclock.weather

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigitalForecastTest {
    private val zone = ZoneId.of("Europe/Sofia")
    private val now = LocalDateTime.of(2026, 11, 24, 10, 16)
    private val start = now.withMinute(0).atZone(zone)
    private val hours = (-1..9).map { offset ->
        val slot = start.plusHours(offset.toLong())
        ForecastStore.Hour(slot.toEpochSecond(), slot.toLocalDateTime(),
            if (offset == 0) 0 else 3, offset.toDouble(), null)
    }

    @Test fun sixColumnsBeginAtCurrentHourAndFadeAtEnd() {
        val slots = ForecastStore.digitalSlots(hours, now, zone)
        assertEquals((10..15).toList(), slots.map { it.time.hour })
        assertEquals(listOf(255, 255, 255, 255, 166, 77), slots.map { it.alpha })
        assertEquals(0, slots.first().hour?.code)
    }

    @Test fun missingForecastKeepsItsPosition() {
        val slots = ForecastStore.digitalSlots(hours.filter { it.localDateTime.hour != 12 }, now, zone)
        assertEquals(6, slots.size)
        assertNull(slots[2].hour)
        assertEquals(13, slots[3].hour?.localDateTime?.hour)
    }

    @Test fun nextHourShiftsColumns() {
        assertEquals((11..16).toList(),
            ForecastStore.digitalSlots(hours, now.plusHours(1), zone).map { it.time.hour })
    }

    @Test fun midnightRollsIntoNextDay() {
        val late = LocalDateTime.of(2026, 11, 24, 23, 45)
        val slots = ForecastStore.digitalSlots(emptyList(), late, zone)
        assertEquals(listOf(23, 0, 1, 2, 3, 4), slots.map { it.time.hour })
        assertEquals(25, slots[1].time.dayOfMonth)
    }
}
