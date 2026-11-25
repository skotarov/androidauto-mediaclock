package com.kotarov.autoclock.media

import java.util.Locale

internal enum class BookFormat {
    PLAIN, EPUB, FB2, DOCX;

    companion object {
        fun detect(name: String, mime: String?): BookFormat? =
            when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "epub" -> EPUB
                "fb2" -> FB2
                "docx" -> DOCX
                "txt", "md", "markdown" -> PLAIN
                else -> when (mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
                    "application/epub+zip" -> EPUB
                    "application/x-fictionbook+xml", "application/fb2+xml" -> FB2
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
                    "text/plain", "text/markdown", "text/x-markdown" -> PLAIN
                    else -> null
                }
            }
    }
}
