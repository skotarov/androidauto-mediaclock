package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class NoticeTestTest {
    @Test fun includesEveryCurrentAndForecastWarningAndLocation() {
        val samples = NoticeTest.samples("Plovdiv")
        assertEquals(14, samples.size)
        assertEquals(14, samples.map { it.subtitle }.distinct().size)
        assertEquals("📍 Plovdiv", samples[0].subtitle)
        assertEquals("ℹ️ Plovdiv", samples[1].subtitle)
        assertEquals("ℹ️ Location unavailable", samples[2].subtitle)
        assertTrue(samples.any { it.body == "Precipitation: 80%" })
        assertEquals(5, samples.count { it.body.startsWith("Within 1h: ") })
    }

    @Test fun testIsExplicitlyMarkedWithoutChangingOriginal() {
        val sample = NoticeTest.samples(null).first { it.body == "Within 1h: Heavy rain" }
        assertEquals("🌧️ Test: Within 1h: Heavy rain", NoticeTest.marked(sample).subtitle)
        assertEquals("Test. Within one hour: Heavy rain", NoticeTest.spoken(sample))
        assertEquals("Within 1h: Heavy rain", sample.body)
    }
}
