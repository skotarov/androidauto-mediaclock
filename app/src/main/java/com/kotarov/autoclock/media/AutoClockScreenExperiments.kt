package com.kotarov.autoclock.media

import android.support.v4.media.session.PlaybackStateCompat

/** Test 2 only: original state unless STATE_NONE is explicitly selected. */
object AutoClockScreenExperiments {
    fun playbackState(running: Boolean, forceNone: Boolean): Int = when {
        forceNone -> PlaybackStateCompat.STATE_NONE
        running -> PlaybackStateCompat.STATE_PLAYING
        else -> PlaybackStateCompat.STATE_PAUSED
    }
}
