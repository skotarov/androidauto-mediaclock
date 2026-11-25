package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class CityAnnouncementTest {
    @Test fun changedCityShowsForFifteenSecondsWithoutRepeating() {
        val city = CityAnnouncement()
        assertFalse(city.update("Sofia", 100L))
        assertNull(city.title(100L))
        assertTrue(city.update("Pazardzhik", 1000L))
        assertEquals("Pazardzhik", city.title(15999L))
        assertEquals(CityAnnouncement.Notice("📍", "Pazardzhik"), city.notice(15999L))
        assertFalse(city.update("Pazardzhik", 5000L))
        assertFalse(city.update(null, 6000L))
        assertNull(city.title(16000L))
        assertFalse(city.update("Pazardzhik", 10000L))
    }

    @Test fun manualRequestRepeatsSameCityForFifteenSeconds() {
        val city = CityAnnouncement()
        city.update("Sofia", 0L)
        city.show("Sofia", 1000L)
        assertEquals("Sofia", city.title(15999L))
        city.show("Sofia", 5000L)
        assertFalse(city.update("Sofia", 6000L))
        assertEquals("Sofia", city.title(19999L))
        assertNull(city.title(20000L))
    }

    @Test fun manualRequestWithoutLocationDoesNotInventCity() {
        val city = CityAnnouncement()
        city.show(null, 1000L)
        assertEquals("Location unavailable", city.title(1000L))
        assertNull(city.title(16000L))
    }

    @Test fun locationAndWarningHaveDifferentHeadingsAndTheSameTimeout() {
        val city = CityAnnouncement()
        city.show("Sofia", 1000L)
        assertEquals(CityAnnouncement.Notice("ℹ️", "Sofia"), city.notice(1000L))
        city.showWarning("Fog", 16000L)
        assertEquals(CityAnnouncement.Notice("🌫️", "Fog"), city.notice(30999L))
        assertNull(city.notice(31000L))
    }

    @Test fun warningIconsKeepFullSubtitle() {
        val city = CityAnnouncement()
        val cases = mapOf(
            "Thunderstorm" to "⛈️",
            "Within 1h: Thunderstorm" to "⛈️",
            "Fog" to "🌫️",
            "Heavy rain" to "🌧️",
            "Precipitation: 80%" to "🌧️",
            "Heavy snow" to "❄️",
            "Risk of ice" to "⚠️",
            "Unknown warning" to "⚠️"
        )
        cases.forEach { (message, icon) ->
            city.showWarning(message, 1000L)
            assertEquals(CityAnnouncement.Notice(icon, message), city.notice(1000L))
            assertEquals("$icon $message", city.notice(1000L)?.subtitle)
        }
    }

    @Test fun newCityReplacesAnActiveTitleAndRestartsFifteenSeconds() {
        val city = CityAnnouncement()
        city.update("Sofia", 0L)
        city.update("Pazardzhik", 1000L)
        assertTrue(city.update("Plovdiv", 4000L))
        assertEquals("Plovdiv", city.title(18999L))
        assertNull(city.title(19000L))
    }
}
