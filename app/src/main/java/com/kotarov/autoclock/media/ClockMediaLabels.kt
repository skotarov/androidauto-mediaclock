package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Concise NOW-PLAYING text for Android Auto. Album art already explains the
 * mode; repeating "Аналогов часовник" or technical METAR station names in the
 * media lines is distracting and easily truncated by the host.
 */
object ClockMediaLabels {
    data class Lines(val title: String, val subtitle: String)

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    const val EMPTY = "\u00A0"

    fun forClock(
        preset: ClockPreset,
        now: LocalDateTime,
        weather: WeatherStore.Snapshot?
    ): Lines {
        val time = now.format(timeFormat)
        if (!preset.usesWeather) {
            // Analog without add-ons still shows only the digital time UNDER
            // the dial on the media card. It does not add digits to the dial.
            return Lines(time, EMPTY)
        }

        val icon = WeatherStore.symbol(weather?.weatherCode, weather?.isDay ?: true)
        val place = weather?.let { WeatherStore.simplePlaceLabel(it.originLabel) }
            ?.takeIf(String::isNotBlank) ?: "Няма данни"
        val location = "$icon $place"

        return when (preset.family) {
            ClockFamily.ANALOG, ClockFamily.DIGITAL -> Lines(time, location)
            ClockFamily.WEATHER -> {
                val conditions = weather?.let { "$icon ${it.formattedTemperature}" }
                    ?: "Няма данни"
                Lines(conditions, location)
            }
        }
    }
}
