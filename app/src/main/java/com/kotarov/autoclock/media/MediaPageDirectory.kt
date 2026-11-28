package com.kotarov.autoclock.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
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
        if (page.localFolder) cache[page.id]?.takeIf { it.parent.url == page.url }?.items?.filterNot { it.browsable } else null

    fun nextPage(parent: StreamLibrary.Station): MediaPageParser.Item? =
        cache[parent.id]?.items?.firstOrNull { it.browsable && it.next }

    fun cachedPage(context: Context, id: String): Boolean =
        context.getSharedPreferences("offline_page_index_v163", Context.MODE_PRIVATE).contains(id)

    fun linkedRoots(context: Context, url: String): Set<String> {
        val prefs = context.getSharedPreferences("offline_page_index_v163", Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            val matched = runCatching {
                val a = JSONObject(value as String).getJSONArray("items")
                (0 until a.length()).any { a.getJSONObject(it).optString("url") == url }
            }.getOrDefault(false)
            if (matched) ContentNavigation.rootId(key) else null
        }.toSet()
    }

    private fun saved(context: Context, page: StreamLibrary.Station): List<MediaPageParser.Item> = runCatching {
        val prefs = context.getSharedPreferences("offline_page_index_v163", Context.MODE_PRIVATE)
        val root = JSONObject(prefs.getString(page.id, "{}").orEmpty())
        if (root.optString("url") != page.url) return@runCatching emptyList()
        val a = root.getJSONArray("items")
        val downloads = DownloadAvailability.byUrl(context.contentResolver,
            StreamLibrary.list(context.getSharedPreferences(AutoClockMediaService.PREFERENCES, Context.MODE_PRIVATE)))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val url = o.getString("url")
            val browsable = o.optBoolean("browsable", false)
            if (url !in downloads && !browsable) null else MediaPageParser.Item(
                o.getString("id"), o.getString("name"), url, o.getString("format"),
                o.optBoolean("textFile", false), browsable, o.optBoolean("next", false))
        }
    }.getOrDefault(emptyList())

    private fun remember(context: Context, page: StreamLibrary.Station, items: List<MediaPageParser.Item>) {
        val a = JSONArray()
        items.filter { it.browsable || MediaDownload.offer(it.url) }.take(1000).forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("format", it.format).put("textFile", it.textFile).put("browsable", it.browsable).put("next", it.next))
        }
        context.getSharedPreferences("offline_page_index_v163", Context.MODE_PRIVATE).edit()
            .putString(page.id, JSONObject().put("url", page.url).put("items", a).toString()).apply()
    }

    fun forget(context: Context, id: String) {
        val prefs = context.getSharedPreferences("offline_page_index_v163", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (ContentNavigation.rootId(id) == id) {
            prefs.all.keys.filter { ContentNavigation.rootId(it) == id }.forEach { edit.remove(it) }
            cache.keys.filter { ContentNavigation.rootId(it) == id }.toList().forEach { cache.remove(it) }
        } else {
            edit.remove(id)
            cache.remove(id)
        }
        edit.apply()
    }

    fun load(context: Context, page: StreamLibrary.Station, done: (Result<List<MediaPageParser.Item>>) -> Unit) {
        val app = context.applicationContext
        val key = page.id + "|" + page.url
        pending[key]?.let { it.add(done); return }
        pending[key] = mutableListOf(done)
        try {
            worker.execute {
                val result = runCatching {
                    if (page.localFolder) LocalMediaFolder.list(app, page)
                    else {
                        val fresh = runCatching { MediaPageParser.fetch(page.id, page.url) }.getOrNull()
                        if (!fresh.isNullOrEmpty()) {
                            runCatching { remember(app, page, fresh) }
                            fresh
                        } else saved(app, page)
                    }
                }
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
