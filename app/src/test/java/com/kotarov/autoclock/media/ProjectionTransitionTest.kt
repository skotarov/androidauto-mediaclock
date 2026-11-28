package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class ProjectionTransitionTest {
    @Test fun standaloneLaunchDoesNotClose() {
        val t = ProjectionTransition()
        assertNull(t.update(false, true))
        assertNull(t.update(false, true))
    }
    @Test fun closesExactlyOnceAfterProjectionEnds() {
        val t = ProjectionTransition()
        assertEquals(true, t.update(true, false))
        assertNull(t.update(true, false))
        assertNull(t.update(false, false))
        assertEquals(false, t.update(false, true))
        assertNull(t.update(false, true))
        assertEquals(true, t.update(true, false))
        assertEquals(false, t.update(false, true))
    }
}
