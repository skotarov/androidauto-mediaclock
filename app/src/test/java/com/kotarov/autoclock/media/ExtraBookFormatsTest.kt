package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class ExtraBookFormatsTest {
    @Test fun htmlReadsTextAndDecodesEntitiesWithoutScripts() {
        val result = StringBuilder()
        HtmlBookExtractor.extract("<html><head><title>skip</title></head><body><h1>Книга</h1><p>One &amp; two<br>Three</p><script>secret</script><p hidden>hidden</p></body></html>".byteInputStream(), {}) { result.append(it) }
        val text = result.toString()
        assertTrue(text.contains("Книга"))
        assertTrue(text.contains("One & two"))
        assertTrue(text.contains("Three"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("hidden"))
        assertFalse(text.contains("skip"))
    }

    @Test fun rtfReadsCyrillicCodepageAndUnicodeWithoutFallbackOrImages() {
        val result = StringBuilder()
        val rtf = """{\rtf1\ansi\ansicpg1251{\fonttbl{\f0 Arial;}}\uc1 \'cf\'f0\'e8\'e2\'e5\'f2 \u1041?\u1075?\par{\pict 123456}End}"""
        RtfBookExtractor.extract(rtf.byteInputStream(), {}) { result.append(it) }
        assertEquals("Привет Бг\nEnd", result.toString())
    }

    @Test fun rtfHandlesEscapesAndIgnoresFieldInstructions() {
        val result = StringBuilder()
        val rtf = """{\rtf1\ansi A \{B\} {\field{\*\fldinst HYPERLINK "https://example.org"}{\fldrslt link}}}"""
        RtfBookExtractor.extract(rtf.byteInputStream(), {}) { result.append(it) }
        assertEquals("A {B} link", result.toString())
    }
}
