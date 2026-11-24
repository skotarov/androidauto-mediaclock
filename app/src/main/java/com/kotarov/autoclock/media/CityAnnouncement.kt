package com.kotarov.autoclock.media

/** A real locality change temporarily replaces the hidden media title.
 * Use monotonic elapsed time, not wall-clock time. No repeated alerts. */
class CityAnnouncement {
    private var lastCity: String? = null
    private var until = 0L
    private var message: String? = null

    fun update(city: String?, now: Long): Boolean {
        val next = city?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val previous = lastCity
        if (next.equals(previous, ignoreCase = true)) return false
        lastCity = next
        if (previous == null) return false
        show(next, now)
        return true
    }

    /** Manual request repeats the current city, without changing playback. */
    fun show(city: String?, now: Long) {
        message = city?.trim()?.takeIf { it.isNotBlank() } ?: "Няма данни за локацията"
        until = now + DURATION_MS
    }

    fun title(now: Long): String? = if (now < until) message else null

    companion object {
        const val DURATION_MS = 8000L
    }
}
