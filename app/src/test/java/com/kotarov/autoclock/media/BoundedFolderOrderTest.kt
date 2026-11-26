package com.kotarov.autoclock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedFolderOrderTest {
    @Test fun keepsFirstHundredEvenInReverseProviderOrder() {
        val entries = (1..10000).map { "track-" + it.toString().padStart(5, '0') }
        val bounded = BoundedFolderOrder<String>(100, naturalOrder())
        entries.asReversed().forEach(bounded::add)
        assertEquals(entries.take(100), bounded.sorted())
    }
    @Test fun retainsShortLists() {
        val bounded = BoundedFolderOrder<String>(100, naturalOrder())
        listOf("c", "a", "b").forEach(bounded::add)
        assertEquals(listOf("a", "b", "c"), bounded.sorted())
    }
}
