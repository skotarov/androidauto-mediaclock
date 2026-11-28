package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadLocationTest {
    @Test fun noOverwriteAndCaseInsensitiveCollision() {
        assertEquals("Talk.mp3", DownloadLocation.uniqueName("Talk.mp3", emptySet()))
        assertEquals("Talk (3).mp3", DownloadLocation.uniqueName("Talk.mp3",
            setOf("talk.mp3", "talk (2).mp3")))
    }
}
