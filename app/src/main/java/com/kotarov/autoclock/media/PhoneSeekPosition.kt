package com.kotarov.autoclock.media

import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object PhoneSeekPosition {
    const val STEPS = 10000
    fun position(progress: Int, duration: Long): Long =
        if (duration <= 0L) 0L else (duration.toDouble() * progress.coerceIn(0, STEPS) / STEPS).roundToLong().coerceIn(0L, duration)

    fun progress(position: Long, duration: Long): Int =
        if (duration <= 0L) 0 else (position.coerceIn(0L, duration).toDouble() / duration * STEPS).roundToInt()

    fun current(position: Long, duration: Long, updated: Long, now: Long, speed: Float): Long {
        if (duration <= 0L) return 0L
        val elapsed = if (updated > 0L && now > updated && speed.isFinite() && speed > 0f) now - updated else 0L
        return (position.toDouble() + elapsed.toDouble() * (if (elapsed > 0) speed else 0f)).roundToLong().coerceIn(0L, duration)
    }

    fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        val h = total / 3600
        val m = (total / 60) % 60
        val s = (total % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
    }
}
