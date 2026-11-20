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
            key == KEY_LAYOUT || key == KEY_PROGRESS_ANIMATED ||
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
                    val family = when {
                        "weather" in q || "врем" in q -> ClockFamily.WEATHER
                        "digital" in q || "цифр" in q -> ClockFamily.DIGITAL
                        else -> ClockFamily.ANALOG
                    }
                    chooseFamily(family)
                    startClock()
                }
                override fun onPause() = stopClock()
                override fun onStop() = stopClock()
                override fun onSkipToNext() {
                    val next = ClockFamily.entries[(preset.family.ordinal + 1) % ClockFamily.entries.size]
                    chooseFamily(next)
                    startClock()
                }
                override fun onSkipToPrevious() {
                    val count = ClockFamily.entries.size
                    val previous = ClockFamily.entries[(preset.family.ordinal + count - 1) % count]
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
        val rev = weatherRevision()
        // One simple item per main radio style; checkboxes are configured on
        // the phone, so Android Auto does not show a confusing 12-item list.
        val items = ClockFamily.entries.map { family ->
            val option = ClockSelectionStore.forFamily(prefs, family)
            val description = MediaDescriptionCompat.Builder()
                .setMediaId("family_${family.id}")
                .setTitle(family.label)
                .setSubtitle(option.label)
                .setIconUri(
                    ClockArtworkProvider.artworkUri(
                        option.id, now,
                        if (option.usesWeather) rev else 0L,
                        compactLayout(), artworkGeneration
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
        handler.removeCallbacks(settingsRefresh)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun selectedPreset(): ClockPreset =
        ClockSelectionStore.current(prefs)

    private fun chooseFamily(family: ClockFamily) {
        preset = ClockSelectionStore.forFamily(prefs, family)
        prefs.edit().putString(ClockPreset.KEY_FAMILY, family.id).apply()
    }

    private fun chooseFromMediaId(id: String?) {
        val family = id?.removePrefix("family_")?.let(ClockFamily::fromId)
        if (family != null) {
            chooseFamily(family)
        } else {
            // Older Android Auto hosts may retain previous media IDs.
            val selected = ClockPreset.fromId(id)
            preset = selected
            ClockSelectionStore.choose(prefs, selected)
        }
    }

    private fun compactLayout(): Boolean =
        prefs.getString(KEY_LAYOUT, "compact") != "standard"

    private fun animateProgress(): Boolean =
        prefs.getBoolean(KEY_PROGRESS_ANIMATED, false)

    private fun hideMediaText(): Boolean =
        prefs.getBoolean(KEY_HIDE_MEDIA_TEXT, true)

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
        handler.postDelayed(
            minuteTick,
            MinuteProgress.millisUntilNextMinute(System.currentTimeMillis())
        )
    }

    private fun requestWeatherIfNecessary() {
        if (!running || !preset.usesWeather) return
        // A successful response is delivered through the SharedPreferences
        // listener; no separate per-second worker or polling is required.
        WeatherStore.refreshIfDue(this)
    }

    private fun weatherRevision(): Long = WeatherStore.current(this)?.observedAtMs ?: 0L

    private fun publishClock() {
        val now = LocalDateTime.now()
        val weather = if (preset.usesWeather) WeatherStore.current(this) else null
        val temp = weather?.formattedTemperature ?: "--°C"
        val description = weather?.description ?: "Няма данни"
        val shortWeather = if (weather == null) "Няма данни" else
            "${WeatherStore.symbol(weather.weatherCode, weather.isDay)} $temp"
        val time = now.format(TIME_FORMAT)

        val title = when (preset.family) {
            ClockFamily.ANALOG ->
                if (preset.hasDigitalTime) time
                else if (preset.usesWeather) shortWeather
                else "Аналогов часовник"
            ClockFamily.DIGITAL -> time
            ClockFamily.WEATHER -> shortWeather
        }
        val subtitle = when {
            preset.family == ClockFamily.WEATHER && preset.hasDigitalTime ->
                "$time • $description"
            preset.usesWeather -> if (weather != null && weather.autoSelected)
                "${weather.originLabel} · ${weather.sourceLabel}" else description
            preset.hasDigitalTime -> now.format(DATE_FORMAT)
            else -> preset.label
        }
        val artwork = ClockArtworkProvider.artworkUri(
            preset.id, now, weather?.observedAtMs ?: 0L,
            compactLayout(), artworkGeneration
        )
        val blank = hideMediaText()
        val shownTitle = if (blank) INVISIBLE_TEXT else title
        val shownSubtitle = if (blank) INVISIBLE_TEXT else subtitle
        val shownDescription = if (blank) INVISIBLE_TEXT else preset.label
        val shownAlbum = if (blank) INVISIBLE_TEXT else "Auto Clock 2"

        val item = MediaDescriptionCompat.Builder()
            .setMediaId(preset.id)
            .setTitle(shownTitle)
            .setSubtitle(shownSubtitle)
            .setDescription(shownDescription)
            .setIconUri(artwork)
            .build()
        session.setQueueTitle(shownAlbum)
        session.setQueue(listOf(MediaSessionCompat.QueueItem(item, preset.queueId)))

        // Provide a small direct bitmap as well as an URI, because some Android
        // Auto versions keep the previous URI artwork while text changes live.
        val art = ClockRenderer.render(
            preset.id, now, weather?.weatherCode, weather?.isDay ?: true,
            weather?.formattedTemperature, compactLayout(),
            outputSize = 320, condition = description,
            sourceLabel = weather?.artworkSource
        )
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, preset.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, shownTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, shownSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, shownAlbum)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, shownTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, shownSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, shownDescription)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artwork.toString())

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
                .setActiveQueueItemId(preset.queueId)
                .build()
        )
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
