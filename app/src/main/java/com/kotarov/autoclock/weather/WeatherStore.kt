package com.kotarov.autoclock.weather

import android.content.Context
import android.location.Location
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
 * Manual cities: Open-Meteo modeled current conditions.
 * Auto: one approximate location fix, then latest *actual observation* from
 * the nearest reporting aerodrome METAR station (NOAA Aviation Weather).
 * If no recent station is available, fall back to an explicitly labeled
 * Open-Meteo model at the approximate device location.
 *
 * No continuous GPS listener, wakelock, ongoing background work or per-minute
 * weather requests. Requests are capped to once per 15 minutes in weather display.
 */
object WeatherStore {
    const val PREFS = "clock_settings"
    const val KEY_CITY = "weather_city"
    const val KEY_COLORED_ICONS = "weather_colored_icons"
    const val AUTO_ID = "auto"
    const val REFRESH_INTERVAL_MS = 15L * 60L * 1000L
    private const val MIN_RETRY_MS = REFRESH_INTERVAL_MS
    private const val MAX_AGE_MS = 3L * 60L * 60L * 1000L
    private const val MAX_OBSERVATION_AGE_MS = 3L * 60L * 60L * 1000L

    enum class City(val id: String, val label: String, val lat: Double, val lon: Double) {
        SOFIA("sofia", "София", 42.6977, 23.3219),
        STARA_ZAGORA("stara_zagora", "Стара Загора", 42.4258, 25.6345),
        PLOVDIV("plovdiv", "Пловдив", 42.1354, 24.7453),
        VARNA("varna", "Варна", 43.2141, 27.9147),
        BURGAS("burgas", "Бургас", 42.5048, 27.4626),
        RUSE("ruse", "Русе", 43.8356, 25.9657),
        PLEVEN("pleven", "Плевен", 43.4080, 24.6203),
        VELIKO_TARNOVO("veliko_tarnovo", "Велико Търново", 43.0757, 25.6172);

