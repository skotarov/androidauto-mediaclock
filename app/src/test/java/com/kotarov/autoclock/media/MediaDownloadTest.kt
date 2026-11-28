package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class MediaDownloadTest {
    @Test fun directAudioUsesCorrectFilename() {
        val spec = MediaDownload.describe("https://example.org/talk.mp3", "Лекция", "audio/mpeg", 1234L, false)
        assertEquals("Лекция.mp3", spec.name)
        assertEquals("audio/mpeg", spec.mime)
        assertEquals(1234L, spec.length)
    }

    @Test fun genericBinaryFileWithKnownExtension() {
        val spec = MediaDownload.describe("https://example.org/book.m4b?token=a", "Book", "application/octet-stream", 123L, false)
        assertEquals("Book.m4b", spec.name)
        assertEquals("audio/mp4", spec.mime)
    }

    @Test fun rejectsUnsafeFilenameCharacters() {
        val spec = MediaDownload.describe("https://example.org/a.mp3", "A/B:C?", "audio/mpeg", 100L, false)
        assertEquals("A_B_C_.mp3", spec.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownLengthRejected() {
        MediaDownload.describe("https://example.org/live", "Radio", "audio/mpeg", -1L, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun icyRadioRejected() {
        MediaDownload.describe("https://example.org/live", "Radio", "audio/mpeg", 123L, true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun htmlNotMedia() {
        MediaDownload.describe("https://example.org/a.mp3", "Page", "text/html", 123L, false)
    }

    @Test fun playlistsNotOffered() {
        assertFalse(MediaDownload.offer("https://example.org/live.m3u8"))
        assertFalse(MediaDownload.offer("https://example.org/list.pls"))
        assertTrue(MediaDownload.offer("https://example.org/file.mp3"))
    }
    @Test fun downloadableDocumentRetainsFormat() {
        val spec = MediaDownload.describe("https://example.org/lesson.pdf", "Lesson", "application/pdf", 100L, false)
        assertEquals("Lesson.pdf", spec.name)
        assertTrue(spec.textFile)
    }
}
