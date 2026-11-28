package com.kotarov.autoclock.media

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BookReadWindowTest {
    @Test fun readsExactlyAcrossWindowsAndWhitespace() {
        val text = " ".repeat(2500) + ("Текст за четене. " + "😀 ").repeat(500)
        val file = File.createTempFile("read-window", ".utf16")
        try {
            file.writeBytes(text.toByteArray(Charsets.UTF_16BE))
            val reader = BookReadWindow(file, text.length)
            var pos = 0
            var chunks = 0
            while (pos < text.length) {
                val chunk = reader.read(pos, false) ?: break
                assertTrue(chunk.end > pos)
                assertEquals(text.substring(chunk.start, chunk.end), chunk.body)
                assertTrue(text.substring(pos, chunk.start).all { it.isWhitespace() })
                assertFalse(Character.isHighSurrogate(chunk.body.last()))
                pos = chunk.end
                chunks++
            }
            assertTrue(chunks > 10)
            assertTrue(text.substring(pos).all { it.isWhitespace() })
            val emoji = text.indexOf("😀")
            assertEquals(emoji, reader.read(emoji + 1, false)?.start)
        } finally { file.delete() }
    }

    @Test fun alignsToWordAfterSeeking() {
        val text = "First word second third."
        val file = File.createTempFile("read-window", ".utf16")
        try {
            file.writeBytes(text.toByteArray(Charsets.UTF_16BE))
            val reader = BookReadWindow(file, text.length)
            assertEquals(11, reader.read(14, true)?.start)
        } finally { file.delete() }
    }
}
