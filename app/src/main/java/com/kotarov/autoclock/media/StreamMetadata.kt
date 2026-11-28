package com.kotarov.autoclock.media

import java.net.URI
import java.net.HttpURLConnection

/** One bounded name lookup only for an explicitly saved unnamed stream. No remote artwork. */
object StreamMetadata {
    data class Info(val name: String?, val icon: String = "")
    fun lookup(url: String): Info {
        if (!CustomStreamPlayer.valid(url)) return Info(null)
        val name = runCatching {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Icy-MetaData", "1")
                c.setRequestProperty("User-Agent", "SmartTripClock/1.0")
                if (c.responseCode in 200..299) c.getHeaderField("icy-name")?.trim()?.take(64)?.takeIf { it.isNotBlank() && '\uFFFD' !in it }
                else null
            } finally { c.disconnect() }
        }.getOrNull()
        return Info(name)
    }
}
