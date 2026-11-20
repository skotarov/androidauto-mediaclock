package com.kotarov.autoclock.media

import android.content.Intent
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A clock presented as a media item for personal Android Auto testing.
 *
 * The media UI is owned by Android Auto. This service never renders frames
 * continuously, acquires a wake lock, polls GPS, or performs network requests.
 * This is NOT an audio player and may not be supported on every Android Auto build.
 */
class AutoClockMediaService : MediaBrowserServiceCompat() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private var running = false
    private var mode = Mode.DIGITAL

    private val nextMinute = object : Runnable {
        override fun run() {
            if (!running) return
            publishClock()
            scheduleNextMinute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        mode = Mode.fromId(getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_MODE, null))
        session = MediaSessionCompat(this, "AutoClockV2").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = startClock()

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    chooseMode(mediaId)
                    startClock()
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    chooseMode(mediaId)
                    publishClock()
                    publishPlaybackState()
                }

                override fun onPause() = stopClock()
                override fun onStop() = stopClock()

                override fun onSkipToNext() {
                    mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
                    saveMode()
                    startClock()
                }

                override fun onSkipToPrevious() {
                    mode = Mode.entries[(mode.ordinal + Mode.entries.size - 1) % Mode.entries.size]
                    saveMode()
                    startClock()
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        publishClock()
        publishPlaybackState()
    }

    // Android Auto binds to the media browser. Never auto-start or request
    // a sticky background service merely because Android recreates the service.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

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
        val items = Mode.entries.map { option ->
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(option.id)
                .setTitle(option.title)
                .setSubtitle(option.description)
                .setIconUri(ClockArtworkProvider.artworkUri(option.artwork, now))
                .build()
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }.toMutableList()
        result.sendResult(items)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(nextMinute)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun chooseMode(mediaId: String?) {
        mode = Mode.fromId(mediaId)
        saveMode()
    }

    private fun saveMode() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.id).apply()
    }

    private fun startClock() {
        // Read a phone-side preference when the user starts a new session.
        // Android Auto selection overrides the saved mode in onPlayFromMediaId.
        if (!running) {
            mode = Mode.fromId(
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_MODE, mode.id)
            )
        }
        running = true
        publishClock()
        publishPlaybackState()
        scheduleNextMinute()
    }

    private fun stopClock() {
        running = false
        handler.removeCallbacks(nextMinute)
        publishPlaybackState()
    }

    private fun scheduleNextMinute() {
        handler.removeCallbacks(nextMinute)
        if (!running) return
        val now = System.currentTimeMillis()
        val delay = 60_000L - (now % 60_000L) + 150L
        handler.postDelayed(nextMinute, delay)
    }

    private fun publishClock() {
        val now = LocalDateTime.now()
        val time = now.format(TIME_FORMAT)
        val date = now.format(DATE_FORMAT)
        val title = when (mode) {
            Mode.DIGITAL, Mode.ANALOG -> time
            Mode.WEATHER -> "Времето: няма данни"
            Mode.BOTH -> "$time • --°C"
        }
        val art = ClockArtworkProvider.artworkUri(mode.artwork, now).toString()

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mode.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, date)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Auto Clock")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, date)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, art)
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, art)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, art)
                .build()
        )
    }

    private fun publishPlaybackState() {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (running) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    0L,
                    if (running) 1f else 0f
                )
                .setActions(actions)
                .build()
        )
    }

    private enum class Mode(val id: String, val title: String, val description: String, val artwork: String) {
        DIGITAL("digital", "Цифров часовник", "Час и дата", "clock"),
        ANALOG("analog", "Аналогов часовник", "Циферблат със стрелки", "analog"),
        WEATHER("weather", "Времето", "Предстои добавяне на прогноза", "weather"),
        BOTH("both", "Часовник + време", "Прогнозата предстои", "both");

        companion object {
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: DIGITAL
        }
    }

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_MODE = "display_mode"
        private const val ROOT_ID = "auto_clock_root"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale("bg", "BG"))
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale("bg", "BG"))
    }
}
