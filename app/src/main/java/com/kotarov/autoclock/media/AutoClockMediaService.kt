package com.kotarov.autoclock.media

import android.content.Intent
import com.kotarov.autoclock.BuildConfig
import com.kotarov.autoclock.PerfDiagnostics
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
    private var folderRequest = 0L
    private val playMode = BuildConfig.PLAY_MEDIA_MODE
    private var requestedPlayback = false
    private var focusInterrupted = false
    private var mediaError: String? = null
    private val focus by lazy { PlayAudioFocus(this) { change -> onFocusChange(change) } }
    private var foregroundAudio = false
    private val callMonitor by lazy { CallInterruptionMonitor(this, handler) { busy -> onCallChanged(busy) } }
    private var callBlocked = false
    private val liteRequested: Boolean get() = ClockSelectionStore.weatherEnabled(prefs)
    private val running: Boolean
        get() = if (playMode) requestedPlayback && !focusInterrupted else liteRequested && !callBlocked
    private val cityAnnouncement = CityAnnouncement()
    private val noticeAudio by lazy { NoticeAudio(this) }
    private val streamPlayer by lazy { CustomStreamPlayer(this) }
    private val phoneMode: PhoneAudioMode get() = PhoneAudioMode.read(prefs)
    private var noticePlaying = false
    private var testNotice: CityAnnouncement.Notice? = null
    private val nowPlayingTitle = NowPlayingTitle()
    private val hideNowPlayingTitle = Runnable {
        nowPlayingTitle.clear()
        if (clientActive) refreshPositionSubtitle()
    }
    private fun clearNowPlayingTitle() {
        handler.removeCallbacks(hideNowPlayingTitle)
        nowPlayingTitle.clear()
    }
    private fun showNowPlayingTitle() {
        clearNowPlayingTitle()
        if (!playMode && !callBlocked && nowPlayingTitle.show(activeSource?.name, SystemClock.elapsedRealtime()))
            handler.postDelayed(hideNowPlayingTitle, NowPlayingTitle.DURATION_MS)
    }
    private fun currentCaption(tick: Long): String? =
        if (callBlocked) "Call · paused" else
            (testNotice ?: cityAnnouncement.notice(tick))?.subtitle ?: nowPlayingTitle.text(tick)
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
    private var externalClient = false
    private var writingTransportPreference = false
    private var removeProjectionListener: (() -> Unit)? = null
    private var suspendedForProjection = false
    private var lastWarningRevision = 0L
    private var queuedWarning: String? = null
    private var baseMetadata: MediaMetadataCompat? = null
    private var artKey: String? = null
    private var miniKey: String? = null
    private var lastPublished: MediaPublicationKey? = null
    private data class PlaybackKey(val running: Boolean, val id: String, val duration: Long?, val advancing: Boolean, val textSkip: Boolean, val textPosition: Long?, val speed: Float, val phoneAudio: Boolean)
    private var lastPlayback: PlaybackKey? = null
    private var lastPlaybackAt = 0L
    private var weatherClient: WeatherStore.Client? = null
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
        if (key == CarLibraryTabs.KEY_ALERT_PIN) {
            notifyLibraryChanged()
            return@OnSharedPreferenceChangeListener
        }
        if (key == CallInterruptionMonitor.PERMISSION_REFRESH) {
            if (!playMode && clientActive) callMonitor.refreshPermission()
            return@OnSharedPreferenceChangeListener
        }
        if (writingTransportPreference && key == ClockPreset.KEY_ADD_WEATHER) return@OnSharedPreferenceChangeListener
        if (!clientActive) {
            if (!playMode && (key == PhoneAudioMode.KEY || key == NoticeAudio.KEY_PHONE_CHANNEL)) publishPlaybackState()
            if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
                activeSource = StreamLibrary.selected(prefs)
                if (key == StreamLibrary.KEY_LIST) notifyLibraryChanged()
                publishPlaybackState()
            }
            return@OnSharedPreferenceChangeListener
        }
        if (PlaybackSpeed.isSourceKey(key)) {
            val source = activeSource ?: return@OnSharedPreferenceChangeListener
            if (key != PlaybackSpeed.sourceKey(source.url)) return@OnSharedPreferenceChangeListener
            streamPlayer.setSpeed(PlaybackSpeed.read(prefs, source), source.url)
            lastPlayback = null
            publishPlaybackState()
            refreshPositionSubtitle()
            return@OnSharedPreferenceChangeListener
        }
        if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
            activeSource = StreamLibrary.selected(prefs)
        }
        if (playMode) {
            if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
                if (activeSource == null && requestedPlayback) setPlayRequested(false)
                else if (requestedPlayback && !focusInterrupted) syncStream()
                if (key == StreamLibrary.KEY_LIST) notifyLibraryChanged()
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
        if (key == ClockPreset.KEY_ADD_WEATHER) {
            if (running) showNowPlayingTitle()
            else { clearNowPlayingTitle(); stopNoticeAudio() }
        }
        if (key == StreamLibrary.KEY_SELECTED && running) showNowPlayingTitle()
        if (key == ClockPreset.KEY_ADD_WEATHER ||
            key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) syncStream()
        if (key == StreamLibrary.KEY_LIST) notifyLibraryChanged()
        if (key == StreamLibrary.KEY_LIST || key == StreamLibrary.KEY_SELECTED) {
            if (activeSource == null) clearNowPlayingTitle()
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
        PlaybackSpeed.migrate(prefs)
        activeSource = StreamLibrary.selected(prefs)
        session = MediaSessionCompat(this, "AutoClockMediaTestsV037").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    val request = ++folderRequest
                    val folderChoice = FolderNavigation.parse(mediaId)
                    if (folderChoice != null) {
                        if (!playMode) playFolder(folderChoice, request)
                        return
                    }
                    if (prepareBookChoice(mediaId) || selectSource(mediaId)) setWeatherEnabled(true)
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (FolderNavigation.parse(mediaId) != null) return
                    if (playMode) setWeatherEnabled(false)
                    if (prepareBookChoice(mediaId) || selectSource(mediaId)) {
                        publishClock()
                        publishPlaybackState()
                    }
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action == ACTION_SPACER) return
                    if (action == ACTION_PHONE_AUDIO) {
                        if (!playMode && android.os.Build.VERSION.SDK_INT >= 31) PhoneAudioMode.toggle(prefs)
                        return
                    }
                    if (action == ACTION_SPEED_DOWN || action == ACTION_SPEED_UP) {
                        val source = activeSource ?: return
                        if (!supportsSpeed(source)) return
                        val current = PlaybackSpeed.read(prefs, source)
                        val next = PlaybackSpeed.step(current, if (action == ACTION_SPEED_UP) 1 else -1)
                        if (next != current) PlaybackSpeed.write(prefs, source, next)
                        return
                    }
                    val delta = when (action) {
                        ACTION_BACK_10 -> -10000
                        ACTION_FORWARD_10 -> 10000
                        else -> return
                    }
                    val station = activeSource
                    val moved = if (station?.textFile == true) streamPlayer.skipText(station.url, delta)
                        else streamPlayer.seekBy(station?.url, delta, remoteBookmark(station))
                    if (moved) {
                        lastPlayback = null
                        publishPlaybackState()
                        refreshPositionSubtitle()
                    }
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val items = StreamLibrary.list(prefs).filterNot { it.mediaPage }
                    val match = if (query.isNullOrBlank()) activeSource ?: items.firstOrNull()
                        else items.firstOrNull { it.name.contains(query, ignoreCase = true) }
                    if (match != null && selectSource(match.id)) setWeatherEnabled(true)
                }
                override fun onSeekTo(pos: Long) {
                    val source = activeSource
                    val moved = if (source?.textFile == true) streamPlayer.seekTextTo(source.url, pos)
                        else streamPlayer.seekTo(source?.url, pos, remoteBookmark(source))
                    if (moved) {
                        lastPlayback = null
                        publishPlaybackState()
                        refreshPositionSubtitle()
                    }
                }
                override fun onPlay() = setWeatherEnabled(true)
                override fun onPause() { folderRequest++; setWeatherEnabled(false) }
                override fun onStop() { folderRequest++; setWeatherEnabled(false) }
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
                externalClient = false
                suspendedForProjection = clientActive
                if (clientActive) suspendClientWork()
                stopSelf()
            } else if (suspendedForProjection) {
                suspendedForProjection = false
                externalClient = true
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
        if (AutoClockBrowseRouting.emptySuggestionsRoot(clientPackageName, requestedSuggestions)) {
            return BrowserRoot(AutoClockBrowseRouting.EMPTY_SUGGESTIONS_ROOT, null)
        }
        if (!AutoClockBrowseRouting.allowLibrary(
                clientPackageName, requestedSuggestions
            )) return null
        // Merely opening our phone panel must not start the car's media/clock loop.
        if (clientPackageName != packageName || playMode) {
            val upgrade = clientPackageName != packageName && !externalClient && clientActive
            if (clientPackageName != packageName) externalClient = true
            resumeClientWork()
            if (upgrade && !playMode) {
                updateWeatherClient()
                lastPlayback = null
                publishClock()
                publishPlaybackState()
            }
        }
        // The car root contains only navigation tabs, never the playable
        // library entries. Other clients retain the original flat root.
        val carRoot = !playMode && clientPackageName == "com.google.android.projection.gearhead"
        val limit = rootHints?.getInt(androidx.media.utils.MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 4) ?: 4
        val extras = if (carRoot) Bundle().apply {
            putParcelable("androidx.media.BrowserRoot.Extras.APPLICATION_PREFERENCES_USING_CAR_APP_LIBRARY_INTENT",
                Intent(this@AutoClockMediaService, com.kotarov.autoclock.car.PhoneSettingsService::class.java))
        } else null
        return BrowserRoot(if (carRoot) CarLibraryTabs.rootId(limit) else ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId == AutoClockBrowseRouting.EMPTY_SUGGESTIONS_ROOT) {
            result.sendResult(mutableListOf())
            return
        }
        val tabLimit = CarLibraryTabs.rootLimit(parentId)
        if (!playMode && tabLimit != null) {
            val items = mutableListOf<MediaBrowserCompat.MediaItem>()
            fun tab(id: String, title: String, icon: Int) {
                val desc = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title)
                    .setIconBitmap(sourceIcon(icon, 64, white = icon != R.drawable.ic_source_alert)).build()
                items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
            }
            tab(CarLibraryTabs.LIBRARY_ID, "Library", R.drawable.ic_source_folder)
            val alertPinned = prefs.getBoolean(CarLibraryTabs.KEY_ALERT_PIN, false)
            if (alertPinned && tabLimit > 1) tab(CarLibraryTabs.ALERT_TAB, "Alert only", R.drawable.ic_source_alert)
            CarLibraryTabs.pinned(StreamLibrary.list(prefs), tabLimit, alertPinned).forEach { station ->
                val id = when {
                    station.browsable -> station.id
                    CarLibraryTabs.browsable(station) -> BookNavigation.BOOK_PREFIX + station.id
                    else -> CarLibraryTabs.sourceTab(station.id)
                }
                tab(id, station.name, libraryIcon(station))
            }
            result.sendResult(items)
            return
        }
        val pinnedId = CarLibraryTabs.sourceId(parentId)
        if (!playMode && (parentId == CarLibraryTabs.ALERT_TAB || pinnedId != null)) {
            val station = StreamLibrary.list(prefs).firstOrNull { it.id == pinnedId && !CarLibraryTabs.browsable(it) }
            val items = mutableListOf<MediaBrowserCompat.MediaItem>()
            if (parentId == CarLibraryTabs.ALERT_TAB || station != null) {
                val desc = MediaDescriptionCompat.Builder()
                    .setMediaId(station?.id ?: CLOCK_ID)
                    .setTitle(station?.name ?: "Alert only")
                    .setIconBitmap(sourceIcon(station?.let { libraryIcon(it) } ?: R.drawable.ic_source_alert))
                    .build()
                items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
            }
            result.sendResult(items)
            return
        }
        if (!playMode && parentId.startsWith(BookNavigation.BOOK_PREFIX)) {
            val book = StreamLibrary.book(prefs, parentId.removePrefix(BookNavigation.BOOK_PREFIX))
            if (book == null) { result.sendResult(mutableListOf()); return }
            result.detach()
            BookNavigation.load(this, book) { chapters ->
                val children = mutableListOf<MediaBrowserCompat.MediaItem>()
                if (StreamLibrary.book(prefs, book.id)?.url == book.url) {
                    BookNavigation.choices(book, chapters).forEach { choice ->
                        val desc = MediaDescriptionCompat.Builder().setMediaId(choice.id).setTitle(choice.title).build()
                        children.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                    }
                }
                result.sendResult(children)
            }
            return
        }
        if (parentId != ROOT_ID && parentId != CarLibraryTabs.LIBRARY_ID) {
            val page = StreamLibrary.list(prefs).firstOrNull { it.browsable && it.id == parentId }
            if (page == null) { result.sendResult(mutableListOf()); return }
            result.detach()
            MediaPageDirectory.load(this, page) { outcome ->
                val valid = StreamLibrary.list(prefs).any { it.id == page.id && it.url == page.url && it.browsable }
                val children = mutableListOf<MediaBrowserCompat.MediaItem>()
                val entries = if (valid) outcome.getOrNull().orEmpty() else emptyList()
                if (!playMode && page.localFolder && entries.isNotEmpty()) {
                    FolderNavigation.choices(page.id).forEach { choice ->
                        val desc = MediaDescriptionCompat.Builder().setMediaId(choice.id)
                            .setTitle(choice.title).build()
                        children.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                    }
                }
                val streamIcon = if (page.mediaPage && entries.size <= 40) sourceIcon(R.drawable.ic_source_podcast, 32) else null
                entries.forEach { item ->
                    val bookFolder = !playMode && item.textFile && BookNavigation.mayHaveContents(item.name)
                    val desc = MediaDescriptionCompat.Builder().setMediaId(if (bookFolder) BookNavigation.BOOK_PREFIX + item.id else item.id).setTitle(item.name)
                        .setSubtitle(item.format).apply {
                            if (streamIcon != null) setIconBitmap(streamIcon)
                            else if (page.mediaPage) setIconUri(browseIconUri(R.drawable.ic_source_podcast))
                        }.build()
                    children.add(MediaBrowserCompat.MediaItem(desc, if (bookFolder) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                }
                result.sendResult(children)
            }
            return
        }
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val stations = StreamLibrary.list(prefs)
        val inlineIcons = stations.size <= 40
        val alert = MediaDescriptionCompat.Builder().setMediaId(CLOCK_ID)
            .setTitle("Alert only")
            .apply { if (inlineIcons) setIconBitmap(sourceIcon(R.drawable.ic_source_alert)) else setIconUri(browseIconUri(R.drawable.ic_source_alert)) }.build()
        if (!playMode) items.add(MediaBrowserCompat.MediaItem(alert, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        stations.forEach { station ->
            val bookFolder = !playMode && station.textFile && BookNavigation.mayHaveContents(station.name)
            val desc = MediaDescriptionCompat.Builder().setMediaId(if (bookFolder) BookNavigation.BOOK_PREFIX + station.id else station.id)
                .setTitle(station.name).apply {
                    val icon = if (station.localFolder) R.drawable.ic_source_folder else if (station.mediaPage) R.drawable.ic_source_web else if (station.textFile) R.drawable.ic_source_text else if (station.local) R.drawable.ic_source_file else R.drawable.ic_source_podcast
                    if (inlineIcons) setIconBitmap(sourceIcon(icon)) else setIconUri(browseIconUri(icon))
                }.build()
            items.add(MediaBrowserCompat.MediaItem(desc, if (station.browsable || bookFolder) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }
        result.sendResult(items)
    }

    private fun playFolder(choice: FolderNavigation.Request, request: Long) {
        val folder = StreamLibrary.list(prefs).firstOrNull { it.localFolder && it.id == choice.folderId } ?: return
        fun start(items: List<MediaPageParser.Item>) {
            if (request != folderRequest || !StreamLibrary.list(prefs).any { it.localFolder && it.id == folder.id && it.url == folder.url }) return
            val remembered = if (choice.resume) StreamLibrary.folderPosition(prefs, folder) else null
            val id = FolderNavigation.target(items.map { it.id }, remembered) ?: return
            val item = items.first { it.id == id }
            if (!choice.resume || remembered != id) {
                setWeatherEnabled(false)
                streamPlayer.resetBookmark(item.url)
            }
            if (selectSource(id)) setWeatherEnabled(true)
        }
        val snapshot = MediaPageDirectory.folderSnapshot(folder)
        if (snapshot != null) start(snapshot)
        else MediaPageDirectory.load(this, folder) { outcome -> start(outcome.getOrNull().orEmpty()) }
    }

    private fun libraryIcon(station: StreamLibrary.Station): Int =
        if (station.localFolder) R.drawable.ic_source_folder
        else if (station.mediaPage) R.drawable.ic_source_web
        else if (station.textFile) R.drawable.ic_source_text
        else if (station.local) R.drawable.ic_source_file else R.drawable.ic_source_podcast

    private fun notifyLibraryChanged() {
        notifyChildrenChanged(ROOT_ID)
        if (!playMode) {
            notifyChildrenChanged(CarLibraryTabs.LIBRARY_ID)
            for (limit in 1..CarLibraryTabs.MAX_TABS) notifyChildrenChanged(CarLibraryTabs.rootId(limit))
        }
    }

    private fun browseIconUri(resource: Int): android.net.Uri =
        android.net.Uri.parse("android.resource://$packageName/$resource")

    private fun sourceIcon(resource: Int, size: Int = 64, white: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val drawable = getDrawable(resource)?.mutate()
        if (white) drawable?.setTint(android.graphics.Color.WHITE)
        val padding = size / 16
        drawable?.setBounds(padding, padding, size - padding, size - padding)
        drawable?.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun prepareBookChoice(id: String?): Boolean {
        if (playMode) return false
        val choice = BookNavigation.parseChoice(id) ?: return false
        val book = StreamLibrary.book(prefs, choice.first) ?: return false
        // Checkpoint the outgoing document before replacing the shared bookmark.
        // No new audio route is created until the normal Play command.
        setWeatherEnabled(false)
        if (!selectSource(book.id)) return false
        getSharedPreferences("text_bookmarks", MODE_PRIVATE).edit().putInt(book.url, choice.second).apply()
        return true
    }

    private fun selectSource(id: String?): Boolean {
        if (playMode && id == CLOCK_ID) return false
        if (!StreamLibrary.select(prefs, id)) return false
        activeSource = StreamLibrary.selected(prefs)
        return true
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): String? =
        try { packageManager.getPackageInfo(packageName, 0).versionName }
        catch (_: Exception) { null }

    private fun resumeClientWork(startAudio: Boolean = true) {
        if (clientActive) return
        suspendedForProjection = false
        clientActive = true
        activeSource = StreamLibrary.selected(prefs)
        if (playMode) {
            publishPlayMetadata()
            publishPlaybackState()
            return
        }
        callBlocked = callMonitor.start()
        NoticeTest.receiver = receiveTest
        if (startAudio) syncStream()
        lastWarningRevision = prefs.getLong(WeatherStore.KEY_WARNING_REV, 0L)
        cityAnnouncement.reset(WeatherStore.locality(this))
        updateWeatherClient()
        publishClock()
        publishPlaybackState()

        scheduleNextMinute()
    }

    private fun updateWeatherClient() {
        val next = if (externalClient) WeatherStore.Client.CAR else WeatherStore.Client.PHONE_MEDIA
        if (weatherClient == next) return
        // Add the new owner before releasing the old one; preserve shared cached data.
        WeatherStore.start(this, next)
        weatherClient?.let { WeatherStore.stop(it) }
        weatherClient = next
    }

    private fun suspendClientWork() {
        if (playMode) {
            requestedPlayback = false
            focusInterrupted = false
            focus.abandon()
            stopPlaybackForeground()
        }
        clientActive = false
        if (!playMode) callMonitor.stop()
        callBlocked = false
        if (PerfDiagnostics.enabled) PerfDiagnostics.audio = "Idle"
        if (NoticeTest.receiver === receiveTest) NoticeTest.receiver = null
        handler.removeCallbacks(hideTestNotice)
        testNotice = null
        clearNowPlayingTitle()
        NoticeTest.publishCaption(null)
        weatherClient?.let { WeatherStore.stop(it) }
        weatherClient = null
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
        setSessionMetadata(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        externalClient = false
        suspendedForProjection = false
        suspendClientWork()
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        // onGetRoot resumes work for the accepted client.
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) ClockRenderer.clearStaticLayers()
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

    /** A call is a temporary interruption, not a change to the user's Play/Pause preference. */
    private fun onCallChanged(busy: Boolean) {
        if (!clientActive || playMode || callBlocked == busy) return
        callBlocked = busy
        lastPlayback = null
        if (busy) {
            clearNowPlayingTitle()
            handler.removeCallbacks(positionTick)
            stopNoticeAudio()
            // stop() checkpoints the local media/TTS position and relinquishes HFP.
            streamPlayer.stop()
        } else if (liteRequested) {
            streamPlayer.retryFailed()
            syncStream()
        }
        publishClock()
        publishPlaybackState()
        refreshPositionSubtitle()
    }

    private fun setWeatherEnabled(enabled: Boolean) {
        if (playMode) {
            setPlayRequested(enabled)
            return
        }
        if (!enabled) {
            clearNowPlayingTitle()
            stopNoticeAudio()
            streamPlayer.stop()
        } else if (running && !noticePlaying) {
            // Retry a failed request without restarting an already playing source.
            streamPlayer.retryFailed()
        }
        writingTransportPreference = true
        try { ClockSelectionStore.setWeatherEnabled(prefs, enabled) }
        finally { writingTransportPreference = false }
        if (enabled && !clientActive) resumeClientWork(startAudio = false)
        if (enabled) showNowPlayingTitle()
        // The same preference listener handles phone switches. Publish now
        // for car transport commands, avoiding duplicate queued artwork.
        handler.removeCallbacks(settingsRefresh)
        if (clientActive) {
            syncStream()
            publishClock()
            publishPlaybackState()
            if (!enabled && !externalClient) suspendClientWork()
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
        PlaybackDisplay.visiblePreset(selectedPreset(), if (playMode) running else liteRequested)

    private fun publishClock() {
        if (!clientActive) return
        updateDiagnosticAudio()
        if (playMode) { publishPlayMetadata(); return }
        val now = LocalDateTime.now()
        val visible = visiblePreset()
        val showArtwork = externalClient
        val weather = if (showArtwork && visible.usesWeather) WeatherStore.current(this) else null
        val theme = selectedTheme()
        val showClockForecast = showArtwork && visible.usesWeather
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
        val artwork = if (showArtwork) ClockArtworkProvider.artworkUri(
            visible.id, now, revision, compactLayout(), 0L, theme
        ) else null
        val miniArtwork = if (showArtwork) ClockArtworkProvider.miniArtworkUri(
            visible.family, now, 0L, theme
        ) else null
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
        val caption = currentCaption(tick)
        val position = if (caption == null) currentMediaPosition() else null
        val subtitle = informationText(caption, position)
        NoticeTest.publishCaption(subtitle)
        val key = if (artwork != null && miniArtwork != null) artwork.toString() + "|" + miniArtwork.toString() else "phone-only"
        if (artwork == null || miniArtwork == null) {
            // Phone previews already draw both clocks. Do not generate invisible car artwork.
            if (artKey != key || baseMetadata == null) {
                artworkWorker.cancel()
                requestedArtKey = null
                miniKey = null
                PublishedArtwork.clear()
                baseMetadata = MediaMetadataCompat.Builder().build()
                artKey = key
            }
            publishMediaSubtitle(key, subtitle)
        } else if (artKey != key || baseMetadata == null) {
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
            publishMediaSubtitle(key, subtitle)
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
            // The host's own progress bar advances independently of this text.
            handler.postDelayed(positionTick, 5_000L)
        }
    }

    private fun refreshPositionSubtitle() {
        if (!clientActive) return
        streamPlayer.checkpointRemote()
        updateDiagnosticAudio()
        val caption = if (playMode) null else currentCaption(SystemClock.elapsedRealtime())
        val position = if (caption == null) currentMediaPosition() else null
        val subtitle = informationText(caption, position)
        NoticeTest.publishCaption(subtitle)
        if (playMode) publishPlayMetadata() else artKey?.let { publishMediaSubtitle(it, subtitle) }
        schedulePositionTick(caption, position)
    }

    private fun informationText(caption: String?, position: Int?): String? {
        if (!caption.isNullOrBlank()) return caption
        val book = bookTimeline()
        val elapsed = if (book != null) MediaPositionSubtitle.book(book.positionMs, book.durationMs)
            else MediaPositionSubtitle.text(null, position ?: mediaSeek()?.position)
        val source = activeSource
        if (!supportsSpeed(source)) return elapsed
        val rate = String.format(java.util.Locale.ROOT, "%.1f×", PlaybackSpeed.read(prefs, source))
        return if (elapsed.isNullOrBlank()) rate else "$elapsed · $rate"
    }

    private fun supportsSpeed(source: StreamLibrary.Station?): Boolean =
        source != null && (source.textFile || source.local || mediaSeek() != null)

    private fun addSpeedActions(builder: PlaybackStateCompat.Builder, source: StreamLibrary.Station?) {
        if (!supportsSpeed(source)) return
        // Keep these after the existing skip controls so Auto can prioritize them.
        builder.addCustomAction(ACTION_SPEED_DOWN, "Decrease speed by 0.1", R.drawable.ic_auto_speed_minus_v114)
        builder.addCustomAction(ACTION_SPEED_UP, "Increase speed by 0.1", R.drawable.ic_auto_speed_plus_v114)
    }

    private fun publishMediaSubtitle(key: String, value: String?) {
        val base = baseMetadata ?: return
        val book = bookTimeline()
        val subtitle = value ?: INVISIBLE_TEXT
        val duration = book?.durationMs ?: mediaSeek()?.duration?.toLong()
        val publication = MediaPublicationKey(key, INVISIBLE_TEXT, subtitle, activeSource?.id ?: CLOCK_ID, duration)
        if (publication == lastPublished) return
        setSessionMetadata(MediaMetadataCompat.Builder(base)
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

    private fun remoteBookmark(station: StreamLibrary.Station?): String? =
        station?.takeUnless { it.local || it.textFile || it.browsable }?.let {
            RemotePlaybackBookmarks.key(StreamLibrary.selectedRootId(prefs), it.id, it.url)
        }

    private fun syncStream() {
        if (!playMode && callBlocked) {
            publishPlaybackState()
            refreshPositionSubtitle()
            return
        }
        if (noticePlaying && running) {
            publishPlaybackState()
            refreshPositionSubtitle()
            return
        }
        val station = if (clientActive && running && !noticePlaying)
            activeSource else null
        val url = station?.url
        streamPlayer.setSpeed(PlaybackSpeed.read(prefs, station), station?.url)
        streamPlayer.sync(url, !playMode && phoneMode != PhoneAudioMode.OFF,
            textLanguage = station?.takeIf { it.textFile }?.language,
            bookmarkKey = remoteBookmark(station), onChanged = {
            if (playMode || station?.textFile != true) lastPlayback = null
            publishPlaybackState()
            refreshPositionSubtitle()
        }) { error ->
            // The route callback may arrive just before the call-state event.
            // Treat a confirmed phone call as an interruption, not a stream failure.
            if (!playMode && callMonitor.isCallNow()) {
                // Synchronize the monitor's reported state as well, so IDLE can resume.
                callMonitor.refreshPermission()
                onCallChanged(true)
                return@sync
            }
            if (playMode) {
                setPlayRequested(false)
                mediaError = if (error == "Media ended") null else error
                lastPlayback = null
                publishPlaybackState()
                return@sync
            }
            lastPlayback = null
            publishPlaybackState()
            if (error == "Media ended") {
                val folder = StreamLibrary.selectedFolder(prefs)
                if (folder != null && station != null && liteRequested) {
                    advanceFolder(folder, station)
                    return@sync
                }
                setWeatherEnabled(false)
            }
            if (clientActive && !noticePlaying && testNotice == null) {
                testNotice = CityAnnouncement.Notice("ℹ️", error)
                handler.removeCallbacks(hideTestNotice)
                handler.postDelayed(hideTestNotice, CityAnnouncement.DURATION_MS)
                publishClock()
            }
        }
        publishPlaybackState()
        refreshPositionSubtitle()
    }

    private fun advanceFolder(folder: StreamLibrary.Station, ended: StreamLibrary.Station) {
        fun advance(items: List<MediaPageParser.Item>) {
            if (!clientActive || !liteRequested || activeSource?.id != ended.id ||
                StreamLibrary.selectedFolder(prefs)?.id != folder.id) return
            val index = items.indexOfFirst { it.id == ended.id }
            val next = if (index >= 0) items.getOrNull(index + 1) else null
            if (next != null) {
                streamPlayer.resetBookmark(next.url)
                if (selectSource(next.id)) setWeatherEnabled(true) else setWeatherEnabled(false)
            } else {
                if (index >= 0) StreamLibrary.clearFolderPosition(prefs, folder)
                setWeatherEnabled(false)
            }
        }
        // Post the transition outside the completed player's callback.
        val snapshot = MediaPageDirectory.folderSnapshot(folder)
        if (snapshot != null && snapshot.any { it.id == ended.id }) handler.post { advance(snapshot) }
        else MediaPageDirectory.load(this, folder) { outcome -> advance(outcome.getOrNull().orEmpty()) }
    }

    private fun playNotice(body: String) {
        if (playMode || callBlocked) return
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

    /** Audio has real milliseconds; book timeline uses a labelled speech-time estimate. */
    private fun bookTimeline(): TextReading.Timeline? = activeSource?.takeIf { it.textFile }
        ?.let { streamPlayer.bookTimeline(it.url) }

    private fun mediaSeek(): CustomStreamPlayer.SeekState? = activeSource?.takeUnless { it.textFile }
        ?.let { streamPlayer.seekState(it.url, remoteBookmark(it)) }

    private fun setSessionMetadata(value: MediaMetadataCompat?) {
        PerfDiagnostics.metadata()
        session.setMetadata(value)
    }

    private fun setSessionPlaybackState(value: PlaybackStateCompat) {
        PerfDiagnostics.playback()
        session.setPlaybackState(value)
    }

    private fun updateDiagnosticAudio() {
        if (!PerfDiagnostics.enabled) return
        PerfDiagnostics.audio = if (clientActive && callBlocked) "Paused for call" else if (!clientActive || !running) "Idle" else
            (if (noticePlaying) "Notice" else if (activeSource == null) "Alerts only" else
                (if (activeSource?.textFile == true) "Text-to-speech" else "Media") +
                    if (streamPlayer.isPlaying()) " playing" else " not playing") +
                (if (phoneMode != PhoneAudioMode.OFF && !playMode) " / phone route requested" else "")
    }

    private fun publishPlaybackState() {
        updateDiagnosticAudio()
        if (playMode) { publishPlayState(); return }
        val isRunning = running
        val station = activeSource
        val seek = mediaSeek()
        val advancing = isRunning && seek != null && !noticePlaying && streamPlayer.isPlaying()
        val textSkip = station?.textFile == true && streamPlayer.canSkipText(station.url)
        val book = bookTimeline()
        val duration = book?.durationMs ?: seek?.duration?.toLong()
        val speed = if (advancing) streamPlayer.actualMediaSpeed() else 0f
        val phoneAudio = phoneMode != PhoneAudioMode.OFF
        val key = PlaybackKey(isRunning, station?.id ?: CLOCK_ID, duration, advancing, textSkip, book?.positionMs, speed, phoneAudio)
        val previous = lastPlayback
        if (previous == key) return
        val now = SystemClock.elapsedRealtime()
        // TTS reports its estimated position every five seconds. Publish the exact
        // current estimate at most every 15s when ONLY the position changed.
        // A seek/speed command clears lastPlayback; transitions are immediate.
        if (book != null && isRunning && !noticePlaying && previous != null &&
            previous.copy(textPosition = key.textPosition) == key &&
            now - lastPlaybackAt < 15_000L) return
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
        if (seek != null || textSkip) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        val builder = PlaybackStateCompat.Builder()
            .setState(ClockPlaybackState.forRunning(isRunning),
                book?.positionMs ?: seek?.position?.toLong() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                speed)
            .setActions(actions)
        // Leave next/previous slots free: Auto may place these custom actions there.
        if (seek != null || textSkip) {
            builder.addCustomAction(ACTION_BACK_10, "Back 10 seconds", R.drawable.ic_replay_10)
            builder.addCustomAction(ACTION_FORWARD_10, "Forward 10 seconds", R.drawable.ic_forward_10)
        }
        addSpeedActions(builder, station)
        // Experimental visual spacer before phone routing. Auto owns the
        // actual slot allocation and may move actions into overflow.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            builder.addCustomAction(ACTION_SPACER, "Spacer", R.drawable.ic_auto_action_spacer)
            builder.addCustomAction(ACTION_PHONE_AUDIO,
                if (phoneAudio) "Disable phone audio" else "Enable phone audio",
                if (phoneAudio) R.drawable.ic_auto_phone_audio_on else R.drawable.ic_auto_phone_audio_off)
        }
        setSessionPlaybackState(builder.build())
        lastPlayback = key
        lastPlaybackAt = now
    }

    private var playMetadataSource: StreamLibrary.Station? = null
    private var playMetadataDuration: Long? = null
    private var playMetadataSubtitle: String? = null
    private fun publishPlayMetadata() {
        val source = activeSource
        val duration = bookTimeline()?.durationMs ?: mediaSeek()?.duration?.toLong()
        val subtitle = informationText(null, currentMediaPosition())
        val previous = session.controller.metadata
        if (source == playMetadataSource && duration == playMetadataDuration && subtitle == playMetadataSubtitle && previous != null) return
        val previousArt = if (source == playMetadataSource) previous?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART) else null
        playMetadataSource = source
        playMetadataDuration = duration
        playMetadataSubtitle = subtitle
        val builder = MediaMetadataCompat.Builder()
        if (source != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, source.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, source.name)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, source.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                    if (source.textFile) "Text reading · estimated time" else if (source.local) "Local audio" else "Audio stream")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle ?: if (source.textFile) "Text reading" else "Audio")
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART,
                    previousArt ?: sourceIcon(if (source.textFile) R.drawable.ic_source_text
                        else if (source.local) R.drawable.ic_source_file else R.drawable.ic_source_podcast))
        } else builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Select audio")
        duration?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it.toLong()) }
        setSessionMetadata(builder.build())
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
                val first = StreamLibrary.list(prefs).firstOrNull { !it.browsable }
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
            val notification = builder.setSmallIcon(R.drawable.ic_car_attribution)
                .setContentTitle(activeSource?.name ?: getString(R.string.app_name))
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
        val book = bookTimeline()
        val textSkip = source?.textFile == true && streamPlayer.canSkipText(source.url)
        if (seek != null || textSkip) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        val builder = PlaybackStateCompat.Builder().setActions(actions)
            .setState(state, book?.positionMs ?: seek?.position?.toLong() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (state == PlaybackStateCompat.STATE_PLAYING && seek != null) streamPlayer.actualMediaSpeed() else 0f)
        mediaError?.let { builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it) }
        if (seek != null || (source?.textFile == true && streamPlayer.canSkipText(source.url))) {
            builder.addCustomAction(ACTION_BACK_10, "Back 10 seconds", R.drawable.ic_replay_10)
            builder.addCustomAction(ACTION_FORWARD_10, "Forward 10 seconds", R.drawable.ic_forward_10)
        }
        addSpeedActions(builder, source)
        setSessionPlaybackState(builder.build())
    }

    companion object {
        private const val ACTION_SPACER = "autoclock.action_spacer"
        const val ACTION_PHONE_AUDIO = "autoclock.phone_audio_toggle"
        const val ACTION_BACK_10 = "autoclock.back_10"
        const val ACTION_FORWARD_10 = "autoclock.forward_10"
        const val ACTION_SPEED_DOWN = "autoclock.speed_down_v114"
        const val ACTION_SPEED_UP = "autoclock.speed_up_v114"
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
