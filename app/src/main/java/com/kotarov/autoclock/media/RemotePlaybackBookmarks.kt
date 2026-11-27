package com.kotarov.autoclock.media

import android.content.Context

/** Persistent, independent bookmarks for finite network audio; no live radio offsets. */
internal class RemotePlaybackBookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("remote_audio_bookmarks_v143", Context.MODE_PRIVATE)
    private val library = context.getSharedPreferences(AutoClockMediaService.PREFERENCES, Context.MODE_PRIVATE)

    fun read(key: String?): CustomStreamPlayer.SeekState? =
        key?.let { decode(prefs.getLong(it, -1L)) }

    fun save(key: String?, state: CustomStreamPlayer.SeekState) {
        if (key == null || !PlaybackTimeline.known(state.position, state.duration)) return
        // A player can finish releasing after the user deletes its source.
        // Never allow that last checkpoint to recreate a deleted bookmark.
        val root = key.substringBefore('|')
        if (StreamLibrary.list(library).none { it.id == root }) return
        val encoded = encode(state)
        if (prefs.getLong(key, -1L) != encoded) prefs.edit().putLong(key, encoded).apply()
    }

    fun removeRoot(root: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(root + "|") }.forEach { edit.remove(it) }
        edit.apply()
    }

    companion object {
        fun key(root: String, id: String, url: String): String = "$root|$id|$url"
        fun encode(state: CustomStreamPlayer.SeekState): Long =
            (state.duration.toLong() shl 32) or (state.position.toLong() and 0xffffffffL)
        fun decode(value: Long): CustomStreamPlayer.SeekState? {
            val position = value.toInt()
            val duration = (value ushr 32).toInt()
            return if (PlaybackTimeline.known(position, duration)) CustomStreamPlayer.SeekState(position, duration) else null
        }
        fun resumePosition(state: CustomStreamPlayer.SeekState?, duration: Int): Int =
            state?.position?.takeIf { it > 0 && it < duration && it < state.duration } ?: 0
    }
}
