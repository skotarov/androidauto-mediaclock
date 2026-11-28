package com.kotarov.autoclock.media

import android.content.ContentResolver
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/** Explicit finite media download to a user-created SAF document, not an app cache. */
internal object MediaDownload {
    data class Spec(val url: String, val name: String, val mime: String, val length: Long, val replaceId: String? = null)

    private val mimeByExtension = mapOf(
        "mp3" to "audio/mpeg", "mp2" to "audio/mpeg", "m4a" to "audio/mp4",
        "m4b" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
        "flac" to "audio/flac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "opus" to "audio/opus", "wma" to "audio/x-ms-wma", "aiff" to "audio/aiff",
        "amr" to "audio/amr", "mp4" to "video/mp4", "m4v" to "video/mp4",
        "webm" to "video/webm", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo",
        "mov" to "video/quicktime", "3gp" to "video/3gpp"
    )
    private val preferredExtension = mapOf(
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a", "audio/aac" to "aac", "audio/aacp" to "aac",
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav",
        "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/ogg" to "ogg",
        "application/ogg" to "ogg", "audio/opus" to "opus", "audio/x-ms-wma" to "wma",
        "audio/aiff" to "aiff", "audio/x-aiff" to "aiff", "audio/amr" to "amr",
        "video/mp4" to "mp4", "video/webm" to "webm", "audio/webm" to "webm",
        "video/x-matroska" to "mkv", "video/x-msvideo" to "avi",
        "video/quicktime" to "mov", "video/3gpp" to "3gp"
    )
    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun urlExtension(url: String): String =
        runCatching { extension(URI(url).path.orEmpty().substringAfterLast('/')) }.getOrDefault("")

    // Streams with a playlist extension are not individual downloadable media files.
    fun offer(url: String): Boolean = CustomStreamPlayer.valid(url) &&
        urlExtension(url) !in setOf("m3u", "m3u8", "pls", "asx", "xspf", "mpd")

    internal fun describe(url: String, name: String, type: String?, length: Long, icy: Boolean): Spec {
        require(offer(url) && !icy && length > 0) { "Only finite audio or video files can be downloaded." }
        val mime = type.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        val ext = urlExtension(url)
        val actual = when {
            mime in preferredExtension -> mime
            mime in setOf("", "application/octet-stream", "binary/octet-stream") -> mimeByExtension[ext]
            else -> null
        } ?: throw IllegalArgumentException("The link is not a supported downloadable media file.")
        val fileExt = ext.takeIf { mimeByExtension[it] == actual ||
            (it == "m4b" && actual in setOf("audio/mp4", "audio/x-m4a")) }
            ?: preferredExtension[actual] ?: error("Unknown media format")
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
            .take(110).ifBlank { "Media" }
        val filename = if (extension(clean) == fileExt) clean else
            (if (extension(clean) in mimeByExtension) clean.substringBeforeLast('.') else clean) + "." + fileExt
        return Spec(url, filename, actual, length)
    }

    private fun <T> connect(address: String, work: (HttpURLConnection, String) -> T): T {
        require(offer(address))
        var url = URI(address)
        repeat(5) {
            require(!Thread.currentThread().isInterrupted) { "Download cancelled" }
            require(offer(url.toString()))
            val c = url.toURL().openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 12_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "TravelToAwaken/1.0")
                c.setRequestProperty("Accept-Encoding", "identity")
                val status = c.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val next = url.resolve(c.getHeaderField("Location") ?: error("Missing redirect"))
                    require(!url.scheme.equals("https", true) || next.scheme.equals("https", true))
                    url = next
                    return@repeat
                }
                require(status == 200) { "Media unavailable (HTTP $status)" }
                return work(c, url.toString())
            } finally { c.disconnect() }
        }
        error("Too many redirects")
    }

    private fun describeResponse(c: HttpURLConnection, url: String, name: String): Spec =
        describe(url, name, c.contentType,
            c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1,
            c.getHeaderField("icy-name") != null || c.getHeaderField("icy-metaint") != null ||
                c.getHeaderField("icy-br") != null)

    // Prefer the actual filename from the direct link; use the library title
    // for extensionless streaming endpoints that resolve to finite media.
    private fun originalName(url: String, fallback: String): String {
        val candidate = runCatching { URI(url).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
        return candidate.takeIf { extension(it) in mimeByExtension } ?: fallback
    }

    // Read headers only before creating the destination document.
    fun inspect(address: String, name: String): Spec =
        connect(address) { c, url ->
            describeResponse(c, url, originalName(url, originalName(address, name))).copy(url = address)
        }

    fun save(resolver: ContentResolver, destination: Uri, spec: Spec,
             cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        connect(spec.url) { c, url ->
            val response = describeResponse(c, url, spec.name)
            require(response.mime == spec.mime) { "Media format changed" }
            require(c.getHeaderField("Content-Encoding").orEmpty().let { it.isBlank() || it.equals("identity", true) })
            val length = response.length
            var copied = 0L
            var lastUpdate = 0L
            c.inputStream.use { input ->
                requireNotNull(resolver.openOutputStream(destination, "w")) { "Cannot write to selected location" }.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        require(!cancelled() && !Thread.currentThread().isInterrupted) { "Download cancelled" }
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        require(copied <= length) { "Unexpected media length" }
                        output.write(buffer, 0, n)
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 250_000_000L) {
                            lastUpdate = now
                            progress(copied, length)
                        }
                    }
                    require(copied == length && !cancelled()) { "Incomplete download" }
                    output.flush()
                }
            }
            progress(copied, length)
        }
    }
}
