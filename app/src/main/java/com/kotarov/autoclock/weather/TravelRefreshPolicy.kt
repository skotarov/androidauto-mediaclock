package com.kotarov.autoclock.weather

/** Pure policy, independent of Android, using monotonic milliseconds. */
object TravelRefreshPolicy {
    const val LOCATION_TICK_MS = 30_000L
    const val FALLBACK_INTERVAL_MS = 60_000L
    const val STATIONARY_INTERVAL_MS = 5 * 60_000L
    const val STATIONARY_AFTER_MS = 2 * 60_000L
    const val QUIET_TICK_MS = 60_000L
    fun failedFixInterval(failures: Int): Long =
        if (failures <= 0) FALLBACK_INTERVAL_MS else (FALLBACK_INTERVAL_MS * (1L shl failures.coerceAtMost(3))).coerceAtMost(STATIONARY_INTERVAL_MS)

    fun fallbackInterval(stationary: Boolean, urgent: Boolean): Long =
        if (stationary && !urgent) STATIONARY_INTERVAL_MS else FALLBACK_INTERVAL_MS
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

    /** Coordinates are a restart checkpoint, not a record of every movement.
     * Live geocoding still uses the half-kilometre in-memory threshold. */
    fun localityCheckpointDue(nameChanged: Boolean, distanceKm: Double): Boolean =
        nameChanged || distanceKm >= WEATHER_DISTANCE_KM

    /** Keep the first valid name, but confirm a changed name on a second lookup.
     * One inconsistent coarse-location/geocoder result is not a move. */
    class LocalityConfirmation {
        private var candidate: String? = null
        val pending: Boolean get() = candidate != null
        fun reset() { candidate = null }
        fun accept(saved: String?, next: String): Boolean {
            if (saved == null || next.equals(saved, ignoreCase = true) ||
                next.equals(candidate, ignoreCase = true)) {
                reset()
                return true
            }
            candidate = next
            return false
        }
    }

    fun localityDue(now: Long, lastAttempt: Long?, distanceKm: Double,
                    hasName: Boolean, lastFailed: Boolean): Boolean {
        if (lastAttempt != null && now - lastAttempt <
            (if (lastFailed) LOCALITY_RETRY_MS else LOCALITY_MIN_ATTEMPT_MS)) return false
        return !hasName || distanceKm >= LOCALITY_DISTANCE_KM || lastFailed
    }
}
