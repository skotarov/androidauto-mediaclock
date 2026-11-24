package com.kotarov.autoclock.weather

/** Local forecast messages, not official emergency alerts. */
object WeatherWarning {
    private fun anomaly(code: Int?): String? = when (code) {
        95, 96, 99 -> "Гръмотевична буря"
        56, 57, 66, 67 -> "Опасност от поледица"
        75, 86 -> "Силен снеговалеж"
        65, 82 -> "Силен дъжд"
        45, 48 -> "Мъгла"
        else -> null
    }

    fun message(hours: List<ForecastStore.Hour>, now: Long, currentCode: Int?): String? {
        anomaly(currentCode)?.let { return it }
        // Open-Meteo precipitation probability describes the preceding hour.
        val near = hours.filter { it.epochSeconds > now && it.epochSeconds <= now + 3600L }
        near.firstNotNullOfOrNull { anomaly(it.code) }?.let { return "До 1 ч: $it" }
        val chance = near.mapNotNull { it.precipitationPercent }.maxOrNull() ?: 0
        return if (chance >= 50) "Вероятност за валежи: $chance%" else null
    }
}
