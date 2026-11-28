package com.kotarov.autoclock.weather

/** Local forecast messages, not official emergency alerts. */
object WeatherWarning {
    private fun anomaly(code: Int?): String? = when (code) {
        95, 96, 99 -> "Thunderstorm"
        56, 57, 66, 67 -> "Risk of ice"
        75, 86 -> "Heavy snow"
        65, 82 -> "Heavy rain"
        45, 48 -> "Fog"
        else -> null
    }

    const val REMINDER_MS = 60 * 60_000L

    /** Identical hazards need at most an hourly reminder; a new hazard is immediate. */
    fun shouldAnnounce(previous: String?, next: String?, elapsedMs: Long): Boolean {
        if (next == null) return false
        if (previous == null) return true
        if (elapsedMs >= REMINDER_MS) return true
        fun chance(message: String) = message.takeIf { it.startsWith("Precipitation: ") }
            ?.removePrefix("Precipitation: ")?.removeSuffix("%")?.toIntOrNull()
        val before = chance(previous)
        val after = chance(next)
        if (before != null && after != null) return after - before >= 20
        return previous != next
    }

    fun testMessages(): List<String> {
        val types = listOf(95, 66, 75, 65, 45).mapNotNull { anomaly(it) }
        return types + types.map { "Within 1h: $it" } + "Precipitation: 80%"
    }

    fun message(hours: List<ForecastStore.Hour>, now: Long, currentCode: Int?): String? {
        anomaly(currentCode)?.let { return it }
        // Open-Meteo precipitation probability describes the preceding hour.
        val near = hours.filter { it.epochSeconds > now && it.epochSeconds <= now + 3600L }
        near.firstNotNullOfOrNull { anomaly(it.code) }?.let { return "Within 1h: $it" }
        val chance = near.mapNotNull { it.precipitationPercent }.maxOrNull() ?: 0
        return if (chance >= 50) "Precipitation: $chance%" else null
    }
}
