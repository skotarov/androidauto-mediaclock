package com.kotarov.autoclock.media

import android.content.SharedPreferences
import kotlin.math.roundToInt

internal object PlaybackSpeed {
    private const val LEGACY_KEY = "playback_speed_v105"
    private const val PREFIX = "playback_speed_source_v109:"
    const val MIN = 0.5f
    const val MAX = 2f
    fun normalize(value: Float): Float =
        if (!value.isFinite()) 1f else (value.coerceIn(MIN, MAX) * 10).roundToInt() / 10f
    fun step(value: Float, direction: Int): Float =
        normalize(normalize(value) + direction.coerceIn(-1, 1) * 0.1f)
    // A source URI is stable across renames, reordering and duplicate library entries.
    internal fun sourceKey(url: String): String = PREFIX + url
    fun isSourceKey(key: String?): Boolean = key?.startsWith(PREFIX) == true
    fun read(prefs: SharedPreferences, source: StreamLibrary.Station?): Float =
        source?.let { normalize(prefs.getFloat(sourceKey(it.url), 1f)) } ?: 1f
    fun write(prefs: SharedPreferences, source: StreamLibrary.Station, value: Float) {
        val key = sourceKey(source.url)
        val speed = normalize(value)
        if (speed == 1f) prefs.edit().remove(key).apply()
        else prefs.edit().putFloat(key, speed).apply()
    }

    // Preserve the old shared setting only for the currently selected item.
    // Other items start at normal speed rather than inheriting another file's setting.
    fun migrate(prefs: SharedPreferences) {
        if (!prefs.contains(LEGACY_KEY)) return
        val selected = StreamLibrary.selected(prefs)
        val old = normalize(prefs.getFloat(LEGACY_KEY, 1f))
        val edit = prefs.edit().remove(LEGACY_KEY)
        if (selected != null && old != 1f && !prefs.contains(sourceKey(selected.url)))
            edit.putFloat(sourceKey(selected.url), old)
        edit.apply()
    }
}
