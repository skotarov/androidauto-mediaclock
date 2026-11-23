package com.kotarov.autoclock.media

import android.content.Intent
import android.content.SharedPreferences
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
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.weather.WeatherStore
import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * v0.31 Play is clock and Pause/Stop is current weather. Browser returns
 * three coming days with icon-only thumbnails and numeric temperatures. Weather API requests are
 * throttled; no foreground activity, audio, wake locks or seconds ticker.
 */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private lateinit var prefs: SharedPreferences
    private var running = false
    private var preset = ClockPreset.ANALOG_PURE
    private var artworkGeneration = 0L
    private var forecastListDirty = false

    private val settingsRefresh = Runnable {
        preset = selectedPreset()
        artworkGeneration += 1L
        if (forecastListDirty) {
            forecastListDirty = false
            notifyChildrenChanged(ROOT_ID)
        }
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ClockPreset.KEY_FAMILY ||
            key == ClockPreset.KEY_ANALOG_ADD_DIGITAL ||
            key == ClockPreset.KEY_ANALOG_ADD_WEATHER ||
            key == ClockPreset.KEY_DIGITAL_ADD_ANALOG ||
            key == ClockPreset.KEY_DIGITAL_ADD_WEATHER ||
            key == KEY_MODE || key == KEY_HIDE_COMPACT_TEXT ||
            key == KEY_LAYOUT || key == ClockTheme.KEY ||
            key == WeatherStore.KEY_COLORED_ICONS ||
            key == WeatherStore.KEY_CITY || key == "weather_cached_city" ||
            key == "weather_cached_mode" || key == "weather_station_label" ||
            key == "weather_source_name" || key == "weather_fetched_at" ||
            key == "weather_code" || key == "weather_temperature_bits" ||
            key == "weather_is_day" || key == "weather_pressure_bits" ||
            key == "weather_elevation_bits" ||
            key == ForecastStore.KEY_SAVED_AT ||
            key == ForecastStore.KEY_MODE
        ) {
            // Observation values update the clock but not the browse forecast.
            // A new forecast or source/theme change refreshes the three rows.
            if (key == ForecastStore.KEY_SAVED_AT || key == ForecastStore.KEY_MODE ||
                key == WeatherStore.KEY_CITY || key == ClockTheme.KEY ||
                key == WeatherStore.KEY_COLORED_ICONS
            ) forecastListDirty = true
            handler.removeCallbacks(settingsRefresh)
            handler.postDelayed(settingsRefresh, 120L)
        }
    }

    /** Clock needs a new image once a minute only during Play. */
    private val minuteTick = object : Runnable {
        override fun run() {
            if (!running) return
            preset = selectedPreset()
            publishClock()
            publishPlaybackState()
            requestWeatherIfNecessary()
            scheduleNextMinute()
        }
    }

    /** Stop retains weather, but doesn't draw 60 identical pictures an hour. */
    private val stoppedWeatherTick = object : Runnable {
        override fun run() {
            if (running) return
            requestWeatherIfNecessary()
            publishClock()
            scheduleStoppedWeatherTick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        preset = selectedPreset()
        session = MediaSessionCompat(this, "AutoClock3DayForecastV030").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    when (mediaId) {
                        ClockLibraryRows.CLOCK_ID -> startClock()
                        ClockLibraryRows.WEATHER_ID -> stopClock()
                        else -> if (ClockLibraryRows.isKnownId(mediaId)) stopClock()
                    }
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (ClockLibraryRows.isKnownId(mediaId)) {
                        publishClock()
                        publishPlaybackState()
                    }
                }

                override fun onPlay() = startClock()
                override fun onPause() = stopClock()
                override fun onStop() = stopClock()
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Initially stopped: the weather is visible until Play.
        stopClock()
    }

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
        val theme = selectedTheme()
        val colored = coloredWeatherIcons()
        val revision = prefs.getLong(ForecastStore.KEY_SAVED_AT, 0L)
        val rows = ClockLibraryRows.create(ForecastStore.current(this), LocalDate.now())
        val items = rows.map { row ->
            val artwork = ClockArtworkProvider.forecastArtworkUri(
                date = row.date,
                forecast = row.forecast,
                revision = revision,
                theme = theme,
                coloredWeatherIcons = colored
            )
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(row.id)
                .setTitle(row.title)
                .setSubtitle(row.subtitle)
                .setIconUri(artwork)
                .build()
            MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()
        result.sendResult(items)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(minuteTick)
        handler.removeCallbacks(stoppedWeatherTick)
        handler.removeCallbacks(settingsRefresh)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun selectedPreset(): ClockPreset = ClockSelectionStore.current(prefs)

    private fun compactLayout(): Boolean =
        prefs.getString(KEY_LAYOUT, "standard") == "compact"

    private fun selectedTheme(): ClockTheme =
        ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null))

    private fun coloredWeatherIcons(): Boolean = WeatherStore.coloredIcons(prefs)

    private fun hideCompactMediaText(): Boolean =
        prefs.getBoolean(KEY_HIDE_COMPACT_TEXT, true)

    private fun startClock() {
        preset = selectedPreset()
        running = true
        handler.removeCallbacks(stoppedWeatherTick)
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
        scheduleNextMinute()
    }

    private fun stopClock() {
        running = false
        handler.removeCallbacks(minuteTick)
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
        scheduleStoppedWeatherTick()
    }

    private fun scheduleStoppedWeatherTick() {
        handler.removeCallbacks(stoppedWeatherTick)
        if (!running) {
            handler.postDelayed(stoppedWeatherTick, WeatherStore.REFRESH_INTERVAL_MS)
        }
    }

    private fun scheduleNextMinute() {
        handler.removeCallbacks(minuteTick)
        if (!running) return
        handler.postDelayed(
            minuteTick,
            MinuteProgress.millisUntilNextMinute(System.currentTimeMillis())
        )
    }

    private fun visiblePreset(): ClockPreset =
        PlaybackDisplay.visiblePreset(preset, running)

    private fun requestWeatherIfNecessary() {
        // A three-hour forecast is fetched in the existing weather worker;
        // the current observations stay capped to 15-minute intervals.
        WeatherStore.refreshIfDue(this)
    }

    private fun publishClock() {
        val now = LocalDateTime.now()
        val visible = visiblePreset()
        val weather = if (visible.usesWeather) WeatherStore.current(this) else null
        val description = weather?.description ?: "Няма данни"
        val lines = ClockMediaLabels.forClock(visible, now, weather)
        val theme = selectedTheme()
        val colored = coloredWeatherIcons()
        val artwork = ClockArtworkProvider.artworkUri(
            visible.id, now, weather?.observedAtMs ?: 0L,
            compactLayout(), artworkGeneration, theme, colored
        )
        val miniArtwork = ClockArtworkProvider.miniArtworkUri(
            visible.family, now,
            if (visible.family == ClockFamily.WEATHER)
                weather?.observedAtMs ?: 0L else 0L,
            theme, colored
        )
        // Split standard title/artist (available to full Now Playing players)
        // from display title/subtitle (used by some compact dashboards).
        // Android Auto may use the same fields in both layouts: its layout
        // state is not exposed by MediaBrowserService / MediaSession APIs.
        val blankCompact = hideCompactMediaText()
        val compactTitle = if (blankCompact) INVISIBLE_TEXT else lines.title
        val compactSubtitle = if (blankCompact) INVISIBLE_TEXT else lines.subtitle
        val activeId = if (running) ClockLibraryRows.CLOCK_ID
            else ClockLibraryRows.WEATHER_ID

        // Exactly ONE active track in the queue, so Next/Previous remain absent.
        val item = MediaDescriptionCompat.Builder()
            .setMediaId(activeId)
            .setTitle(lines.title)
            .setSubtitle(lines.subtitle)
            .setIconUri(artwork)
            .build()
        session.setQueue(listOf(MediaSessionCompat.QueueItem(item, SINGLE_QUEUE_ID)))
        session.setQueueTitle(null)

        // Keep both a direct reduced-size bitmap and an artwork URI to cope
        // with Android Auto implementations that cache either representation.
        val art = ClockRenderer.render(
            visible.id, now, weather?.weatherCode, weather?.isDay ?: true,
            weather?.formattedTemperature, compactLayout(),
            outputSize = 320, condition = description,
            sourceLabel = weather?.artworkSource, theme = theme,
            coloredWeatherIcons = colored
        )
        val miniBitmap = ClockRenderer.renderMini(
            visible.family, now, weather?.weatherCode,
            weather?.isDay ?: true, weather?.formattedTemperature, theme,
            coloredWeatherIcons = colored
        )
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, miniBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, activeId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, lines.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lines.subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, compactTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, compactSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, miniArtwork.toString())
        // No duration or fake audio is provided.
        session.setMetadata(metadata.build())
    }

    private fun publishPlaybackState() {
        val actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (running) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f
                )
                .setActions(actions)
                .setActiveQueueItemId(SINGLE_QUEUE_ID)
                .build()
        )
    }

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_MODE = "display_mode"
        // Fresh key: on upgrade from v0.29 the new default is hidden even
        // when the old, differently-scoped checkbox had been unchecked.
        const val KEY_HIDE_COMPACT_TEXT = "hide_compact_media_text_v30"
        const val KEY_LAYOUT = "clock_layout"
        const val KEY_PROGRESS_ANIMATED = "progress_animated"
        private const val INVISIBLE_TEXT = ClockMediaLabels.EMPTY
        private const val ROOT_ID = "auto_clock_root"
        private const val SINGLE_QUEUE_ID = 1L
    }
}
