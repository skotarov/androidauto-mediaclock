package com.kotarov.autoclock.media

import android.content.ContentResolver
import android.net.Uri

/** Do not infer existence from a saved URI, or delete entries when a card is removed. */
internal object DownloadAvailability {
    fun find(resolver: ContentResolver, stations: List<StreamLibrary.Station>, url: String): StreamLibrary.Station? =
        stations.firstOrNull { (it.downloadSourceUrl == url || it.articleUrl == url) && available(resolver, it) }

    fun byUrl(resolver: ContentResolver, stations: List<StreamLibrary.Station>): Map<String, StreamLibrary.Station> =
        stations.filter { (it.downloadSourceUrl != null || it.articleUrl != null) && available(resolver, it) }
            .associateBy { requireNotNull(it.downloadSourceUrl ?: it.articleUrl) }

    fun available(resolver: ContentResolver, station: StreamLibrary.Station): Boolean {
        if (!station.local || (station.downloadSourceUrl == null && station.articleUrl == null && !WebTextProvider.owns(station.url))) return false
        return runCatching {
            resolver.openFileDescriptor(Uri.parse(station.url), "r")?.use {
                it.statSize != 0L
            } ?: false
        }.getOrDefault(false)
    }
}
