package com.kotarov.autoclock.weather

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Identified Bulgarian aerodrome meteorological stations.
 * Sources: ICAO codes, national AIP, NOAA station records.
 * These are observation points, not arbitrary model grid points.
 */
object WeatherStations {
    data class Station(val icao: String, val name: String, val lat: Double, val lon: Double) {
        val label: String get() = "$name ($icao)"
    }

    val stations = listOf(
        Station("LBSF", "Sofia", 42.6964, 23.4177),
        Station("LBPD", "Plovdiv", 42.0678, 24.8508),
        Station("LBPG", "Graf Ignatievo", 42.2908, 24.7139),
        Station("LBGO", "Gorna Oryahovitsa", 43.1514, 25.7129),
        Station("LBBG", "Burgas", 42.5699, 27.5152),
        Station("LBWN", "Varna", 43.2321, 27.8251),
        Station("LBPL", "Dolna Mitropolia", 43.4517, 24.5028),
        Station("LBWB", "Balchik", 43.4244, 28.1808)
    )

    fun kilometers(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2)
        return radius * 2 * asin(min(1.0, sqrt(a)))
    }

    fun nearest(lat: Double, lon: Double, available: Set<String>): Station? =
        stations.asSequence()
            .filter { it.icao in available }
            .minByOrNull { kilometers(lat, lon, it.lat, it.lon) }
}
