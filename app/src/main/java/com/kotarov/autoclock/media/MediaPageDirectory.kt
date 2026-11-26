package com.kotarov.autoclock.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared by phone and Auto; only a user browse triggers network. Main-thread callbacks. */
internal object MediaPageDirectory {
    data class Hit(val parent: StreamLibrary.Station, val item: MediaPageParser.Item)
    private data class Cached(val parent: StreamLibrary.Station, val items: List<MediaPageParser.Item>)
    private val cache = LinkedHashMap<String, Cached>()
    private val pending = HashMap<String, MutableList<(Result<List<MediaPageParser.Item>>) -> Unit>>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(4))

    fun find(id: String): Hit? = cache.values.firstNotNullOfOrNull { cached ->
        cached.items.firstOrNull { it.id == id }?.let { Hit(cached.parent, it) }
    }

    // Automatic advancement uses the same ordered snapshot the user opened.
    // Explicit opening still refreshes, and a process restart can rebuild it.
    fun folderSnapshot(page: StreamLibrary.Station): List<MediaPageParser.Item>? =
        if (page.localFolder) cache[page.id]?.takeIf { it.parent.url == page.url }?.items else null

    fun load(context: Context, page: StreamLibrary.Station, done: (Result<List<MediaPageParser.Item>>) -> Unit) {
        val app = context.applicationContext
        val key = page.id + "|" + page.url
        pending[key]?.let { it.add(done); return }
        pending[key] = mutableListOf(done)
        try {
            worker.execute {
                val result = runCatching { if (page.localFolder) LocalMediaFolder.list(app, page) else MediaPageParser.fetch(page.id, page.url) }
                handler.post {
                    result.getOrNull()?.let { items ->
                        cache.remove(page.id)
                        cache[page.id] = Cached(page, items)
                        while (cache.size > 12) cache.remove(cache.keys.first())
                    }
                    pending.remove(key).orEmpty().forEach { it(result) }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pending.remove(key).orEmpty().forEach { it(Result.failure(e)) }
        }
    }
}
