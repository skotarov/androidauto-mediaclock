package com.kotarov.autoclock.media

import android.content.Intent
import com.kotarov.autoclock.BuildConfig
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
import com.kotarov.autoclock.R
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
    private var activeSource: StreamLibrary.Station? = null
    private val playMode = BuildConfig.PLAY_MEDIA_MODE
    private var requestedPlayback = false
    private var focusInterrupted = false
    private var mediaError: String? = null
    private val focus by lazy { PlayAudioFocus(this) { change -> onFocusChange(change) } }
    private var foregroundAudio = false
    private val running: Boolean
        get() = if (playMode) requestedPlayback && !focusInterrupted else ClockSelectionStore.weatherEnabled(prefs)
    private val cityAnnouncement = CityAnnouncement()
    private val noticeAudio by lazy { NoticeAudio(this) }
    private val streamPlayer by lazy { CustomStreamPlayer(this) }
    private val phoneMode: PhoneAudioMode get() = PhoneAudioMode.read(prefs)
    private var noticePlaying = false
    private var testNotice: CityAnnouncement.Notice? = null
    private val hideTestNotice = Runnable {
        testNotice = null
        if (clientActive) publishClock()
    }
    private val receiveTest: (CityAnnouncement.Notice) -> Unit = { sample ->
        testNotice = NoticeTest.marked(sample)
        handler.removeCallbacks(hideTestNotice)
        handler.postDelayed(hideTestNotice, CityAnnouncement.DURATION_MS)
        publishClock()
        playNotice(NoticeTest.spoken(sample))
    }
    private var lastSoundNotice: CityAnnouncement.Notice? = null
    private var clientActive = false
    private var removeProjectionListener: (() -> Unit)? = null
    private var suspendedForProjection = false
    private var lastWarningRevision = 0L
    private var queuedWarning: String? = null
    private var baseMetadata: MediaMetadataCompat? = null
    private var artKey: String? = null
    private var miniKey: String? = null
    private var lastPublished: MediaPublicationKey? = null
    private data class PlaybackKey(val running: Boolean, val id: String, val duration: Int?, val advancing: Boolean)
    private var lastPlayback: PlaybackKey? = null
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

    // Only text metadata is refreshed on the position tick; artwork remains cached.
    private val positionTick = Runnable { refreshPositionSubtitle() }

    private val settingsRefresh = Runnable {
        if (clientActive) {
            publishClock()
            publishPlaybackState()

        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!clientActive) return@OnSharedPreferenceChangeListener
        if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
            activeSource = StreamLibrary.selected(prefs)
        }
        if (playMode) {
            if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
                if (activeSource == null && requestedPlayback) setPlayRequested(false)
                else if (requestedPlayback && !focusInterrupted) syncStream()
                if (key == StreamLibrary.KEY_LIST) notifyChildrenChanged(ROOT_ID)
                publishPlayMetadata()
                publishPlaybackState()
            }
            return@OnSharedPreferenceChangeListener
        }
        if (key == PhoneAudioMode.KEY || key == NoticeAudio.KEY_PHONE_CHANNEL) {
            stopNoticeAudio()
            streamPlayer.stop()
            syncStream()
        }
        if (key == ClockPreset.KEY_ADD_WEATHER && !running) stopNoticeAudio()
        if (key == ClockPreset.KEY_ADD_WEATHER ||
            key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) syncStream()
        if (key == StreamLibrary.KEY_LIST) notifyChildrenChanged(ROOT_ID)
        if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
            lastPublished = null
            publishClock()
        }
        if (key == NoticeAudio.KEY_SPEAK) { stopNoticeAudio(); syncStream() }
        if (key == ClockPreset.KEY_FAMILY ||
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
        StreamLibrary.migrate(prefs)
        activeSource = StreamLibrary.selected(prefs)
        session = MediaSessionCompat(this, "AutoClockMediaTestsV037").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (selectSource(mediaId)) setWeatherEnabled(true)
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (playMode) setWeatherEnabled(false)
                    if (selectSource(mediaId)) {
                        publishClock()
                        publishPlaybackState()
                    }
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    val delta = when (action) {
                        ACTION_BACK_10 -> -10000
                        ACTION_FORWARD_10 -> 10000
                        else -> return
                    }
                    val station = activeSource
                    if (station?.textFile != true && streamPlayer.seekBy(station?.url, delta)) {
                        lastPlayback = null
                        publishPlaybackState()
                    }
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val items = StreamLibrary.list(prefs)
                    val match = if (query.isNullOrBlank()) activeSource ?: items.firstOrNull()
                        else items.firstOrNull { it.name.contains(query, ignoreCase = true) }
                    if (match != null && selectSource(match.id)) setWeatherEnabled(true)
                }
                override fun onSeekTo(pos: Long) {
                    if (activeSource?.textFile != true && streamPlayer.seekTo(activeSource?.url, pos)) {
                        lastPlayback = null
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
        removeProjectionListener = CarProjectionMonitor.subscribe(this) { connected ->
            if (!connected) {
                suspendedForProjection = clientActive
                if (clientActive) suspendClientWork()
                stopSelf()
            } else if (suspendedForProjection) {
                suspendedForProjection = false
                resumeClientWork()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (playMode && intent?.action == "autoclock.stop_playback") {
            setPlayRequested(false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

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
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val alert = MediaDescriptionCompat.Builder().setMediaId(CLOCK_ID)
            .setTitle("Alert only")
            .setIconBitmap(sourceIcon(R.drawable.ic_source_travel)).build()
        if (!playMode) items.add(MediaBrowserCompat.MediaItem(alert, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        StreamLibrary.list(prefs).forEach { station ->
            val desc = MediaDescriptionCompat.Builder().setMediaId(station.id)
                .setTitle(station.name).setIconBitmap(station.bitmap() ?: sourceIcon(if (station.textFile) R.drawable.ic_source_text else if (station.local) R.drawable.ic_source_file else R.drawable.ic_source_podcast)).build()
            items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }
        result.sendResult(items)
    }

    private fun sourceIcon(resource: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val drawable = getDrawable(resource)
        drawable?.setBounds(4, 4, 60, 60)
        drawable?.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun selectSource(id: String?): Boolean {
        if (playMode && id == CLOCK_ID) return false
        if (id != CLOCK_ID && StreamLibrary.list(prefs).none { it.id == id }) return false
        prefs.edit().putString(StreamLibrary.KEY_SELECTED, id).apply()
        activeSource = StreamLibrary.selected(prefs)
        return true
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): String? =
        try { packageManager.getPackageInfo(packageName, 0).versionName }
        catch (_: Exception) { null }

    private fun resumeClientWork() {
        if (clientActive) return
        suspendedForProjection = false
        clientActive = true
        activeSource = StreamLibrary.selected(prefs)
        if (playMode) {
            publishPlayMetadata()
            publishPlaybackState()
            return
        }
        NoticeTest.receiver = receiveTest
        syncStream()
        lastWarningRevision = prefs.getLong(WeatherStore.KEY_WARNING_REV, 0L)
        cityAnnouncement.reset(WeatherStore.locality(this))
        WeatherStore.start(this, WeatherStore.Client.CAR)
        publishClock()
        publishPlaybackState()

        scheduleNextMinute()
    }

    private fun suspendClientWork() {
        if (playMode) {
            requestedPlayback = false
            focusInterrupted = false
            focus.abandon()
            stopPlaybackForeground()
        }
        clientActive = false
        if (NoticeTest.receiver === receiveTest) NoticeTest.receiver = null
        handler.removeCallbacks(hideTestNotice)
        testNotice = null
        NoticeTest.publishCaption(null)
        WeatherStore.stop(WeatherStore.Client.CAR)
        handler.removeCallbacks(minuteTick)
        handler.removeCallbacks(positionTick)
        handler.removeCallbacks(settingsRefresh)
        handler.removeCallbacks(hideCityTitle)
        queuedWarning = null
        stopNoticeAudio()
        streamPlayer.stop()
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
        suspendedForProjection = false
        suspendClientWork()
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        // onGetRoot resumes work for the accepted client.
    }

    override fun onDestroy() {
        removeProjectionListener?.invoke()
        removeProjectionListener = null
        suspendedForProjection = false
        suspendClientWork()
        streamPlayer.close()
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

    private fun stopNoticeAudio() {
        noticePlaying = false
        noticeAudio.stop()
    }

    private fun setWeatherEnabled(enabled: Boolean) {
        if (playMode) {
            setPlayRequested(enabled)
            return
        }
        if (!enabled) {
            stopNoticeAudio()
            streamPlayer.stop()
        } else if (running && !noticePlaying) {
            // Retry a failed request without restarting an already playing source.
            streamPlayer.retryFailed()
        }
        ClockSelectionStore.setWeatherEnabled(prefs, enabled)
        // The same preference listener handles phone switches. Publish now
        // for car transport commands, avoiding duplicate queued artwork.
        handler.removeCallbacks(settingsRefresh)
        if (clientActive) {
            syncStream()
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
        if (playMode) { publishPlayMetadata(); return }
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
        val caption = (testNotice ?: notice)?.subtitle
        val position = if (caption == null) currentMediaPosition() else null
        NoticeTest.publishCaption(caption)
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
            publishMediaSubtitle(key, caption, position)
        }
        // Each newly created notice is a distinct object, even if the same
        // warning appears again at a later refresh. Never sound on redraw.
        if (testNotice == null && notice != null && notice !== lastSoundNotice) {
            lastSoundNotice = notice
            if (running) {
                playNotice(notice.body)
            }
        }
        schedulePositionTick(caption, position)
    }

    private fun currentMediaPosition(): Int? {
        if (!clientActive || !running || noticePlaying) return null
        return streamPlayer.activePositionMs(activeSource?.url)
    }

    private fun schedulePositionTick(caption: String?, position: Int?) {
        handler.removeCallbacks(positionTick)
        if (clientActive && caption == null && position != null) {
            handler.postDelayed(positionTick, (1000L - position % 1000L).coerceAtLeast(100L))
        }
    }

    private fun refreshPositionSubtitle() {
        if (!clientActive || playMode) return
        val caption = (testNotice ?: cityAnnouncement.notice(SystemClock.elapsedRealtime()))?.subtitle
        val position = if (caption == null) currentMediaPosition() else null
        artKey?.let { publishMediaSubtitle(it, caption, position) }
        schedulePositionTick(caption, position)
    }

    private fun publishMediaSubtitle(key: String, caption: String?, position: Int?) {
        val base = baseMetadata ?: return
        val textProgress = if (running && !noticePlaying && caption == null)
            streamPlayer.readingProgress(activeSource?.url) else null
        val subtitle = MediaPositionSubtitle.text(caption, position) ?: textProgress ?: INVISIBLE_TEXT
        val duration = mediaSeek()?.duration
        val publication = MediaPublicationKey(key, INVISIBLE_TEXT, subtitle, activeSource?.id ?: CLOCK_ID, duration)
        if (publication == lastPublished) return
        session.setMetadata(MediaMetadataCompat.Builder(base)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, activeSource?.id ?: CLOCK_ID)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, INVISIBLE_TEXT)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .apply { duration?.let { putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it.toLong()) } }
            .build())
        lastPublished = publication
    }

    private fun syncStream() {
        if (noticePlaying && running) {
            publishPlaybackState()
            refreshPositionSubtitle()
            return
        }
        val station = if (clientActive && running && !noticePlaying)
            activeSource else null
        val url = station?.url
        streamPlayer.sync(url, !playMode && phoneMode != PhoneAudioMode.OFF,
            textLanguage = station?.takeIf { it.textFile }?.language, onChanged = {
            if (playMode || station?.textFile != true) lastPlayback = null
            publishPlaybackState()
            if (playMode) publishPlayMetadata() else refreshPositionSubtitle()
        }) { error ->
            if (playMode) {
                setPlayRequested(false)
                mediaError = if (error == "Media ended") null else error
                lastPlayback = null
                publishPlaybackState()
                return@sync
            }
            lastPlayback = null
            publishPlaybackState()
            if (error == "Media ended") setWeatherEnabled(false)
            if (clientActive && !noticePlaying && testNotice == null) {
                testNotice = CityAnnouncement.Notice("ℹ️", error)
                handler.removeCallbacks(hideTestNotice)
                handler.postDelayed(hideTestNotice, CityAnnouncement.DURATION_MS)
                publishClock()
            }
        }
        publishPlaybackState()
        if (playMode) publishPlayMetadata() else refreshPositionSubtitle()
    }

    private fun playNotice(body: String) {
        if (playMode) return
        val sound = NoticeSound.fromId(prefs.getString(NoticeSound.KEY, null))
        val speak = prefs.getBoolean(NoticeAudio.KEY_SPEAK, true)
        // A visual-only notice must not touch playback or the audio route.
        if (!speak && sound == NoticeSound.SILENT) return
        noticePlaying = true
        handler.removeCallbacks(positionTick)
        // Release the HFP route, but retain an already prepared TXT reader.
        streamPlayer.suspendForNotice()
        publishPlaybackState()
        noticeAudio.play(sound, body, phoneMode != PhoneAudioMode.OFF,
            speakEnabled = speak) {
            noticePlaying = false
            syncStream()
        }
    }

    /** TXT progress is measured in characters, not milliseconds. Never invent a duration. */
    private fun mediaSeek(): CustomStreamPlayer.SeekState? = activeSource?.takeUnless { it.textFile }
        ?.let { streamPlayer.seekState(it.url) }

    private fun publishPlaybackState() {
        if (playMode) { publishPlayState(); return }
        val isRunning = running
        val station = activeSource
        val seek = mediaSeek()
        val advancing = isRunning && seek != null && !noticePlaying && streamPlayer.isPlaying()
        val key = PlaybackKey(isRunning, station?.id ?: CLOCK_ID, seek?.duration, advancing)
        if (lastPlayback == key) return
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
        if (seek != null) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        val builder = PlaybackStateCompat.Builder()
            .setState(ClockPlaybackState.forRunning(isRunning),
                seek?.position?.toLong() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (advancing) 1f else 0f)
            .setActions(actions)
        // Leave next/previous slots free: Auto may place these custom actions there.
        if (seek != null) {
            builder.addCustomAction(ACTION_BACK_10, "Back 10 seconds", R.drawable.ic_replay_10)
            builder.addCustomAction(ACTION_FORWARD_10, "Forward 10 seconds", R.drawable.ic_forward_10)
        }
        session.setPlaybackState(builder.build())
        lastPlayback = key
    }

    private var playMetadataSource: StreamLibrary.Station? = null
    private var playMetadataDuration: Int? = null
    private fun publishPlayMetadata() {
        val source = activeSource
        val duration = mediaSeek()?.duration
        if (source == playMetadataSource && duration == playMetadataDuration && session.controller.metadata != null) return
        playMetadataSource = source
        playMetadataDuration = duration
        val builder = MediaMetadataCompat.Builder()
        if (source != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, source.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, source.name)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, source.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                    if (source.textFile) "Text reading" else if (source.local) "Local audio" else "Audio stream")
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART,
                    source.bitmap() ?: sourceIcon(if (source.textFile) R.drawable.ic_source_text
                        else if (source.local) R.drawable.ic_source_file else R.drawable.ic_source_podcast))
        } else builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Select audio")
        duration?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it.toLong()) }
        session.setMetadata(builder.build())
    }

    private fun setPlayRequested(enabled: Boolean) {
        mediaError = null
        if (!enabled || !clientActive) {
            requestedPlayback = false
            focusInterrupted = false
            streamPlayer.stop()
            focus.abandon()
            stopPlaybackForeground()
        } else {
            if (activeSource == null) {
                val first = StreamLibrary.list(prefs).firstOrNull()
                if (first != null) selectSource(first.id)
            }
            if (activeSource == null) {
                mediaError = "Add an audio or text file on your phone."
            } else if (startPlaybackForeground() && focus.request()) {
                requestedPlayback = true
                focusInterrupted = false
                streamPlayer.retryFailed()
            } else {
                requestedPlayback = false
                focusInterrupted = false
                streamPlayer.stop()
                focus.abandon()
                stopPlaybackForeground()
                mediaError = "Audio focus unavailable"
            }
        }
        syncStream()
        publishPlayMetadata()
        publishPlaybackState()
    }

    private fun onFocusChange(change: Int) {
        if (!playMode || !requestedPlayback) return
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> setPlayRequested(false)
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusInterrupted = true
                streamPlayer.suspendForNotice()
                publishPlaybackState()
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                focusInterrupted = false
                syncStream()
            }
        }
    }

    private fun startPlaybackForeground(): Boolean {
        if (foregroundAudio) return true
        return runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                android.app.NotificationChannel("playback", "Audio playback", android.app.NotificationManager.IMPORTANCE_LOW))
            val builder = if (android.os.Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(this, "playback") else android.app.Notification.Builder(this)
            val stop = android.app.PendingIntent.getService(this, 2,
                Intent(this, AutoClockMediaService::class.java).setAction("autoclock.stop_playback"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val launch = android.app.PendingIntent.getActivity(this, 1,
                Intent(this, com.kotarov.autoclock.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val notification = builder.setSmallIcon(R.drawable.ic_auto_clock)
                .setContentTitle(activeSource?.name ?: "SmartTrip Clock")
                .setContentText("Audio playback").setContentIntent(launch)
                .addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
                .setOngoing(true).setOnlyAlertOnce(true).build()
            startForeground(97, notification)
            foregroundAudio = true
            true
        }.getOrDefault(false)
    }

    private fun stopPlaybackForeground() {
        if (!foregroundAudio) return
        foregroundAudio = false
        if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else { @Suppress("DEPRECATION") stopForeground(true) }
    }

    private fun publishPlayState() {
        val source = activeSource
        val seek = mediaSeek()
        val state = when {
            mediaError != null -> PlaybackStateCompat.STATE_ERROR
            source == null -> PlaybackStateCompat.STATE_NONE
            !running -> PlaybackStateCompat.STATE_PAUSED
            streamPlayer.isPlaying() -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_BUFFERING
        }
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        if (seek != null) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        val builder = PlaybackStateCompat.Builder().setActions(actions)
            .setState(state, seek?.position?.toLong() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (state == PlaybackStateCompat.STATE_PLAYING && seek != null) 1f else 0f)
        mediaError?.let { builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it) }
        if (seek != null) {
            builder.addCustomAction(ACTION_BACK_10, "Back 10 seconds", R.drawable.ic_replay_10)
            builder.addCustomAction(ACTION_FORWARD_10, "Forward 10 seconds", R.drawable.ic_forward_10)
        }
        session.setPlaybackState(builder.build())
    }

    companion object {
        const val ACTION_BACK_10 = "autoclock.back_10"
        const val ACTION_FORWARD_10 = "autoclock.forward_10"
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
