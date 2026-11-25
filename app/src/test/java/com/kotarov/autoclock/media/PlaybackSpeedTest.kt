package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun boundsAndSteps() {
        assertEquals(1.1f, PlaybackSpeed.step(1f, 1), 0.001f)
        assertEquals(0.9f, PlaybackSpeed.step(1f, -1), 0.001f)
        assertEquals(2f, PlaybackSpeed.step(2f, 1), 0.001f)
        assertEquals(0.5f, PlaybackSpeed.step(0.5f, -1), 0.001f)
        assertEquals(1f, PlaybackSpeed.normalize(Float.NaN), 0.001f)
    }
    @Test fun bookPositionUsesEstimatedTimeAndPercent() {
        assertEquals("~02:00 · 40%", MediaPositionSubtitle.book(120000, 300000))
        assertEquals("~1:02:03 · 50%", MediaPositionSubtitle.book(3723000, 7446000))
        assertNull(MediaPositionSubtitle.book(0, 0))
    }
}
