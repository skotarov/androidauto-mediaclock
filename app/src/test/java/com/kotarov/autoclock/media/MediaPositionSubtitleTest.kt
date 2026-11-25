package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class MediaPositionSubtitleTest {
    @Test fun formatsElapsedPosition() {
        assertEquals("00:00", MediaPositionSubtitle.text(null, 0))
        assertEquals("12:34", MediaPositionSubtitle.text(null, 754999))
        assertEquals("1:02:03", MediaPositionSubtitle.text(null, 3723000))
    }
    @Test fun noticesHavePriorityOverPosition() {
        assertEquals("⚠️ Fog", MediaPositionSubtitle.text("⚠️ Fog", 754000))
        assertEquals("📍 Sofia", MediaPositionSubtitle.text("📍 Sofia", null))
        assertEquals("00:10", MediaPositionSubtitle.text(null, 10000))
    }
    @Test fun unknownPositionLeavesSubtitleEmpty() {
        assertNull(MediaPositionSubtitle.text(null, null))
        assertNull(MediaPositionSubtitle.text(null, -1))
    }
}
