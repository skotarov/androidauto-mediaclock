package com.kotarov.autoclock.media

import java.time.LocalDateTime

/**
 * Round the browse-thumbnail clock down to a seven-minute window.
 * Based on local wall-clock minutes, so refreshes align to 00:00, 00:07,
 * 00:14... rather than an arbitrary time after service startup.
 */
object ClockIconSchedule {
    private const val INTERVAL_MINUTES = 7L
    private const val BUCKETS_PER_DAY = 206L // ceil(1440 / 7)

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
