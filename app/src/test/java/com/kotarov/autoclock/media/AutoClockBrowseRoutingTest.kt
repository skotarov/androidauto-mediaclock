package com.kotarov.autoclock.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoClockBrowseRoutingTest {
    @Test fun regularAndroidAutoLibraryCanBrowse() {
        assertTrue(AutoClockBrowseRouting.allowLibrary(
            "com.google.android.projection.gearhead", false))
    }

    @Test fun recommendationHintDeclinesBrowserRoot() {
        assertFalse(AutoClockBrowseRouting.allowLibrary(
            "com.google.android.projection.gearhead", true))
    }

    @Test fun separateGoogleSearchRecommendationsDeclined() {
        assertFalse(AutoClockBrowseRouting.allowLibrary(
            "com.google.android.googlequicksearchbox", false))
    }

    @Test fun otherNormalMediaClientsRemainSupported() {
        assertTrue(AutoClockBrowseRouting.allowLibrary(
            "androidx.media.test", false))
    }
}
