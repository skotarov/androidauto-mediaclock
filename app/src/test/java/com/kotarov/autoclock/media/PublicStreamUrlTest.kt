package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class PublicStreamUrlTest {
    @Test fun publicHttpAndHttps() {
        assertTrue(CustomStreamPlayer.valid("http://193.108.24.21:8000/fresh"))
        assertTrue(CustomStreamPlayer.valid("https://radio.example/live?token=abc"))
        assertTrue(CustomStreamPlayer.valid("http://radio.example/live.mp3"))
    }
    @Test fun rejectsUnsafeAddresses() {
        assertFalse(CustomStreamPlayer.valid("http://user:pass@radio.example/live"))
        assertFalse(CustomStreamPlayer.valid("http://radio.example/live?token=abc"))
        assertFalse(CustomStreamPlayer.valid("http://radio.example/live#part"))
        assertFalse(CustomStreamPlayer.valid("ftp://radio.example/live"))
        assertFalse(CustomStreamPlayer.valid("https://user@radio.example/live"))
        assertFalse(CustomStreamPlayer.valid("http:///live"))
    }
}
