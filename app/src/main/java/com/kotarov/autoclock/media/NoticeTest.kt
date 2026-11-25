package com.kotarov.autoclock.media

import com.kotarov.autoclock.weather.WeatherWarning

/** In-process, transient test dispatch. Never persists simulated weather or location. */
object NoticeTest {
    // Both callers are on the main thread. The media service owns this callback
    // only while a media client is connected.
    var receiver: ((CityAnnouncement.Notice) -> Unit)? = null

    fun send(notice: CityAnnouncement.Notice): Boolean {
        val active = receiver ?: return false
        active(notice)
        return true
    }

    fun samples(locality: String?): List<CityAnnouncement.Notice> {
        val city = locality?.takeIf { it.isNotBlank() } ?: "Sofia"
        return listOf(
            CityAnnouncement.Notice("📍", city),
            CityAnnouncement.Notice("ℹ️", city),
            CityAnnouncement.Notice("ℹ️", "Location unavailable")
        ) + WeatherWarning.testMessages().map {
            CityAnnouncement.Notice(CityAnnouncement.warningIcon(it), it)
        }
    }

    fun marked(notice: CityAnnouncement.Notice): CityAnnouncement.Notice =
        notice.copy(body = "Test: " + notice.body)

    fun spoken(notice: CityAnnouncement.Notice): String =
        "Test. " + NoticeSpeechText.format(notice.body)
}
