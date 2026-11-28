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
    @Test fun explicitArticleBodyWinsOverLinkCardsAndMainWrapper() {
        val first = "Първият абзац разглежда една важна тема и обяснява основната идея с достатъчно подробности за читателя."
        val second = "Вторият абзац развива аргумента и дава допълнителен пример, който помага за разбирането на текста."
        val page = WebReader.extractHtml("""
            <main><div><a href="/home">Начало</a><a href="/news">Новини</a><a href="/contact">Контакти</a></div>
            <article><h1>Заглавие</h1><div itemprop="articleBody"><p>$first</p>
            <div class="adSlot"><p>Купете сега рекламния продукт на специална цена.</p></div>
            <h2>Следваща част</h2><p>$second</p><p><a href="/next">Прочетете още интересни новини</a></p>
            <div class="relatedPosts"><p>Препоръчана публикация, която няма отношение към основния материал.</p></div>
            <p style="display: none">Скрит текст, който не бива да се чете.</p></div></article>
            <section><h2>Други новини</h2><p><a href="/a">Друг материал с дълго заглавие</a></p></section></main>
        """.trimIndent())
        assertEquals("Заглавие", page.title)
        assertTrue(page.text.contains(first))
        assertTrue(page.text.contains(second))
        assertTrue(page.text.contains("Следваща част"))
        for (excluded in listOf("Начало", "Купете", "Прочетете още", "Препоръчана публикация", "Скрит текст", "Друг материал")) {
            assertFalse(page.text.contains(excluded))
        }
    }

    @Test fun legacyPageUsesParagraphContainerNotWholeSite() {
        val a = "The first paragraph contains a detailed discussion of the subject and explains its main argument."
        val b = "The second paragraph continues the discussion with another useful example and a practical conclusion."
        val page = WebReader.extractHtml("""
            <div id="top"><a href="/1">Navigation item one</a><a href="/2">Navigation item two</a></div>
            <div id="story"><h1>Story</h1><p>$a</p><p>$b</p></div>
            <div id="more"><ul><li><a href="/3">Unrelated headlines</a></li><li><a href="/4">More offers</a></li></ul></div>
        """.trimIndent())
        assertTrue(page.text.contains(a))
        assertTrue(page.text.contains(b))
        assertFalse(page.text.contains("Navigation item"))
        assertFalse(page.text.contains("More offers"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun linkOnlyDirectoryIsNotImportedAsArticle() {
        WebReader.extractHtml("""
            <main><h1>News</h1><p><a href="/1">A very long headline from a completely unrelated article on the site.</a></p>
            <p><a href="/2">Another very long headline with no genuine article body on this page.</a></p></main>
        """.trimIndent())
    }
}
