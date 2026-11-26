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
    private const val MAX_ITEMS = 100
    private val extensions = setOf("mp3","aac","m4a","mp4","m4v","ogg","oga","opus","wav","flac","webm","m3u8","ts","3gp")
    private fun ext(url: String) = runCatching { URI(url).path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT) }.getOrDefault("")
    private fun allowed(value: String): Boolean = runCatching {
        val u = URI(value)
        (u.scheme.equals("https", true) || (u.scheme.equals("http", true) && u.rawQuery == null)) &&
            !u.host.isNullOrBlank() && u.userInfo == null && u.fragment == null && value.length <= 4096
    }.getOrDefault(false)
    private fun absolute(base: String, value: String): String? = runCatching {
        URI(base).resolve(value.trim()).toString().takeIf { allowed(it) }
    }.getOrNull()
    fun fetch(parent: String, address: String): List<Item> {
        require(WebReader.valid(address))
        var uri = URI(address)
        val deadline = System.nanoTime() + 30_000_000_000L
        repeat(5) {
            require(WebReader.valid(uri.toString()) && System.nanoTime() < deadline)
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
                if (mime.startsWith("audio/") && !mime.contains("mpegurl") && !mime.contains("scpls") || mime.startsWith("video/")) {
                    return parse(parent, uri.toString(), "", "direct:" + mime)
                }
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
                    return parse(parent, uri.toString(), "", mime, doc)
                }
                val text = RadioText.decode(bytes, charset, mime.contains("json"))
                return parse(parent, uri.toString(), text, mime)
            } finally { conn.disconnect() }
        }
        error("Too many redirects")
    }
    internal fun parse(parent: String, base: String, content: String, mime: String = "text/html", htmlDoc: org.jsoup.nodes.Document? = null): List<Item> {
        val items = LinkedHashMap<String, Item>()
        fun add(raw: String, name: String, type: String = "", explicit: Boolean = false) {
            if (items.size >= MAX_ITEMS || raw.isBlank()) return
            val url = absolute(base, raw) ?: return
            if (items.containsKey(url)) return
            val e = ext(url)
            val t = type.substringBefore(';').lowercase(Locale.ROOT)
            if (!explicit && e !in extensions && !t.startsWith("audio/") && !t.startsWith("video/")) return
            if (e in setOf("m3u","pls") && !t.contains("mpegurl")) return
            val label = RadioText.cleanName(name, url).trim().replace(Regex("\\s+"), " ").take(128).ifBlank {
                runCatching { URI(url).path.orEmpty().substringAfterLast('/').ifBlank { URI(url).host } }.getOrDefault("Media").take(128)
            }
            val hash = MessageDigest.getInstance("SHA-256").digest((parent + "|" + url).toByteArray())
                .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
            val format = if (e in extensions) e.uppercase(Locale.ROOT) else if (t.contains("mpegurl")) "M3U8" else t.substringAfter('/', "").uppercase(Locale.ROOT).ifBlank { "STREAM" }
            items.putIfAbsent(url, Item("webmedia:" + hash, label, url, format))
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
                    if (items.size >= MAX_ITEMS) break
                    val o = array.optJSONObject(i) ?: continue
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    if (o.has("lastcheckok") && o.optInt("lastcheckok", 1) == 0) continue
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
                        "a" -> add(el.attr("href"), el.text().ifBlank { el.attr("title") }, el.attr("type"))
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
