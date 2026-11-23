package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockThemeTest {
    @Test fun darkIsTheDefault() {
        assertEquals(ClockTheme.DARK, ClockTheme.fromId(null))
        assertEquals(ClockTheme.DARK, ClockTheme.fromId("invalid"))
    }
    @Test fun lightThemeCanBeSelected() {
        assertEquals(ClockTheme.LIGHT, ClockTheme.fromId("light"))
    }
}
