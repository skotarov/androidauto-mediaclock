package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class PerSourceSpeedTest {
    @Test fun fileKeysAreIndependentAndRecognizable() {
        val first = PlaybackSpeed.sourceKey("content://books/one")
        val second = PlaybackSpeed.sourceKey("content://books/two")
        assertNotEquals(first, second)
        assertEquals(first, PlaybackSpeed.sourceKey("content://books/one"))
        assertTrue(PlaybackSpeed.isSourceKey(first))
        assertFalse(PlaybackSpeed.isSourceKey("playback_speed_v105"))
        assertFalse(PlaybackSpeed.isSourceKey(null))
    }
}
