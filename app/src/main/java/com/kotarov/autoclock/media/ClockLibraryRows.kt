package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.ForecastStore
import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Exactly three daily forecast leaves, not an animated clock or playlist. */
object ClockLibraryRows {
    // Retained for active Now Playing metadata; these are not library rows.
    const val CLOCK_ID = "autoclock_clock"
    const val WEATHER_ID = "autoclock_weather"
    private const val FORECAST_ID_PREFIX = "autoclock_forecast_day_"

    data class Row(
        val id: String, val title: String, val subtitle: String,
        val date: LocalDate, val forecast: ForecastStore.Day?
    )
    private val dateFormat = DateTimeFormatter.ofPattern("EEE dd.MM", Locale("bg", "BG"))

    fun create(days: List<ForecastStore.Day>, today: LocalDate): List<Row> {
        val byDate = days.associateBy { it.date }
        return (1..3).map { index ->
            val date = today.plusDays(index.toLong())
            val day = byDate[date]
            val label = date.format(dateFormat)
            if (day == null) {
                Row("$FORECAST_ID_PREFIX$index", "$label · Няма прогноза",
                    "Данните още не са заредени", date, null)
            } else {
                val text = WeatherStore.describe(day.code)
                val rain = day.precipitationPercent?.let { " · Валежи до $it%" } ?: ""
                Row(
                    "$FORECAST_ID_PREFIX$index",
                    "$label  ↑${day.maxText} ↓${day.minText}",
                    "$text$rain", date, day
                )
            }
        }
    }

    fun isKnownId(mediaId: String?): Boolean =
        mediaId != null && (1..3).any { mediaId == "$FORECAST_ID_PREFIX$it" }
}
