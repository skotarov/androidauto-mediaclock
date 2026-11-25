package com.kotarov.autoclock.media

/** Temporary media messages. Use monotonic elapsed time;
 * ordinary locality updates do not repeat. */
class CityAnnouncement {
    private var lastCity: String? = null
    private var until = 0L
    data class Notice(val heading: String, val body: String) {
        val subtitle: String get() = "$heading $body"
    }
    private var message: Notice? = null

    fun update(city: String?, now: Long): Boolean {
        val next = city?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val previous = lastCity
        if (next.equals(previous, ignoreCase = true)) return false
        lastCity = next
        if (previous == null) return false
        message = Notice("📍", next)
        until = now + DURATION_MS
        return true
    }

    /** Manual request repeats the current city, without changing playback. */
    fun show(city: String?, now: Long) {
        message = Notice("ℹ️",
            city?.trim()?.takeIf { it.isNotBlank() } ?: "Location unavailable")
        until = now + DURATION_MS
    }

    fun showWarning(warning: String, now: Long) {
        message = Notice(warningIcon(warning), warning)
        until = now + DURATION_MS
    }

    fun notice(now: Long): Notice? = if (now < until) message else null

    fun title(now: Long): String? = notice(now)?.body

    companion object {
        /** Prefix the complete warning subtitle with its symbol. */
        internal fun warningIcon(warning: String): String =
            when (warning.removePrefix("Within 1h: ").trim()) {
                "Thunderstorm" -> "⛈️"
                "Heavy snow" -> "❄️"
                "Heavy rain" -> "🌧️"
                "Fog" -> "🌫️"
                "Risk of ice" -> "⚠️"
                else -> if (warning.startsWith("Precipitation:")) "🌧️" else "⚠️"
            }

        const val DURATION_MS = 15000L
    }
}
