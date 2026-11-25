package com.kotarov.autoclock.weather

import org.junit.Assert.*
import org.junit.Test

class OptimizationPolicyTest {
    @Test fun warningDeduplicationKeepsNewHazardsImmediate() {
        assertTrue(WeatherWarning.shouldAnnounce(null, "Fog", 0))
        assertFalse(WeatherWarning.shouldAnnounce("Fog", "Fog", 900000))
        assertTrue(WeatherWarning.shouldAnnounce("Fog", "Fog", WeatherWarning.REMINDER_MS))
        assertTrue(WeatherWarning.shouldAnnounce("Fog", "Risk of ice", 1))
        assertTrue(WeatherWarning.shouldAnnounce("Within 1h: Fog", "Fog", 1))
        assertFalse(WeatherWarning.shouldAnnounce("Precipitation: 50%", "Precipitation: 60%", 1))
        assertTrue(WeatherWarning.shouldAnnounce("Precipitation: 50%", "Precipitation: 80%", 1))
        assertFalse(WeatherWarning.shouldAnnounce("Fog", null, 0))
    }
    @Test fun stationaryFallbackNeverDelaysUrgentFix() {
        assertEquals(300000L, TravelRefreshPolicy.fallbackInterval(true, false))
        assertEquals(60000L, TravelRefreshPolicy.fallbackInterval(true, true))
        assertEquals(60000L, TravelRefreshPolicy.fallbackInterval(false, false))
    }
}
