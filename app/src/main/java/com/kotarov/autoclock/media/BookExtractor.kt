package com.kotarov.autoclock.media

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URI
import java.util.Locale
import java.util.zip.ZipFile

/** Stream text into the existing fixed-width speech cache. No WebView, network,
 * archive extraction to paths, or complete in-memory document. */
internal object BookExtractor {
    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRY = 32L * 1024 * 1024

    private class Budget(var left: Long)
    private class Limited(input: InputStream, private val budget: Budget,
                          private var entry: Long, private val check: () -> Unit) : FilterInputStream(input) {
        private fun count(n: Int): Int {
            check()
            if (n > 0) {
                entry -= n
                budget.left -= n
                require(entry >= 0 && budget.left >= 0) { "Document too large" }
            }
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) else check() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
    }

    fun extract(format: BookFormat, input: InputStream, directory: File,
                check: () -> Unit, emit: (String) -> Unit) {
        val budget = Budget(MAX_BYTES)
        when (format) {
            BookFormat.PLAIN -> plain(Limited(input, budget, MAX_BYTES, check), check, emit)
            BookFormat.PDF -> PdfBookExtractor.extract(Limited(input, budget, MAX_BYTES, check), directory, check, emit)
            BookFormat.HTML -> HtmlBookExtractor.extract(Limited(input, budget, 4L * 1024 * 1024, check), check, emit)
            BookFormat.RTF -> RtfBookExtractor.extract(Limited(input, budget, MAX_BYTES, check), check, emit)
            BookFormat.FB2 -> xmlText(Limited(input, budget, MAX_BYTES, check), format, check, emit)
            BookFormat.EPUB, BookFormat.DOCX -> {
                val archive = File.createTempFile("book-", ".zip", directory)
                try {
                    archive.outputStream().buffered().use { out ->
                        Limited(input, Budget(MAX_BYTES), MAX_BYTES, check).copyTo(out, 8192)
                    }
                    ZipFile(archive).use { zip ->
                        require(zip.size() <= 20000)
                        fun entry(name: String): InputStream {
                            check()
                            val e = zip.getEntry(name) ?: error("Missing document part")
                            require(!e.isDirectory && (e.size < 0 || e.size <= MAX_ENTRY))
                            return Limited(zip.getInputStream(e), budget, MAX_ENTRY, check)
                        }
                        if (format == BookFormat.DOCX) {
                            entry("word/document.xml").use { xmlText(it, format, check, emit) }
                        } else {
                            val packagePath = entry("META-INF/container.xml").use { stream ->
                                var path: String? = null
                                events(stream, check) { p ->
                                    if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile" && path == null)
                                        path = p.getAttributeValue(null, "full-path")
                                }
                                path ?: error("No EPUB package")
                            }
                            val opf = resolve("", packagePath)
                            val manifest = LinkedHashMap<String, Pair<String, String>>()
                            val spine = ArrayList<String>()
                            entry(opf).use { stream ->
                                events(stream, check) { p ->
                                    if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                                        "item" -> {
                                            val id = p.getAttributeValue(null, "id")
                                            val href = p.getAttributeValue(null, "href")
                                            val mime = p.getAttributeValue(null, "media-type").orEmpty()
                                            if (id != null && href != null) manifest[id] = href to mime
                                            require(manifest.size <= 10000)
                                        }
                                        "itemref" -> {
                                            if (p.getAttributeValue(null, "linear") != "no")
                                                p.getAttributeValue(null, "idref")?.let { spine.add(it) }
                                            require(spine.size <= 10000)
                                        }
                                    }
                                }
                            }
                            require(spine.isNotEmpty()) { "Empty EPUB spine" }
                            var chapters = 0
                            for (id in spine) {
                                check()
                                val item = manifest[id] ?: error("Missing EPUB spine item")
                                if (item.second !in listOf("application/xhtml+xml", "text/html")) continue
                                entry(resolve(opf, item.first)).use { xmlText(it, format, check, emit) }
                                emit("\n\n")
                                chapters++
                            }
                            require(chapters > 0) { "No readable EPUB chapters" }
                        }
                    }
                } finally { archive.delete() }
            }
        }
    }

    private fun resolve(base: String, href: String): String {
        val uri = URI(href)
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null)
        val path = URI(null, null, "/" + base, null).resolve(uri).normalize().path
        require(path.startsWith("/") && path.split('/').none { it == ".." || it == "." } &&
            !path.contains('\\') && path.length <= 4096)
        return path.removePrefix("/")
    }

    private fun events(input: InputStream, check: () -> Unit, action: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // No processing of document declarations, external entities or DTDs.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(input, null)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            check()
            require(parser.depth <= 256)
            action(parser)
            parser.next()
        }
    }

    private fun xmlText(input: InputStream, format: BookFormat, check: () -> Unit, emit: (String) -> Unit) {
        var bodyDepth = -1
        var skipDepth = -1
        var textDepth = -1
        var emitted = false
        val blocks = setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li",
            "title", "subtitle", "v", "stanza", "tr", "td", "br", "empty-line")
        val skip = setOf("script", "style", "svg", "math", "binary", "nav")
        events(input, check) { p ->
            val name = p.name?.lowercase(Locale.ROOT).orEmpty()
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    if (skipDepth >= 0) return@events
                    if (name == "body" && bodyDepth < 0) {
                        if (format == BookFormat.FB2 && p.getAttributeValue(null, "name") == "notes") skipDepth = p.depth
                        else bodyDepth = p.depth
                    } else if (name in skip || (format == BookFormat.DOCX && name in setOf("del", "instrtext"))) {
                        skipDepth = p.depth
                    }
                    if (bodyDepth >= 0 && skipDepth < 0) {
                        if (format == BookFormat.DOCX && name == "t") textDepth = p.depth
                        if (name == "br" || name == "empty-line" || name == "tab") emit("\n")
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (bodyDepth >= 0 && skipDepth < 0 && (format != BookFormat.DOCX || textDepth >= 0)) {
                        val value = p.text.orEmpty()
                        if (value.isNotBlank()) emitted = true
                        emit(value)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skipDepth >= 0) {
                        if (p.depth == skipDepth) skipDepth = -1
                    } else if (bodyDepth >= 0) {
                        if (name in blocks) emit("\n")
                        if (p.depth == textDepth) textDepth = -1
                        if (p.depth == bodyDepth) bodyDepth = -1
                    }
                }
            }
        }
        // Empty chapters can be legal; a whole empty book is rejected by the reader.
        if (emitted) emit("\n")
    }

    private fun plain(input: InputStream, check: () -> Unit, emit: (String) -> Unit) {
        PushbackInputStream(input, 3).use { stream ->
            val prefix = ByteArray(3)
            var n = 0
            while (n < prefix.size) {
                val count = stream.read(prefix, n, prefix.size - n)
                if (count <= 0) break
                n += count
            }
            val b = prefix.map { it.toInt() and 255 }
            val (charset, skip) = when {
                n >= 3 && b == listOf(239, 187, 191) -> Charsets.UTF_8 to 3
                n >= 2 && b[0] == 255 && b[1] == 254 -> Charsets.UTF_16LE to 2
                n >= 2 && b[0] == 254 && b[1] == 255 -> Charsets.UTF_16BE to 2
                else -> Charsets.UTF_8 to 0
            }
            if (n > skip) stream.unread(prefix, skip, n - skip)
            stream.reader(charset).buffered().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    check()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    emit(String(buffer, 0, count))
                }
            }
        }
    }
}
