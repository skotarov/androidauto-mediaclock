package com.kotarov.autoclock.media

import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.io.ByteArrayOutputStream
import java.util.Locale

/** RTF text subset: group-scoped codepages/Unicode, paragraphs, escaped bytes.
 * Skip formatting tables, binary images, embedded objects and destinations.
 * No execution or access to linked files. */
internal object RtfBookExtractor {
    private data class State(var skip: Boolean = false, var uc: Int = 1,
                             var codepage: Int = 1252, var hidden: Boolean = false)
    private val destinations = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict",
        "object", "objdata", "filetbl", "listtable", "listoverridetable", "revtbl",
        "rsidtbl", "generator", "datastore", "themedata", "colorschememapping",
        "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
        "annotation", "atnauthor", "atnid", "fldinst", "xmlnstbl", "latentstyles",
        "nonshppict", "shppict", "shp", "shpinst", "shprslt", "pntext", "listtext",
        "private", "docvar", "footnote", "background", "mmath", "upr")
    fun extract(input: InputStream, check: () -> Unit, emit: (String) -> Unit) {
        val stream = PushbackInputStream(input.buffered(), 1)
        val stack = ArrayDeque<State>()
        var state = State()
        var fallback = 0
        var header = false
        var operations = 0
        val bytes = ByteArrayOutputStream(4096)
        val output = StringBuilder(4096)
        var codepage = 1252
        fun flushOutput() {
            if (output.isNotEmpty()) { emit(output.toString()); output.setLength(0) }
        }
        fun add(value: String) {
            if (!state.skip && !state.hidden) {
                output.append(value)
                if (output.length >= 4096) flushOutput()
            }
        }
        fun flushBytes() {
            if (bytes.size() > 0) {
                val charset = runCatching {
                    Charset.forName(when (codepage) {
                        65001 -> "UTF-8"
                        10000 -> "x-MacRoman"
                        437 -> "IBM437"
                        850 -> "IBM850"
                        932 -> "Shift_JIS"
                        936 -> "GBK"
                        949 -> "x-windows-949"
                        950 -> "Big5"
                        else -> "windows-$codepage"
                    })
                }.getOrDefault(Charsets.ISO_8859_1)
                add(String(bytes.toByteArray(), charset))
                bytes.reset()
            }
        }
        fun rawByte(c: Int) {
            if (fallback > 0) { fallback--; return }
            if (state.skip || state.hidden) return
            if (codepage != state.codepage) { flushBytes(); codepage = state.codepage }
            bytes.write(c)
            // Keep multibyte runs intact; limit a single uninterrupted run.
            require(bytes.size() <= 1024 * 1024) { "RTF text run too large" }
            if (bytes.size() >= 8192 && codepage in 1250..1258) flushBytes()
        }
        fun symbol(value: String) {
            if (fallback > 0) { fallback--; return }
            flushBytes(); add(value)
        }
        fun letter(c: Int) = c in 65..90 || c in 97..122
        fun digit(c: Int) = c in 48..57
        fun unread(c: Int) { if (c >= 0) stream.unread(c) }
        while (true) {
            if (++operations % 4096 == 0) check()
            val c = stream.read()
            if (c < 0) break
            when (c) {
                123 -> { // {
                    flushBytes()
                    require(stack.size < 256)
                    stack.addLast(state)
                    state = state.copy()
                }
                125 -> { // }
                    flushBytes()
                    require(stack.isNotEmpty())
                    state = stack.removeLast()
                    fallback = 0
                }
                10, 13 -> Unit // source formatting, not a paragraph
                92 -> { // backslash
                    val next = stream.read()
                    require(next >= 0)
                    if (next == 39) {
                        val a = stream.read().toChar().digitToIntOrNull(16)
                        val b = stream.read().toChar().digitToIntOrNull(16)
                        require(a != null && b != null)
                        rawByte(a * 16 + b)
                    } else if (!letter(next)) {
                        when (next) {
                            42 -> { flushBytes(); state.skip = true } // \*
                            92, 123, 125 -> rawByte(next)
                            126 -> symbol("\u00a0")
                            95 -> symbol("\u2011")
                            45 -> symbol("\u00ad")
                            10, 13 -> symbol("\n")
                        }
                    } else {
                        val word = StringBuilder().append(next.toChar())
                        var end = stream.read()
                        while (letter(end)) {
                            require(word.length < 64)
                            word.append(end.toChar()); end = stream.read()
                        }
                        val neg = end == 45
                        if (neg) end = stream.read()
                        var number: Long? = null
                        while (digit(end)) {
                            number = (number ?: 0) * 10 + end - 48
                            require(number <= Int.MAX_VALUE.toLong())
                            end = stream.read()
                        }
                        val n = number?.toInt()?.let { if (neg) -it else it }
                        if (end != 32) unread(end)
                        val key = word.toString().lowercase(Locale.ROOT)
                        if (key == "bin") {
                            flushBytes()
                            val size = n ?: error("Invalid RTF binary")
                            require(size in 0..(128 * 1024 * 1024))
                            var remaining = size
                            val scratch = ByteArray(8192)
                            while (remaining > 0) {
                                check()
                                val read = stream.read(scratch, 0, minOf(remaining, scratch.size))
                                require(read > 0)
                                remaining -= read
                            }
                            continue
                        }
                        if (key in destinations) { flushBytes(); state.skip = true; continue }
                        if (state.skip) continue
                        when (key) {
                            "rtf" -> header = true
                            "ansi", "mac", "pc", "pca", "ansicpg" -> {
                                flushBytes()
                                state.codepage = when (key) {
                                    "mac" -> 10000
                                    "pc" -> 437
                                    "pca" -> 850
                                    "ansicpg" -> n ?: 1252
                                    else -> 1252
                                }
                            }
                            "uc" -> state.uc = (n ?: 1).coerceIn(0, 16)
                            "u" -> {
                                flushBytes()
                                if (n != null) add((n and 65535).toChar().toString())
                                fallback = state.uc
                            }
                            "v" -> { flushBytes(); state.hidden = n != 0 }
                            "par", "line", "page", "row" -> symbol("\n")
                            "tab", "cell" -> symbol("\t")
                            "emdash" -> symbol("\u2014")
                            "endash" -> symbol("\u2013")
                            "bullet" -> symbol("\u2022")
                            "lquote" -> symbol("\u2018")
                            "rquote" -> symbol("\u2019")
                            "ldblquote" -> symbol("\u201c")
                            "rdblquote" -> symbol("\u201d")
                        }
                    }
                }
                else -> if (stack.isNotEmpty()) rawByte(c)
            }
        }
        flushBytes()
        flushOutput()
        require(header && stack.isEmpty()) { "Malformed RTF" }
        check()
    }
}
