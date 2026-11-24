package com.kotarov.autoclock.media

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockPlaybackStateTest {
    @Test fun runningUsesPlayingState() {
        assertEquals(PlaybackStateCompat.STATE_PLAYING, ClockPlaybackState.forRunning(true))
    }

    @Test fun pausedUsesPausedState() {
        assertEquals(PlaybackStateCompat.STATE_PAUSED, ClockPlaybackState.forRunning(false))
    }
}
