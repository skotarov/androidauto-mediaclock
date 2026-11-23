package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.ForecastStore
import com.kotarov.autoclock.weather.WeatherStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Six next-hour forecast leaves. No live clock or browse refresh each minute. */
object ClockLibraryRows {
    const val CLOCK_ID = "autoclock_clock"
    const val WEATHER_ID = "autoclock_weather"
    private const val FORECAST_ID_PREFIX = "autoclock_forecast_hour_"
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    data class Row(
        val id: String,
        val title: String,
        val subtitle: String,
        val forecast: ForecastStore.Hour?,
        /** Unique timestamp for the forecast artwork cache. */
        val epochSeconds: Long
    )

    fun create(
        hours: List<ForecastStore.Hour>,
        nowEpochSeconds: Long,
        fallbackZone: ZoneId = ZoneId.systemDefault()
    ): List<Row> {
        val next = ForecastStore.nextSixHours(hours, nowEpochSeconds)
        // For missing data, show only hour labels, never invented temps/code.
        val firstHour = ((nowEpochSeconds / 3600L) + 1L) * 3600L
        val byEpoch = next.associateBy { it.epochSeconds }
        return (0 until 6).map { offset ->
            val epoch = firstHour + offset * 3600L
            // Model results include a location timezone. Use the predicted
            // hour's own local time even if the device is elsewhere.
            val forecast = byEpoch[epoch]
            val local = forecast?.localDateTime ?: LocalDateTime.ofInstant(
                Instant.ofEpochSecond(epoch), fallbackZone
            )
            val label = local.format(timeFormat)
            if (forecast == null) {
                Row(
                    "${FORECAST_ID_PREFIX}${offset + 1}",
                    "$label · Няма прогноза",
                    "Данните още не са заредени",
                    null,
                    epoch
                )
            } else {
                val conditions = WeatherStore.describe(forecast.code)
                val probability = forecast.precipitationPercent
                    ?.let { " · Валежи $it%" } ?: ""
                Row(
                    "${FORECAST_ID_PREFIX}${offset + 1}",
                    "$label · ${forecast.temperatureText}",
                    "$conditions$probability",
                    forecast,
                    epoch
                )
            }
        }
    }

    fun isKnownId(mediaId: String?): Boolean =
        mediaId != null && (1..6).any { mediaId == "$FORECAST_ID_PREFIX$it" }
}
