package com.kotarov.autoclock.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

internal object CacheOwnership {
    fun original(s: StreamLibrary.Station): String? = s.downloadSourceUrl ?: s.articleUrl

    fun ownsCopy(s: StreamLibrary.Station): Boolean = s.local &&
        (s.downloadSourceUrl != null || WebTextProvider.owns(s.url))

    fun removeCopy(context: Context, s: StreamLibrary.Station): Boolean {
        if (!ownsCopy(s)) return false
        BookDiskCache.removeSource(context, s.url)
        val removed = if (WebTextProvider.owns(s.url)) {
            WebTextProvider.delete(context, s.url)
            !DownloadAvailability.available(context.contentResolver, s)
        } else runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(s.url))
        }.getOrDefault(false)
        if (removed) {
            val prefs = context.getSharedPreferences(AutoClockMediaService.PREFERENCES, Context.MODE_PRIVATE)
            prefs.edit().remove(PlaybackSpeed.sourceKey(s.url)).apply()
            context.getSharedPreferences("text_bookmarks", Context.MODE_PRIVATE).edit().remove(s.url)
                .remove("pdf_backend:" + s.url).apply()
            context.getSharedPreferences("audio_bookmarks", Context.MODE_PRIVATE).edit().remove(s.url).apply()
        }
        return removed
    }

    fun roots(context: Context, s: StreamLibrary.Station, entries: List<StreamLibrary.Station>): Set<String> {
        val existing = entries.filter { it.browsable }.map { it.id }.toSet()
        return (s.cacheOwners + (original(s)?.let { MediaPageDirectory.linkedRoots(context, it) } ?: emptySet()))
            .intersect(existing)
    }

    fun claim(context: Context, url: String, root: String?) {
        val prefs = context.getSharedPreferences(AutoClockMediaService.PREFERENCES, Context.MODE_PRIVATE)
        val entries = StreamLibrary.list(prefs)
        val station = DownloadAvailability.find(context.contentResolver, entries, url) ?: return
        val updated = if (root == null) station.copy(independent = true)
            else station.copy(cacheOwners = station.cacheOwners + ContentNavigation.rootId(root))
        if (station != updated) StreamLibrary.save(prefs, entries.map { if (it.id == station.id) updated else it })
    }
}
