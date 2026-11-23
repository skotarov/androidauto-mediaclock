package com.kotarov.autoclock.media

/**
 * Keep real Android Auto browsing separate from suggestion-only controllers.
 * Host may still display a "For You" page using its own recommendations.
 */
object AutoClockBrowseRouting {
    private const val GOOGLE_SEARCH = "com.google.android.googlequicksearchbox"

    fun allowLibrary(clientPackageName: String, suggestedRootRequested: Boolean): Boolean =
        !suggestedRootRequested && clientPackageName != GOOGLE_SEARCH
}
