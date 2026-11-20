package com.kotarov.autoclock.media

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.kotarov.autoclock.artwork.ClockArtworkProvider
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Low-power media browser experiment:
 * - Only one minute-aligned handler while actively selected in Android Auto
 * - No GPS, wakelock, silent audio track, or foreground-service restart
 * - Weather fetched only when the user enables the weather overlay or selects weather
 *
 * Android Auto's audio-media UI is not intended for standalone clocks.
 * Support for the dashboard card depends on the Android Auto version.
 */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private var running = false
    private var mode = Mode.DIGITAL
    private lateinit var prefs: SharedPreferences
    private var artworkGeneration = 0L

    // SharedPreferences changes from the phone UI arrive in the same process.
    // Debounce related weather cache writes into a single metadata refresh.
    private val settingsRefresh = Runnable {
        mode = savedMode()
        artworkGeneration += 1L
        notifyChildrenChanged(ROOT_ID)
        publishClock()
        publishPlaybackState()
        if (running) requestWeatherIfNecessary()
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_MODE || key == KEY_HIDE_MEDIA_TEXT ||
            key == KEY_LAYOUT || key == KEY_PROGRESS_ANIMATED ||
            key == WeatherStore.KEY_CITY || key == "weather_cached_city" ||
            key == "weather_fetched_at" || key == "weather_code" ||
            key == "weather_temperature_bits" || key == "weather_is_day"
        ) {
            handler.removeCallbacks(settingsRefresh)
            handler.postDelayed(settingsRefresh, 120L)
        }
    }

    private val minuteTick = object : Runnable {
        override fun run() {
            if (!running) return
            mode = savedMode()
            publishClock()
            // Re-anchor the progress to the actual seconds when the minute changes.
            // Android Auto can animate the remainder without per-second messages.
            publishPlaybackState()
            requestWeatherIfNecessary()
            scheduleNextMinute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
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
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
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
                .setIconUri(
                    ClockArtworkProvider.artworkUri(
                        option.artwork, now,
                        if (option.usesWeather) revision else 0L,
                        compactLayout(),
                        artworkGeneration
                    )
                )
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
        handler.removeCallbacks(settingsRefresh)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun compactLayout(): Boolean =
        prefs.getString(KEY_LAYOUT, "compact") != "standard"

    private fun animateProgress(): Boolean =
        prefs.getBoolean(KEY_PROGRESS_ANIMATED, true)

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
        handler.postDelayed(minuteTick, MinuteProgress.millisUntilNextMinute(now))
    }

        private fun hideMediaText(): Boolean =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_HIDE_MEDIA_TEXT, true)

    // Pure analog is always weather-free; only explicit weather modes fetch data.
    private fun wantsWeather(): Boolean = mode.usesWeather

    private fun requestWeatherIfNecessary() {
        if (!running || !wantsWeather()) return
        WeatherStore.refreshIfDue(this) { success ->
            if (success && running && wantsWeather()) publishClock()
        }
    }

    private fun weatherRevision(): Long = WeatherStore.current(this)?.observedAtMs ?: 0L

    private fun publishClock() {
        val now = LocalDateTime.now()
        val weather = if (wantsWeather()) WeatherStore.current(this) else null
        val weatherLabel = if (weather == null) "Няма данни"
        else "${WeatherStore.symbol(weather.weatherCode, weather.isDay)} ${weather.formattedTemperature}"
        val weatherDescription = weather?.description ?: "Няма данни"
        val city = WeatherStore.city(this).label
        val time = now.format(TIME_FORMAT)
        val date = now.format(DATE_FORMAT)

        val title = when (mode) {
            Mode.DIGITAL, Mode.ANALOG -> time
            Mode.WEATHER -> weatherLabel
            Mode.BOTH -> if (weather == null) time else "$time • $weatherLabel"
        }
        val subtitle = when (mode) {
            Mode.WEATHER, Mode.BOTH -> "$city • $weatherDescription"
            else -> date
        }
        val artwork = ClockArtworkProvider.artworkUri(
            mode.artwork, now, weather?.observedAtMs ?: 0L,
            compactLayout(), artworkGeneration
        )

        // Android Auto decides whether to render a media title at all.
        // Supplying a non-breaking space is a best-effort experiment; some
        // hosts may replace it with a fallback label or retain an empty area.
        // Keep the real labels available as a phone-side fallback setting.
        val cleanArtwork = hideMediaText()
        val displayedTitle = if (cleanArtwork) INVISIBLE_TEXT else title
        val displayedSubtitle = if (cleanArtwork) INVISIBLE_TEXT else subtitle
        val displayedDescription = if (cleanArtwork) INVISIBLE_TEXT else mode.title
        val displayedAlbum = if (cleanArtwork) INVISIBLE_TEXT else "Auto Clock 2"

        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mode.id)
            .setTitle(displayedTitle)
            .setSubtitle(displayedSubtitle)
            .setDescription(displayedDescription)
            .setIconUri(artwork)
            .build()

        session.setQueueTitle(displayedAlbum)
        session.setQueue(listOf(MediaSessionCompat.QueueItem(description, mode.queueId)))
        // Some Android Auto head units cache content URIs for the whole card.
        // A small direct Bitmap alongside the URI nudges the media host to
        // repaint on a phone-side size/mode change without restarting playback.
        // 320x320 RGB_565 is approximately 200 KiB, avoiding large binder IPC.
        val artBitmap = ClockRenderer.render(
            mode.artwork, now, weather?.weatherCode,
            weather?.isDay ?: true, weather?.formattedTemperature,
            compactLayout(), outputSize = 320
        )
        val metadata = MediaMetadataCompat.Builder()
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artBitmap)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mode.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayedTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayedSubtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, displayedAlbum)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayedTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayedSubtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayedDescription)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artwork.toString())
        if (animateProgress()) metadata.putLong(
            MediaMetadataCompat.METADATA_KEY_DURATION, MinuteProgress.DURATION_MS
        )
        session.setMetadata(metadata.build())
    }

    private fun publishPlaybackState() {
        // Capture the position from wall time and the monotonic anchor together.
        // A start at HH:mm:24 starts at ~24/60 instead of the beginning.
        val nowWallMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val progressEnabled = animateProgress()
        val currentSecondPositionMs = if (progressEnabled)
            MinuteProgress.positionMs(nowWallMs)
        else PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
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
                    currentSecondPositionMs,
                    if (running && progressEnabled) 1f else 0f,
                    nowElapsedMs
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
        const val KEY_HIDE_MEDIA_TEXT = "hide_media_text"
        const val KEY_LAYOUT = "clock_layout"
        const val KEY_PROGRESS_ANIMATED = "progress_animated"
        private const val INVISIBLE_TEXT = "\u00A0"
        private const val ROOT_ID = "auto_clock_root"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale("bg", "BG"))
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale("bg", "BG"))
    }
}