        companion object {
            fun byId(id: String?): City = entries.firstOrNull { it.id == id } ?: SOFIA
        }
    }

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
        /** Pressure reported by METAR altimeter setting, or modeled surface pressure. */
        val pressureHpa: Double? = null,
        /** Elevation of the reporting station/model grid, NOT the car's GPS altitude. */
        val elevationMeters: Double? = null
    ) {
        val formattedTemperature: String get() = "${temperatureC.roundToInt()}°C"
        val description: String get() = describe(weatherCode)
        val pressureText: String get() =
            pressureHpa?.takeIf { it.isFinite() && it in 500.0..1100.0 }
                ?.let { "${it.roundToInt()} hPa" } ?: "няма данни"
        val elevationText: String get() =
            elevationMeters?.takeIf { it.isFinite() && it in -500.0..9000.0 }
                ?.let { "${it.roundToInt()} м" } ?: "няма данни"
        // Station/ICAO and observation provenance stay in phone settings.
        // Only the city/place is shown below the clock face.
        val artworkSource: String? get() =
            if (autoSelected) WeatherStore.simplePlaceLabel(originLabel) else null
    }

    /** Strip station markers and bracketed ICAO codes on the main clock. */
    fun simplePlaceLabel(value: String): String =
        value.removePrefix("Станция ")
            .removePrefix("Район ")
            .substringBefore(" (")
            .trim()

    /** Automatic weather is the default unless the user chose a source. */
    fun selectedMode(saved: String?): String = saved ?: AUTO_ID

    fun coloredIcons(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(KEY_COLORED_ICONS, true)

    private data class Reading(
        val temperature: Double,
        val code: Int,
        val isDay: Boolean,
        val place: String,
        val source: String,
        val reportTimeMs: Long,
        val pressureHpa: Double? = null,
        val elevationMeters: Double? = null
    )

    fun isAuto(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CITY, null).let { selectedMode(it) } == AUTO_ID

    fun city(context: Context): City =
        City.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CITY, null))

    fun setCity(context: Context, id: String) {
        val chosen = if (id == AUTO_ID) AUTO_ID else City.byId(id).id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CITY, chosen)
            // Switching station/city should trigger a fresh reading immediately.
            .putLong("weather_attempt_at", 0L)
            .apply()
    }

    fun sourceStatus(context: Context): String {
        val snapshot = current(context)
        if (snapshot != null) {
            val whenRead = java.text.SimpleDateFormat(
                "HH:mm", Locale("bg", "BG")
            ).format(java.util.Date(snapshot.reportTimeMs))
            return "${snapshot.originLabel} — ${snapshot.sourceLabel} ($whenRead)"
        }
        return if (isAuto(context)) {
            if (WeatherLocation.permitted(context))
                "Авто: търся най-близка станция с актуално измерване"
            else "Авто: разреши приблизително местоположение"
        } else {
            "${city(context).label}: няма актуални данни"
        }
    }

    /** Cache is tied to the manual city or auto mode; never show old-city data. */
    fun current(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selected = selectedMode(prefs.getString(KEY_CITY, null))
        val cachedMode = prefs.getString("weather_cached_mode",
            prefs.getString("weather_cached_city", null))
        if (cachedMode != selected) return null
        val fetchedAt = prefs.getLong("weather_fetched_at", 0L)
        if (fetchedAt <= 0 || System.currentTimeMillis() - fetchedAt !in 0..MAX_AGE_MS) return null
        val temp = java.lang.Double.longBitsToDouble(
            prefs.getLong("weather_temperature_bits",
                java.lang.Double.doubleToRawLongBits(Double.NaN))
        )
        if (!temp.isFinite()) return null
        val source = prefs.getString("weather_source_name", null)
            ?: if (selected == AUTO_ID) "Няма източник" else "Open-Meteo · модел"
        val label = prefs.getString("weather_station_label", null)
            ?: if (selected == AUTO_ID) "Авто" else city(context).label
        val reportTime = prefs.getLong("weather_observation_time", fetchedAt)
        if (selected == AUTO_ID && source.contains("METAR") &&
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
            selected == AUTO_ID,
            pressureHpa = storedDouble(prefs, "weather_pressure_bits", 500.0..1100.0),
            elevationMeters = storedDouble(prefs, "weather_elevation_bits", -500.0..9000.0)
        )
    }

    private fun storedDouble(
        prefs: android.content.SharedPreferences, key: String, range: ClosedFloatingPointRange<Double>
    ): Double? {
        val bits = prefs.getLong(key, java.lang.Double.doubleToRawLongBits(Double.NaN))
        val value = java.lang.Double.longBitsToDouble(bits)
        return value.takeIf { it.isFinite() && it in range }
    }

    /**
     * Works from the main thread, returning through callback on that thread.
     * Auto location is requested only if new weather data is due.
     */
    fun refreshIfDue(
        context: Context,
        force: Boolean = false,
        callback: (Boolean) -> Unit = {}
    ) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selected = selectedMode(prefs.getString(KEY_CITY, null))
        val now = System.currentTimeMillis()
        val lastAttempt = prefs.getLong("weather_attempt_at", 0L)
        if (!force && prefs.getString("weather_attempt_city", null) == selected &&
            now >= lastAttempt && now - lastAttempt < MIN_RETRY_MS
        ) return

        prefs.edit().putLong("weather_attempt_at", now)
            .putString("weather_attempt_city", selected).apply()

        if (selected == AUTO_ID) {
            // Do not prompt from the media service. The phone Activity handles
            // the Android runtime permission request when Auto is selected.
            WeatherLocation.requestOnce(app) { location ->
                if (location == null) {
                    callback(false)
                } else {
                    fetchOnThread(
                        app, selected, callback, location.latitude, location.longitude
                    ) {
                        fetchNearestStation(location) ?: fetchAutoModelFallback(location)
                    }
                }
            }
        } else {
            val manual = City.byId(selected)
            fetchOnThread(app, selected, callback, manual.lat, manual.lon) {
                fetchModel(
                    manual.lat, manual.lon,
                    manual.label, "Open-Meteo · модел"
                )
            }
        }
    }

    private fun fetchOnThread(
        app: Context,
        selectionAtStart: String,
        callback: (Boolean) -> Unit,
        latitude: Double,
        longitude: Double,
        request: () -> Reading?
    ) {
        Thread({
            val result = try { request() } catch (_: Exception) { null }
            // Reuse the already acquired approximate coordinates. No new GPS
            // request and at most one extra forecast HTTP call each 3 hours.
            try {
                ForecastStore.refreshIfDueOnWorker(
                    app, selectionAtStart, latitude, longitude
                )
            } catch (_: Exception) {
                // Three-day forecast is optional; keep actual current weather.
            }
            var saved = false
            if (result != null &&
                selectedMode(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CITY, null)) == selectionAtStart
            ) {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("weather_cached_mode", selectionAtStart)
                    .putString("weather_cached_city", selectionAtStart)
                    .putLong("weather_fetched_at", System.currentTimeMillis())
                    .putLong("weather_observation_time", result.reportTimeMs)
                    .putString("weather_station_label", result.place)
                    .putString("weather_source_name", result.source)
                    .putLong("weather_temperature_bits",
                        java.lang.Double.doubleToRawLongBits(result.temperature))
                    .putInt("weather_code", result.code)
                    .putBoolean("weather_is_day", result.isDay)
                    // Store NaN for missing values so stale station metrics
                    // cannot survive switching source or location.
                    .putLong("weather_pressure_bits",
                        java.lang.Double.doubleToRawLongBits(
                            result.pressureHpa ?: Double.NaN))
                    .putLong("weather_elevation_bits",
                        java.lang.Double.doubleToRawLongBits(
                            result.elevationMeters ?: Double.NaN))
                    .apply()
                saved = true
            }
            Handler(Looper.getMainLooper()).post { callback(saved) }
        }, "AutoClockWeather").apply { isDaemon = true }.start()
    }

    /** Exactly one API call lists the latest reports for all eight stations. */
    private fun fetchNearestStation(location: Location): Reading? {
        val ids = WeatherStations.stations.joinToString(",") { it.icao }
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
            if (id !in WeatherStations.stations.map { it.icao }) continue
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
            reportTimeMs = timeMs,
            // NOAA METAR JSON altim is in hPa, elev in meters MSL.
            pressureHpa = report.optDouble("altim", Double.NaN)
                .takeIf { it.isFinite() && it in 500.0..1100.0 },
            elevationMeters = report.optDouble("elev", Double.NaN)
                .takeIf { it.isFinite() && it in -500.0..9000.0 }
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

    private fun fetchAutoModelFallback(location: Location): Reading? {
        val nearestCity = City.entries.minByOrNull {
            WeatherStations.kilometers(location.latitude, location.longitude,
                it.lat, it.lon)
        }
        val close = nearestCity != null &&
            WeatherStations.kilometers(location.latitude, location.longitude,
                nearestCity.lat, nearestCity.lon) < 120.0
        val label = if (close) "Район ${nearestCity!!.label}"
                    else "Текущо местоположение"
        return fetchModel(location.latitude, location.longitude,
            label, "Open-Meteo · модел (няма станция)")
    }

    private fun fetchModel(
        lat: Double, lon: Double, label: String, source: String
    ): Reading? {
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=${String.format(Locale.US, "%.4f", lat)}&" +
            "longitude=${String.format(Locale.US, "%.4f", lon)}&" +
            "current=temperature_2m,weather_code,is_day,surface_pressure&timezone=auto&forecast_days=1"
        val body = fetchText(url, "AutoClock2/0.13 (Android personal weather display)")
            ?: return null
        val current = JSONObject(body).optJSONObject("current") ?: return null
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (!temperature.isFinite() || temperature !in -100.0..70.0) return null
        return Reading(
            temperature,
            current.optInt("weather_code", -1),
            current.optInt("is_day", 1) == 1,
            label,
            source,
            System.currentTimeMillis(),
            pressureHpa = current.optDouble("surface_pressure", Double.NaN)
                .takeIf { it.isFinite() && it in 500.0..1100.0 },
            elevationMeters = JSONObject(body).optDouble("elevation", Double.NaN)
                .takeIf { it.isFinite() && it in -500.0..9000.0 }
        )
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
