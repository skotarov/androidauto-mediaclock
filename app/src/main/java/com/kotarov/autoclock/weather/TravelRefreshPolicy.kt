package com.kotarov.autoclock.weather

/** Pure policy, independent of Android, using monotonic milliseconds. */
object TravelRefreshPolicy {
    const val LOCATION_TICK_MS = 30_000L
    const val FALLBACK_INTERVAL_MS = 60_000L
    const val WEATHER_INTERVAL_MS = 15 * 60_000L
    const val WEATHER_DISTANCE_KM = 5.0
    const val WEATHER_MIN_ATTEMPT_MS = 60_000L
    const val LOCALITY_DISTANCE_KM = 0.5
    const val LOCALITY_MIN_ATTEMPT_MS = 60_000L
    const val LOCALITY_RETRY_MS = 2 * 60_000L

    data class Point(val lat: Double, val lon: Double) {
        fun distanceFrom(other: Point?) = if (other == null) Double.POSITIVE_INFINITY
            else WeatherStations.kilometers(lat, lon, other.lat, other.lon)
    }

    fun weatherDue(now: Long, lastSuccess: Long?, distanceKm: Double,
                   lastAttempt: Long?, failures: Int): Boolean {
        val delay = if (failures > 0) retryDelay(failures) else WEATHER_MIN_ATTEMPT_MS
        if (lastAttempt != null && now - lastAttempt < delay) return false
        return failures > 0 || lastSuccess == null ||
            now - lastSuccess >= WEATHER_INTERVAL_MS || distanceKm >= WEATHER_DISTANCE_KM
    }

    fun retryDelay(failures: Int): Long =
        (WEATHER_MIN_ATTEMPT_MS * (1L shl (failures - 1).coerceIn(0, 6)))
            .coerceAtMost(60 * 60_000L)

    fun localityDue(now: Long, lastAttempt: Long?, distanceKm: Double,
                    hasName: Boolean, lastFailed: Boolean): Boolean {
        if (lastAttempt != null && now - lastAttempt <
            (if (lastFailed) LOCALITY_RETRY_MS else LOCALITY_MIN_ATTEMPT_MS)) return false
        return !hasName || distanceKm >= LOCALITY_DISTANCE_KM || lastFailed
    }
}
