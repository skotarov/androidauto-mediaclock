package com.kotarov.autoclock.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * No GPS, wake locks, alarms or recurring background jobs.
 * A single HTTP request is allowed when an active weather display needs it.
 * Successful readings survive process restarts; failures are throttled too.
 */
object WeatherStore {
    const val PREFS = "clock_settings"
    const val KEY_CITY = "weather_city"
    private const val MIN_RETRY_MS = 60L * 60L * 1000L
    private const val MAX_AGE_MS = 3L * 60L * 60L * 1000L

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
        val observedAtMs: Long,
        val isDay: Boolean
    ) {
        val formattedTemperature: String get() = "${temperatureC.roundToInt()}°C"
        val description: String get() = describe(weatherCode)
    }

    fun city(context: Context): City =
        City.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CITY, null))

    fun setCity(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CITY, City.byId(id).id).apply()
    }

    /** Ignore old or different-city readings; never present stale weather as current. */
    fun current(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("weather_cached_city", null) != city(context).id) return null
        val fetchedAt = prefs.getLong("weather_fetched_at", 0L)
        if (fetchedAt <= 0 || System.currentTimeMillis() - fetchedAt !in 0..MAX_AGE_MS) return null
        val temp = java.lang.Double.longBitsToDouble(
            prefs.getLong("weather_temperature_bits", java.lang.Double.doubleToRawLongBits(Double.NaN))
        )
        if (!temp.isFinite()) return null
        return Snapshot(temp, prefs.getInt("weather_code", -1), fetchedAt,
            prefs.getBoolean("weather_is_day", true))
    }

    /**
     * Network I/O is isolated from the UI and browser service main thread.
     * callback is posted only when a new reading succeeds.
     */
    fun refreshIfDue(context: Context, force: Boolean = false, callback: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selected = city(appContext)
        val now = System.currentTimeMillis()
        val lastAttempt = prefs.getLong("weather_attempt_at", 0L)
        val lastCity = prefs.getString("weather_attempt_city", null)
        if (!force && lastCity == selected.id && now >= lastAttempt &&
            now - lastAttempt < MIN_RETRY_MS
        ) return

        prefs.edit().putLong("weather_attempt_at", now)
            .putString("weather_attempt_city", selected.id).apply()

        Thread({
            val reading = try { fetch(selected) } catch (_: Exception) { null }
            if (reading != null && city(appContext).id == selected.id) {
                prefs.edit()
                    .putString("weather_cached_city", selected.id)
                    .putLong("weather_fetched_at", System.currentTimeMillis())
                    .putLong("weather_temperature_bits", java.lang.Double.doubleToRawLongBits(reading.temperature))
                    .putInt("weather_code", reading.code)
                    .putBoolean("weather_is_day", reading.isDay)
                    .apply()
            }
            Handler(Looper.getMainLooper()).post { callback(reading != null) }
        }, "AutoClockWeather").apply { isDaemon = true }.start()
    }

    private data class Reading(val temperature: Double, val code: Int, val isDay: Boolean)

    private fun fetch(city: City): Reading? {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?" +
                "latitude=${String.format(Locale.US, "%.4f", city.lat)}&" +
                "longitude=${String.format(Locale.US, "%.4f", city.lon)}&" +
                "current=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=1"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText())
            }
            val current = json.optJSONObject("current") ?: return null
            val temperature = current.optDouble("temperature_2m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            if (!temperature.isFinite() || temperature !in -100.0..70.0) return null
            return Reading(temperature, code, current.optInt("is_day", 1) == 1)
        } finally {
            connection.disconnect()
        }
    }

    /** Compact Unicode glyph for Android Auto metadata (vector artwork has its own icon). */
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
