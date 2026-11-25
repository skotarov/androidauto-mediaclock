package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class BookTimelineTest {
    @Test fun timelineTracksTextFraction() {
        val t = TextReading.timeline(600, 1500)!!
        assertEquals(40000L, t.positionMs)
        assertEquals(100000L, t.durationMs)
        assertEquals(600, TextReading.offsetAtTime(t.positionMs, 1500))
    }
    @Test fun veryLongBooksAndBounds() {
        val t = TextReading.timeline(50000000, 50000000)!!
        assertTrue(t.durationMs > Int.MAX_VALUE.toLong())
        assertEquals(50000000, TextReading.offsetAtTime(t.durationMs, 50000000))
        assertEquals(0, TextReading.offsetAtTime(-1, 50000000))
        assertEquals(50000000, TextReading.offsetAtTime(Long.MAX_VALUE, 50000000))
        assertNull(TextReading.timeline(0, 0))
    }
}
