package com.kotarov.autoclock.weather

import android.content.Context
import android.content.SharedPreferences
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
 * no extra GPS fix or service; normal HTTP reads cap at 5 minutes.
 */
object ForecastStore {
    // Fresh cache keys: never read the older v0.35 daily forecast as hourly.
    const val KEY_SAVED_AT = "forecast_hourly_saved_at_v36"
    const val KEY_MODE = "forecast_hourly_mode_v36"
    private const val KEY_DATA = "forecast_hourly_payload_v36"
    private const val KEY_LAT = "forecast_hourly_lat_v47"
    private const val KEY_LON = "forecast_hourly_lon_v47"
    private const val MAX_CACHE_AGE_MS = 4L * 60L * 60L * 1000L
    private data class Parsed(val payload: String, val hours: List<Hour>)
    @Volatile private var parsed: Parsed? = null

    @Synchronized private fun parsedHours(payload: String): List<Hour> {
        parsed?.takeIf { it.payload == payload }?.let { return it.hours }
        return parse(payload).also { parsed = Parsed(payload, it) }
    }

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

    const val DIGITAL_HOURS = 6

    /** Stable six columns beginning at the current local hour. Missing data
     * leaves an empty column rather than shifting later forecasts forward. */
    data class DigitalSlot(val time: LocalDateTime, val hour: Hour?, val alpha: Int)

    fun digitalSlots(
        forecast: List<Hour>, now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<DigitalSlot> {
        val start = now.atZone(zone).withMinute(0).withSecond(0).withNano(0)
        val byEpoch = forecast.associateBy { it.epochSeconds }
        return (0 until DIGITAL_HOURS).map { offset ->
            val slot = start.plusHours(offset.toLong())
            DigitalSlot(slot.toLocalDateTime(), byEpoch[slot.toEpochSecond()],
                forecastAlpha(offset, DIGITAL_HOURS - 1))
        }
    }

    const val RING_HOURS = 11
    const val RING_FUTURE_HOURS = 9

    /** Current and next three hours are fully opaque; the rest are 45%,
     * except the final future hour (20%). The past slot is also 45%. */
    fun forecastAlpha(offset: Int, lastOffset: Int): Int = when {
        offset == lastOffset -> 51
        offset in 0..3 -> 255
        else -> 115
    }

    fun ringAlpha(offset: Int): Int = forecastAlpha(offset, RING_FUTURE_HOURS)

    /** Match the actual Canvas glyph, not the raw WMO intensity code.
     * Day/night matters only for glyphs that visibly include sun or moon. */
    private fun ringGlyph(hour: Hour): Int = when (hour.code) {
        0 -> if (hour.isDay) 0 else 1
        1, 2 -> if (hour.isDay) 2 else 3
        45, 48 -> 4
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 ->
            if (hour.isDay) 5 else 6
        71, 73, 75, 77, 85, 86 -> if (hour.isDay) 7 else 8
        95, 96, 99 -> if (hour.isDay) 9 else 10
        else -> 11
    }

    /** Keep current and past icons; future icons mark changes only.
     * Missing data breaks continuity. Temperatures are independent. */
    fun ringIconEpochs(hours: List<Hour>, currentSlot: Long): Set<Long> {
        val icons = mutableSetOf<Long>()
        var previous: Hour? = null
        for (hour in hours.sortedBy { it.epochSeconds }) {
            if (hour.epochSeconds < currentSlot - 3600L ||
                hour.epochSeconds > currentSlot + RING_FUTURE_HOURS * 3600L) continue
            if (hour.code < 0) {
                previous = null
                continue
            }
            val prior = previous
            if (hour.epochSeconds <= currentSlot || prior == null ||
                hour.epochSeconds - prior.epochSeconds != 3600L ||
                ringGlyph(hour) != ringGlyph(prior)) {
                icons.add(hour.epochSeconds)
            }
            previous = hour
        }
        return icons
    }

    /** Previous hourly model slot, current observation and nine future slots. */
    fun currentRing(
        context: Context,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        observation: WeatherStore.Snapshot? = WeatherStore.current(context)
    ): List<Hour> = ringWithObservation(cached(context), nowEpochSeconds, observation)

    fun ringHours(
        all: List<Hour>, nowEpochSeconds: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Hour> {
        val start = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone)
            .withMinute(0).withSecond(0).withNano(0).toEpochSecond()
        return all.filter { it.epochSeconds in (start - 3600L)..(start + RING_FUTURE_HOURS * 3600L) }
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
        val hours = ringHours(all, nowEpochSeconds, zone)
        if (observation == null || observation.weatherCode < 0) return hours
        val slot = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone)
            .withMinute(0).withSecond(0).withNano(0)
        val epoch = slot.toEpochSecond()
        val current = Hour(epoch, slot.toLocalDateTime(),
            observation.weatherCode, observation.temperatureC, null, observation.isDay)
        // Never allow a modeled point at the same dial hour to overpaint
        // the measured current conditions, including offset time zones.
        val surrounding = hours.filter {
            it.epochSeconds != epoch &&
                Instant.ofEpochSecond(it.epochSeconds).atZone(zone).hour != slot.hour
        }
        return (listOf(current) + surrounding).sortedBy { it.epochSeconds }.take(RING_HOURS)
    }

