package com.kotarov.autoclock.media

import java.time.LocalDateTime

/**
 * A new browse/display icon image is eligible every minute while Play runs.
 * Alternating red and green makes Android Auto's image caching easy to test.
 */
object ClockIconSchedule {
    private const val INTERVAL_MINUTES = 1L
    private const val BUCKETS_PER_DAY = 1440L

    fun isRed(time: LocalDateTime): Boolean = time.minute % 2 == 0

    fun bucket(time: LocalDateTime): Long =
        time.toLocalDate().toEpochDay() * BUCKETS_PER_DAY +
            (time.hour * 60L + time.minute) / INTERVAL_MINUTES

    fun slotStart(time: LocalDateTime): LocalDateTime {
        val minuteOfDay = time.hour * 60L + time.minute
        val slotMinute = (minuteOfDay / INTERVAL_MINUTES * INTERVAL_MINUTES).toInt()
        return time.toLocalDate().atStartOfDay()
            .plusMinutes(slotMinute.toLong())
    }
}
