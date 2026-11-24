package com.kotarov.autoclock.media

/** Temporary two-line media messages. Use monotonic elapsed time;
 * ordinary locality updates do not repeat. */
class CityAnnouncement {
    private var lastCity: String? = null
    private var until = 0L
    data class Notice(val heading: String, val body: String)
    private var message: Notice? = null

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
        message = Notice("Информация",
            city?.trim()?.takeIf { it.isNotBlank() } ?: "Няма данни за локацията")
        until = now + DURATION_MS
    }

    fun showWarning(warning: String, now: Long) {
        message = Notice("Внимание", warning)
        until = now + DURATION_MS
    }

    fun notice(now: Long): Notice? = if (now < until) message else null

    fun title(now: Long): String? = notice(now)?.body

    companion object {
        const val DURATION_MS = 8000L
    }
}
