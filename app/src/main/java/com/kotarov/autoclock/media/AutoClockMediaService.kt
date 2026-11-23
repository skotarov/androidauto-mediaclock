package com.kotarov.autoclock.media

import android.app.PendingIntent
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.LocalDateTime

/**
 * v0.36: confirmed v0.35 routing keeps the map dashboard on the clock
 * while the normal full-screen library exposes SIX next-hour weather rows.
 * No minute browse refresh. No public Android Auto fullscreen/compact
 * callback exists: full date is supplied in standard track metadata only.
 */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private lateinit var prefs: SharedPreferences
    private var running = false
    private var preset = ClockPreset.ANALOG_PURE
    private var artworkGeneration = 0L
    private var forecastListDirty = false
    private var lastBrowseTestEnabled: Boolean? = null

    private val settingsRefresh = Runnable {
        preset = selectedPreset()
        artworkGeneration += 1L
        updateSessionBrowseLink()
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
            key == KEY_MODE ||
            key == KEY_TEST_DIRECT_BROWSE || key == KEY_TEST_STATE_NONE ||
            key == KEY_LAYOUT || key == ClockTheme.KEY ||
            key == WeatherStore.KEY_CITY || key == "weather_cached_city" ||
            key == "weather_cached_mode" || key == "weather_station_label" ||
            key == "weather_source_name" || key == "weather_fetched_at" ||
            key == "weather_code" || key == "weather_temperature_bits" ||
            key == "weather_is_day" || key == "weather_pressure_bits" ||
            key == "weather_elevation_bits" ||
            key == ForecastStore.KEY_SAVED_AT ||
            key == ForecastStore.KEY_MODE
        ) {
            // Hour rows roll forward on the next observation (up to 15m),
            // or immediately when a new hourly model forecast is saved.
            // No per-minute browse update or extra location request.
            if (key == ForecastStore.KEY_SAVED_AT || key == ForecastStore.KEY_MODE ||
                key == "weather_fetched_at" ||
                key == WeatherStore.KEY_CITY || key == ClockTheme.KEY
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
        // Force automatic local weather even when Android Auto starts the
        // service before the phone settings activity is opened.
        if (prefs.getString(WeatherStore.KEY_CITY, null) != WeatherStore.AUTO_ID) {
            WeatherStore.setCity(this, WeatherStore.AUTO_ID)
        }
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        preset = selectedPreset()
        session = MediaSessionCompat(this, "AutoClockMediaTestsV037").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    when (mediaId) {
                        ClockLibraryRows.CLOCK_ID -> startClock()
                        ClockLibraryRows.WEATHER_ID -> stopClock(forceRefresh = true)
                        else -> if (ClockLibraryRows.isKnownId(mediaId))
                            stopClock(forceRefresh = true)
                    }
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (ClockLibraryRows.isKnownId(mediaId)) {
                        publishClock()
                        publishPlaybackState()
                    }
                }

                override fun onPlay() = startClock()
                override fun onPause() = stopClock(forceRefresh = true)
                override fun onStop() = stopClock(forceRefresh = true)
            })
            isActive = true
        }
        // The playback queue stays empty. The separate browser library is
        // visible only to ordinary media browsers, not suggestion callers.
        session.setQueue(emptyList())
        session.setQueueTitle(null)
        updateSessionBrowseLink()
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
    ): BrowserRoot? {
        val requestedSuggestions = rootHints?.getBoolean(
            BrowserRoot.EXTRA_SUGGESTED, false
        ) ?: false
        // Google Assistant / search has been observed requesting the Auto
        // dashboard "For You" recommendations as a different controller.
        // Refuse recommendations, NOT the real Android Auto media library.
        if (!AutoClockBrowseRouting.allowLibrary(
                clientPackageName, requestedSuggestions
            )) return null
        return BrowserRoot(ROOT_ID, null)
    }

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
        val epoch = System.currentTimeMillis() / 1000L
        val rows = ClockLibraryRows.create(ForecastStore.current(this, epoch), epoch)
        val items = rows.map { row ->
            val artwork = ClockArtworkProvider.forecastArtworkUri(
                epochSeconds = row.epochSeconds,
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

    /**
     * Test 1: offer a session-activity deep link ONLY if the user opted in.
     * The host may ignore it. Never launch an Activity from this background
     * service; only a user/host invocation can follow the PendingIntent.
     */
    private fun updateSessionBrowseLink() {
        val enabled = prefs.getBoolean(KEY_TEST_DIRECT_BROWSE, false)
        if (lastBrowseTestEnabled == enabled) return
        lastBrowseTestEnabled = enabled
        val activity = if (enabled) {
            PendingIntent.getActivity(
                this,
                37,
                Intent(this, AutoClockBrowseTrampolineActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        session.setSessionActivity(activity)
    }

    private fun selectedPreset(): ClockPreset = ClockSelectionStore.current(prefs)

    private fun compactLayout(): Boolean =
        prefs.getString(KEY_LAYOUT, "standard") == "compact"

    private fun selectedTheme(): ClockTheme =
        ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null))

    // The built-in Canvas glyphs remain; no external icon library or
    // colorful-icon resource package is used. No color preference exists.
    private fun coloredWeatherIcons(): Boolean = true

    // No compact-text setting: retain only the standard title/artist/date
    // metadata for hosts that display it in full-screen Now Playing.
    private fun hideCompactMediaText(): Boolean = true

    private fun startClock() {
        preset = selectedPreset()
        running = true
        handler.removeCallbacks(stoppedWeatherTick)
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
        scheduleNextMinute()
    }

    private fun stopClock(forceRefresh: Boolean = false) {
        running = false
        handler.removeCallbacks(minuteTick)
        publishClock()
        publishPlaybackState()
        // Pause/Stop explicitly requests fresh weather. The routine clock
        // and stopped-weather ticks remain at their existing 15m throttle.
        requestWeatherIfNecessary(forceRefresh)
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

    private fun requestWeatherIfNecessary(force: Boolean = false) {
        // Hourly forecast shares the same worker/approximate location fix.
        // Force only on explicit Pause/Stop; routine checks keep 15m interval.
        WeatherStore.refreshIfDue(this, force = force)
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
        // The host does not expose whether this session appears in the compact
        // dashboard or the full Now Playing view. Supply a date in STANDARD
        // metadata but keep display metadata's original compact behavior.
        // This is a best-effort display hint, not reliable view detection.
        val blankCompact = hideCompactMediaText()
        val compactTitle = if (blankCompact) INVISIBLE_TEXT else lines.title
        val compactSubtitle = if (blankCompact) INVISIBLE_TEXT else lines.subtitle
        val activeId = if (running) ClockLibraryRows.CLOCK_ID
            else ClockLibraryRows.WEATHER_ID
        val fullDate = now.format(
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale("bg", "BG"))
        )
        val fullSubtitle = if (lines.subtitle.isBlank() ||
            lines.subtitle == INVISIBLE_TEXT
        ) fullDate else "$fullDate · ${lines.subtitle}"

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
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, fullSubtitle)
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
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    AutoClockScreenExperiments.playbackState(
                        running,
                        prefs.getBoolean(KEY_TEST_STATE_NONE, false)
                    ),
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f
                )
                .setActions(actions)
                .build()
        )
    }

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_MODE = "display_mode"
        const val KEY_LAYOUT = "clock_layout"
        const val KEY_PROGRESS_ANIMATED = "progress_animated"
        const val KEY_TEST_DIRECT_BROWSE = "try_media_host_browse_link_v37"
        const val KEY_TEST_STATE_NONE = "try_playback_state_none_v37"
        private const val INVISIBLE_TEXT = ClockMediaLabels.EMPTY
        const val ROOT_ID = "auto_clock_root"
    }
}
