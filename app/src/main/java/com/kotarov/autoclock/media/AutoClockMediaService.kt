package com.kotarov.autoclock.media

import android.content.Intent
import android.graphics.Bitmap
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
import com.kotarov.autoclock.artwork.PublishedArtwork
import com.kotarov.autoclock.artwork.ArtworkWorkers
import com.kotarov.autoclock.weather.WeatherStore
import com.kotarov.autoclock.weather.ForecastStore
import java.time.LocalDateTime

/** Play/Pause controls the single persisted weather preference.
 * Both playback states show a live clock; audio is limited to short notices. */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private lateinit var prefs: SharedPreferences
    private val running: Boolean
        get() = ClockSelectionStore.weatherEnabled(prefs)
    private val cityAnnouncement = CityAnnouncement()
    private val noticeSound = NoticeSoundPlayer()
    private var lastSoundNotice: CityAnnouncement.Notice? = null
    private var clientActive = false
    private var lastWarningRevision = 0L
    private var queuedWarning: String? = null
    private var baseMetadata: MediaMetadataCompat? = null
    private var artKey: String? = null
    private var miniKey: String? = null
    private var lastPublished: MediaPublicationKey? = null
    private var lastPlayback: Boolean? = null
    private data class WeatherArtwork(val locality: String?, val forecast: List<ForecastStore.Hour>)
    private var lastWeatherArtwork: WeatherArtwork? = null
    private var weatherArtworkRevision = 0L
    private data class RenderedArt(val main: Bitmap, val mini: Bitmap, val ownsMini: Boolean) {
        fun discard() {
            main.recycle()
            if (ownsMini) mini.recycle()
        }
    }
    private val artworkWorker = ArtworkWorkers.create<RenderedArt> { it.discard() }
    private var requestedArtKey: String? = null
    private val hideCityTitle = Runnable {
        if (clientActive) {
            val next = queuedWarning
            queuedWarning = null
            if (next != null) {
                cityAnnouncement.showWarning(next, SystemClock.elapsedRealtime())
                scheduleCityTitleHide()
            }
            publishClock()
        }
    }

    private val settingsRefresh = Runnable {
        if (clientActive) {
            publishClock()
            publishPlaybackState()

        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!clientActive) return@OnSharedPreferenceChangeListener
        if (key == KEY_SHOW_LOCATION) {
            val city = WeatherStore.locality(this)
            cityAnnouncement.show(city, SystemClock.elapsedRealtime())
            scheduleCityTitleHide()
            publishClock()
        } else if (key == ClockPreset.KEY_FAMILY ||
            key == ClockPreset.KEY_ADD_WEATHER ||
            key == KEY_LAYOUT || key == ClockTheme.KEY ||
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

            scheduleNextMinute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        // Force automatic local weather even when Android Auto starts the
        // service before the phone settings activity is opened.
        val legacyTests = listOf(KEY_TEST_DIRECT_BROWSE, KEY_TEST_STATE_NONE,
            KEY_TEST_STATE_STOPPED, "debug_media_labels_v54")
        val obsolete = legacyTests.filter { prefs.contains(it) }
        if (obsolete.isNotEmpty()) {
            val edit = prefs.edit()
            obsolete.forEach { edit.remove(it) }
            edit.apply()
        }
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
        WeatherStore.start(this, WeatherStore.Client.CAR)
        publishClock()
        publishPlaybackState()

        scheduleNextMinute()
    }

    private fun suspendClientWork() {
        clientActive = false
        WeatherStore.stop(WeatherStore.Client.CAR)
        handler.removeCallbacks(minuteTick)
        handler.removeCallbacks(settingsRefresh)
        handler.removeCallbacks(hideCityTitle)
        queuedWarning = null
        noticeSound.stop()
        artworkWorker.cancel()
        requestedArtKey = null
        baseMetadata = null
        artKey = null
        miniKey = null
        lastPublished = null
        lastPlayback = null
        lastWeatherArtwork = null
        weatherArtworkRevision = 0L
        lastSoundNotice = null
        PublishedArtwork.clear()
        session.setMetadata(null)
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

    private fun publishClock() {
        if (!clientActive) return
        val now = LocalDateTime.now()
        val visible = visiblePreset()
        val weather = if (visible.usesWeather) WeatherStore.current(this) else null
        val theme = selectedTheme()
        val showClockForecast = visible.usesWeather
        // Freshness timestamps alone do not change artwork. Reuse the
        // existing revision when the actual six forecast slots and label
        // are unchanged, while still processing all warnings separately.
        val locality = WeatherStore.locality(this)
        val forecast = if (showClockForecast) ForecastStore.currentRing(this,
            now.atZone(java.time.ZoneId.systemDefault()).toEpochSecond(), weather) else emptyList()
        val label = if (showClockForecast) locality ?: weather?.artworkSource else null
        val nextWeatherArtwork = if (showClockForecast) WeatherArtwork(label, forecast) else null
        if (nextWeatherArtwork != lastWeatherArtwork) {
            lastWeatherArtwork = nextWeatherArtwork
            weatherArtworkRevision = if (nextWeatherArtwork == null) 0L else
                prefs.getLong(ForecastStore.KEY_SAVED_AT, 0L) +
                (weather?.observedAtMs ?: 0L) + prefs.getLong("weather_locality_revision_v68", 0L)
        }
        val revision = weatherArtworkRevision
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
        if (cityAnnouncement.update(locality, tick)) scheduleCityTitleHide()
        val warningRevision = prefs.getLong(WeatherStore.KEY_WARNING_REV, 0L)
        if (warningRevision != lastWarningRevision) {
            lastWarningRevision = warningRevision
            val warning = prefs.getString(WeatherStore.KEY_WARNING, null)
            queuedWarning = null
            if (warning != null) {
                if (cityAnnouncement.title(tick) != null) queuedWarning = warning
                else {
                    cityAnnouncement.showWarning(warning, tick)
                    scheduleCityTitleHide()
                }
            }
        }
        val notice = cityAnnouncement.notice(tick)
        // Title / Display Title: icon only. Artist / Display Subtitle:
        // the full message, for hosts using either metadata convention.
        val temporaryTitle = notice?.heading ?: INVISIBLE_TEXT
        val temporarySubtitle = notice?.body ?: INVISIBLE_TEXT
        val key = artwork.toString() + "|" + miniArtwork.toString()
        if (artKey != key || baseMetadata == null) {
            if (requestedArtKey != key) {
                requestedArtKey = key
                // Snapshot every render input on the owner thread. Never read
                // mutable service state or touch MediaSession in the worker.
                val compact = compactLayout()
                 val miniUri = miniArtwork.toString()
                val artUri = artwork.toString()
                val previousMini = if (miniKey == miniUri)
                    baseMetadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON) else null
                artworkWorker.submit({
                    val art = ClockRenderer.render(visible.id, now, compact,
                        outputSize = if (showClockForecast && !visible.hasAnalog) ClockRenderer.SIZE
                            else ClockRenderer.MEDIA_BITMAP_SIZE,
                        sourceLabel = label, theme = theme, forecast = forecast)
                    try {
                        RenderedArt(art, previousMini ?: ClockRenderer.renderMini(visible.family, now, theme),
                            previousMini == null)
                    } catch (e: Throwable) {
                        art.recycle()
                        throw e
                    }
                }) { outcome ->
                    requestedArtKey = null
                    val result = outcome.getOrNull()
                    if (result == null) {
                        android.util.Log.w("AutoClock", "Artwork render failed", outcome.exceptionOrNull())
                    } else if (!clientActive) {
                        result.discard()
                    } else {
                        // The worker suppresses superseded requests; the
                        // latest settings are checked again by publishClock.
                        miniKey = miniUri
                        PublishedArtwork.publish(artUri, result.main, false)
                        PublishedArtwork.publish(miniUri, result.mini, true)
                        baseMetadata = MediaMetadataCompat.Builder()
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, result.mini)
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, result.main)
                            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, CLOCK_ID)
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, INVISIBLE_TEXT)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, INVISIBLE_TEXT)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, INVISIBLE_TEXT)
                            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUri)
                            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUri)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, miniUri)
                            .build()
                        artKey = key
                        publishClock()
                    }
                }
            }
        } else {
            // A rapid change back to the already displayed design must
            // invalidate an outstanding render of the intermediate design.
            if (requestedArtKey != null) {
                artworkWorker.cancel()
                requestedArtKey = null
            }
            val publication = MediaPublicationKey(key, temporaryTitle, temporarySubtitle)
            if (publication != lastPublished) {
                session.setMetadata(MediaMetadataCompat.Builder(baseMetadata!!)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, temporaryTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, temporarySubtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, INVISIBLE_TEXT)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, temporaryTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, temporarySubtitle)
                    .build())
                lastPublished = publication
            }
        }
        // Each newly created notice is a distinct object, even if the same
        // warning appears again at a later refresh. Never sound on redraw.
        if (notice != null && notice !== lastSoundNotice) {
            lastSoundNotice = notice
            noticeSound.play(NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null)))
        }
    }

    private fun publishPlaybackState() {
        val isRunning = running
        if (lastPlayback == isRunning) return
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    ClockPlaybackState.forRunning(isRunning),
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f
                )
                .setActions(actions)
                .build()
        )
        lastPlayback = isRunning
    }

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_LAYOUT = "clock_layout"
        const val KEY_SHOW_LOCATION = "show_location_message_v48"
        private const val KEY_TEST_DIRECT_BROWSE = "try_media_host_browse_link_v37"
        private const val KEY_TEST_STATE_NONE = "try_playback_state_none_v37"
        private const val KEY_TEST_STATE_STOPPED = "try_playback_state_stopped_v39"
        private const val INVISIBLE_TEXT = "\u00A0"
        const val CLOCK_ID = "autoclock_clock"
        const val ROOT_ID = "auto_clock_root"
    }
}
