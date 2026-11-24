package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class CityAnnouncementTest {
    @Test fun changedCityShowsForEightSecondsWithoutRepeating() {
        val city = CityAnnouncement()
        assertFalse(city.update("София", 100L))
        assertNull(city.title(100L))
        assertTrue(city.update("Пазарджик", 1000L))
        assertEquals("Пазарджик", city.title(8999L))
        assertFalse(city.update("Пазарджик", 5000L))
        assertFalse(city.update(null, 6000L))
        assertNull(city.title(9000L))
        assertFalse(city.update("Пазарджик", 10000L))
    }

    @Test fun manualRequestRepeatsSameCityForEightSeconds() {
        val city = CityAnnouncement()
        city.update("София", 0L)
        city.show("София", 1000L)
        assertEquals("София", city.title(8999L))
        city.show("София", 5000L)
        assertFalse(city.update("София", 6000L))
        assertEquals("София", city.title(12999L))
        assertNull(city.title(13000L))
    }

    @Test fun manualRequestWithoutLocationDoesNotInventCity() {
        val city = CityAnnouncement()
        city.show(null, 1000L)
        assertEquals("Няма данни за локацията", city.title(1000L))
        assertNull(city.title(9000L))
    }

    @Test fun newCityReplacesAnActiveTitleAndRestartsEightSeconds() {
        val city = CityAnnouncement()
        city.update("София", 0L)
        city.update("Пазарджик", 1000L)
        assertTrue(city.update("Пловдив", 4000L))
        assertEquals("Пловдив", city.title(11999L))
        assertNull(city.title(12000L))
    }
}
