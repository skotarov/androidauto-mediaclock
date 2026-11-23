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
import com.kotarov.autoclock.media.ClockTheme
import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime
import java.util.Locale

/**
 * Personal Android Auto media-card clock experiment. One update per minute
 * while active. No fake sound, location polling, wake locks or background
 * animation. Android Auto owns its media card and may cache the artwork.
 */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private lateinit var prefs: SharedPreferences
    private var running = false
    private var preset = ClockPreset.ANALOG_PURE
    private var artworkGeneration = 0L
    private val settingsRefresh = Runnable {
        preset = selectedPreset()
        artworkGeneration += 1L
        notifyChildrenChanged(ROOT_ID)
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
            key == ClockPreset.KEY_WEATHER_ADD_ANALOG ||
            key == ClockPreset.KEY_WEATHER_ADD_DIGITAL ||
            key == KEY_MODE || key == KEY_HIDE_MEDIA_TEXT ||
            key == KEY_LAYOUT || key == ClockTheme.KEY ||
            key == WeatherStore.KEY_COLORED_ICONS ||
            key == KEY_PROGRESS_ANIMATED ||
            key == WeatherStore.KEY_CITY || key == "weather_cached_city" ||
            key == "weather_cached_mode" || key == "weather_station_label" ||
            key == "weather_source_name" ||
            key == "weather_fetched_at" || key == "weather_code" ||
            key == "weather_temperature_bits" || key == "weather_is_day"
        ) {
            handler.removeCallbacks(settingsRefresh)
            handler.postDelayed(settingsRefresh, 120L)
        }
    }

    // Pause is a weather screen. One small Handler task refreshes it every
    // 15 minutes while the media service is alive, with no wake lock or GPS
    // subscription. It is cancelled as soon as Play resumes.
    private val pausedWeatherTick = object : Runnable {
        override fun run() {
            if (running) return
            requestWeatherIfNecessary()
            publishClock()
            schedulePausedWeatherRefresh()
        }
    }

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

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        preset = selectedPreset()
        session = MediaSessionCompat(this, "AutoClockV3").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    preset = selectedPreset()
                    startClock()
                }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    chooseFromMediaId(mediaId)
                    startClock()
                }
                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    chooseFromMediaId(mediaId)
                    publishClock()
                    publishPlaybackState()
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val q = query?.lowercase(Locale.ROOT).orEmpty()
                    val family = if ("digital" in q || "цифр" in q)
                        ClockFamily.DIGITAL else ClockFamily.ANALOG
                    chooseFamily(family)
                    startClock()
                }
                override fun onPause() = stopClock()
                override fun onStop() = stopClock()
                override fun onSkipToNext() {
                    val options = PlaybackDisplay.selectableFamilies
                    val next = options[(options.indexOf(preset.family) + 1) % options.size]
                    chooseFamily(next)
                    startClock()
                }
                override fun onSkipToPrevious() {
                    val options = PlaybackDisplay.selectableFamilies
                    val previous = options[
                        (options.indexOf(preset.family) + options.size - 1) % options.size
                    ]
                    chooseFamily(previous)
                    startClock()
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        publishClock()
        publishPlaybackState()
        requestWeatherIfNecessary()
        schedulePausedWeatherRefresh()
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
        val now = LocalDateTime.now()
        // Only Analog and Digital appear in the media browser; weather is
        // shown through Pause, not as a third selectable clock.
        val items = PlaybackDisplay.selectableFamilies.map { family ->
            val option = ClockSelectionStore.forFamily(prefs, family)
            val description = MediaDescriptionCompat.Builder()
                .setMediaId("family_${family.id}")
                .setTitle(family.label)
                .setSubtitle(option.label)
                .setIconUri(
                    ClockArtworkProvider.miniArtworkUri(
                        family, now,
                        if (family == ClockFamily.WEATHER)
                            WeatherStore.current(this)?.observedAtMs ?: 0L else 0L,
                        selectedTheme(), coloredWeatherIcons()
                    )
                ).build()
            MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()
        result.sendResult(items)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(minuteTick)
        handler.removeCallbacks(pausedWeatherTick)
        handler.removeCallbacks(settingsRefresh)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun selectedPreset(): ClockPreset =
        ClockSelectionStore.current(prefs)

    private fun chooseFamily(family: ClockFamily) {
        val clockFamily = PlaybackDisplay.clockFamily(family)
        preset = ClockSelectionStore.forFamily(prefs, clockFamily)
        prefs.edit().putString(ClockPreset.KEY_FAMILY, clockFamily.id).apply()
    }

    private fun chooseFromMediaId(id: String?) {
        val family = id?.removePrefix("family_")?.let(ClockFamily::fromId)
        if (family != null) {
            chooseFamily(family)
        } else {
            // Previously cached weather-only media IDs are interpreted as
            // analog clock; Pause displays weather independently of selection.
            val selected = ClockPreset.fromId(id)
            if (selected.family == ClockFamily.WEATHER) {
                chooseFamily(ClockFamily.ANALOG)
            } else {
                preset = selected
                ClockSelectionStore.choose(prefs, selected)
            }
        }
    }

    private fun compactLayout(): Boolean =
        prefs.getString(KEY_LAYOUT, "standard") == "compact"

    private fun selectedTheme(): ClockTheme =
        ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null))

    private fun coloredWeatherIcons(): Boolean =
        WeatherStore.coloredIcons(prefs)

    private fun animateProgress(): Boolean =
        prefs.getBoolean(KEY_PROGRESS_ANIMATED, false)

    private fun hideMediaText(): Boolean =
        prefs.getBoolean(KEY_HIDE_MEDIA_TEXT, false)

    private fun startClock() {
        running = true
        handler.removeCallbacks(pausedWeatherTick)
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
        schedulePausedWeatherRefresh()
    }

    private fun schedulePausedWeatherRefresh() {
        handler.removeCallbacks(pausedWeatherTick)
        if (!running) {
            handler.postDelayed(pausedWeatherTick, WeatherStore.REFRESH_INTERVAL_MS)
        }
    }

    private fun visiblePreset(): ClockPreset =
        PlaybackDisplay.visiblePreset(preset, running)

    private fun scheduleNextMinute() {
        handler.removeCallbacks(minuteTick)
        if (!running) return
        handler.postDelayed(
            minuteTick,
            MinuteProgress.millisUntilNextMinute(System.currentTimeMillis())
        )
    }

    private fun requestWeatherIfNecessary() {
        if (!visiblePreset().usesWeather) return
        // A successful response triggers the preferences listener; Pause
        // checks once per 15 minutes, while Play uses the minute clock tick.
        WeatherStore.refreshIfDue(this)
    }

    private fun publishClock() {
        val now = LocalDateTime.now()
        val visible = visiblePreset()
        val weather = if (visible.usesWeather) WeatherStore.current(this) else null
        val description = weather?.description ?: "Няма данни"
        val mediaLines = ClockMediaLabels.forClock(visible, now, weather)
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
        val blank = hideMediaText()
        // The Android Auto playback tile should contain only the useful
        // clock/weather labels, never preset names or METAR station metadata.
        val shownTitle = if (blank) INVISIBLE_TEXT else mediaLines.title
        val shownSubtitle = if (blank) INVISIBLE_TEXT else mediaLines.subtitle
        val shownDescription = INVISIBLE_TEXT
        val shownAlbum = INVISIBLE_TEXT

        val item = MediaDescriptionCompat.Builder()
            .setMediaId(visible.id)
            .setTitle(shownTitle)
            .setSubtitle(shownSubtitle)
            .setDescription(shownDescription)
            .setIconUri(artwork)
            .build()
        session.setQueueTitle(shownAlbum)
        session.setQueue(listOf(MediaSessionCompat.QueueItem(item, visible.queueId)))

        // Provide a small direct bitmap as well as an URI, because some Android
        // Auto versions keep the previous URI artwork while text changes live.
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
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, visible.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, shownTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, shownSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, shownAlbum)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, shownTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, shownSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, shownDescription)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, miniArtwork.toString())

        if (animateProgress()) {
            metadata.putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                MinuteProgress.DURATION_MS
            )
        }
        session.setMetadata(metadata.build())
    }

    private fun publishPlaybackState() {
        val moving = animateProgress()
        val position = if (moving)
            MinuteProgress.positionMs(System.currentTimeMillis())
        else
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN

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
                    position,
                    if (running && moving) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .setActions(actions)
                .setActiveQueueItemId(visiblePreset().queueId)
                .build()
        )
    }

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_MODE = "display_mode"
        const val KEY_HIDE_MEDIA_TEXT = "hide_media_text"
        const val KEY_LAYOUT = "clock_layout"
        const val KEY_PROGRESS_ANIMATED = "progress_animated"
        private const val INVISIBLE_TEXT = ClockMediaLabels.EMPTY
        private const val ROOT_ID = "auto_clock_root"
    }
}
