package com.kotarov.autoclock.media

import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale

/** Bounded local HTML-to-text tokenizer; no DOM, scripts, image loads or links. */
internal object HtmlBookExtractor {
    private val blocks = setOf("p","div","article","section","main","li","h1","h2","h3","h4","h5","h6","br","tr","td","th","blockquote","pre","hr")
    private val skip = setOf("head","script","style","noscript","template","svg","iframe","object")
    private val voids = setOf("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "copy" to "©", "reg" to "®", "euro" to "€")
    private val entity = Regex("&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[A-Za-z][A-Za-z0-9]{0,31});")
    private val charsetTag = Regex("""(?i)charset\s*=\s*["']?\s*([a-z0-9_.-]+)""")
    private val hidden = Regex("""(?i)(?:^|\s)(?:hidden(?=\s|=|/|$)|aria-hidden\s*=\s*(?:"true"|'true'|true(?=\s|/|$)))""")
    private val tagName = Regex("""^/?\s*([a-zA-Z][a-zA-Z0-9:_-]*)""")
    private fun decode(value: String): String = entity.replace(value) { match ->
        val name = match.groupValues[1]
        val code = when {
            name.startsWith("#x", true) -> name.substring(2).toIntOrNull(16)
            name.startsWith("#") -> name.substring(1).toIntOrNull()
            else -> null
        }
        if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF && code != 0)
            String(Character.toChars(code))
        else named[name] ?: runCatching {
            android.text.Html.fromHtml(match.value, 0).toString().takeIf { it.isNotEmpty() } ?: match.value
        }.getOrDefault(match.value)
    }
    fun extract(input: InputStream, check: () -> Unit, emit: (String) -> Unit) {
        val bytes = input.readBytes()
        check()
        val probe = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> runCatching { Charset.forName(charsetTag.find(probe)?.groupValues?.get(1) ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        }
        val html = String(bytes, charset).removePrefix("\uFEFF")
        var i = 0
        val suppressed = ArrayDeque<String>()
        var raw: String? = null
        val out = StringBuilder(4096)
        fun flush() { if (out.isNotEmpty()) { emit(out.toString()); out.setLength(0) } }
        fun add(value: String) { out.append(value); if (out.length >= 4096) flush() }
        while (i < html.length) {
            check()
            val rawName = raw
            if (rawName != null) {
                val end = html.indexOf("</$rawName", i, ignoreCase = true)
                if (end < 0) break
                i = end
                raw = null
            }
            if (html[i] != '<') {
                val end = html.indexOf('<', i).let { if (it < 0) html.length else it }
                if (suppressed.isEmpty()) add(decode(html.substring(i, end)).replace(Regex("""[\t\r\n\f ]+"""), " "))
                i = end
                continue
            }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                if (end < 0) break
                i = end + 3; continue
            }
            var j = i + 1
            var quote = '\u0000'
            while (j < html.length) {
                val c = html[j]
                if (quote != '\u0000') { if (c == quote) quote = '\u0000' }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '>') break
                j++
                if (j % 4096 == 0) check()
            }
            if (j == html.length) break
            val token = html.substring(i + 1, j).trim()
            i = j + 1
            val name = tagName.find(token)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: continue
            val closing = token.startsWith("/")
            if (closing) {
                if (suppressed.isNotEmpty()) {
                    if (name in suppressed) {
                        while (suppressed.isNotEmpty()) { if (suppressed.removeLast() == name) break }
                    }
                } else if (name in blocks) add("\n")
                continue
            }
            val self = token.endsWith("/") || name in voids
            if (suppressed.isNotEmpty() || name in skip || hidden.containsMatchIn(token.substringAfter(name, token))) {
                if (!self) {
                    require(suppressed.size < 256)
                    suppressed.addLast(name)
                    if (name == "script" || name == "style") raw = name
                }
            } else if (name in blocks) add("\n")
        }
        flush()
    }
}
