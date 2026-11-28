package com.kotarov.autoclock.media

import java.util.Locale

internal enum class BookFormat {
    PLAIN, EPUB, FB2, DOCX, PDF, HTML, RTF;

    companion object {
        fun detect(name: String, mime: String?): BookFormat? =
            when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "epub" -> EPUB
                "fb2" -> FB2
                "docx" -> DOCX
                "pdf" -> PDF
                "html", "htm", "xhtml" -> HTML
                "rtf" -> RTF
                "txt", "md", "markdown" -> PLAIN
                else -> when (mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
                    "application/epub+zip" -> EPUB
                    "application/pdf" -> PDF
                    "text/html", "application/xhtml+xml" -> HTML
                    "application/rtf", "text/rtf", "application/x-rtf" -> RTF
                    "application/x-fictionbook+xml", "application/fb2+xml" -> FB2
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
                    "text/plain", "text/markdown", "text/x-markdown" -> PLAIN
                    else -> null
                }
            }
    }
}
