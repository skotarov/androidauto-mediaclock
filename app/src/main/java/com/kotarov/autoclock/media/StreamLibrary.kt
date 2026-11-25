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
    const val ALERT_ID = "autoclock_clock"
    data class Station(val id: String, val name: String, val url: String, val icon: String = "", val local: Boolean = false,
        val textFile: Boolean = false, val language: String = "auto") {
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
                url, o.optString("icon"), local, local && o.optBoolean("textFile", false), o.optString("language", "auto"))
        }
        }.getOrDefault(emptyList())
        cachedRaw = raw
        return cachedList
    }
    fun selected(prefs: SharedPreferences): Station? =
        list(prefs).firstOrNull { it.id == prefs.getString(KEY_SELECTED, ALERT_ID) }
    fun save(prefs: SharedPreferences, stations: List<Station>) {
        val a = JSONArray()
        stations.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("icon", s.icon).put("local", s.local).put("textFile", s.textFile).put("language", s.language))
        }
        val edit = prefs.edit().putString(KEY_LIST, a.toString())
        if (stations.none { it.id == prefs.getString(KEY_SELECTED, ALERT_ID) })
            edit.putString(KEY_SELECTED, ALERT_ID)
        edit.apply()
    }
    fun newStation(url: String) = Station("stream:" + UUID.randomUUID().toString(), defaultName(url), url)
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
