package com.kotarov.autoclock.media

import android.support.v4.media.session.PlaybackStateCompat

/** Stable playback states; removed screen experiments cannot override them. */
object ClockPlaybackState {
    fun forRunning(running: Boolean): Int =
        if (running) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
}
