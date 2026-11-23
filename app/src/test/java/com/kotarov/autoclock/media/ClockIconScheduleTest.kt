package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ClockIconScheduleTest {
    @Test fun iconIsStableDuringTheSameMinute() {
        val first = LocalDateTime.of(2026, 11, 23, 19, 29)
        val later = first.plusSeconds(45)
        assertEquals(ClockIconSchedule.bucket(first), ClockIconSchedule.bucket(later))
        assertEquals(first, ClockIconSchedule.slotStart(later))
    }

    @Test fun iconChangesEveryMinute() {
        val a = LocalDateTime.of(2026, 11, 23, 19, 29)
        val b = a.plusMinutes(1)
        assertNotEquals(ClockIconSchedule.bucket(a), ClockIconSchedule.bucket(b))
        assertEquals(a, ClockIconSchedule.slotStart(a))
        assertEquals(b, ClockIconSchedule.slotStart(b))
    }

    @Test fun iconWindowAcrossDayBoundaryChanges() {
        val endOfDay = LocalDateTime.of(2026, 11, 23, 23, 59)
        assertEquals(endOfDay, ClockIconSchedule.slotStart(endOfDay))
        assertNotEquals(ClockIconSchedule.bucket(endOfDay),
            ClockIconSchedule.bucket(endOfDay.plusMinutes(1)))
        assertEquals(LocalDateTime.of(2026, 11, 24, 0, 0),
            ClockIconSchedule.slotStart(endOfDay.plusMinutes(1)))
    }

    @Test fun diagnosticColorsAlternateAcrossMinuteAndMidnight() {
        assertTrue(ClockIconSchedule.isRed(LocalDateTime.of(2026, 11, 23, 19, 28)))
        assertFalse(ClockIconSchedule.isRed(LocalDateTime.of(2026, 11, 23, 19, 29)))
        assertTrue(ClockIconSchedule.isRed(LocalDateTime.of(2026, 11, 23, 19, 30)))
        assertFalse(ClockIconSchedule.isRed(LocalDateTime.of(2026, 11, 23, 23, 59)))
        assertTrue(ClockIconSchedule.isRed(LocalDateTime.of(2026, 11, 24, 0, 0)))
    }
}
