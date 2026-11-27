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
}
