package com.kotarov.autoclock.media

import android.support.v4.media.session.PlaybackStateCompat

/** Independent experiments: STATE_STOPPED may preserve host playback UI. */
object AutoClockScreenExperiments {
    fun playbackState(
        running: Boolean,
        forceNone: Boolean,
        forceStopped: Boolean = false
    ): Int = when {
        forceNone -> PlaybackStateCompat.STATE_NONE
        forceStopped -> PlaybackStateCompat.STATE_STOPPED
        running -> PlaybackStateCompat.STATE_PLAYING
        else -> PlaybackStateCompat.STATE_PAUSED
    }
}
