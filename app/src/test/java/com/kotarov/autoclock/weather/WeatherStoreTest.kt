package com.kotarov.autoclock.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherStoreTest {
    @Test fun refreshIntervalIsFifteenMinutes() {
        assertEquals(15L * 60L * 1000L, WeatherStore.REFRESH_INTERVAL_MS)
    }

    @Test fun weatherCodesAreMappedToShortEnglishLabels() {
        assertEquals("Clear", WeatherStore.describe(0))
        assertEquals("Fog", WeatherStore.describe(45))
        assertEquals("Rain", WeatherStore.describe(63))
        assertEquals("Snow", WeatherStore.describe(73))
        assertEquals("Storm", WeatherStore.describe(95))
        assertEquals("Weather", WeatherStore.describe(-1))
    }

    @Test fun weatherSymbolsAreMappedAcrossDifferentConditions() {
        assertEquals("☀", WeatherStore.symbol(0, true))
        assertEquals("☾", WeatherStore.symbol(0, false))
        assertEquals("⛅", WeatherStore.symbol(2))
        assertEquals("≋", WeatherStore.symbol(45))
        assertEquals("☂", WeatherStore.symbol(63))
        assertEquals("❄", WeatherStore.symbol(73))
        assertEquals("⚡", WeatherStore.symbol(95))
    }

    @Test fun clockPlaceLabelOmitsStationAndBracketedCode() {
        assertEquals("Sofia",
            WeatherStore.simplePlaceLabel("Station Sofia (LBSF)"))
        assertEquals("Sofia",
            WeatherStore.simplePlaceLabel("Area Sofia"))
        assertEquals("Varna",
            WeatherStore.simplePlaceLabel("Station Varna (LBWN)"))
        val observed = WeatherStore.Snapshot(
            temperatureC = 12.0,
            weatherCode = 2,
            observedAtMs = 10L,
            isDay = true,
            originLabel = "Station Sofia (LBSF)",
            sourceLabel = "NOAA · METAR observation",
            reportTimeMs = 10L,
            autoSelected = true
        )
        assertEquals("Sofia", observed.artworkSource)
        // Retain full provenance for phone source/status.
        assertEquals("Station Sofia (LBSF)", observed.originLabel)
    }

}