    private fun cached(context: Context): List<Hour> {
        val prefs = context.getSharedPreferences(WeatherStore.PREFS, Context.MODE_PRIVATE)
        val mode = WeatherStore.AUTO_ID
        if (prefs.getString(KEY_MODE, null) != mode) return emptyList()
        val saved = prefs.getLong(KEY_SAVED_AT, 0L)
        if (saved <= 0 || System.currentTimeMillis() - saved !in 0..MAX_CACHE_AGE_MS)
            return emptyList()
        return try {
            parsedHours(prefs.getString(KEY_DATA, null) ?: "")
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

    /** The weather worker already throttles requests to 5 minutes.
     * Return the data without publishing it, so weather and location can be
     * committed together in one SharedPreferences transaction. */
    fun fetchOnWorker(latitude: Double, longitude: Double): String? {
        val now = System.currentTimeMillis()
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=${String.format(Locale.US, "%.4f", latitude)}&" +
            "longitude=${String.format(Locale.US, "%.4f", longitude)}&" +
            "hourly=temperature_2m,weather_code,precipitation_probability,is_day&" +
            "current=temperature_2m,weather_code,is_day&timezone=auto&timeformat=unixtime&past_hours=1&forecast_hours=11"
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AutoClock2/0.50 (hourly forecast)")
            }
        } catch (_: Exception) { return null }
        val body = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
        if (nextSixHours(parsedHours(body), now / 1000L).size < 6) return null

        return body
    }

    fun currentModel(body: String?): ModelCurrent? {
        val root = try { JSONObject(body ?: return null) } catch (_: Exception) { return null }
        val current = root.optJSONObject("current") ?: return null
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (!temperature.isFinite() || temperature !in -100.0..70.0) return null
        return ModelCurrent(temperature, current.optInt("weather_code", -1),
            current.optInt("is_day", 1) == 1)
    }

    data class ModelCurrent(val temperature: Double, val code: Int, val isDay: Boolean)

    fun warning(body: String?, nowEpochSeconds: Long, currentCode: Int?): String? =
        WeatherWarning.message(if (body == null) emptyList() else parsedHours(body),
            nowEpochSeconds, currentCode)

    fun matchesLocation(prefs: SharedPreferences, mode: String, lat: Double, lon: Double): Boolean {
        if (prefs.getString(KEY_MODE, null) != mode) return false
        val oldLat = Double.fromBits(prefs.getLong(KEY_LAT, Double.NaN.toBits()))
        val oldLon = Double.fromBits(prefs.getLong(KEY_LON, Double.NaN.toBits()))
        return oldLat.isFinite() && oldLon.isFinite() &&
            WeatherStations.kilometers(oldLat, oldLon, lat, lon) <= 1.0
    }

    fun putForecast(editor: SharedPreferences.Editor, mode: String,
                    lat: Double, lon: Double, body: String) {
        editor.putString(KEY_MODE, mode).putString(KEY_DATA, body)
            .putLong(KEY_LAT, lat.toBits()).putLong(KEY_LON, lon.toBits())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
    }

    fun clearForecast(editor: SharedPreferences.Editor) {
        editor.remove(KEY_MODE).remove(KEY_DATA).remove(KEY_LAT).remove(KEY_LON)
            .putLong(KEY_SAVED_AT, 0L)
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
