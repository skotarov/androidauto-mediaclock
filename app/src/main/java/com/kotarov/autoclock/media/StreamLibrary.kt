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
    data class Station(val id: String, val name: String, val url: String, val icon: String = "") {
        fun bitmap(): Bitmap? = runCatching {
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
    fun list(prefs: SharedPreferences): List<Station> = runCatching {
        val a = JSONArray(prefs.getString(KEY_LIST, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!CustomStreamPlayer.valid(url)) null else Station(o.getString("id"),
                o.optString("name").ifBlank { defaultName(url) }, url, o.optString("icon"))
        }
    }.getOrDefault(emptyList())
    fun selected(prefs: SharedPreferences): Station? =
        list(prefs).firstOrNull { it.id == prefs.getString(KEY_SELECTED, ALERT_ID) }
    fun save(prefs: SharedPreferences, stations: List<Station>) {
        val a = JSONArray()
        stations.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("icon", s.icon))
        }
        val edit = prefs.edit().putString(KEY_LIST, a.toString())
        if (stations.none { it.id == prefs.getString(KEY_SELECTED, ALERT_ID) })
            edit.putString(KEY_SELECTED, ALERT_ID)
        edit.apply()
    }
    fun newStation(url: String) = Station("stream:" + UUID.randomUUID().toString(), defaultName(url), url)
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
