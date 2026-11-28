package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class MediaPageParserTest {
    @Test fun htmlFindsPublicMediaButNotOtherLinks() {
        val items = MediaPageParser.parse("folder", "https://example.org/radios/", """
            <a href="one.mp3">Radio One</a><a href="/news">News</a>
            <audio title="Radio Two" src="/live"></audio>
            <a href="one.mp3">Duplicate</a><video><source src="/talk.mp4" type="video/mp4"></video>
        """.trimIndent())
        assertEquals(3, items.size)
        assertEquals("Radio One", items[0].name)
        assertEquals("https://example.org/radios/one.mp3", items[0].url)
        assertEquals("Radio Two", items[1].name)
    }
    @Test fun playlistNamesAndLiveManifest() {
        val base = "https://example.org/radio.m3u"
        val items = MediaPageParser.parse("p",base,"#EXTM3U\n#EXTINF:-1,Station\nhttps://example.org/live")
        assertEquals("Station",items.single().name)
        assertEquals("https://example.org/live",items.single().url)
        val hls = MediaPageParser.parse("p","https://example.org/live.m3u8","#EXTM3U\n#EXT-X-TARGETDURATION:5\nsegment.ts")
        assertEquals("https://example.org/live.m3u8",hls.single().url)
    }
    @Test fun longPlaylistKeepsEveryEntryInOriginalOrder() {
        val playlist = "#EXTM3U\n" + (1..250).joinToString("\n") { i ->
            "#EXTINF:-1,Station $i\nhttps://example.org/live$i"
        }
        val items = MediaPageParser.parse("p", "https://example.org/list.m3u", playlist)
        assertEquals(250, items.size)
        assertEquals("Station 1", items.first().name)
        assertEquals("Station 250", items.last().name)
        assertEquals(250, items.map { it.id }.toSet().size)
    }
    @Test fun rejectsCredentialsAndNonMediaLinks() {
        val items = MediaPageParser.parse("p","https://example.org/", """<a href="https://u:p@example.org/a.mp3">bad</a><a href="javascript:alert(1)">bad</a><a href="/article">article</a>""")
        assertTrue(items.isEmpty())
    }
    @Test fun mixedCourseIncludesReadableAttachmentsNotNavigation() {
        val items = MediaPageParser.parse("course", "https://example.org/course/", """
            <a href="lesson.mp3">Lesson</a><a href="notes.pdf">Notes</a>
            <a href="book.epub">Book</a><a href="exercise.docx">Exercise</a>
            <a href="readme.txt">Text</a><a href="home.html">Home</a>
            <a href="download.html" download>Document</a>
            <a href="/fetch/42" type="application/pdf">PDF attachment</a>
        """.trimIndent())
        assertEquals(7, items.size)
        assertFalse(items.first().textFile)
        assertTrue(items.drop(1).all { it.textFile })
        assertFalse(items.any { it.name == "Home" })
    }
    @Test fun significantLinksKeepChaptersAndExcludeSiteFurniture() {
        val base = "https://example.org/books/"
        val doc = org.jsoup.Jsoup.parse("""
            <header><a href="/home/">Home</a></header>
            <main><a href="/books/one/">First book</a><a href="/books/two/">Second book</a>
              <a href="/notes.pdf">PDF</a><a href="/books/one/">Duplicate</a>
              <a href="https://other.example/book/">External</a></main>
            <nav class="post-navigation"><a rel="next" href="/books/three/">Next chapter</a></nav>
            <aside class="advertisement"><a href="/sale/">Sale</a></aside>
        """.trimIndent(), base)
        val rows = MediaPageParser.navigationLinks("root", base, doc)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.browsable })
        assertEquals("https://example.org/books/three/", rows.last().url)
        assertTrue(rows.last().next)
        assertTrue(MediaPageParser.navigationLinks("root", base, doc, all = true).size > rows.size)
    }
    @Test fun catalogKeepsBookHeadingsOverRepeatedReadNowLinks() {
        val base = "https://example.org/books/"
        val doc = org.jsoup.Jsoup.parse("""
            <main><h2><a href="one/">Революционна Психология</a></h2>
            <a href="one/">Четете сега</a>
            <h2><a href="two/">Големият Бунт</a></h2><a href="two/">Четете сега</a></main>
        """.trimIndent(), base)
        val rows = MediaPageParser.navigationLinks("root", base, doc)
        assertEquals(listOf("Революционна Психология", "Големият Бунт"), rows.map { it.name })
        assertEquals("Browse website", MediaPageParser.Inspection(items = rows).suggested().first())
    }
}
