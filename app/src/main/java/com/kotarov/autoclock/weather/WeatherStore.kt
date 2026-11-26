package com.kotarov.autoclock.weather

import android.content.Context
import com.kotarov.autoclock.PerfDiagnostics
import android.location.Location
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Location, reverse geocoding and weather have independent schedules.
 * Main-thread coordinator shared by the car and the visible phone settings.
 * Workers deliver only to the current active generation. */
object WeatherStore {
    const val PREFS = "clock_settings"
    const val KEY_LOCALITY = "weather_locality_en_v71"
    const val KEY_WARNING = "weather_warning_en_v71"
    const val KEY_WARNING_REV = "weather_warning_revision_en_v71"
    private const val KEY_LAT = "weather_locality_lat_v47"
    private const val KEY_LON = "weather_locality_lon_v47"
    const val AUTO_ID = "auto"
    const val REFRESH_INTERVAL_MS = TravelRefreshPolicy.WEATHER_INTERVAL_MS
    private const val MAX_AGE_MS = 3L * 60L * 60L * 1000L
    private const val MAX_OBSERVATION_AGE_MS = 3L * 60L * 60L * 1000L

    enum class Client { CAR, PHONE }
    private val clients = mutableSetOf<Client>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var generation = 0L
    private var passiveCancel: (() -> Unit)? = null
    private var fallbackCancel: (() -> Unit)? = null
    private var fixInFlight = false
    private var latest: Location? = null
    private var lastFallback: Long? = null
    private var failedFixes = 0
    private var stationarySince: Long? = null
    private var lastLocalityFixNanos = Long.MIN_VALUE
    private var weatherThread: Thread? = null
    private var localityThread: Thread? = null
    private var lastWeatherAttempt: Long? = null
    private var lastWeatherSuccess: Long? = null
    private var lastWeatherPoint: TravelRefreshPolicy.Point? = null
    private var failures = 0
    private var lastLocalityAttempt: Long? = null
    private var lastLocalityPoint: TravelRefreshPolicy.Point? = null
    private var localityFailed = false
    private val localityConfirmation = TravelRefreshPolicy.LocalityConfirmation()
    private var stationReportsAt: Long? = null
    private var stationReports: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (clients.isEmpty()) return
            checkLocation()
            val now = SystemClock.elapsedRealtime()
            val quiet = stationarySince?.let { now - it >= TravelRefreshPolicy.STATIONARY_AFTER_MS } == true &&
                lastWeatherSuccess?.let { now - it < TravelRefreshPolicy.WEATHER_INTERVAL_MS } == true &&
                !localityConfirmation.pending && !localityFailed
            handler.postDelayed(this, if (quiet) TravelRefreshPolicy.QUIET_TICK_MS else TravelRefreshPolicy.LOCATION_TICK_MS)
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
        value.removePrefix("Station ")
            .removePrefix("Area ")
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
        // Older releases cached Bulgarian display strings. Keep valid
        // numerical weather data, but rebuild its human-readable labels.
        val cachedSource = prefs.getString("weather_source_name", null)
        val cachedLabel = prefs.getString("weather_station_label", null)
        val station = WeatherStations.stations.firstOrNull {
            cachedLabel?.endsWith("(${it.icao})") == true
        }
        val source = when {
            cachedSource?.contains("METAR") == true -> "NOAA · METAR observation"
            cachedSource?.contains("Open-Meteo") == true -> "Open-Meteo · model"
            else -> "No source"
        }
        val label = when {
            station != null -> "Station ${station.label}"
            source.startsWith("Open-Meteo") -> "Current location"
            else -> "Auto"
        }
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

    fun locality(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOCALITY, null)

    /** Called on the main thread. No extra permission is requested here. */
    fun start(context: Context, client: Client) {
        app = context.applicationContext
        val first = clients.isEmpty()
        clients.add(client)
        if (first) {
            generation++
            latest = null
            localityConfirmation.reset()
            stationarySince = null
            lastLocalityFixNanos = Long.MIN_VALUE
            lastFallback = null
            failedFixes = 0
            val prefs = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lat = Double.fromBits(prefs.getLong(KEY_LAT, Double.NaN.toBits()))
            val lon = Double.fromBits(prefs.getLong(KEY_LON, Double.NaN.toBits()))
            lastLocalityPoint = if (lat.isFinite() && lon.isFinite())
                TravelRefreshPolicy.Point(lat, lon) else null
            handler.removeCallbacks(tick)
            tick.run()
        } else checkLocation()
    }

