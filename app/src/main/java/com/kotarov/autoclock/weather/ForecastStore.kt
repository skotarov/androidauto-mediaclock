package com.kotarov.autoclock.weather

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Six NEXT whole hours from an Open-Meteo modeled forecast, separate from
 * current METAR weather. The existing weather worker reuses its coordinates:
 * no extra GPS fix or service; normal HTTP reads cap at 15 minutes.
 * Explicit Pause/settings-open refreshes may bypass this cap.
 */
object ForecastStore {
    // Fresh cache keys: never read the older v0.35 daily forecast as hourly.
    const val KEY_SAVED_AT = "forecast_hourly_saved_at_v36"
    const val KEY_MODE = "forecast_hourly_mode_v36"
    private const val KEY_DATA = "forecast_hourly_payload_v36"
    private const val KEY_LAST_ATTEMPT = "forecast_hourly_attempt_v36"
    private const val KEY_ATTEMPT_MODE = "forecast_hourly_attempt_mode_v36"
    private const val FETCH_INTERVAL_MS = 15L * 60L * 1000L
    private const val RETRY_INTERVAL_MS = 10L * 60L * 1000L
    private const val MAX_CACHE_AGE_MS = 4L * 60L * 60L * 1000L

    data class Hour(
        val epochSeconds: Long,
        /** Local time of the FORECAST location, not necessarily the phone. */
        val localDateTime: LocalDateTime,
        val code: Int,
        val temperatureC: Double,
        val precipitationPercent: Int?,
        val isDay: Boolean = localDateTime.hour in 6..17
    ) {
        val temperatureText: String get() = "${temperatureC.roundToInt()}°C"
    }

    fun current(
        context: Context,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): List<Hour> = nextSixHours(cached(context), nowEpochSeconds)

    const val RING_HOURS = 10

    /** Current observation plus nine future slots; the Pause list is unchanged. */
    fun currentRing(context: Context, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): List<Hour> =
        ringWithObservation(cached(context), nowEpochSeconds, WeatherStore.current(context))

    fun ringHours(all: List<Hour>, nowEpochSeconds: Long): List<Hour> {
        val start = all.filter { it.epochSeconds <= nowEpochSeconds }
            .maxOfOrNull { it.epochSeconds }?.takeIf { nowEpochSeconds - it < 3600L }
            ?: all.filter { it.epochSeconds > nowEpochSeconds }
                .minOfOrNull { it.epochSeconds }?.minus(3600L)
            ?: return emptyList()
        if (nowEpochSeconds - start !in 0L..3599L) return emptyList()
        return all.filter { it.epochSeconds in start..(start + (RING_HOURS - 1) * 3600L) }
            .distinctBy { it.epochSeconds }.sortedBy { it.epochSeconds }
    }

