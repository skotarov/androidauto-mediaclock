package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class TextReadingTest {
    @Test fun chunksCoverEntireTextWithoutDroppingCharacters() {
        val text = "Hello world. Следващо изречение! ".repeat(100) + "End."
        var offset = 0
        val rebuilt = StringBuilder()
        while (offset < text.length) {
            val end = TextReading.chunkEnd(text, offset)
            assertTrue(end > offset)
            assertTrue(end - offset <= 350)
            rebuilt.append(text.substring(offset, end))
            offset = end
        }
        assertEquals(text, rebuilt.toString())
    }
    @Test fun respectsSelectedLanguageAndDetectsScripts() {
        assertEquals("bg", TextReading.language("Това е текст на български.", "auto", "en"))
        assertEquals("ru", TextReading.language("Это русский текст.", "auto", "bg"))
        assertEquals("en", TextReading.language("Hello", "auto", "bg"))
        assertEquals("fr", TextReading.language("Hello", "fr", "bg"))
    }
    @Test fun percentIsBounded() {
        assertEquals(50, TextReading.percent(10, 20))
        assertEquals(0, TextReading.percent(0, 0))
    }
}
