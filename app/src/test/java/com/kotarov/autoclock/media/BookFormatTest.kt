package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class BookFormatTest {
    @Test fun extensionOrMimeRecognizesSupportedBooks() {
        assertEquals(BookFormat.EPUB, BookFormat.detect("Book.EPUB", "application/octet-stream"))
        assertEquals(BookFormat.FB2, BookFormat.detect("book.fb2", null))
        assertEquals(BookFormat.DOCX, BookFormat.detect("document", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(BookFormat.PLAIN, BookFormat.detect("notes.md", null))
        assertEquals(BookFormat.PLAIN, BookFormat.detect("book.txt", "text/plain"))
        assertEquals(BookFormat.PDF, BookFormat.detect("scan.pdf", "application/pdf"))
        assertEquals(BookFormat.HTML, BookFormat.detect("book.HTM", null))
        assertEquals(BookFormat.RTF, BookFormat.detect("book", "application/rtf"))
        assertNull(BookFormat.detect("book.mobi", "application/octet-stream"))
    }
}
