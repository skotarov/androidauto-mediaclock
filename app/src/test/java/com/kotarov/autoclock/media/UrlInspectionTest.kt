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
        assertEquals(listOf("Read article", "Browse website"), MediaPageParser.Inspection(listOf(item), page).suggested())
        assertEquals(listOf("Read article"), MediaPageParser.Inspection(article = page).suggested())
        assertEquals(listOf("Browse website"), MediaPageParser.Inspection(listOf(item)).suggested())
        assertTrue(MediaPageParser.Inspection().suggested().isEmpty())
    }
    @Test fun catalogPrefersWebsiteBrowsing() {
        val link = MediaPageParser.Item("book", "Book", "https://example.com/book/", "WEB", browsable = true)
        val article = WebReader.Page("Catalog", "Introduction")
        assertEquals(listOf("Browse website", "Read article"), MediaPageParser.Inspection(listOf(link), article).suggested())
    }
}
