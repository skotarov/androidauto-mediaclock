package com.kotarov.autoclock.media

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlin.math.min

/** One bounded, explicit HTTPS download. No scripts, cookies, embedded resources or WebView. */
internal object WebReader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_CHARS = 500_000
    data class Page(val title: String, val text: String)

    fun valid(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme.equals("https", true) && !u.host.isNullOrBlank() &&
            u.userInfo == null && (u.port == -1 || u.port == 443) && value.length <= 4096
    }.getOrDefault(false)

    fun fetch(address: String): Page {
        require(valid(address))
        var url = URI(address)
        val deadline = System.nanoTime() + 30_000_000_000L
        fun check() {
            require(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) { "Import cancelled or timed out" }
        }
        repeat(5) {
            check()
            require(valid(url.toString()))
            val c = url.toURL().openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "SmartTripClock/1.0")
                c.setRequestProperty("Accept", "text/html, application/xhtml+xml")
                c.setRequestProperty("Accept-Encoding", "identity")
                val status = c.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = url.resolve(c.getHeaderField("Location") ?: error("Missing redirect"))
                    return@repeat
                }
                require(status in 200..299) { "Page unavailable" }
                val type = c.contentType.orEmpty()
                require(type.substringBefore(';').trim().lowercase(Locale.ROOT) in listOf("text/html", "application/xhtml+xml")) { "Not a webpage" }
                require((c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= MAX_BYTES)
                val bytes = c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= MAX_BYTES) { "Page too large" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val charset = Regex("(?i)charset\\s*=\\s*[\"']?([a-z0-9._-]+)").find(type)?.groupValues?.get(1)
                check()
                val doc = Jsoup.parse(bytes.inputStream(), charset, url.toString())
                return extract(doc, url.host)
            } finally { c.disconnect() }
        }
        error("Too many redirects")
    }

    internal fun extractHtml(html: String, fallback: String = "Web page"): Page =
        extract(Jsoup.parse(html), fallback)

    private val clutter = Regex("(?i)(^|[-_\\s])(ads?|advertisement|advertising|sidebar|related|recommendations?|comments?|commentlist|cookie|cookies|newsletter|subscribe|social|share|sharing|breadcrumb|breadcrumbs|menu|navigation|pagination|popup|modal|paywall|toolbar|footer|masthead)([-_\\s]|$)")
    internal fun extract(doc: org.jsoup.nodes.Document, fallback: String): Page {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("article h1, main h1, h1")?.text().orEmpty() }
            .ifBlank { doc.title() }.ifBlank { fallback }.take(128)
        doc.select("script, style, noscript, template, svg, iframe, object, nav, aside, footer, form, button, input, select, textarea, dialog, [hidden], [aria-hidden=true], [role=navigation], [role=banner], [role=complementary], [role=contentinfo]").remove()
        // Keep article headers (title/byline), but remove site-wide headers.
        doc.select("header").toList().filter { it.closest("article, main, [role=main]") == null }.forEach { it.remove() }
        doc.select("[class], [id]").toList().filter {
            it.tagName() !in setOf("html", "body", "main", "article") &&
                clutter.containsMatchIn(it.id() + " " + it.className())
        }.forEach { it.remove() }
        val body = doc.body()
        val candidates = body.select("article, main, [role=main], section, div").toList()
        fun score(el: Element): Double {
            val text = el.text()
            if (text.length < 80) return 0.0
            val linked = el.select("a").sumOf { it.text().length }.toDouble() / text.length
            val paragraphs = el.select("p").count { it.text().length >= 40 }
            val bonus = when {
                el.tagName() == "article" -> 1.8
                el.tagName() == "main" || el.attr("role") == "main" -> 1.5
                else -> 1.0
            }
            return min(text.length, 100000) * (1.0 - linked.coerceIn(0.0, 1.0)) *
                (1.0 + min(paragraphs, 20) / 20.0) * bonus
        }
        // Score bounded candidates only. Prefer a substantial semantic article to a site wrapper.
        val semantic = candidates.filter { it.tagName() in setOf("article", "main") || it.attr("role") == "main" }
            .take(100).maxByOrNull { score(it) }
        val content = semantic?.takeIf { score(it) >= 100 } ?:
            candidates.take(350).maxByOrNull { score(it) }?.takeIf { score(it) >= 100 } ?: body
        val blocks = content.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td").toList()
        val lines = blocks.filter { e -> e.select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td").none { it !== e } }
            .map { it.text().trim() }.filter { it.isNotBlank() }
        val bodyText = if (lines.sumOf { it.length } >= 80) lines.joinToString("\n\n") else content.text().trim()
        require(bodyText.length >= 80 && bodyText.length <= MAX_CHARS) { "No readable article or article too long" }
        val text = if (bodyText.startsWith(title, ignoreCase = true)) bodyText else "$title\n\n$bodyText"
        return Page(title, text)
    }
}
