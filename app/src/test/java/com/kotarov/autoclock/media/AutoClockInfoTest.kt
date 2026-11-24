package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutoClockInfoTest {
    @Test fun titleUsesInstalledVersion() {
        assertEquals("Auto Clock v.0.51", AutoClockInfo.title("0.51.0-beta"))
        assertEquals("Auto Clock v.0.52", AutoClockInfo.title("0.52.0-beta"))
        assertEquals("Auto Clock", AutoClockInfo.title(null))
    }

    @Test fun informationIsNotAPlaybackItem() {
        assertNotEquals(ClockLibraryRows.CLOCK_ID, AutoClockInfo.ID)
        assertNotEquals(ClockLibraryRows.WEATHER_ID, AutoClockInfo.ID)
    }
}
