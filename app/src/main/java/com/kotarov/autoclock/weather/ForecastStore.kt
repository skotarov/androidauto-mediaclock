package com.kotarov.autoclock.weather

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Three NEXT days' model forecast, independent of current METAR observations.
 * Runs inside WeatherStore's existing background weather worker, reuses its
 * one-shot coordinates, and fetches at most once every three hours.
 */
object ForecastStore {
    const val KEY_SAVED_AT = "forecast_saved_at"
    const val KEY_MODE = "forecast_saved_mode"
    private const val KEY_DATA = "forecast_daily_payload"
    private const val KEY_LAST_ATTEMPT = "forecast_last_attempt"
    private const val KEY_ATTEMPT_MODE = "forecast_attempt_mode"
    private const val FETCH_INTERVAL_MS = 3L * 60L * 60L * 1000L
    private const val RETRY_INTERVAL_MS = 45L * 60L * 1000L
    private const val MAX_CACHE_AGE_MS = 20L * 60L * 60L * 1000L

    data class Day(
        val date: LocalDate,
        val code: Int,
        val maxC: Double,
        val minC: Double,
        val precipitationPercent: Int?
    ) {
        val maxText: String get() = "${maxC.roundToInt()}°"
        val minText: String get() = "${minC.roundToInt()}°"
        val iconTemperature: String get() = "${maxC.roundToInt()}°C"
    }

    fun current(context: Context, today: LocalDate = LocalDate.now()): List<Day> {
        val prefs = context.getSharedPreferences(WeatherStore.PREFS, Context.MODE_PRIVATE)
        val mode = WeatherStore.selectedMode(prefs.getString(WeatherStore.KEY_CITY, null))
        if (prefs.getString(KEY_MODE, null) != mode) return emptyList()
        val saved = prefs.getLong(KEY_SAVED_AT, 0L)
        if (saved <= 0L || System.currentTimeMillis() - saved !in 0..MAX_CACHE_AGE_MS)
            return emptyList()
        return try {
            nextThreeDays(parse(prefs.getString(KEY_DATA, null) ?: ""), today)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Tomorrow, the next day, and the day after; never silently insert today. */
    fun nextThreeDays(all: List<Day>, today: LocalDate): List<Day> =
        all.filter { !it.date.isBefore(today.plusDays(1)) }
            .sortedBy { it.date }.take(3)

    /**
     * Caller is already on WeatherStore's background thread.
     * This method never starts another worker or GPS subscription.
     */
    fun refreshIfDueOnWorker(
        app: Context, mode: String, latitude: Double, longitude: Double
    ) {
        val prefs = app.getSharedPreferences(WeatherStore.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val hasFresh = prefs.getString(KEY_MODE, null) == mode &&
            savedAt > 0 && now - savedAt in 0..FETCH_INTERVAL_MS
        if (hasFresh) return
        val lastTry = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        if (prefs.getString(KEY_ATTEMPT_MODE, null) == mode &&
            now - lastTry in 0..RETRY_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_ATTEMPT, now)
            .putString(KEY_ATTEMPT_MODE, mode).apply()

        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=${String.format(Locale.US, "%.4f", latitude)}&" +
            "longitude=${String.format(Locale.US, "%.4f", longitude)}&" +
            "daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max&timezone=auto&forecast_days=4"
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AutoClock2/0.30 (3-day forecast)")
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
        if (nextThreeDays(parse(body), LocalDate.now()).size != 3) return
        // The user may change the selected city while this HTTP call is active.
        val latest = app.getSharedPreferences(WeatherStore.PREFS, Context.MODE_PRIVATE)
        if (WeatherStore.selectedMode(latest.getString(WeatherStore.KEY_CITY, null)) != mode)
            return
        latest.edit()
            .putString(KEY_MODE, mode)
            .putString(KEY_DATA, body)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun parse(json: String): List<Day> {
        if (json.isBlank()) return emptyList()
        val daily = try { JSONObject(json).optJSONObject("daily") } catch (_: Exception) { null }
            ?: return emptyList()
        val dates = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code") ?: return emptyList()
        val maxes = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val mins = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val rain = daily.optJSONArray("precipitation_probability_max")
        val days = mutableListOf<Day>()
        for (i in 0 until minOf(dates.length(), codes.length(), maxes.length(), mins.length())) {
            val date = try { LocalDate.parse(dates.optString(i)) } catch (_: Exception) { null }
                ?: continue
            val high = maxes.optDouble(i, Double.NaN)
            val low = mins.optDouble(i, Double.NaN)
            if (!high.isFinite() || !low.isFinite() ||
                high !in -100.0..70.0 || low !in -100.0..70.0) continue
            val probability = rain?.optInt(i, -1)?.takeIf { it in 0..100 }
            days += Day(date, codes.optInt(i, -1), high, low, probability)
        }
        return days
    }
}