    fun stop(client: Client) {
        clients.remove(client)
        if (clients.isNotEmpty()) return
        generation++
        handler.removeCallbacks(tick)
        passiveCancel?.invoke()
        passiveCancel = null
        fallbackCancel?.invoke()
        fallbackCancel = null
        fixInFlight = false
        latest = null
        weatherThread?.interrupt()
        localityThread?.interrupt()
        // Threads are not replaced until their completion callbacks arrive.
        // Bounded HTTP timeouts remain in force if interruption cannot abort I/O.
        app = null
    }

    /** A manual request bypasses the ordinary retry interval, but never
     * starts a second worker or a second concurrent location request. */
    fun refreshLocality(context: Context) {
        if (!WeatherLocation.permitted(context)) return
        lastLocalityAttempt = null
        lastFallback = null
        failedFixes = 0
        start(context, Client.PHONE)
    }

    fun localityFailure(context: Context): String = when {
        !WeatherLocation.permitted(context) -> "Location permission required"
        !WeatherLocation.enabled(context) -> "Turn on phone location"
        latest != null -> "Coordinates found; place name unavailable"
        else -> "No location fix; check location services"
    }

    private fun checkLocation() {
        val context = app ?: return
        if (!WeatherLocation.permitted(context) || !WeatherLocation.enabled(context)) {
            passiveCancel?.invoke()
            passiveCancel = null
            fallbackCancel?.invoke()
            fallbackCancel = null
            fixInFlight = false
            latest = null
            return
        }
        if (passiveCancel == null) {
            val token = generation
            passiveCancel = WeatherLocation.passive(context) {
                if (token == generation && clients.isNotEmpty()) acceptFix(it)
            }
        }
        val now = SystemClock.elapsedRealtime()
        val stationary = stationarySince?.let { now - it >= TravelRefreshPolicy.STATIONARY_AFTER_MS } == true
        val weatherNeedsFix = lastWeatherSuccess?.let { now - it >= TravelRefreshPolicy.WEATHER_INTERVAL_MS } ?: true
        val fallbackInterval = maxOf(TravelRefreshPolicy.fallbackInterval(stationary,
            weatherNeedsFix || localityConfirmation.pending || localityFailed),
            TravelRefreshPolicy.failedFixInterval(failedFixes))
        if (WeatherLocation.fresh(latest)) {
            processFix(latest!!)
        } else if (!fixInFlight &&
            (lastFallback == null || now - lastFallback!! >= fallbackInterval)) {
            lastFallback = now
            fixInFlight = true
            val token = generation
            PerfDiagnostics.location()
            val cancel = WeatherLocation.requestOnce(context) {
                if (token == generation) {
                    fixInFlight = false
                    fallbackCancel = null
                    if (it != null) acceptFix(it) else failedFixes = (failedFixes + 1).coerceAtMost(3)
                }
            }
            if (fixInFlight) fallbackCancel = cancel
        }
    }

    private fun acceptFix(location: Location) {
        val context = app ?: return
        if (!WeatherLocation.permitted(context) || !WeatherLocation.fresh(location)) return
        val prior = latest
        if (prior != null && location.elapsedRealtimeNanos <= prior.elapsedRealtimeNanos) return
        val now = SystemClock.elapsedRealtime()
        stationarySince = if (prior != null && prior.distanceTo(location) <= 100f &&
            (!location.hasSpeed() || location.speed <= 1f)) stationarySince ?: now else null
        failedFixes = 0
        latest = Location(location)
        processFix(location)
    }

