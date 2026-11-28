package com.kotarov.autoclock.media

import org.jsoup.Jsoup
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Direct public media only. No script execution, converter, login or player scraping. */
internal object MediaPageParser {
    data class Item(val id: String, val name: String, val url: String, val format: String, val textFile: Boolean = false)
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val extensions = setOf("mp3","aac","m4a","m4b","mp4","m4v","ogg","oga","opus","wav","flac","webm","m3u8","ts","3gp","wma","aiff","amr","mkv","avi","mov")
    private val documents = setOf("pdf","epub","fb2","docx","rtf","txt","md","markdown","html","htm","xhtml")
    // Ordinary links to HTML pages are navigation, not document attachments.
    // Only explicit HTML downloads/type hints are included.
    private fun documentFormat(url: String, type: String, explicit: Boolean): BookFormat? {
        val e = ext(url)
        if (e in extensions || e in setOf("m3u", "pls", "asx", "xspf", "mpd")) return null
        val format = BookFormat.detect(URI(url).path.orEmpty().substringAfterLast('/'), type)
        if (format == BookFormat.HTML && !explicit) return null
        return format?.takeIf { e in documents || type.isNotBlank() }
    }
    private fun ext(url: String) = runCatching { URI(url).path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT) }.getOrDefault("")
    private fun allowed(value: String): Boolean = runCatching {
        val u = URI(value)
        (u.scheme.equals("https", true) || (u.scheme.equals("http", true) && u.rawQuery == null)) &&
            !u.host.isNullOrBlank() && u.userInfo == null && u.fragment == null && value.length <= 4096
    }.getOrDefault(false)
    private fun absolute(base: String, value: String): String? = runCatching {
        URI(base).resolve(value.trim()).toString().takeIf { allowed(it) }
    }.getOrNull()
    data class Inspection(val items: List<Item> = emptyList(), val article: WebReader.Page? = null,
        val direct: Boolean = false, val streamName: String? = null, val document: Boolean = false) {
        fun suggested(): List<String> = if (document) listOf("Browse media") else if (direct) listOf("Stream") else
            listOfNotNull("Read article".takeIf { article != null }, "Browse media".takeIf { items.isNotEmpty() })
    }

    fun fetch(parent: String, address: String): List<Item> = download(parent, address, false).items

    fun inspect(address: String): Inspection = download("inspect", address, true)

    private fun download(parent: String, address: String, inspect: Boolean): Inspection {
        require(if (inspect) CustomStreamPlayer.valid(address) else WebReader.valid(address))
        var uri = URI(address)
        val deadline = System.nanoTime() + 30_000_000_000L
        repeat(5) {
            require(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
            require(if (inspect) CustomStreamPlayer.valid(uri.toString()) else WebReader.valid(uri.toString()))
            val conn = uri.toURL().openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "SmartTripClock/1.0")
                conn.setRequestProperty("Accept", "text/html, application/rss+xml, application/xml, application/json, audio/x-mpegurl, */*;q=0.1")
                val status = conn.responseCode
                if (status in listOf(301,302,303,307,308)) {
                    uri = uri.resolve(conn.getHeaderField("Location") ?: error("No redirect"))
                    return@repeat
                }
                require(status in 200..299)
                val type = conn.contentType.orEmpty()
                val mime = type.substringBefore(';').trim().lowercase(Locale.ROOT)
                // Never download a complete direct media file merely to browse a folder.
                val icyName = conn.getHeaderField("icy-name")?.trim()?.take(64)
                    ?.takeIf { it.isNotBlank() && '\uFFFD' !in it }
                val direct = (mime.startsWith("audio/") && !mime.contains("mpegurl") && !mime.contains("scpls")) ||
                    mime.startsWith("video/") || (inspect && (icyName != null || conn.getHeaderField("icy-br") != null ||
                    (mime in setOf("", "application/octet-stream") && ext(uri.toString()) in extensions && ext(uri.toString()) != "m3u8")))
                val document = documentFormat(uri.toString(), mime, false)
                if (document != null && mime !in setOf("text/html", "application/xhtml+xml") &&
                    (mime != "text/plain" || ext(uri.toString()) in documents)) {
                    return Inspection(parse(parent, uri.toString(), "", "direct:" + mime), document = true)
                }
                if (direct) {
                    return Inspection(parse(parent, uri.toString(), "", "direct:" + mime), direct = true, streamName = icyName)
                }
                // Web import and browsing retain the existing HTTPS-only policy.
                if (inspect && (!WebReader.valid(address) || !WebReader.valid(uri.toString()))) return Inspection()
                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        require(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= MAX_BYTES)
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val charset = Regex("(?i)charset\\s*=\\s*[\"']?([a-z0-9._-]+)").find(type)?.groupValues?.get(1)
                if (mime.contains("html")) {
                    val doc = Jsoup.parse(bytes.inputStream(), charset, uri.toString())
                    val items = parse(parent, uri.toString(), "", mime, doc)
                    val article = if (inspect) runCatching { WebReader.extract(doc, uri.host) }.getOrNull() else null
                    return Inspection(items, article)
                }
                val text = RadioText.decode(bytes, charset, mime.contains("json"))
                val items = parse(parent, uri.toString(), text, mime)
                val hls = text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF")
                return Inspection(items, direct = inspect && hls)
            } finally { conn.disconnect() }
        }
        error("Too many redirects")
    }
    internal fun parse(parent: String, base: String, content: String, mime: String = "text/html", htmlDoc: org.jsoup.nodes.Document? = null): List<Item> {
        val items = LinkedHashMap<String, Item>()
        fun add(raw: String, name: String, type: String = "", explicit: Boolean = false) {
            if (raw.isBlank()) return
            val url = absolute(base, raw) ?: return
            if (items.containsKey(url)) return
            val e = ext(url)
            val t = type.substringBefore(';').lowercase(Locale.ROOT)
            val book = documentFormat(url, t, explicit || t == "text/html" || t == "application/xhtml+xml")
            if (!explicit && book == null && e !in extensions && !t.startsWith("audio/") && !t.startsWith("video/")) return
            if (e in setOf("m3u","pls") && !t.contains("mpegurl")) return
            val label = RadioText.cleanName(name, url).trim().replace(Regex("\\s+"), " ").take(128).ifBlank {
                runCatching { URI(url).path.orEmpty().substringAfterLast('/').ifBlank { URI(url).host } }.getOrDefault("Media").take(128)
            }
            val hash = MessageDigest.getInstance("SHA-256").digest((parent + "|" + url).toByteArray())
                .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
            val format = if (book != null) {
                if (e in documents) e.uppercase(Locale.ROOT) else book.name
            } else if (e in extensions) e.uppercase(Locale.ROOT) else if (t.contains("mpegurl")) "M3U8" else t.substringAfter('/', "").uppercase(Locale.ROOT).ifBlank { "STREAM" }
            items.putIfAbsent(url, Item("webmedia:" + hash, label, url, format, textFile = book != null))
        }
        val trimmed = content.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        when {
            mime.startsWith("direct:") -> add(base, "", mime.removePrefix("direct:"), true)
            trimmed.contains("#EXT-X-TARGETDURATION") || trimmed.contains("#EXT-X-STREAM-INF") ->
                add(base, "Live stream", "audio/mpegurl", true)
            trimmed.startsWith("#EXTM3U") || mime.contains("mpegurl") || ext(base) == "m3u" -> {
                var title = ""
                for (line in content.lineSequence()) {
                    val v = line.trim()
                    if (v.startsWith("#EXTINF:", true)) title = v.substringAfter(',', "")
                    else if (v.isNotBlank() && !v.startsWith("#")) { add(v, title, explicit = true); title = "" }
                }
            }
            trimmed.startsWith("[playlist]", true) || mime.contains("scpls") || ext(base) == "pls" -> {
                val values = content.lineSequence().mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0,i).trim().lowercase(Locale.ROOT) to line.substring(i+1).trim()
                }.toMap()
                values.filterKeys { it.matches(Regex("file[0-9]+")) }.forEach { (key,url) ->
                    add(url, values["title" + key.removePrefix("file")].orEmpty(), explicit = true)
                }
            }
            trimmed.startsWith("[") && mime.contains("json") -> {
                // Compatible with Radio Browser station API responses.
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    val codec = o.optString("codec").trim().lowercase(Locale.ROOT)
                    val type = if (o.optInt("hls", 0) == 1) "audio/mpegurl" else if (codec in setOf("mp3","aac","aac+","ogg","opus","flac","wav")) "audio/$codec" else ""
                    add(url, o.optString("name"), type, explicit = true)
                }
            }
            mime.contains("xml") || trimmed.startsWith("<?xml") || trimmed.startsWith("<rss") || trimmed.startsWith("<feed") -> {
                val doc = Jsoup.parse(content, base, org.jsoup.parser.Parser.xmlParser())
                for (entry in doc.select("item, entry")) {
                    val title = entry.selectFirst("title")?.text().orEmpty()
                    for (el in entry.select("enclosure, link[rel=enclosure], media|content")) {
                        val t = el.attr("type")
                        add(el.attr("url").ifBlank { el.attr("href") }, title, t,
                            t.isBlank() && (el.tagName() == "enclosure" || el.attr("rel") == "enclosure"))
                    }
                }
            }
            else -> {
                val doc = htmlDoc ?: Jsoup.parse(content, base)
                for (el in doc.select("audio[src], video[src], audio source[src], video source[src], a[href], meta[content], [data-stream-url], [data-audio-url]")) {
                    if (el.hasAttr("data-stream-url") || el.hasAttr("data-audio-url")) {
                        val address = el.attr("data-stream-url").ifBlank { el.attr("data-audio-url") }
                        add(address, el.attr("title").ifBlank { el.text() }, explicit = true)
                    }
                    when (el.tagName()) {
                        "meta" -> {
                            val property = el.attr("property")
                            if (property in setOf("og:audio","og:audio:url","og:audio:secure_url","og:video","og:video:url","og:video:secure_url"))
                                add(el.attr("content"), doc.title(), explicit = property.startsWith("og:audio"))
                        }
                        "a" -> {
                            val href = el.attr("href")
                            val type = el.attr("type")
                            val htmlAttachment = el.hasAttr("download") &&
                                absolute(base, href)?.let { ext(it) in setOf("html", "htm", "xhtml") } == true
                            add(href, el.text().ifBlank { el.attr("title") }, type, htmlAttachment)
                        }
                        "audio", "video", "source" -> {
                            val owner = el.closest("audio, video") ?: el
                            val title = el.attr("title").ifBlank { owner.attr("title") }.ifBlank { owner.attr("aria-label") }.ifBlank { doc.title() }
                            add(el.attr("src"), title, el.attr("type"), true)
                        }
                    }
                }
            }
        }
        return items.values.toList()
    }
}
