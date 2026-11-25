package com.kotarov.autoclock.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Best-effort discovery, invoked only on explicit add/edit, never during playback. */
object StreamMetadata {
    data class Info(val name: String?, val icon: String)
    private fun connection(url: String): HttpsURLConnection =
        (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            instanceFollowRedirects = false
            setRequestProperty("Icy-MetaData", "1")
            setRequestProperty("User-Agent", "AutoClock/0.85")
        }
    fun lookup(url: String): Info {
        var name: String? = null
        var iconUrl: String? = null
        runCatching {
            val c = connection(url)
            try {
                if (c.responseCode in 200..299) {
                    name = c.getHeaderField("icy-name")?.trim()?.take(64)?.takeIf { it.isNotBlank() }
                    iconUrl = c.getHeaderField("icy-logo") ?: c.getHeaderField("icy-icon")
                }
            } finally { c.disconnect() }
        }
        val origin = URI(url)
        val candidate = runCatching {
            val u = origin.resolve(iconUrl ?: "/favicon.ico")
            // Do not contact unrelated sites automatically or disclose a stream token.
            if (u.scheme == "https" && u.host.equals(origin.host, true) && u.userInfo == null)
                u.toString() else null
        }.getOrNull()
        val icon = candidate?.let { fetchIcon(it) }.orEmpty()
        return Info(name, icon)
    }
    private fun fetchIcon(url: String): String = runCatching {
        val c = connection(url)
        try {
            if (c.responseCode !in 200..299) return@runCatching ""
            val bytes = c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (out.size() <= 256 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (out.size() > 256 * 1024) return@runCatching ""
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return@runCatching ""
            val opts = BitmapFactory.Options().apply {
                inSampleSize = maxOf(1, Integer.highestOneBit(maxOf(bounds.outWidth, bounds.outHeight) / 96))
            }
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching ""
            val small = Bitmap.createScaledBitmap(original, 64, 64, true)
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (small !== original) small.recycle()
            original.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally { c.disconnect() }
    }.getOrDefault("")
}
