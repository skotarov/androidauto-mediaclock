package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class MediaPublicationKeyTest {
    @Test fun onlyIdenticalPublicationsCanBeSkipped() {
        val key = MediaPublicationKey("minute1", " ", " ")
        assertEquals(key, MediaPublicationKey("minute1", " ", " "))
        assertNotEquals(key, key.copy(artwork = "minute2"))
        assertNotEquals(key, key.copy(title = "Внимание"))
        assertNotEquals(key, key.copy(subtitle = "София"))
    }
}
