package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class NowPlayingTitleTest {
    @Test fun expiresAtEightSeconds() {
        val title = NowPlayingTitle()
        assertTrue(title.show("Radio Fresh", 1000))
        assertEquals("Radio Fresh", title.text(8999))
        assertNull(title.text(9000))
    }
    @Test fun newTrackRestartsWindowAndPauseClears() {
        val title = NowPlayingTitle()
        title.show("First", 1000)
        title.show("Second", 7000)
        assertEquals("Second", title.text(14999))
        assertNull(title.text(15000))
        title.show("Third", 16000)
        title.clear()
        assertNull(title.text(16001))
    }
    @Test fun noMediaHasNoCaption() {
        val title = NowPlayingTitle()
        title.show("Previous", 1000)
        assertFalse(title.show(null, 2000))
        assertNull(title.text(2001))
    }
}
