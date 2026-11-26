package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class BookContentsIndexTest {
    @Test fun resolvesNavigationToExactOffsets() {
        val index = BookContentsIndex()
        index.navigation("Chapter one", "one.xhtml")
        index.navigation("Subchapter", "one.xhtml#sub")
        index.anchor("one.xhtml")
        index.append("First paragraph\n")
        index.anchor("one.xhtml#sub")
        index.append("Second paragraph")
        assertEquals(listOf(0, 16), index.chapters().map { it.offset })
    }
    @Test fun recognizesSplitPlainTextHeadings() {
        val index = BookContentsIndex(true)
        index.append("Intro\nГла")
        index.append("ва 1\nТекст\n## Next\nMore")
        assertEquals(listOf("Глава 1", "Next"), index.chapters().map { it.title })
        assertEquals(6, index.chapters().first().offset)
    }
}