    /** Use exactly the same current conditions as the weather-only screen.
     * Do not replace future forecast values with station observations.
     * If the observation is unavailable, retain the modeled current slot.
     */
    fun ringWithObservation(
        all: List<Hour>, nowEpochSeconds: Long,
        observation: WeatherStore.Snapshot?, zone: ZoneId = ZoneId.systemDefault()
    ): List<Hour> {
        val hours = ringHours(all, nowEpochSeconds)
        if (observation == null || observation.weatherCode < 0) return hours
        val slot = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone)
            .withMinute(0).withSecond(0).withNano(0)
        val epoch = hours.firstOrNull { it.epochSeconds <= nowEpochSeconds }?.epochSeconds
            ?: slot.toEpochSecond()
        val current = Hour(epoch, LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone),
            observation.weatherCode, observation.temperatureC, null, observation.isDay)
        return (listOf(current) + hours.filter { it.epochSeconds > nowEpochSeconds })
            .sortedBy { it.epochSeconds }.take(RING_HOURS)
    }

    private fun cached(context: Context): List<Hour> {
        val prefs = context.getSharedPreferences(WeatherStore.PREFS, Context.MODE_PRIVATE)
        val mode = WeatherStore.selectedMode(prefs.getString(WeatherStore.KEY_CITY, null))
        if (prefs.getString(KEY_MODE, null) != mode) return emptyList()
        val saved = prefs.getLong(KEY_SAVED_AT, 0L)
        if (saved <= 0 || System.currentTimeMillis() - saved !in 0..MAX_CACHE_AGE_MS)
            return emptyList()
        return try {
            parse(prefs.getString(KEY_DATA, null) ?: "")
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Strictly later than NOW; includes the next six hour boundaries. */
    fun nextSixHours(all: List<Hour>, nowEpochSeconds: Long): List<Hour> =
        all.asSequence()
            .filter { it.epochSeconds > nowEpochSeconds }
            .sortedBy { it.epochSeconds }
            .take(6)
            .toList()

    /** Invoked ONLY from WeatherStore's existing background weather thread. */
    fun refreshIfDueOnWorker(
        app: Context, mode: String, latitude: Double, longitude: Double,
        force: Boolean = false
    ) {
        val prefs = app.getSharedPreferences(WeatherStore.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val hasFresh = prefs.getString(KEY_MODE, null) == mode &&
            savedAt > 0 && now - savedAt in 0..FETCH_INTERVAL_MS
        if (!force && hasFresh) return
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        if (!force && prefs.getString(KEY_ATTEMPT_MODE, null) == mode &&
            now >= lastAttempt && now - lastAttempt < RETRY_INTERVAL_MS) return
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT, now)
            .putString(KEY_ATTEMPT_MODE, mode)
            .apply()

        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=${String.format(Locale.US, "%.4f", latitude)}&" +
            "longitude=${String.format(Locale.US, "%.4f", longitude)}&" +
            "hourly=temperature_2m,weather_code,precipitation_probability,is_day&" +
            "timezone=auto&timeformat=unixtime&forecast_days=2"
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AutoClock2/0.43 (hourly forecast)")
            }
        } catch (_: Exception) { return }
        val body = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            return
        } finally {
            connection.disconnect()
        }
        if (nextSixHours(parse(body), now / 1000L).size != 6) return

        // Do not save weather for an old city if settings changed mid-request.
        val latest = app.getSharedPreferences(WeatherStore.PREFS, Context.MODE_PRIVATE)
        if (WeatherStore.selectedMode(latest.getString(WeatherStore.KEY_CITY, null)) != mode)
            return
        latest.edit()
            .putString(KEY_MODE, mode)
            .putString(KEY_DATA, body)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun parse(json: String): List<Hour> {
        if (json.isBlank()) return emptyList()
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        val hourly = root.optJSONObject("hourly") ?: return emptyList()
        val timestamps = hourly.optJSONArray("time") ?: return emptyList()
        val codes = hourly.optJSONArray("weather_code") ?: return emptyList()
        val temperatures = hourly.optJSONArray("temperature_2m") ?: return emptyList()
        val rain = hourly.optJSONArray("precipitation_probability")
        val daylight = hourly.optJSONArray("is_day")
        val zone = try {
            ZoneId.of(root.optString("timezone", "UTC"))
        } catch (_: Exception) {
            ZoneId.of("UTC")
        }
        val hours = mutableListOf<Hour>()
        for (i in 0 until minOf(timestamps.length(), codes.length(), temperatures.length())) {
            val epoch = timestamps.optLong(i, 0L)
            val temp = temperatures.optDouble(i, Double.NaN)
            if (epoch <= 0L || !temp.isFinite() || temp !in -100.0..70.0)
                continue
            val local = try {
                LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone)
            } catch (_: Exception) { continue }
            val code = codes.optInt(i, -1)
            val probability = rain?.optInt(i, -1)?.takeIf { it in 0..100 }
            val isDay = daylight?.optInt(i, -1)?.takeIf { it in 0..1 }?.let { it == 1 }
                ?: (local.hour in 6..17)
            hours.add(Hour(epoch, local, code, temp, probability, isDay))
        }
        return hours
    }
}
