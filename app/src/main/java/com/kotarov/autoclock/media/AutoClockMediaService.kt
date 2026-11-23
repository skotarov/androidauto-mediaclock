package com.kotarov.autoclock.media

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.kotarov.autoclock.artwork.ClockArtworkProvider
import com.kotarov.autoclock.artwork.ClockRenderer
import com.kotarov.autoclock.media.ClockTheme
import com.kotarov.autoclock.weather.WeatherStore
import java.time.LocalDateTime

/**
 * v0.26 diagnostic: NO playable entries and NO transport actions.
 * The media session still publishes clock art, though Android Auto may hide
 * the entire card if the browse root is empty and playback is STATE_NONE.
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
        val wantWeather = prefs.getBoolean(KEY_SHOW_WEATHER_WITHOUT_BUTTONS, false)
        if (wantWeather == running) {
            // Only the phone settings can switch the display in this test.
            if (wantWeather) stopClock() else startClock()
        } else {
            publishClock()
            publishPlaybackState()
            requestWeatherIfNecessary()
        }
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
            key == KEY_SHOW_WEATHER_WITHOUT_BUTTONS ||
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

    // Phone-selected weather display checks every 15 minutes while the
    // service is alive; no wake lock, periodic GPS subscription or media button.
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
        session = MediaSessionCompat(this, "AutoClockNoButtonsV026").apply {
            // No media button flags and no transport callbacks.
            setFlags(0)
            isActive = true
        }
        session.setQueue(emptyList())
        session.setQueueTitle(null)
        sessionToken = session.sessionToken
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (prefs.getBoolean(KEY_SHOW_WEATHER_WITHOUT_BUTTONS, false)) {
            stopClock()
        } else {
            startClock()
        }
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
        // Deliberately no "songs" or selectable media content.
        result.sendResult(mutableListOf())
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

    private fun compactLayout(): Boolean =
        prefs.getString(KEY_LAYOUT, "standard") == "compact"

    private fun selectedTheme(): ClockTheme =
        ClockTheme.fromId(prefs.getString(ClockTheme.KEY, null))

    private fun coloredWeatherIcons(): Boolean =
        WeatherStore.coloredIcons(prefs)

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
        // A successful response triggers the preferences listener; the phone
        // selects weather checks or minute-aligned clock drawing.
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

        // Metadata remains published without any playlist or current track.
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
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, shownTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, shownSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, shownTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, shownSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, shownDescription)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, miniArtwork.toString())

        // No duration: old preferences cannot bring a progress bar back.
        session.setMetadata(metadata.build())
    }

    private fun publishPlaybackState() {
        // No Play/Pause, Next, Previous, Stop or custom media actions.
        // STATE_NONE may cause Android Auto to hide the tile; that is what
        // this experimental release is testing.
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    PlaybackStateCompat.STATE_NONE,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f
                )
                .setActions(0L)
                .build()
        )
    }

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_MODE = "display_mode"
        const val KEY_HIDE_MEDIA_TEXT = "hide_media_text"
        const val KEY_LAYOUT = "clock_layout"
        const val KEY_PROGRESS_ANIMATED = "progress_animated"
        const val KEY_SHOW_WEATHER_WITHOUT_BUTTONS = "no_buttons_show_weather"
        private const val INVISIBLE_TEXT = ClockMediaLabels.EMPTY
        private const val ROOT_ID = "auto_clock_root"
    }
}
