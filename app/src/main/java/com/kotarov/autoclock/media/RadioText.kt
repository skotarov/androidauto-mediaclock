package com.kotarov.autoclock.media

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object RadioText {
    private fun strict(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    fun decode(bytes: ByteArray, declared: String?, json: Boolean): String {
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
        // JSON is UTF-8 by default; tolerate servers with inaccurate charset headers.
        if (json || charset == null) {
            strict(bytes, Charsets.UTF_8)?.let { return it.removePrefix("\uFEFF") }
        }
        if (charset != null) strict(bytes, charset)?.let { return it.removePrefix("\uFEFF") }
        strict(bytes, Charsets.UTF_8)?.let { return it.removePrefix("\uFEFF") }
        // Legacy Cyrillic playlists sometimes omit the charset entirely.
        return String(bytes, Charset.forName("windows-1251")).removePrefix("\uFEFF")
    }

    fun cleanName(name: String, url: String): String {
        // U+FFFD already delivered by the server has lost the original characters.
        // Never show a row of replacement diamonds or invent a station identity.
        if ('\uFFFD' !in name) return name
        return runCatching {
            val uri = URI(url)
            val host = uri.host.orEmpty().removePrefix("www.")
            val path = uri.path.orEmpty().trim('/').substringAfterLast('/').take(60)
            if (path.isBlank()) host else "$host · $path"
        }.getOrDefault("Radio stream")
    }
}
