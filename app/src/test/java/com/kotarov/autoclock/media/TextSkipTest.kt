package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSkipTest {
    @Test fun tenSecondStepsAndBounds() {
        assertEquals(650, TextReading.skipOffset(500, 1000, 10000))
        assertEquals(350, TextReading.skipOffset(500, 1000, -10000))
        assertEquals(0, TextReading.skipOffset(50, 1000, -10000))
        assertEquals(1000, TextReading.skipOffset(950, 1000, 10000))
        assertEquals(0, TextReading.skipOffset(0, 0, 10000))
    }
    @Test fun alignAtWordStart() {
        assertEquals(6, TextReading.wordStart("Здрав свят", 8))
        assertEquals(6, TextReading.wordStart("Здрав свят", 6))
        assertEquals(0, TextReading.wordStart("Здрав свят", 2))
    }
}
