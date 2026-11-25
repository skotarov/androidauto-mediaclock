package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun finiteMediaHasTimeline() {
        assertTrue(PlaybackTimeline.known(0, 10000))
        assertTrue(PlaybackTimeline.known(5000, 10000))
        assertTrue(PlaybackTimeline.known(10000, 10000))
    }
    @Test fun unknownOrInvalidTimelineIsHidden() {
        assertFalse(PlaybackTimeline.known(0, -1))
        assertFalse(PlaybackTimeline.known(0, 0))
        assertFalse(PlaybackTimeline.known(-1, 10000))
        assertFalse(PlaybackTimeline.known(11000, 10000))
    }
}
