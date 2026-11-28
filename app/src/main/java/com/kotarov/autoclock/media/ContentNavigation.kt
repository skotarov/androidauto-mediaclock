package com.kotarov.autoclock.media

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import java.net.URI
import java.util.Locale

/** Virtual folders never become independent root library entries. */
internal object ContentNavigation {
    private const val PREFIX = "nav:"
    fun rootId(id: String): String = if (id.startsWith(PREFIX)) id.removePrefix(PREFIX).substringBefore('|') else id

    private fun encode(url: String): String = Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(id: String): String? = runCatching {
        require(id.startsWith(PREFIX) && id.length <= 12000 && '|' in id)
        String(Base64.decode(id.substringAfter('|'), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrNull()

    fun id(root: String, url: String): String = PREFIX + rootId(root) + "|" + encode(url)

    fun sameSite(a: String, b: String): Boolean = runCatching {
        fun host(s: String) = URI(s).host.lowercase(Locale.ROOT).removePrefix("www.")
        WebReader.valid(b) && host(a) == host(b)
    }.getOrDefault(false)

    fun resolve(prefs: SharedPreferences, id: String, title: String? = null): StreamLibrary.Station? {
        val root = StreamLibrary.list(prefs).firstOrNull { it.id == rootId(id) && it.browsable } ?: return null
        if (root.id == id) return root
        val url = decode(id) ?: return null
        if (root.localFolder) {
            val allowed = runCatching {
                val tree = Uri.parse(root.url)
                val child = Uri.parse(url)
                DocumentsContract.isTreeUri(child) && child.authority == tree.authority &&
                    DocumentsContract.getTreeDocumentId(child) == DocumentsContract.getTreeDocumentId(tree)
            }.getOrDefault(false)
            if (!allowed) return null
            return root.copy(id = id, name = title ?: Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('/'), url = url)
        }
        if (!sameSite(root.url, url)) return null
        return root.copy(id = id, name = title ?: StreamLibrary.defaultName(url), url = url)
    }

    fun child(prefs: SharedPreferences, parent: StreamLibrary.Station, item: MediaPageParser.Item): StreamLibrary.Station? =
        if (item.browsable) resolve(prefs, id(parent.id, item.url), item.name) else null
}
