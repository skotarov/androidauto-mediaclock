package com.kotarov.autoclock.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherStoreTest {
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

    @Test fun unconfiguredWeatherDefaultsToAutoButRespectsManualCity() {
        assertEquals(WeatherStore.AUTO_ID, WeatherStore.selectedMode(null))
        assertEquals(WeatherStore.AUTO_ID, WeatherStore.selectedMode("auto"))
        assertEquals("sofia", WeatherStore.selectedMode("sofia"))
        assertEquals("varna", WeatherStore.selectedMode("varna"))
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

    @Test fun unknownCityFallsBackToSofia() {
        assertEquals(WeatherStore.City.SOFIA, WeatherStore.City.byId("unknown"))
        assertEquals(WeatherStore.City.SOFIA, WeatherStore.City.byId(null))
    }

    @Test fun configuredCitiesAreUniqueAndHaveValidCoordinates() {
        val cities = WeatherStore.City.entries
        assertEquals(cities.size, cities.map { it.id }.toSet().size)
        cities.forEach {
            assertTrue(it.lat in -90.0..90.0)
            assertTrue(it.lon in -180.0..180.0)
        }
    }
}
