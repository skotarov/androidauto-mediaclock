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
import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDateTime

/**
 * v0.39: keep stable dashboard/library routing; test STATE_STOPPED separately
 * from STATE_NONE, which Android Auto hides from playback surfaces.
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
    private val cityAnnouncement = CityAnnouncement()
    private val hideCityTitle = Runnable { publishClock() }

    private val settingsRefresh = Runnable {
        preset = selectedPreset()
        artworkGeneration += 1L
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SHOW_LOCATION) {
            val city = WeatherStore.current(this)?.localityLabel
            cityAnnouncement.show(city, SystemClock.elapsedRealtime())
            scheduleCityTitleHide()
            publishClock()
        } else if (key == ClockPreset.KEY_FAMILY ||
            key == ClockPreset.KEY_ADD_WEATHER ||
            key == KEY_MODE ||
            key == KEY_LAYOUT || key == ClockTheme.KEY ||
            key == WeatherStore.KEY_CITY || key == "weather_cached_city" ||
            key == "weather_cached_mode" || key == "weather_station_label" ||
            key == WeatherStore.KEY_LOCALITY ||
            key == "weather_source_name" || key == "weather_fetched_at" ||
            key == "weather_code" || key == "weather_temperature_bits" ||
            key == "weather_is_day" || key == "weather_pressure_bits" ||
            key == "weather_elevation_bits" ||
            key == ForecastStore.KEY_SAVED_AT ||
            key == ForecastStore.KEY_MODE
        ) {
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
        prefs.edit().remove(KEY_TEST_DIRECT_BROWSE).remove(KEY_TEST_STATE_NONE)
            .remove(KEY_TEST_STATE_STOPPED).apply()
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        preset = selectedPreset()
        session = MediaSessionCompat(this, "AutoClockMediaTestsV037").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == ClockLibraryRows.CLOCK_ID) startClock()
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == ClockLibraryRows.CLOCK_ID) {
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
        session.setSessionActivity(null)
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
        // One playable entry preserves Android Auto media discovery.
        // The separate hourly forecast list is no longer exposed.
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(ClockLibraryRows.CLOCK_ID)
            .setTitle("Auto Clock")
            .build()
        result.sendResult(mutableListOf(MediaBrowserCompat.MediaItem(
            description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)))
    }

    override fun onDestroy() {
        running = false
        artworkGeneration += 1L
        handler.removeCallbacks(minuteTick)
        handler.removeCallbacks(stoppedWeatherTick)
        handler.removeCallbacks(settingsRefresh)
        handler.removeCallbacks(hideCityTitle)
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

    // The built-in Canvas glyphs remain; no external icon library or
    // colorful-icon resource package is used. No color preference exists.
    private fun coloredWeatherIcons(): Boolean = true

    private fun scheduleCityTitleHide() {
        handler.removeCallbacks(hideCityTitle)
        handler.postDelayed(hideCityTitle, CityAnnouncement.DURATION_MS)
    }

    private fun startClock() {
        preset = selectedPreset()
        running = true
        artworkGeneration += 1L
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
        val theme = selectedTheme()
        val colored = coloredWeatherIcons()
        val showClockForecast = visible.family != ClockFamily.WEATHER && visible.usesWeather
        val forecast = if (showClockForecast) ForecastStore.currentRing(this,
            now.atZone(java.time.ZoneId.systemDefault()).toEpochSecond(), weather) else emptyList()
        val revision = if (showClockForecast) prefs.getLong(ForecastStore.KEY_SAVED_AT, 0L) +
            (weather?.observedAtMs ?: 0L) else weather?.observedAtMs ?: 0L
        val artwork = ClockArtworkProvider.artworkUri(
            visible.id, now, revision,
            compactLayout(), artworkGeneration, theme, colored
        )
        val miniArtwork = ClockArtworkProvider.miniArtworkUri(
            visible.family, now,
            if (visible.family == ClockFamily.WEATHER)
                weather?.observedAtMs ?: 0L else 0L,
            theme, colored
        )
        val tick = SystemClock.elapsedRealtime()
        // Observe locality even when the clock's weather option is off.
        val locality = (weather ?: WeatherStore.current(this))?.localityLabel
        if (cityAnnouncement.update(locality, tick)) scheduleCityTitleHide()
        val temporaryTitle = cityAnnouncement.title(tick) ?: INVISIBLE_TEXT
        val activeId = if (running) ClockLibraryRows.CLOCK_ID
            else ClockLibraryRows.WEATHER_ID

        // Keep both a direct reduced-size bitmap and an artwork URI to cope
        // with Android Auto implementations that cache either representation.
        val art = ClockRenderer.render(
            visible.id, now, weather?.weatherCode, weather?.isDay ?: true,
            weather?.formattedTemperature, compactLayout(),
            outputSize = 320, condition = description,
            sourceLabel = weather?.artworkSource, theme = theme,
            coloredWeatherIcons = colored, forecast = forecast
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
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, temporaryTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, temporaryTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, INVISIBLE_TEXT)
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
                    ClockPlaybackState.forRunning(running),
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
        const val KEY_SHOW_LOCATION = "show_location_message_v48"
        private const val KEY_TEST_DIRECT_BROWSE = "try_media_host_browse_link_v37"
        private const val KEY_TEST_STATE_NONE = "try_playback_state_none_v37"
        private const val KEY_TEST_STATE_STOPPED = "try_playback_state_stopped_v39"
        private const val INVISIBLE_TEXT = ClockMediaLabels.EMPTY
        const val ROOT_ID = "auto_clock_root"
    }
}
