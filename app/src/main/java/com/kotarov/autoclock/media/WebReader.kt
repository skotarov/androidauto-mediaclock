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

    // Match whole CSS tokens, including camelCase and BEM names, not arbitrary
    // substrings such as "ad" in "reading" or "share" in an article title.
    private val camelCase = Regex("([a-z0-9])([A-Z])")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val noiseTokens = setOf("ad", "ads", "advert", "adverts", "advertisement",
        "advertising", "adslot", "adblock", "adsense", "sponsor", "sponsored",
        "sidebar", "related", "recommendation", "recommendations", "recommended",
        "recirculation", "outbrain", "taboola", "comment", "comments", "commentlist",
        "disqus", "cookie", "cookies", "consent", "newsletter", "subscribe",
        "subscription", "social", "share", "sharing", "breadcrumb", "breadcrumbs",
        "menu", "navigation", "navbar", "pagination", "popup", "modal", "paywall",
        "toolbar", "footer", "masthead", "widget", "widgets", "promo", "promoted",
        "teaser", "teasers", "trending", "relatedposts", "readmore", "mostread",
        "login", "signin", "signup", "byline", "authorbox", "bio", "caption")
    private val strongContent = Regex("(?i)(article|post|entry|story|news)[-_ ]?(body|content|text)|articleBody|text[-_ ]?content|rich[-_ ]?text|prose")
    private val boilerplate = Regex("(?iu)^(?:read (?:more|also|next)|related (?:articles|posts|stories)|recommended (?:for you|reading)|you (?:may|might) also like|advertisement|sponsored content|share (?:this|on)|subscribe(?: to (?:our|the) newsletter)?|sign up(?: for .{1,60})?|accept (?:all )?cookies|all rights reserved|прочетете (?:още|също)|вижте (?:още|също)|още по темата|още от|свързани (?:статии|публикации)|препоръчано за вас|реклама|споделете(?: в .{1,30})?|абонирайте се|всички права запазени)(?:[\\s:!….-]*|[:：].*)$")
    private val leafSelector = "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td"
    private val structural = setOf("article", "main", "section", "div", "body")
    private val proseTags = setOf("p", "blockquote", "pre", "div", "section")
    private data class Block(val element: Element, val text: String, val linked: Double, val prose: Boolean)

    private fun linkDensity(el: Element, length: Int): Double =
        if (length == 0) 0.0 else el.select("a").sumOf { it.text().length }.toDouble() / length

    private fun isNoise(el: Element): Boolean {
        val raw = el.id() + " " + el.className() + " " + el.attr("data-testid") + " " + el.attr("data-component")
        val tokens = separators.split(camelCase.replace(raw, "$1 $2").lowercase(Locale.ROOT))
        return tokens.any { it in noiseTokens } ||
            (tokens.contains("read") && tokens.contains("more")) ||
            (tokens.contains("most") && tokens.contains("read")) ||
            (tokens.contains("author") && tokens.contains("box"))
    }

    private fun blocks(root: Element): List<Block> {
        val elements = root.select("$leafSelector,div,section").toList()
        return elements.mapNotNull { el ->
            val tag = el.tagName()
            // Container text must not duplicate its nested paragraphs/lists.
            if (el.select(leafSelector).any { it !== el }) return@mapNotNull null
            if (tag in setOf("div", "section") &&
                el.children().any { it.tagName() in structural }) return@mapNotNull null
            val value = el.text().trim()
            if (value.isBlank() || boilerplate.matches(value)) return@mapNotNull null
            val density = linkDensity(el, value.length)
            if (density >= 0.55) return@mapNotNull null
            if (tag in setOf("div", "section") && value.length < 65) return@mapNotNull null
            val prose = tag in proseTags && value.length >= 45 &&
                value.count { it.isLetter() } >= 25 && density < 0.30
            Block(el, value, density, prose)
        }
    }

    internal fun extract(doc: org.jsoup.nodes.Document, fallback: String): Page {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("article h1, main h1, h1")?.text().orEmpty() }
            .ifBlank { doc.title() }.ifBlank { fallback }.take(128)
        doc.select("script,style,noscript,template,svg,iframe,object,nav,aside,footer,form,button,input,select,textarea,dialog,figcaption,[hidden],[inert],[aria-hidden=true],[role=navigation],[role=banner],[role=complementary],[role=contentinfo],[role=dialog],[role=menu],[role=menubar],[role=search],[role=advertisement],[data-ad-slot],[data-ad-client]").remove()
        doc.select("header").toList().filter { it.closest("article") == null }.forEach { it.remove() }
        doc.select("[style]").toList().filter {
            val style = it.attr("style").replace(Regex("\\s+"), "").lowercase(Locale.ROOT)
            style.contains("display:none") || style.contains("visibility:hidden")
        }.forEach { it.remove() }
        doc.select("[class],[id],[data-testid],[data-component]").toList().filter {
            it.tagName() !in setOf("html", "body", "main", "article") && isNoise(it)
        }.forEach { it.remove() }
        val body = doc.body()

        // Reject unmarked link-heavy cards, menus and recirculation modules as
        // units, not just individual anchors. Do not remove the article wrapper.
        body.select("ul,ol,div,section").toList().filter { el ->
            if (el.select("article,main,[itemprop=articleBody]").isNotEmpty()) return@filter false
            val value = el.text()
            value.isNotBlank() && el.select("a").size >= 2 &&
                linkDensity(el, value.length) >= 0.55
        }.forEach { it.remove() }

        val all = blocks(body)
        val candidates = linkedSetOf<Element>()
        body.select("[itemprop=articleBody],article,main,[role=main],[class],[id]").take(900).forEach {
            if (it.tagName() in structural && (it.tagName() in setOf("article", "main") ||
                it.attr("role") == "main" || it.attr("itemprop") == "articleBody" ||
                strongContent.containsMatchIn(it.id() + " " + it.className()))) candidates.add(it)
        }
        // Paragraph-driven ancestor discovery also works on older sites
        // without semantic tags or recognizable CSS class names.
        all.filter { it.prose }.take(300).forEach { block ->
            var node = block.element.parent()
            var depth = 0
            while (node != null && node !== body && depth++ < 5) {
                if (node.tagName() in structural) candidates.add(node)
                node = node.parent()
            }
        }
        candidates.add(body)

        // Aggregate paragraph counts once instead of repeatedly walking all
        // paragraph ancestors for every possible container.
        data class Stats(var chars: Int = 0, var count: Int = 0)
        val stats = java.util.IdentityHashMap<Element, Stats>()
        all.filter { it.prose }.forEach { block ->
            var node: Element? = block.element
            while (node != null) {
                if (node in candidates) {
                    val entry = stats.getOrPut(node) { Stats() }
                    entry.chars += block.text.length
                    entry.count++
                }
                if (node === body) break
                node = node.parent()
            }
        }
        data class Candidate(val el: Element, val proseChars: Int, val score: Double)
        fun candidate(el: Element): Candidate {
            val stat = stats[el] ?: return Candidate(el, 0, 0.0)
            val chars = stat.chars
            if (chars < 80) return Candidate(el, chars, 0.0)
            val total = el.text().length.coerceAtLeast(chars)
            val purity = chars.toDouble() / total
            val links = linkDensity(el, total).coerceIn(0.0, 1.0)
            val bonus = when {
                el.attr("itemprop") == "articleBody" -> 3.2
                strongContent.containsMatchIn(el.id() + " " + el.className()) -> 2.5
                el.tagName() == "article" -> 2.2
                el.tagName() == "main" || el.attr("role") == "main" -> 1.25
                el === body -> 0.50
                else -> 1.0
            }
            return Candidate(el, chars, min(chars, 100000) * bonus *
                (0.25 + 0.75 * purity) * (1.0 - links) * (1.0 + min(stat.count, 12) / 30.0))
        }
        val scored = candidates.take(1000).map(::candidate).filter { it.proseChars >= 80 && it.score > 0 }
        val best = scored.maxByOrNull { it.score }
            ?: throw IllegalArgumentException("No readable article")
        // Prefer a sufficiently complete, explicit article body over a broad
        // main/body wrapper containing additional article cards.
        val explicit = scored.filter { it.el.attr("itemprop") == "articleBody" ||
            it.el.tagName() == "article" || strongContent.containsMatchIn(it.el.id() + " " + it.el.className()) }
            .maxByOrNull { it.score }
        val content = if (explicit != null && explicit.proseChars >= best.proseChars * 0.30 &&
            explicit.score >= best.score * 0.45) explicit.el else best.el

        val chosen = all.filter { b -> b.element === content || b.element.parents().any { it === content } }
        // Ignore heading/list fragments before the first or after the last
        // paragraph unless they are meaningful unlinked article blocks.
        val first = chosen.indexOfFirst { it.prose }
        val last = chosen.indexOfLast { it.prose }
        val seen = hashSetOf<String>()
        val lines = chosen.mapIndexedNotNull { index, b ->
            val tag = b.element.tagName()
            val heading = tag.matches(Regex("h[1-6]"))
            if (heading && b.text.equals(title, ignoreCase = true)) return@mapIndexedNotNull null
            if ((index < first || index > last) && heading) return@mapIndexedNotNull null
            if ((index < first || index > last) && b.linked >= 0.30) return@mapIndexedNotNull null
            if ((index < first || index > last) && tag in setOf("li", "td") && b.linked > 0.0) return@mapIndexedNotNull null
            if (b.text.length < 4 || !seen.add(b.text)) return@mapIndexedNotNull null
            b.text
        }
        val bodyText = lines.joinToString("\n\n")
        // Never fall back to body.text(): if extraction fails, reject instead
        // of importing the entire site's navigation and promotional text.
        require(bodyText.length >= 80 && bodyText.length <= MAX_CHARS) { "No readable article or article too long" }
        val text = if (bodyText.startsWith(title, ignoreCase = true)) bodyText else "$title\n\n$bodyText"
        return Page(title, text)
    }
}
