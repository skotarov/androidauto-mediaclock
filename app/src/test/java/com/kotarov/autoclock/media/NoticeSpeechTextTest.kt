package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeSpeechTextTest {
    @Test fun expandsTimeAndPercentage() {
        assertEquals("Within one hour: Heavy rain", NoticeSpeechText.format("Within 1h: Heavy rain"))
        assertEquals("Precipitation: 80 percent", NoticeSpeechText.format("Precipitation: 80%"))
    }

    @Test fun preservesExistingMessages() {
        assertEquals("Risk of ice", NoticeSpeechText.format("Risk of ice"))
        assertEquals("Sofia", NoticeSpeechText.format("Sofia"))
        assertEquals("Location unavailable", NoticeSpeechText.format("Location unavailable"))
    }
}
