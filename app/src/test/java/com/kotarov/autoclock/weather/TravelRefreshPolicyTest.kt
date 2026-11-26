package com.kotarov.autoclock.weather

import org.junit.Assert.*
import org.junit.Test

class TravelRefreshPolicyTest {
    @Test fun changedLocalityNeedsTwoMatchingLookups() {
        val confirmation = TravelRefreshPolicy.LocalityConfirmation()
        org.junit.Assert.assertTrue(confirmation.accept(null, "Sofia"))
        org.junit.Assert.assertFalse(confirmation.accept("Sofia", "Bankya"))
        org.junit.Assert.assertTrue(confirmation.pending)
        org.junit.Assert.assertTrue(confirmation.accept("Sofia", "Sofia"))
        org.junit.Assert.assertFalse(confirmation.pending)
        org.junit.Assert.assertFalse(confirmation.accept("Sofia", "Bankya"))
        org.junit.Assert.assertTrue(confirmation.accept("Sofia", "BANKYA"))
        org.junit.Assert.assertFalse(confirmation.pending)
    }

    @Test fun stationaryWeatherRefreshesAfterFifteenMinutes() {
        assertFalse(TravelRefreshPolicy.weatherDue(899_999L, 0, 0.0, 0, 0))
        assertTrue(TravelRefreshPolicy.weatherDue(900_000L, 0, 0.0, 0, 0))
    }
    @Test fun travelAt100KphTriggersAfterThreeMinutes() {
        assertFalse(TravelRefreshPolicy.weatherDue(179_000L, 0, 4.97, 0, 0))
        assertTrue(TravelRefreshPolicy.weatherDue(180_000L, 0, 5.0, 0, 0))
    }
    @Test fun freshWeatherNeverSpamsRequests() {
        assertFalse(TravelRefreshPolicy.weatherDue(59_999L, 0, 10.0, 0, 0))
        assertTrue(TravelRefreshPolicy.weatherDue(60_000L, 0, 10.0, 0, 0))
    }
    @Test fun failuresBackOffAndRetryEvenWhenStationary() {
        assertEquals(60_000L, TravelRefreshPolicy.retryDelay(1))
        assertEquals(120_000L, TravelRefreshPolicy.retryDelay(2))
        assertEquals(3_600_000L, TravelRefreshPolicy.retryDelay(7))
        assertFalse(TravelRefreshPolicy.weatherDue(119_999L, 0, 0.0, 0, 2))
        assertTrue(TravelRefreshPolicy.weatherDue(120_000L, 0, 0.0, 0, 2))
    }
    @Test fun localityUsesItsOwnHalfKilometreThreshold() {
        assertFalse(TravelRefreshPolicy.localityDue(60_000L, 0, 0.49, true, false))
        assertTrue(TravelRefreshPolicy.localityDue(60_000L, 0, 0.5, true, false))
        assertFalse(TravelRefreshPolicy.localityDue(59_999L, 0, 1.0, true, false))
        assertTrue(TravelRefreshPolicy.localityDue(120_000L, 0, 0.0, true, true))
    }
    @Test fun localityCheckpointDoesNotWriteEveryHalfKilometre() {
        assertFalse(TravelRefreshPolicy.localityCheckpointDue(false, 0.5))
        assertFalse(TravelRefreshPolicy.localityCheckpointDue(false, 4.99))
        assertTrue(TravelRefreshPolicy.localityCheckpointDue(false, 5.0))
        assertTrue(TravelRefreshPolicy.localityCheckpointDue(true, 0.0))
        assertTrue(TravelRefreshPolicy.localityCheckpointDue(false, Double.POSITIVE_INFINITY))
    }

    @Test fun failedLocationRequestsBackOffWithoutChangingWeatherInterval() {
        assertEquals(60_000L, TravelRefreshPolicy.failedFixInterval(0))
        assertEquals(120_000L, TravelRefreshPolicy.failedFixInterval(1))
        assertEquals(240_000L, TravelRefreshPolicy.failedFixInterval(2))
        assertEquals(300_000L, TravelRefreshPolicy.failedFixInterval(3))
        assertEquals(300_000L, TravelRefreshPolicy.failedFixInterval(20))
        assertEquals(900_000L, TravelRefreshPolicy.WEATHER_INTERVAL_MS)
    }

    @Test fun unknownLocationAndFirstForecastAreDue() {
        assertTrue(TravelRefreshPolicy.localityDue(0, null, Double.POSITIVE_INFINITY, false, false))
        assertTrue(TravelRefreshPolicy.weatherDue(0, null, Double.POSITIVE_INFINITY, null, 0))
    }
}
