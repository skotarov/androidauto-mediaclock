package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class SpeechRangeMailboxTest {
    @Test fun coalescesEventsAndPreservesExactLatestPosition() {
        val box = SpeechRangeMailbox()
        var scheduled = 0
        box.begin("first")
        repeat(100) { box.offer("first", it) { scheduled++ } }
        assertEquals(1, scheduled)
        assertEquals(99, box.latest())
        assertEquals(99, box.drain())
        box.offer("first", 101) { scheduled++ }
        assertEquals(2, scheduled)
        assertEquals(101, box.end())
        box.offer("first", 102) { scheduled++ }
        assertNull(box.latest())
        box.begin("second")
        box.offer("first", 200) { scheduled++ }
        box.offer("second", 3) { scheduled++ }
        assertEquals(3, scheduled)
        assertEquals(3, box.end())
    }
}
