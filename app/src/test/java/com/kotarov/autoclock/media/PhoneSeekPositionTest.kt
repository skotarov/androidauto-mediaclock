package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class PhoneSeekPositionTest {
    @Test fun sliderMapsToLongDuration() {
        assertEquals(1_800_000L, PhoneSeekPosition.position(5000, 3_600_000L))
        assertEquals(5000, PhoneSeekPosition.progress(1_800_000L, 3_600_000L))
        assertEquals(0L, PhoneSeekPosition.position(-1, 1000L))
        assertEquals(1000L, PhoneSeekPosition.position(11000, 1000L))
        assertEquals(0, PhoneSeekPosition.progress(20L, 0L))
    }
    @Test fun respectsSpeedAndEnd() {
        assertEquals(11500L, PhoneSeekPosition.current(10000L, 20000L, 1000L, 2000L, 1.5f))
        assertEquals(10000L, PhoneSeekPosition.current(10000L, 20000L, 1000L, 2000L, 0f))
        assertEquals(20000L, PhoneSeekPosition.current(19000L, 20000L, 1000L, 3000L, 2f))
        assertEquals("1:01:01", PhoneSeekPosition.format(3661000L))
        assertEquals("2:05", PhoneSeekPosition.format(125000L))
    }
}
