package com.kotarov.autoclock.media

/** A short visual caption, independent of sound notices and clock artwork. */
internal class NowPlayingTitle {
    private var value: String? = null
    private var until = 0L

    fun show(name: String?, now: Long): Boolean {
        clear()
        value = name?.trim()?.takeIf { it.isNotBlank() }
        if (value == null) return false
        until = now + DURATION_MS
        return true
    }

    fun text(now: Long): String? = value?.takeIf { now < until }
    fun clear() { value = null; until = 0L }

    companion object { const val DURATION_MS = 8_000L }
}
