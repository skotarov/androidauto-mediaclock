package com.kotarov.autoclock.media

/** Information-only browser entry. It is never a playback media ID. */
object AutoClockInfo {
    const val ID = "autoclock_version_info"

    fun title(versionName: String?): String {
        val version = versionName?.substringBefore("-")?.removeSuffix(".0")
            ?.takeIf { it.isNotBlank() }
        return if (version == null) "Auto Clock" else "Auto Clock v.$version"
    }
}
