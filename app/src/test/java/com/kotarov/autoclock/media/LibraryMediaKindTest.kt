package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMediaKindTest {
    private fun kind(name: String, format: String, text: Boolean = false, local: Boolean = true) =
        LibraryMediaKind.item(MediaPageParser.Item("id", name, "https://example.org/media", format, text), local)

    @Test fun distinguishesSupportedMedia() {
        assertEquals(LibraryMediaKind.BOOK, kind("Book", "EPUB", true))
        assertEquals(LibraryMediaKind.DOCUMENT, kind("Notes.pdf", "PDF", true))
        assertEquals(LibraryMediaKind.ARTICLE, kind("Page.html", "HTML", true))
        assertEquals(LibraryMediaKind.AUDIO, kind("Talk", "MP3"))
        assertEquals(LibraryMediaKind.AUDIOBOOK, kind("Book", "M4B"))
        assertEquals(LibraryMediaKind.VIDEO, kind("Clip", "MP4", local = false))
        assertEquals(LibraryMediaKind.STREAM, kind("Station", "M3U8", local = false))
        assertEquals(LibraryMediaKind.FILE, kind("Unknown", "MEDIA"))
    }
}
