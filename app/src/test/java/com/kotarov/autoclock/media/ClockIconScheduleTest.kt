package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

class ClockIconScheduleTest {
    @Test fun sameIconWithinOneSevenMinuteWindow() {
        val first = LocalDateTime.of(2026, 11, 23, 19, 35)
        assertEquals(ClockIconSchedule.bucket(first), ClockIconSchedule.bucket(first.plusMinutes(6)))
        assertEquals(first, ClockIconSchedule.slotStart(first.plusMinutes(6)))
    }

    @Test fun iconChangesAtSevenMinuteBoundary() {
        val a = LocalDateTime.of(2026, 11, 23, 19, 35)
        val b = LocalDateTime.of(2026, 11, 23, 19, 42)
        assertNotEquals(ClockIconSchedule.bucket(a), ClockIconSchedule.bucket(b))
        assertEquals(a, ClockIconSchedule.slotStart(a))
        assertEquals(b, ClockIconSchedule.slotStart(b))
    }

    @Test fun iconWindowAcrossHourOrDateIsStable() {
        val endOfDay = LocalDateTime.of(2026, 11, 23, 23, 59)
        assertEquals(LocalDateTime.of(2026, 11, 23, 23, 55),
            ClockIconSchedule.slotStart(endOfDay))
        assertNotEquals(ClockIconSchedule.bucket(endOfDay),
            ClockIconSchedule.bucket(endOfDay.plusMinutes(1)))
        assertEquals(LocalDateTime.of(2026, 11, 24, 0, 0),
            ClockIconSchedule.slotStart(endOfDay.plusMinutes(1)))
    }
}
