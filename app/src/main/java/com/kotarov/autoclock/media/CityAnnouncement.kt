package com.kotarov.autoclock.media

/** A real locality change temporarily replaces the hidden media title.
 * Use monotonic elapsed time, not wall-clock time. No repeated alerts. */
class CityAnnouncement {
    private var lastCity: String? = null
    private var until = 0L

    fun update(city: String?, now: Long): Boolean {
        val next = city?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val previous = lastCity
        if (next.equals(previous, ignoreCase = true)) return false
        lastCity = next
        if (previous == null) return false
        until = now + DURATION_MS
        return true
    }

    fun title(now: Long): String? = if (now < until) lastCity else null

    companion object {
        const val DURATION_MS = 8000L
    }
}
