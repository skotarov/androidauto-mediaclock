package com.kotarov.autoclock.weather

import android.content.Context
import android.location.Location
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One approximate location fix, then latest *actual observation* from
 * the nearest reporting aerodrome METAR station (NOAA Aviation Weather).
 * If no recent station is available, fall back to an explicitly labeled
 * Open-Meteo model at the approximate device location.
 *
 * No continuous GPS listener, wakelock or per-minute
 * weather requests. Requests are capped to once per 5 minutes in weather display.
 */
object WeatherStore {
    const val PREFS = "clock_settings"
    const val KEY_LOCALITY = "weather_locality_v47"
    const val KEY_WARNING = "weather_warning_v52"
    const val KEY_WARNING_REV = "weather_warning_revision_v52"
    private var inFlight = false
    private const val KEY_LAT = "weather_locality_lat_v47"
    private const val KEY_LON = "weather_locality_lon_v47"
    const val AUTO_ID = "auto"
    const val REFRESH_INTERVAL_MS = 5L * 60L * 1000L
    private const val MIN_RETRY_MS = REFRESH_INTERVAL_MS
    private const val MAX_AGE_MS = 3L * 60L * 60L * 1000L
    private const val MAX_OBSERVATION_AGE_MS = 3L * 60L * 60L * 1000L

    data class Snapshot(
        val temperatureC: Double,
        val weatherCode: Int,
        /** When data was saved into the local app cache. */
        val observedAtMs: Long,
        val isDay: Boolean,
        val originLabel: String,
        val sourceLabel: String,
        val reportTimeMs: Long,
        val autoSelected: Boolean,
        val localityLabel: String? = null
    ) {
        val formattedTemperature: String get() = "${temperatureC.roundToInt()}°C"
        val description: String get() = describe(weatherCode)
        // Station/ICAO and observation provenance stay in phone settings.
        // Only the city/place is shown below the clock face.
        val artworkSource: String? get() =
            localityLabel ?: WeatherStore.simplePlaceLabel(originLabel).takeIf { it.isNotBlank() }
    }

    /** Strip station markers and bracketed ICAO codes on the main clock. */
    fun simplePlaceLabel(value: String): String =
        value.removePrefix("Станция ")
            .removePrefix("Район ")
            .substringBefore(" (")
            .trim()


    private data class Reading(
        val temperature: Double,
        val code: Int,
        val isDay: Boolean,
        val place: String,
        val source: String,
        val reportTimeMs: Long
    )

    /** Cache is tied to the manual city or auto mode; never show old-city data. */
    fun current(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedMode = prefs.getString("weather_cached_mode",
            prefs.getString("weather_cached_city", null))
        if (cachedMode != AUTO_ID) return null
        val fetchedAt = prefs.getLong("weather_fetched_at", 0L)
        if (fetchedAt <= 0 || System.currentTimeMillis() - fetchedAt !in 0..MAX_AGE_MS) return null
        val temp = java.lang.Double.longBitsToDouble(
            prefs.getLong("weather_temperature_bits",
                java.lang.Double.doubleToRawLongBits(Double.NaN))
        )
        if (!temp.isFinite()) return null
        val source = prefs.getString("weather_source_name", null)
            ?: "Няма източник"
        val label = prefs.getString("weather_station_label", null)
            ?: "Авто"
        val reportTime = prefs.getLong("weather_observation_time", fetchedAt)
        if (source.contains("METAR") &&
            System.currentTimeMillis() - reportTime !in 0..MAX_OBSERVATION_AGE_MS
        ) return null
        return Snapshot(
            temp,
            prefs.getInt("weather_code", -1),
            fetchedAt,
            prefs.getBoolean("weather_is_day", true),
            label,
            source,
            reportTime,
            true,
            localityLabel = prefs.getString(KEY_LOCALITY, null)
        )
    }

