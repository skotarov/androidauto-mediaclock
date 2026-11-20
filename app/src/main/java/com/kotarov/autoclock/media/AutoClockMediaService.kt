package com.kotarov.autoclock.media

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.kotarov.autoclock.artwork.ClockArtworkProvider
import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Low-power media browser experiment:
 * - Only one minute-aligned handler while actively selected in Android Auto
 * - No GPS, wakelock, silent audio track, or foreground-service restart
 * - Weather fetched only for a selected weather display, at most hourly
 *
 * Android Auto's audio-media UI is not intended for standalone clocks.
 * Support for the dashboard card depends on the Android Auto version.
 */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private var running = false
    private var mode = Mode.DIGITAL

    private val minuteTick = object : Runnable {
        override fun run() {
            if (!running) return
            mode = savedMode()
            publishClock()
            requestWeatherIfNecessary()
            scheduleNextMinute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        mode = savedMode()
        session = MediaSessionCompat(this, "AutoClockV2").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    mode = savedMode()
                    startClock()
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    chooseMode(Mode.fromId(mediaId))
                    startClock()
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    chooseMode(Mode.fromId(mediaId))
                    publishClock()
                    publishPlaybackState()
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val search = query?.lowercase(Locale.ROOT).orEmpty()
                    val requested = when {
                        "weather" in search || "врем" in search -> Mode.WEATHER
                        "analog" in search || "аналог" in search -> Mode.ANALOG
                        else -> Mode.DIGITAL
                    }
                    chooseMode(requested)
                    startClock()
                }

                override fun onPause() = stopClock()
                override fun onStop() = stopClock()

                override fun onSkipToNext() {
                    chooseMode(Mode.entries[(mode.ordinal + 1) % Mode.entries.size])
                    startClock()
                }

                override fun onSkipToPrevious() {
                    chooseMode(Mode.entries[(mode.ordinal + Mode.entries.size - 1) % Mode.entries.size])
                    startClock()
                }
            })
            setQueueTitle("Auto Clock 2")
            isActive = true
        }
        sessionToken = session.sessionToken
        publishClock()
        publishPlaybackState()
    }

    // Android Auto binds to this service. Never start the clock just because
    // Android recreates a stopped process, and never ask for sticky restarts.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val now = LocalDateTime.now()
        val revision = weatherRevision()
        val items = Mode.entries.map { option ->
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(option.id)
                .setTitle(option.title)
                .setSubtitle(option.description)
                .setIconUri(ClockArtworkProvider.artworkUri(option.artwork, now, revision))
                .build()
            MediaBrowserCompat.MediaItem(
                description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()
        result.sendResult(items)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(minuteTick)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun savedMode(): Mode {
        val key = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_MODE, null)
        return Mode.fromId(key)
    }

    private fun chooseMode(value: Mode) {
        mode = value
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit().putString(KEY_MODE, value.id).apply()
    }

    private fun startClock() {
        running = true
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
        scheduleNextMinute()
    }

    private fun stopClock() {
        running = false
        handler.removeCallbacks(minuteTick)
        publishPlaybackState()
    }

    private fun scheduleNextMinute() {
        handler.removeCallbacks(minuteTick)
        if (!running) return
        val now = System.currentTimeMillis()
        handler.postDelayed(minuteTick, 60_000L - now % 60_000L + 150L)
    }

    private fun requestWeatherIfNecessary() {
        if (!running || !mode.usesWeather) return
        WeatherStore.refreshIfDue(this) { success ->
            if (success && running && mode.usesWeather) publishClock()
        }
    }

    private fun weatherRevision(): Long = WeatherStore.current(this)?.observedAtMs ?: 0L

    private fun publishClock() {
        val now = LocalDateTime.now()
        val weather = if (mode.usesWeather) WeatherStore.current(this) else null
        val weatherText = weather?.formattedTemperature ?: "--°C"
        val weatherDescription = weather?.description ?: "Няма данни"
        val city = WeatherStore.city(this).label
        val time = now.format(TIME_FORMAT)
        val date = now.format(DATE_FORMAT)

        val title = when (mode) {
            Mode.DIGITAL, Mode.ANALOG -> time
            Mode.WEATHER -> weatherText
            Mode.BOTH -> "$time • $weatherText"
        }
        val subtitle = when (mode) {
            Mode.WEATHER, Mode.BOTH -> "$city • $weatherDescription"
            else -> date
        }
        val artwork = ClockArtworkProvider.artworkUri(
            mode.artwork,
            now,
            if (mode.usesWeather) weather?.observedAtMs ?: 0L else 0L
        )

        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mode.id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(mode.title)
            .setIconUri(artwork)
            .build()

        session.setQueue(listOf(MediaSessionCompat.QueueItem(description, mode.queueId)))
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mode.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Auto Clock 2")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, mode.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artwork.toString())
                .build()
        )
    }

    private fun publishPlaybackState() {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (running) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    0L,
                    if (running) 1f else 0f
                )
                .setActions(actions)
                .setActiveQueueItemId(mode.queueId)
                .build()
        )
    }

    private enum class Mode(
        val id: String,
        val title: String,
        val description: String,
        val artwork: String,
        val queueId: Long,
        val usesWeather: Boolean
    ) {
        DIGITAL("digital", "Часовник", "Цифров: час и дата", "clock", 1L, false),
        ANALOG("analog", "Аналогов часовник", "Циферблат със стрелки", "analog", 2L, false),
        WEATHER("weather", "Времето", "Температура и условия", "weather", 3L, true),
        BOTH("both", "Часовник + време", "Час, температура и условия", "both", 4L, true);

        companion object {
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: DIGITAL
        }
    }

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_MODE = "display_mode"
        private const val ROOT_ID = "auto_clock_root"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale("bg", "BG"))
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale("bg", "BG"))
    }
}
