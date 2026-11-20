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
