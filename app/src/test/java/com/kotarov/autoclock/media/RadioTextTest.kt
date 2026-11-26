package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class RadioTextTest {
    @Test fun jsonPrefersUtf8EvenWithWrongHeader() {
        val value = "[{\"name\":\"Радио София\"}]"
        assertEquals(value, RadioText.decode(value.toByteArray(Charsets.UTF_8), "ISO-8859-1", true))
    }
    @Test fun legacyCyrillicWithoutHeader() {
        val value = "#EXTINF:-1,Радио София"
        assertEquals(value, RadioText.decode(value.toByteArray(Charset.forName("windows-1251")), null, false))
    }
    @Test fun damagedServerNameUsesAddressAndValidNameRemains() {
        assertEquals("Радио София", RadioText.cleanName("Радио София", "https://example.org/live"))
        assertEquals("example.org · live", RadioText.cleanName("\uFFFD\uFFFD", "https://example.org/live"))
    }
}
