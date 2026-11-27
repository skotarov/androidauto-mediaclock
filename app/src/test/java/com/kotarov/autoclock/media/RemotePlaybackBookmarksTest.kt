package com.kotarov.autoclock.media

import org.junit.Assert.*
import org.junit.Test

class RemotePlaybackBookmarksTest {
    @Test fun independentSourcesKeepSeparateKeys() {
        val url = "https://example.com/audio"
        assertNotEquals(RemotePlaybackBookmarks.key("stream:a", "stream:a", url),
            RemotePlaybackBookmarks.key("stream:b", "stream:b", url))
        assertNotEquals(RemotePlaybackBookmarks.key("page:a", "webmedia:a", url),
            RemotePlaybackBookmarks.key("page:a", "webmedia:b", url))
    }
    @Test fun roundTripAndResume() {
        val state = CustomStreamPlayer.SeekState(42_000, 120_000)
        assertEquals(state, RemotePlaybackBookmarks.decode(RemotePlaybackBookmarks.encode(state)))
        assertEquals(42_000, RemotePlaybackBookmarks.resumePosition(state, 120_000))
        assertEquals(0, RemotePlaybackBookmarks.resumePosition(state, 40_000))
        assertEquals(0, RemotePlaybackBookmarks.resumePosition(CustomStreamPlayer.SeekState(120_000, 120_000), 120_000))
        assertNull(RemotePlaybackBookmarks.decode(-1L))
    }
}
