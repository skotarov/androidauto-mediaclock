package com.kotarov.autoclock.media

/**
 * A virtual 60-second media item aligned to the phone's wall clock.
 * The media controller can advance this progress locally at 1x speed.
 * No per-second wakeups or fake audio samples are needed.
 */
object MinuteProgress {
    const val DURATION_MS = 60_000L

    fun positionMs(epochMillis: Long): Long =
        Math.floorMod(epochMillis, DURATION_MS)

    fun millisUntilNextMinute(epochMillis: Long): Long =
        DURATION_MS - positionMs(epochMillis) + 150L
}
