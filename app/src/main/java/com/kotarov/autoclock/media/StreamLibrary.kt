package com.kotarov.autoclock.media

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

object StreamLibrary {
    const val KEY_LIST = "stream_library_v85"
    const val KEY_SELECTED = "stream_selected_v85"
    private const val KEY_CHILD = "stream_selected_web_child_v120"
    const val ALERT_ID = "autoclock_clock"
    data class Station(val id: String, val name: String, val url: String, val icon: String = "", val local: Boolean = false,
        val textFile: Boolean = false, val language: String = "auto", val mediaPage: Boolean = false) {
        fun bitmap(): Bitmap? = if (icon.isBlank()) null else runCatching {
            val bytes = Base64.decode(icon, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    fun defaultName(url: String): String = runCatching {
        val uri = URI(url)
        val host = uri.host.removePrefix("www.")
        val path = uri.path.substringAfterLast('/').substringBeforeLast('.')
        if (path.isNotBlank() && path.lowercase() !in listOf("stream", "live", "radio", "index"))
            path.replace('-', ' ').replace('_', ' ').take(48)
        else host.take(48)
    }.getOrDefault("Stream")
    private var cachedRaw: String? = null
    private var cachedList: List<Station> = emptyList()
    @Synchronized fun list(prefs: SharedPreferences): List<Station> {
        val raw = prefs.getString(KEY_LIST, "[]").orEmpty()
        if (raw == cachedRaw) return cachedList
        cachedList = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            val local = o.optBoolean("local", false)
            if (!(if (local) CustomStreamPlayer.validLocal(url) else CustomStreamPlayer.valid(url))) null
            else Station(o.getString("id"), o.optString("name").ifBlank { defaultName(url) },
                url, o.optString("icon"), local, local && o.optBoolean("textFile", false), o.optString("language", "auto"), !local && o.optBoolean("mediaPage", false))
        }
        }.getOrDefault(emptyList())
        cachedRaw = raw
        return cachedList
    }
    fun selected(prefs: SharedPreferences): Station? {
        val id = prefs.getString(KEY_SELECTED, ALERT_ID)
        list(prefs).firstOrNull { it.id == id && !it.mediaPage }?.let { return it }
        return selectedChild(prefs)?.second
    }
    private fun selectedChild(prefs: SharedPreferences): Pair<String, Station>? = runCatching {
        val o = JSONObject(prefs.getString(KEY_CHILD, "").orEmpty())
        val id = o.getString("id")
        if (id != prefs.getString(KEY_SELECTED, ALERT_ID)) return@runCatching null
        val folder = list(prefs).firstOrNull { it.mediaPage && it.id == o.getString("parent") &&
            it.url == o.getString("pageUrl") } ?: return@runCatching null
        val url = o.getString("url")
        if (!CustomStreamPlayer.valid(url)) return@runCatching null
        folder.id to Station(id, o.getString("name"), url)
    }.getOrNull()
    fun selectedRootId(prefs: SharedPreferences): String =
        selectedChild(prefs)?.first ?: prefs.getString(KEY_SELECTED, ALERT_ID).orEmpty()
    fun select(prefs: SharedPreferences, id: String?): Boolean {
        if (id == null) return false
        if (id == ALERT_ID || list(prefs).any { it.id == id && !it.mediaPage }) {
            prefs.edit().remove(KEY_CHILD).putString(KEY_SELECTED, id).apply()
            return true
        }
        val hit = MediaPageDirectory.find(id)
        if (hit != null && list(prefs).any { it.mediaPage && it.id == hit.parent.id && it.url == hit.parent.url }) {
            val item = hit.item
            val o = JSONObject().put("id", item.id).put("parent", hit.parent.id).put("pageUrl", hit.parent.url)
                .put("url", item.url).put("name", item.name)
            prefs.edit().putString(KEY_CHILD, o.toString()).putString(KEY_SELECTED, item.id).apply()
            return true
        }
        return selectedChild(prefs)?.second?.id == id
    }
    fun save(prefs: SharedPreferences, stations: List<Station>) {
        val a = JSONArray()
        stations.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("icon", s.icon).put("local", s.local).put("textFile", s.textFile).put("language", s.language).put("mediaPage", s.mediaPage))
        }
        val edit = prefs.edit().putString(KEY_LIST, a.toString())
        val child = selectedChild(prefs)
        val keepChild = child != null && stations.any { next ->
            next.mediaPage && next.id == child.first && list(prefs).any { old -> old.id == next.id && old.url == next.url }
        }
        if (!keepChild && stations.none { it.id == prefs.getString(KEY_SELECTED, ALERT_ID) && !it.mediaPage })
            edit.remove(KEY_CHILD).putString(KEY_SELECTED, ALERT_ID)
        edit.apply()
    }
    fun newStation(url: String) = Station("stream:" + UUID.randomUUID().toString(), defaultName(url), url)
    fun newMediaPage(url: String, name: String) = Station("page:" + UUID.randomUUID().toString(), name, url, mediaPage = true)
    fun newFile(uri: String, name: String, textFile: Boolean = false) = Station("file:" + UUID.randomUUID().toString(),
        name.take(128).ifBlank { "Local file" }, uri, local = true, textFile = textFile)
    fun move(stations: List<Station>, from: String, to: String, after: Boolean): List<Station> {
        if (from == to) return stations
        val source = stations.firstOrNull { it.id == from } ?: return stations
        val rest = stations.filterNot { it.id == from }.toMutableList()
        val target = rest.indexOfFirst { it.id == to }
        if (target < 0) return stations
        rest.add(target + if (after) 1 else 0, source)
        return rest
    }
    fun migrate(prefs: SharedPreferences) {
        if (prefs.contains(KEY_LIST)) return
        val url = prefs.getString(CustomStreamPlayer.KEY_URL, "").orEmpty()
        val station = if (CustomStreamPlayer.valid(url)) newStation(url) else null
        val stations = listOfNotNull(station)
        val a = JSONArray()
        stations.forEach { s -> a.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)) }
        prefs.edit().putString(KEY_LIST, a.toString())
            .putString(KEY_SELECTED, if (prefs.getBoolean(CustomStreamPlayer.KEY_ENABLED, false))
                station?.id ?: ALERT_ID else ALERT_ID)
            .remove(CustomStreamPlayer.KEY_ENABLED).remove(CustomStreamPlayer.KEY_URL).apply()
    }
}
