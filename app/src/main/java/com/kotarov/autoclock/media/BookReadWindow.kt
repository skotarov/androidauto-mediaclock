package com.kotarov.autoclock.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Worker-only, one small decoded window. No open file descriptor between utterances. */
internal class BookReadWindow(private val file: File, private val length: Int) {
    data class Chunk(val start: Int, val end: Int, val body: String)
    private data class Window(val start: Int, val text: String) {
        val end: Int get() = start + text.length
    }
    private var cached: Window? = null

    private fun at(position: Int, minimum: Int): Window {
        val required = minOf(minimum, length - position)
        cached?.let {
            if (position >= it.start && (position == 0 || position > it.start) &&
                position + required <= it.end) return it
        }
        // One preceding code unit lets us recognize a low surrogate at the target.
        val start = (position - 1).coerceAtLeast(0)
        val count = minOf(2048, length - start)
        val bytes = ByteArray(count * 2)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start.toLong() * 2L)
            input.readFully(bytes)
        }
        val chars = CharArray(count)
        ByteBuffer.wrap(bytes).asCharBuffer().get(chars)
        return Window(start, String(chars)).also { cached = it }
    }

    fun read(position: Int, alignWord: Boolean): Chunk? {
        var start = position.coerceIn(0, length)
        if (alignWord && start in 1 until length) {
            val from = (start - 64).coerceAtLeast(0)
            val window = at(from, start - from + 1)
            val around = window.text.substring(from - window.start, start - window.start + 1)
            start = from + TextReading.wordStart(around, start - from)
        }
        while (start < length) {
            if (Thread.currentThread().isInterrupted) error("Cancelled")
            var window = at(start, 351)
            var first = start - window.start
            if (start > 0 && Character.isLowSurrogate(window.text[first])) first--
            while (first < window.text.length && window.text[first].isWhitespace()) first++
            if (first == window.text.length) {
                start = window.end
                continue
            }
            val absolute = window.start + first
            if (window.end - absolute < minOf(351, length - absolute)) {
                window = at(absolute, 351)
                first = absolute - window.start
            }
            val end = TextReading.chunkEnd(window.text, first)
            return Chunk(window.start + first, window.start + end, window.text.substring(first, end))
        }
        return null
    }
}
