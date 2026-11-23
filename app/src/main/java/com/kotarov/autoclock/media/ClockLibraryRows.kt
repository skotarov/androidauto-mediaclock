package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Three readable media leaves; missing weather values must never be fabricated. */
object ClockLibraryRows {
    const val CLOCK_ID = "autoclock_clock"
    const val WEATHER_ID = "autoclock_weather"
    const val METRICS_ID = "autoclock_metrics"

    enum class Icon { CLOCK, WEATHER, METRICS }
    data class Row(val id: String, val title: String, val subtitle: String, val icon: Icon)
    private val format = DateTimeFormatter.ofPattern("HH:mm")

    fun create(
        now: LocalDateTime, selected: ClockPreset, weather: WeatherStore.Snapshot?
    ): List<Row> {
        val clock = Row(
            CLOCK_ID, now.format(format),
            if (selected.family == ClockFamily.DIGITAL) "Цифров часовник"
            else "Аналогов часовник",
            Icon.CLOCK
        )
        val conditions = if (weather != null) {
            val location = WeatherStore.simplePlaceLabel(weather.originLabel)
            val glyph = WeatherStore.symbol(weather.weatherCode, weather.isDay)
            Row(WEATHER_ID, "$glyph $location · ${weather.formattedTemperature}",
                weather.description, Icon.WEATHER)
        } else Row(WEATHER_ID, "Времето · няма данни",
            "Изчаква се актуално измерване", Icon.WEATHER)

        val heightType = if (weather?.autoSelected == true &&
            weather.sourceLabel.contains("METAR")) "станция" else "терен"
        val metrics = Row(
            METRICS_ID,
            "Налягане: ${weather?.pressureText ?: "няма данни"}",
            "Надм. височина ($heightType): ${weather?.elevationText ?: "няма данни"}",
            Icon.METRICS
        )
        return listOf(clock, conditions, metrics)
    }

    fun isKnownId(id: String?): Boolean =
        id == CLOCK_ID || id == WEATHER_ID || id == METRICS_ID
}
