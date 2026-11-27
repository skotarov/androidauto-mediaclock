package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class FolderNavigationTest {
    @Test fun commandsHaveDistinctIdsAndOrder() {
        val choices = FolderNavigation.choices("folder:abc")
        assertEquals(listOf("Play all", "Continue"), choices.map { it.title })
        assertEquals(FolderNavigation.Request("folder:abc", false), FolderNavigation.parse(choices[0].id))
        assertEquals(FolderNavigation.Request("folder:abc", true), FolderNavigation.parse(choices[1].id))
        assertNull(FolderNavigation.parse("foldermedia:abc"))
        assertNull(FolderNavigation.parse("folderplay:"))
    }

    @Test fun resumeTargetsRememberedFileOrFirstAvailable() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", FolderNavigation.target(ids, "b"))
        assertEquals("a", FolderNavigation.target(ids, "deleted"))
        assertEquals("a", FolderNavigation.target(ids, null))
        assertNull(FolderNavigation.target(emptyList(), "b"))
    }
}