    /**
     * Works from the main thread, returning through callback on that thread.
     * Auto location is requested only if new weather data is due.
     */
    fun refreshIfDue(
        context: Context,
        callback: (Boolean) -> Unit = {}
    ) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selected = selectedMode(prefs.getString(KEY_CITY, null))
        val now = System.currentTimeMillis()
        val lastAttempt = prefs.getLong("weather_attempt_at", 0L)
        if (inFlight || (now >= lastAttempt && now - lastAttempt < MIN_RETRY_MS)) {
            callback(false)
            return
        }
        inFlight = true

        prefs.edit().putLong("weather_attempt_at", now)
            .apply()

        WeatherLocation.requestOnce(app) { location ->
            if (location == null) {
                inFlight = false
                callback(false)
            } else {
                fetchOnThread(app, callback, location.latitude, location.longitude, now) {
                    fetchNearestStation(location)
                }
            }
        }
    }

    private fun fetchOnThread(
        app: Context,
        callback: (Boolean) -> Unit,
        latitude: Double,
        longitude: Double,
        requestAt: Long,
        request: () -> Reading?
    ) {
        Thread({
            val observed = try { request() } catch (_: Exception) { null }
            val forecast = try { ForecastStore.fetchOnWorker(latitude, longitude) }
                catch (_: Exception) { null }
            val result = observed ?: ForecastStore.currentModel(forecast)?.let {
                Reading(it.temperature, it.code, it.isDay, "Текущо местоположение",
                    "Open-Meteo · модел", System.currentTimeMillis())
            }
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val oldLat = Double.fromBits(prefs.getLong(KEY_LAT, Double.NaN.toBits()))
            val oldLon = Double.fromBits(prefs.getLong(KEY_LON, Double.NaN.toBits()))
            val near = oldLat.isFinite() && oldLon.isFinite() &&
                WeatherStations.kilometers(oldLat, oldLon, latitude, longitude) <= 0.3
            val known = prefs.getString(KEY_LOCALITY, null)
            val reuseLocality = near && known != null
            val locality = if (reuseLocality) known else resolveLocality(app, latitude, longitude)
            val warning = ForecastStore.warning(forecast, System.currentTimeMillis() / 1000L,
                result?.code)
            var saved = false
            if (result != null &&
                prefs.getLong("weather_attempt_at", 0L) == requestAt
            ) {
                val editor = prefs.edit()
                    .putString(KEY_LOCALITY, locality)
                    .putString(KEY_WARNING, warning)
                    .putLong(KEY_WARNING_REV, requestAt)
                    .putString("weather_cached_mode", AUTO_ID)
                    .putLong("weather_fetched_at", System.currentTimeMillis())
                    .putLong("weather_observation_time", result.reportTimeMs)
                    .putString("weather_station_label", result.place)
                    .putString("weather_source_name", result.source)
                    .putLong("weather_temperature_bits",
                        java.lang.Double.doubleToRawLongBits(result.temperature))
                    .putInt("weather_code", result.code)
                    .putBoolean("weather_is_day", result.isDay)
                if (!reuseLocality) editor.putLong(KEY_LAT, latitude.toBits())
                    .putLong(KEY_LON, longitude.toBits())
                if (forecast != null)
                    ForecastStore.putForecast(editor, AUTO_ID, latitude, longitude, forecast)
                else if (!ForecastStore.matchesLocation(prefs, AUTO_ID, latitude, longitude))
                    ForecastStore.clearForecast(editor)
                editor.apply()
                saved = true
            }
            Handler(Looper.getMainLooper()).post {
                inFlight = false
                callback(saved)
            }
        }, "AutoClockWeather").apply { isDaemon = true }.start()
    }

    /** Android reverse geocoding on the existing worker, not a GPS listener. */
    @Suppress("DEPRECATION")
    private fun resolveLocality(context: Context, latitude: Double, longitude: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val address = Geocoder(context, Locale("bg", "BG"))
                .getFromLocation(latitude, longitude, 1)?.firstOrNull()
            address?.locality?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    private val STATION_ID_SET = WeatherStations.stations.map { it.icao }.toSet()
    private val STATION_IDS = STATION_ID_SET.joinToString(",")

    /** Exactly one API call lists the latest reports for all eight stations. */
    private fun fetchNearestStation(location: Location): Reading? {
        val ids = STATION_IDS
        val json = fetchText(
            "https://aviationweather.gov/api/data/metar?ids=$ids&format=json",
            "AutoClock2/0.13 (Android personal weather display)"
        ) ?: return null
        val results = JSONArray(json)
        val now = System.currentTimeMillis()
        val byStation = mutableMapOf<String, JSONObject>()
        for (i in 0 until results.length()) {
            val report = results.optJSONObject(i) ?: continue
            val id = report.optString("icaoId", report.optString("stationId", ""))
            if (id !in STATION_ID_SET) continue
            val temp = report.optDouble("temp", Double.NaN)
            val stamp = report.optLong("obsTime", 0L)
            val timeMs = if (stamp > 10_000_000_000L) stamp else stamp * 1000L
            if (!temp.isFinite() || temp !in -100.0..70.0 ||
                now - timeMs !in 0..MAX_OBSERVATION_AGE_MS
            ) continue
            // Prefer newest result for a given station.
            val prior = byStation[id]
            if (prior == null || report.optLong("obsTime") > prior.optLong("obsTime"))
                byStation[id] = report
        }
        val nearest = WeatherStations.nearest(
            location.latitude, location.longitude, byStation.keys
        ) ?: return null
        // Stations farther than 180 km are not truly local weather observations.
        if (WeatherStations.kilometers(location.latitude, location.longitude,
                nearest.lat, nearest.lon) > 180.0
        ) return null
        val report = byStation[nearest.icao] ?: return null
        val stamp = report.optLong("obsTime", 0L)
        val timeMs = if (stamp > 10_000_000_000L) stamp else stamp * 1000L
        return Reading(
            temperature = report.optDouble("temp"),
            code = metarWeatherCode(report),
            isDay = estimatedDaylightBulgaria(),
            place = "Станция ${nearest.label}",
            source = "NOAA · измерване METAR",
            reportTimeMs = timeMs
        )
    }

    private fun metarWeatherCode(report: JSONObject): Int {
        val w = report.optString("wxString", "").uppercase(Locale.ROOT)
        if ("TS" in w) return 95
        if ("SN" in w || "SG" in w) return 71
        if ("FZRA" in w) return 66
        if ("SHRA" in w) return 80
        if ("RA" in w) return 61
        if ("DZ" in w) return 51
        if ("FG" in w || "BR" in w || "HZ" in w) return 45
        val clouds = report.optJSONArray("clouds")
        var highestCoverage = 0
        if (clouds != null) {
            for (i in 0 until clouds.length()) {
                val coverage = clouds.optJSONObject(i)?.optString("cover", "") ?: ""
                highestCoverage = maxOf(highestCoverage, when (coverage) {
                    "OVC", "BKN" -> 3
                    "SCT" -> 2
                    "FEW" -> 1
                    else -> 0
                })
            }
        }
        return highestCoverage
    }

    private fun estimatedDaylightBulgaria(): Boolean {
        val now = ZonedDateTime.now(ZoneId.of("Europe/Sofia"))
        val hour = now.hour
        return when (now.monthValue) {
            4, 5, 6, 7, 8 -> hour in 6..20
            3, 9, 10 -> hour in 7..18
            else -> hour in 8..16
        }
    }

    private fun fetchText(url: String, agent: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", agent)
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Compact Unicode glyph for Android Auto metadata. */
    fun symbol(code: Int?, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀" else "☾"
        1, 2 -> "⛅"
        3, -1, null -> "☁"
        45, 48 -> "≋"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "☂"
        71, 73, 75, 77, 85, 86 -> "❄"
        95, 96, 99 -> "⚡"
        else -> "☁"
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Ясно"
        1, 2 -> "Частична облачност"
        3 -> "Облачно"
        45, 48 -> "Мъгла"
        51, 53, 55, 56, 57 -> "Ръмеж"
        61, 63, 65, 66, 67 -> "Дъжд"
        71, 73, 75, 77 -> "Сняг"
        80, 81, 82 -> "Превалявания"
        85, 86 -> "Снеговалеж"
        95, 96, 99 -> "Буря"
        else -> "Времето"
    }
}
