package com.kotarov.autoclock.media

/** Notices own the second title. Elapsed playback is only its fallback. */
internal object MediaPositionSubtitle {
    fun text(notice: String?, positionMs: Int?): String? {
        if (!notice.isNullOrBlank()) return notice
        return format(positionMs?.takeIf { it >= 0 }?.toLong() ?: return null)
    }

    fun book(positionMs: Long, durationMs: Long): String? {
        if (durationMs <= 0 || positionMs < 0) return null
        val percent = (positionMs.coerceAtMost(durationMs) * 100 / durationMs).coerceIn(0, 100)
        return "~${format(positionMs)} · $percent%"
    }

    private fun format(positionMs: Long): String {
        val seconds = positionMs / 1000
        val hours = seconds / 3600
        val minutes = (seconds / 60) % 60
        val remainder = seconds % 60
        fun two(value: Long) = value.toString().padStart(2, '0')
        return if (hours > 0) "${hours}:${two(minutes)}:${two(remainder)}"
            else "${two(minutes)}:${two(remainder)}"
    }
}
