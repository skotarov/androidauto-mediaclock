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

/** Play/Pause controls the single persisted weather preference.
 * Both playback states always show a live clock; no audio is played. */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private lateinit var prefs: SharedPreferences
    private val running: Boolean
        get() = ClockSelectionStore.weatherEnabled(prefs)
    private val cityAnnouncement = CityAnnouncement()
    private var clientActive = false
    private var lastWarningRevision = 0L
    private var queuedWarning: String? = null
    private var baseMetadata: MediaMetadataCompat? = null
    private var artKey: String? = null
    private var miniKey: String? = null
    private val hideCityTitle = Runnable {
        if (clientActive) {
            val next = queuedWarning
            queuedWarning = null
            if (next != null) {
                cityAnnouncement.show(next, SystemClock.elapsedRealtime())
                scheduleCityTitleHide()
            }
            publishClock()
        }
    }

    private val settingsRefresh = Runnable {
        if (clientActive) {
            publishClock()
            publishPlaybackState()
            requestWeatherIfNecessary()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!clientActive) return@OnSharedPreferenceChangeListener
        if (key == KEY_SHOW_LOCATION) {
            val city = WeatherStore.current(this)?.localityLabel
            cityAnnouncement.show(city, SystemClock.elapsedRealtime())
            scheduleCityTitleHide()
            publishClock()
        } else if (key == ClockPreset.KEY_FAMILY ||
            key == ClockPreset.KEY_ADD_WEATHER ||
            key == KEY_LAYOUT || key == ClockTheme.KEY || key == KEY_DEBUG_MEDIA_LABELS ||
            key == "weather_cached_mode" || key == "weather_station_label" ||
            key == WeatherStore.KEY_LOCALITY || key == WeatherStore.KEY_WARNING_REV ||
            key == "weather_source_name" || key == "weather_fetched_at" ||
            key == "weather_code" || key == "weather_temperature_bits" ||
            key == "weather_is_day" ||
            key == ForecastStore.KEY_SAVED_AT ||
            key == ForecastStore.KEY_MODE
        ) {
            handler.removeCallbacks(settingsRefresh)
            handler.postDelayed(settingsRefresh, 120L)
        }
    }

    /** Both Play and Pause display the clock, so both advance every minute. */
    private val minuteTick = object : Runnable {
        override fun run() {
            if (!clientActive) return
            publishClock()
            requestWeatherIfNecessary()
            scheduleNextMinute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        // Force automatic local weather even when Android Auto starts the
        // service before the phone settings activity is opened.
        prefs.edit().remove(KEY_TEST_DIRECT_BROWSE).remove(KEY_TEST_STATE_NONE)
            .remove(KEY_TEST_STATE_STOPPED).apply()
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        session = MediaSessionCompat(this, "AutoClockMediaTestsV037").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == CLOCK_ID) setWeatherEnabled(true)
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == CLOCK_ID) {
                        publishClock()
                        publishPlaybackState()
                    }
                }

                override fun onPlay() = setWeatherEnabled(true)
                override fun onPause() = setWeatherEnabled(false)
                override fun onStop() = setWeatherEnabled(false)
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
        // Restore the last explicit phone/car choice. Do not overwrite it.
        publishPlaybackState()
        // Do not start periodic work until an accepted media client connects.
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
        resumeClientWork()
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
        // Information only: the browser exposes no playable track or folder.
        // Keep the active media session intact for the dashboard clock and
        // Play/Pause. Android Auto itself still decides when to open Now Playing.
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(AutoClockInfo.ID)
            .setTitle(AutoClockInfo.title(installedVersion()))
            .build()
        result.sendResult(mutableListOf(MediaBrowserCompat.MediaItem(description, 0)))
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): String? =
        try { packageManager.getPackageInfo(packageName, 0).versionName }
        catch (_: Exception) { null }

    private fun resumeClientWork() {
        if (clientActive) return
        clientActive = true
        lastWarningRevision = prefs.getLong(WeatherStore.KEY_WARNING_REV, 0L)
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
        scheduleNextMinute()
    }

    private fun suspendClientWork() {
        clientActive = false
        handler.removeCallbacks(minuteTick)
        handler.removeCallbacks(settingsRefresh)
        handler.removeCallbacks(hideCityTitle)
        queuedWarning = null
        baseMetadata = null
        artKey = null
        miniKey = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        suspendClientWork()
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        // onGetRoot resumes work for the accepted client.
    }

    override fun onDestroy() {
        suspendClientWork()
        // Service shutdown must not change the saved weather preference.
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

    private fun scheduleCityTitleHide() {
        handler.removeCallbacks(hideCityTitle)
        handler.postDelayed(hideCityTitle, CityAnnouncement.DURATION_MS)
    }

    private fun setWeatherEnabled(enabled: Boolean) {
        ClockSelectionStore.setWeatherEnabled(prefs, enabled)
        // The same preference listener handles phone switches. Publish now
        // for car transport commands, avoiding duplicate queued artwork.
        handler.removeCallbacks(settingsRefresh)
        if (clientActive) {
            publishClock()
            publishPlaybackState()
            requestWeatherIfNecessary()
        }
    }

    private fun scheduleNextMinute() {
        handler.removeCallbacks(minuteTick)
        if (!clientActive) return
        handler.postDelayed(
            minuteTick,
            MinuteProgress.millisUntilNextMinute(System.currentTimeMillis())
        )
    }

    private fun visiblePreset(): ClockPreset =
        PlaybackDisplay.visiblePreset(selectedPreset(), running)

    private fun requestWeatherIfNecessary() {
        // Keep locality announcements available even with forecast hidden.
        // The shared worker retains its normal 5-minute throttle.
        WeatherStore.refreshIfDue(this)
    }

    private fun publishClock() {
        val now = LocalDateTime.now()
        val visible = visiblePreset()
        val weather = if (visible.usesWeather) WeatherStore.current(this) else null
        val theme = selectedTheme()
        val showClockForecast = visible.usesWeather
        val revision = if (showClockForecast) prefs.getLong(ForecastStore.KEY_SAVED_AT, 0L) +
            (weather?.observedAtMs ?: 0L) else weather?.observedAtMs ?: 0L
        val artwork = ClockArtworkProvider.artworkUri(
            visible.id, now, revision,
            compactLayout(), 0L, theme
        )
        val miniArtwork = ClockArtworkProvider.miniArtworkUri(
            visible.family, now,
            0L,
            theme
        )
        val tick = SystemClock.elapsedRealtime()
        // Observe locality even when the clock's weather option is off.
        val locality = (weather ?: WeatherStore.current(this))?.localityLabel
        if (cityAnnouncement.update(locality, tick)) scheduleCityTitleHide()
        val warningRevision = prefs.getLong(WeatherStore.KEY_WARNING_REV, 0L)
        if (warningRevision != lastWarningRevision) {
            lastWarningRevision = warningRevision
            val warning = prefs.getString(WeatherStore.KEY_WARNING, null)
            queuedWarning = null
            if (warning != null) {
                if (cityAnnouncement.title(tick) != null) queuedWarning = warning
                else {
                    cityAnnouncement.show(warning, tick)
                    scheduleCityTitleHide()
                }
            }
        }
        val temporaryTitle = cityAnnouncement.title(tick) ?: INVISIBLE_TEXT
        val activeId = CLOCK_ID

        // Keep both a direct reduced-size bitmap and an artwork URI to cope
        // with Android Auto implementations that cache either representation.
        val key = artwork.toString() + "|" + miniArtwork.toString()
        if (artKey != key || baseMetadata == null) {
        val forecast = if (showClockForecast) ForecastStore.currentRing(this,
            now.atZone(java.time.ZoneId.systemDefault()).toEpochSecond(), weather) else emptyList()
        val art = ClockRenderer.render(
            visible.id, now, compactLayout(),
            outputSize = ClockRenderer.MEDIA_BITMAP_SIZE, sourceLabel = weather?.artworkSource,
            theme = theme, forecast = forecast
        )
        val currentMiniKey = miniArtwork.toString()
        val miniBitmap = if (miniKey == currentMiniKey)
            baseMetadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
                ?: ClockRenderer.renderMini(visible.family, now, theme)
            else ClockRenderer.renderMini(visible.family, now, theme)
        miniKey = currentMiniKey
        baseMetadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, miniBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, activeId)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, miniArtwork.toString())
            .build()
        artKey = key
        }
        // A title change does not allocate or redraw any artwork.
        val debug = prefs.getBoolean(KEY_DEBUG_MEDIA_LABELS, false)
        session.setMetadata(MediaMetadataCompat.Builder(baseMetadata!!)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, if (debug) "TITLE" else temporaryTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, if (debug) "ARTIST" else INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, if (debug) "ALBUM" else INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, if (debug) "DISPLAY_TITLE" else temporaryTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, if (debug) "DISPLAY_SUBTITLE" else INVISIBLE_TEXT)
            .build())
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
        const val KEY_LAYOUT = "clock_layout"
        const val KEY_SHOW_LOCATION = "show_location_message_v48"
        const val KEY_DEBUG_MEDIA_LABELS = "debug_media_labels_v54"
        private const val KEY_TEST_DIRECT_BROWSE = "try_media_host_browse_link_v37"
        private const val KEY_TEST_STATE_NONE = "try_playback_state_none_v37"
        private const val KEY_TEST_STATE_STOPPED = "try_playback_state_stopped_v39"
        private const val INVISIBLE_TEXT = "\u00A0"
        const val CLOCK_ID = "autoclock_clock"
        const val ROOT_ID = "auto_clock_root"
    }
}
