package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class CarLibraryTabsTest {
    private fun folder(id: String, pinned: Boolean = true) =
        StreamLibrary.Station(id, id, "content://test/" + id, local = true, localFolder = true, pinned = pinned)

    @Test fun keepsLibrarySlotAndOrder() {
        val sources = listOf(folder("b"), folder("a"), folder("c"), folder("d"))
        assertEquals(listOf("b", "a", "c"), CarLibraryTabs.pinned(sources, 4).map { it.id })
        assertEquals(listOf("b"), CarLibraryTabs.pinned(sources, 2).map { it.id })
        assertTrue(CarLibraryTabs.pinned(sources, 1).isEmpty())
        assertEquals(4, sources.size)
    }

    @Test fun playableSourcesCanBePinned() {
        val audio = StreamLibrary.Station("audio", "song.mp3", "https://example.com/song.mp3")
        val sources = listOf(folder("a"), folder("b"), folder("c"), folder("d", false), audio)
        assertTrue(CarLibraryTabs.eligible(audio))
        assertFalse(CarLibraryTabs.browsable(audio))
        assertEquals("audio", CarLibraryTabs.sourceId(CarLibraryTabs.sourceTab("audio")))
        assertNull(CarLibraryTabs.toggle(sources, "audio"))
        assertEquals(2, CarLibraryTabs.pinned(sources, 4, true).size)
        assertNull(CarLibraryTabs.toggle(sources, "d"))
        val unpinned = CarLibraryTabs.toggle(sources, "a")!!
        val pinned = CarLibraryTabs.toggle(unpinned, "d")!!
        assertEquals(listOf("b", "c", "d"), CarLibraryTabs.pinned(pinned, 4).map { it.id })
    }

    @Test fun rootLimitIsBounded() {
        assertEquals(4, CarLibraryTabs.rootLimit(CarLibraryTabs.rootId(20)))
        assertEquals(1, CarLibraryTabs.rootLimit(CarLibraryTabs.rootId(0)))
        assertNull(CarLibraryTabs.rootLimit(CarLibraryTabs.LIBRARY_ID))
    }
}
