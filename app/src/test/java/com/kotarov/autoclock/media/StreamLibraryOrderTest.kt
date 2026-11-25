package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLibraryOrderTest {
    private val a = StreamLibrary.Station("a", "A", "https://example.com/a")
    private val b = StreamLibrary.Station("b", "B", "content://example/b", local = true)
    private val c = StreamLibrary.Station("c", "C", "https://example.com/c")
    private val items = listOf(a,b,c)

    @Test fun mixedSourcesReorderBothDirections() {
        assertEquals(listOf(b,c,a), StreamLibrary.move(items,"a","c",true))
        assertEquals(listOf(c,a,b), StreamLibrary.move(items,"c","a",false))
        assertEquals(listOf(a,c,b), StreamLibrary.move(items,"b","c",true))
    }

    @Test fun invalidMoveDoesNotChangeList() {
        assertEquals(items, StreamLibrary.move(items,"a",StreamLibrary.ALERT_ID,false))
        assertEquals(items, StreamLibrary.move(items,"b","b",false))
    }
}
