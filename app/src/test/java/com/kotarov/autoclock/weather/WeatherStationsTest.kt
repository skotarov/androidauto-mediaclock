package com.kotarov.autoclock.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherStationsTest {
    @Test fun stationCoordinatesAndCodesAreUnique() {
        assertEquals(8, WeatherStations.stations.size)
        assertEquals(8, WeatherStations.stations.map { it.icao }.toSet().size)
        WeatherStations.stations.forEach {
            assertTrue(it.lat in -90.0..90.0)
            assertTrue(it.lon in -180.0..180.0)
            assertTrue(it.icao.length == 4)
        }
    }

    @Test fun picksNearestAvailableReportingStation() {
        val all = WeatherStations.stations.map { it.icao }.toSet()
        assertEquals("LBSF",
            WeatherStations.nearest(42.70, 23.39, all)?.icao)
        assertEquals("LBWN",
            WeatherStations.nearest(43.22, 27.83, all)?.icao)
        assertEquals("LBPG",
            WeatherStations.nearest(42.29, 24.71, all)?.icao)
    }

    @Test fun ignoresStationsWithoutFreshObservation() {
        assertEquals("LBPD",
            WeatherStations.nearest(42.70, 23.39, setOf("LBPD"))?.icao)
        assertEquals(null,
            WeatherStations.nearest(42.70, 23.39, emptySet()))
    }

    @Test fun geographicDistanceIsSymmetricAndZeroAtSamePlace() {
        assertEquals(0.0,
            WeatherStations.kilometers(42.7, 23.4, 42.7, 23.4), 0.000001)
        val a = WeatherStations.kilometers(42.7, 23.4, 43.2, 27.8)
        val b = WeatherStations.kilometers(43.2, 27.8, 42.7, 23.4)
        assertEquals(a, b, 0.00001)
        assertTrue(a > 100.0)
    }
}
