package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class CityAnnouncementTest {
    @Test fun changedCityShowsForEightSecondsWithoutRepeating() {
        val city = CityAnnouncement()
        assertFalse(city.update("Sofia", 100L))
        assertNull(city.title(100L))
        assertTrue(city.update("Pazardzhik", 1000L))
        assertEquals("Pazardzhik", city.title(8999L))
        assertFalse(city.update("Pazardzhik", 5000L))
        assertFalse(city.update(null, 6000L))
        assertNull(city.title(9000L))
        assertFalse(city.update("Pazardzhik", 10000L))
    }

    @Test fun manualRequestRepeatsSameCityForEightSeconds() {
        val city = CityAnnouncement()
        city.update("Sofia", 0L)
        city.show("Sofia", 1000L)
        assertEquals("Sofia", city.title(8999L))
        city.show("Sofia", 5000L)
        assertFalse(city.update("Sofia", 6000L))
        assertEquals("Sofia", city.title(12999L))
        assertNull(city.title(13000L))
    }

    @Test fun manualRequestWithoutLocationDoesNotInventCity() {
        val city = CityAnnouncement()
        city.show(null, 1000L)
        assertEquals("Location unavailable", city.title(1000L))
        assertNull(city.title(9000L))
    }

    @Test fun locationAndWarningHaveDifferentHeadingsAndTheSameTimeout() {
        val city = CityAnnouncement()
        city.show("Sofia", 1000L)
        assertEquals(CityAnnouncement.Notice("Info", "Sofia"), city.notice(1000L))
        city.showWarning("Fog", 9000L)
        assertEquals(CityAnnouncement.Notice("Warning", "Fog"), city.notice(16999L))
        assertNull(city.notice(17000L))
    }

    @Test fun newCityReplacesAnActiveTitleAndRestartsEightSeconds() {
        val city = CityAnnouncement()
        city.update("Sofia", 0L)
        city.update("Pazardzhik", 1000L)
        assertTrue(city.update("Plovdiv", 4000L))
        assertEquals("Plovdiv", city.title(11999L))
        assertNull(city.title(12000L))
    }
}
