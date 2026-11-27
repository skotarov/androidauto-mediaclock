package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class UrlInspectionTest {
    @Test fun directMediaDoesNotBecomeAFolder() {
        val item = MediaPageParser.Item("one", "Radio", "https://example.com/live", "MP3")
        assertEquals(listOf("Stream"), MediaPageParser.Inspection(listOf(item), direct = true).suggested())
    }
    @Test fun ambiguityPreservesBothChoices() {
        val item = MediaPageParser.Item("one", "Audio", "https://example.com/a.mp3", "MP3")
        val page = WebReader.Page("Title", "Text")
        assertEquals(listOf("Read article", "Browse media"), MediaPageParser.Inspection(listOf(item), page).suggested())
        assertEquals(listOf("Read article"), MediaPageParser.Inspection(article = page).suggested())
        assertEquals(listOf("Browse media"), MediaPageParser.Inspection(listOf(item)).suggested())
        assertTrue(MediaPageParser.Inspection().suggested().isEmpty())
    }
}
