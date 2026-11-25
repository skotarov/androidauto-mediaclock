package com.kotarov.autoclock.media

/** Notices own the second title. Elapsed playback is only its fallback. */
internal object MediaPositionSubtitle {
    fun text(notice: String?, positionMs: Int?): String? {
        if (!notice.isNullOrBlank()) return notice
        val seconds = (positionMs?.takeIf { it >= 0 } ?: return null) / 1000
        val hours = seconds / 3600
        val minutes = (seconds / 60) % 60
        val remainder = seconds % 60
        fun two(value: Int) = value.toString().padStart(2, '0')
        return if (hours > 0) "${hours}:${two(minutes)}:${two(remainder)}"
            else "${two(minutes)}:${two(remainder)}"
    }
}
