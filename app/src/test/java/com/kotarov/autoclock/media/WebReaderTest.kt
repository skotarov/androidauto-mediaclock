package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class WebReaderTest {
    @Test fun readerRemovesNavigationAdvertisingAndComments() {
        val paragraph = "This is the main article and contains enough meaningful words to be recognized as the readable content of the page."
        val page = WebReader.extractHtml("""
            <html><head><title>Example</title></head><body>
            <header>Site banner</header><nav>Navigation menu</nav>
            <main><article><h1>Article title</h1><p>$paragraph</p>
            <div class="advertisement">Buy now</div><p>Second paragraph with useful information.</p>
            <section id="comments">Comment text</section></article></main>
            <footer>Footer links</footer><script>doNotRun()</script></body></html>
        """.trimIndent())
        assertEquals("Article title", page.title)
        assertTrue(page.text.contains(paragraph))
        assertTrue(page.text.contains("Second paragraph"))
        for (excluded in listOf("Navigation menu", "Buy now", "Comment text", "Footer links", "Site banner", "doNotRun"))
            assertFalse(page.text.contains(excluded))
    }
    @Test fun onlyHttpsWithoutCredentials() {
        assertTrue(WebReader.valid("https://example.org/article?a=1"))
        assertFalse(WebReader.valid("http://example.org"))
        assertFalse(WebReader.valid("https://user:pass@example.org"))
        assertFalse(WebReader.valid("file:///data/example"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun noReadableTextIsRejected() { WebReader.extractHtml("<nav>Menu</nav><p>Short</p>") }
}
