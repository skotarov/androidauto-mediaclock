package com.kotarov.autoclock.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherStoreTest {
    @Test fun refreshIntervalIsFiveMinutes() {
        assertEquals(5L * 60L * 1000L, WeatherStore.REFRESH_INTERVAL_MS)
    }

    @Test fun weatherCodesAreMappedToShortBulgarianLabels() {
        assertEquals("Ясно", WeatherStore.describe(0))
        assertEquals("Мъгла", WeatherStore.describe(45))
        assertEquals("Дъжд", WeatherStore.describe(63))
        assertEquals("Сняг", WeatherStore.describe(73))
        assertEquals("Буря", WeatherStore.describe(95))
        assertEquals("Времето", WeatherStore.describe(-1))
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
        assertEquals("София",
            WeatherStore.simplePlaceLabel("Станция София (LBSF)"))
        assertEquals("София",
            WeatherStore.simplePlaceLabel("Район София"))
        assertEquals("Варна",
            WeatherStore.simplePlaceLabel("Станция Варна (LBWN)"))
        val observed = WeatherStore.Snapshot(
            temperatureC = 12.0,
            weatherCode = 2,
            observedAtMs = 10L,
            isDay = true,
            originLabel = "Станция София (LBSF)",
            sourceLabel = "NOAA · измерване METAR",
            reportTimeMs = 10L,
            autoSelected = true
        )
        assertEquals("София", observed.artworkSource)
        // Retain full provenance for phone source/status.
        assertEquals("Станция София (LBSF)", observed.originLabel)
    }

}
