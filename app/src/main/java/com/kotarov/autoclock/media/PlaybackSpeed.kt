package com.kotarov.autoclock.media

import android.content.SharedPreferences
import kotlin.math.roundToInt

internal object PlaybackSpeed {
    const val KEY = "playback_speed_v105"
    const val MIN = 0.5f
    const val MAX = 2f
    fun normalize(value: Float): Float =
        if (!value.isFinite()) 1f else (value.coerceIn(MIN, MAX) * 10).roundToInt() / 10f
    fun step(value: Float, direction: Int): Float =
        normalize(normalize(value) + direction.coerceIn(-1, 1) * 0.1f)
    fun read(prefs: SharedPreferences): Float = normalize(prefs.getFloat(KEY, 1f))
}