    private fun processFix(location: Location) {
        val context = app ?: return
        if (!WeatherLocation.permitted(context)) return
        val point = TravelRefreshPolicy.Point(location.latitude, location.longitude)
        val now = SystemClock.elapsedRealtime()
        if (localityThread == null && location.elapsedRealtimeNanos != lastLocalityFixNanos &&
            TravelRefreshPolicy.localityDue(now,
                lastLocalityAttempt, point.distanceFrom(lastLocalityPoint),
                locality(context) != null && !localityConfirmation.pending, localityFailed)) {
            lastLocalityAttempt = now
            lastLocalityFixNanos = location.elapsedRealtimeNanos
            val token = generation
            localityThread = Thread({
                val name = resolveLocality(context, point.lat, point.lon)
                handler.post {
                    localityThread = null
                    if (token == generation && clients.isNotEmpty() &&
                        WeatherLocation.permitted(context)) {
                        localityFailed = name == null
                        if (name == null) localityConfirmation.reset()
                        if (name != null) {
                            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            val savedName = prefs.getString(KEY_LOCALITY, null)
                            val changed = !name.equals(savedName, ignoreCase = true)
                            if (localityConfirmation.accept(savedName, name)) {
                                lastLocalityPoint = point
                                val savedLat = Double.fromBits(prefs.getLong(KEY_LAT, Double.NaN.toBits()))
                                val savedLon = Double.fromBits(prefs.getLong(KEY_LON, Double.NaN.toBits()))
                                val savedPoint = if (savedLat.isFinite() && savedLon.isFinite())
                                    TravelRefreshPolicy.Point(savedLat, savedLon) else null
                                if (TravelRefreshPolicy.localityCheckpointDue(changed, point.distanceFrom(savedPoint))) {
                                    val edit = prefs.edit().putLong(KEY_LAT, point.lat.toBits())
                                        .putLong(KEY_LON, point.lon.toBits())
                                    if (changed) {
                                        edit.putString(KEY_LOCALITY, name)
                                            .putLong("weather_locality_revision_v68", System.currentTimeMillis())
                                    }
                                    edit.apply()
                                }
                            }
                        }
                    }
                }
            }, "AutoClockLocality").apply { isDaemon = true; start() }
        }
        if (weatherThread == null && TravelRefreshPolicy.weatherDue(now,
                lastWeatherSuccess, point.distanceFrom(lastWeatherPoint),
                lastWeatherAttempt, failures)) {
            lastWeatherAttempt = now
            fetchWeather(context, Location(location), point, generation)
        }
    }

