package com.kotarov.autoclock.weather

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class WeatherWarningTest {
    private fun hour(epoch: Long, code: Int, chance: Int?) =
        ForecastStore.Hour(epoch, LocalDateTime.of(2026, 11, 24, 12, 0), code, 4.0, chance)

    @Test fun repeatsSameWarningForEachFreshCheck() {
        val hours = listOf(hour(3600, 61, 80))
        assertEquals("Вероятност за валежи: 80%", WeatherWarning.message(hours, 1000, 0))
        assertEquals("Вероятност за валежи: 80%", WeatherWarning.message(hours, 1300, 0))
    }
    @Test fun prioritizesCurrentHazardAndIgnoresDistantForecast() {
        assertEquals("Мъгла", WeatherWarning.message(emptyList(), 1000, 45))
        assertEquals("До 1 ч: Гръмотевична буря", WeatherWarning.message(listOf(hour(3600, 95, 80)), 1000, 0))
        assertNull(WeatherWarning.message(listOf(hour(7200, 95, 100)), 1000, 0))
    }
    @Test fun noWarningForLowOrMissingProbability() {
        assertNull(WeatherWarning.message(listOf(hour(3600, 3, 40)), 1000, 3))
        assertNull(WeatherWarning.message(emptyList(), 1000, null))
    }
}
