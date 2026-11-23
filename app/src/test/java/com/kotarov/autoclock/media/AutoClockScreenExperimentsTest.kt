package com.kotarov.autoclock.media

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoClockScreenExperimentsTest {
    @Test fun unchangedDefaultRunningState() {
        assertEquals(PlaybackStateCompat.STATE_PLAYING,
            AutoClockScreenExperiments.playbackState(true, false))
    }

    @Test fun unchangedDefaultStoppedState() {
        assertEquals(PlaybackStateCompat.STATE_PAUSED,
            AutoClockScreenExperiments.playbackState(false, false))
    }

    @Test fun testStateNoneOverridesBothRunningStates() {
        assertEquals(PlaybackStateCompat.STATE_NONE,
            AutoClockScreenExperiments.playbackState(true, true))
        assertEquals(PlaybackStateCompat.STATE_NONE,
            AutoClockScreenExperiments.playbackState(false, true))
    }

    @Test fun testStateStoppedOverridesBothRunningStates() {
        assertEquals(PlaybackStateCompat.STATE_STOPPED,
            AutoClockScreenExperiments.playbackState(true, false, true))
        assertEquals(PlaybackStateCompat.STATE_STOPPED,
            AutoClockScreenExperiments.playbackState(false, false, true))
    }

    @Test fun stateNoneTakesPrecedenceWhenBothExperimentsEnabled() {
        assertEquals(PlaybackStateCompat.STATE_NONE,
            AutoClockScreenExperiments.playbackState(true, true, true))
    }

    @Test fun browseTestUsesDocumentedIntentAndRoot() {
        assertEquals("auto_clock_root", AutoClockMediaService.ROOT_ID)
        assertEquals("androidx.car.app.mediaextensions.action.MEDIA_TEMPLATE_V2",
            AutoClockBrowseHostLaunch.ACTION_MEDIA_TEMPLATE_V2)
        assertEquals("android.car.intent.extra.MEDIA_COMPONENT",
            AutoClockBrowseHostLaunch.EXTRA_KEY_MEDIA_COMPONENT)
    }
}