    private fun fetchWeather(context: Context, location: Location,
                             point: TravelRefreshPolicy.Point, token: Long) {
        val now = SystemClock.elapsedRealtime()
        val canUseStation = WeatherStations.stations.any {
            point.distanceFrom(TravelRefreshPolicy.Point(it.lat, it.lon)) <= 180.0
        }
        val cachedReports = stationReports.takeIf {
            stationReportsAt?.let { at -> now - at < REFRESH_INTERVAL_MS } == true
        }
        weatherThread = Thread({
            var reports = cachedReports
            var fetchedReports = false
            var forecast: String? = null
            var reading: Reading? = null
            var warning: String? = null
            try {
                // Fetch the local model first, and never query Bulgarian
                // stations from an unrelated country/region.
                forecast = ForecastStore.fetchOnWorker(point.lat, point.lon)
                val model = ForecastStore.currentModel(forecast)
                if (canUseStation && !Thread.currentThread().isInterrupted) {
                    if (reports == null) {
                        reports = runCatching { fetchText(
                            "https://aviationweather.gov/api/data/metar?ids=$STATION_IDS&format=json",
                            "AutoClock2/0.68 (Android weather display)") }.getOrNull()
                        fetchedReports = reports != null
                    }
                    val observed = runCatching { reports?.let { fetchNearestStation(location, it) } }.getOrNull()
                    reading = observed?.copy(isDay = model?.isDay ?: observed.isDay,
                        code = if (observed.code >= 0) observed.code else model?.code ?: -1)
                }
                if (reading == null && model != null) reading = Reading(model.temperature,
                    model.code, model.isDay, "Current location",
                    "Open-Meteo · model", System.currentTimeMillis())
                warning = ForecastStore.warning(forecast, System.currentTimeMillis() / 1000L,
                    model?.code ?: reading?.code)
            } catch (_: Exception) {
                // A failed provider must not stop the coordinator.
            } finally {
                handler.post {
                    weatherThread = null
                    if (token == generation && clients.isNotEmpty() &&
                        WeatherLocation.permitted(context)) {
                        if (fetchedReports) {
                            stationReports = reports
                            stationReportsAt = SystemClock.elapsedRealtime()
                        }
                        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        val result = reading
                        val body = forecast
                        val editor = prefs.edit()
                        if (result != null) {
                            val savedAt = System.currentTimeMillis()
                            val announced = prefs.getString("warning_announced_v95", prefs.getString(KEY_WARNING, null))
                            val lastAnnounced = prefs.getLong(KEY_WARNING_REV, 0L)
                            editor.putString(KEY_WARNING, warning)
                            if (WeatherWarning.shouldAnnounce(announced, warning, savedAt - lastAnnounced)) {
                                editor.putString("warning_announced_v95", warning)
                                    .putLong(KEY_WARNING_REV, savedAt)
                            } else if (warning == null && prefs.getString(KEY_WARNING, null) != null) {
                                editor.remove("warning_announced_v95").putLong(KEY_WARNING_REV, savedAt)
                            }
                            editor.putString("weather_cached_mode", AUTO_ID)
                                .putLong("weather_fetched_at", savedAt)
                                .putLong("weather_observation_time", result.reportTimeMs)
                                .putString("weather_station_label", result.place)
                                .putString("weather_source_name", result.source)
                                .putLong("weather_temperature_bits", result.temperature.toBits())
                                .putInt("weather_code", result.code)
                                .putBoolean("weather_is_day", result.isDay)
                        }
                        if (body != null && result != null) {
                            ForecastStore.putForecast(editor, AUTO_ID, point.lat, point.lon, body)
                            lastWeatherPoint = point
                            lastWeatherSuccess = SystemClock.elapsedRealtime()
                            failures = 0
                        } else {
                            failures = (failures + 1).coerceAtMost(7)
                            // A distant cached forecast must not masquerade as local.
                            if (!ForecastStore.matchesLocation(prefs, AUTO_ID, point.lat, point.lon)) {
                                ForecastStore.clearForecast(editor)
                                if (result == null) editor.remove("weather_cached_mode")
                                    .remove("weather_cached_city").putLong("weather_fetched_at", 0L)
                            }
                        }
                        editor.apply()
                    }
                }
            }
        }, "AutoClockWeather").apply { isDaemon = true; start() }
    }

    /** Android reverse geocoding on the existing worker, not a GPS listener. */
    @Suppress("DEPRECATION")
    private fun resolveLocality(context: Context, latitude: Double, longitude: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            PerfDiagnostics.geocode()
            val addresses = Geocoder(context, Locale.ENGLISH)
                .getFromLocation(latitude, longitude, 3).orEmpty()
            // Some providers omit locality on the first address.
            addresses.firstNotNullOfOrNull { it.locality?.trim()?.takeIf(String::isNotEmpty) }
                ?: addresses.firstNotNullOfOrNull { it.subLocality?.trim()?.takeIf(String::isNotEmpty) }
        } catch (_: Exception) { null }
    }

    private val STATION_ID_SET = WeatherStations.stations.map { it.icao }.toSet()
    private val STATION_IDS = STATION_ID_SET.joinToString(",")

    /** Select a station from the shared, periodically refreshed report set. */
    private fun fetchNearestStation(location: Location, json: String): Reading? {
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
            place = "Station ${nearest.label}",
            source = "NOAA · METAR observation",
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
        // Missing cloud observations are not proof of a clear sky.
        var highestCoverage = -1
        if (clouds != null) {
            for (i in 0 until clouds.length()) {
                val coverage = clouds.optJSONObject(i)?.optString("cover", "") ?: ""
                highestCoverage = maxOf(highestCoverage, when (coverage) {
                    "OVC", "BKN" -> 3
                    "SCT" -> 2
                    "FEW" -> 1
                    "CLR", "SKC", "NSC", "NCD" -> 0
                    "VV" -> 3
                    else -> -1
                })
            }
        }
        if (highestCoverage < 0 && report.optString("rawOb", "").contains("CAVOK")) return 0
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
        PerfDiagnostics.network()
        val connection = (URL(url).openConnection() as javax.net.ssl.HttpsURLConnection).apply {
            instanceFollowRedirects = false
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
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Storm"
        else -> "Weather"
    }
}
