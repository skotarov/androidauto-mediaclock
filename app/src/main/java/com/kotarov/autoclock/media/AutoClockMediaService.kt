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

class AutoClockMediaService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy")
    private var activeMode: DisplayMode = DisplayMode.CLOCK

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateRadioTrack()
            scheduleNextMinuteUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "AutoClockMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    startRadio()
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    activeMode = DisplayMode.fromMediaId(mediaId)
                    startRadio()
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    activeMode = DisplayMode.fromMediaId(mediaId)
                    updateRadioTrack()
                    setPausedState()
                }

                override fun onPause() {
                    pauseRadio()
                }

                override fun onStop() {
                    pauseRadio()
                }

                override fun onSkipToNext() {
                    activeMode = activeMode.next()
                    startRadio()
                }

                override fun onSkipToPrevious() {
                    activeMode = activeMode.previous()
                    startRadio()
                }
            })
            setQueueTitle("Auto Clock Radio")
            isActive = true
        }

        sessionToken = mediaSession.sessionToken
        updateRadioTrack()
        setPausedState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRadio()
        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = DisplayMode.entries.map { mode ->
            MediaBrowserCompat.MediaItem(
                buildModeDescription(mode, LocalDateTime.now()),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()

        result.sendResult(items)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaSession.release()
        super.onDestroy()
    }

    private fun startRadio() {
        mediaSession.isActive = true
        updateRadioTrack()
        setPlayingState()
        scheduleNextMinuteUpdate()
    }

    private fun pauseRadio() {
        handler.removeCallbacks(updateRunnable)
        setPausedState()
    }

    private fun updateRadioTrack() {
        val now = LocalDateTime.now()
        val timeText = now.format(timeFormatter)
        val dateText = now.format(dateFormatter)
        val weatherText = getWeatherText()
        val title = when (activeMode) {
            DisplayMode.CLOCK -> timeText
            DisplayMode.WEATHER -> weatherText
            DisplayMode.BOTH -> "$timeText • $weatherText"
        }
        val subtitle = when (activeMode) {
            DisplayMode.CLOCK -> dateText
            DisplayMode.WEATHER -> "Weather mode"
            DisplayMode.BOTH -> dateText
        }
        val artworkUri = ClockArtworkProvider.artworkUri(activeMode.artworkMode, now)

        val description = MediaDescriptionCompat.Builder()
            .setMediaId(activeMode.mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(activeMode.stationTitle)
            .setIconUri(artworkUri)
            .build()

        mediaSession.setQueue(
            listOf(MediaSessionCompat.QueueItem(description, activeMode.queueId))
        )

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, activeMode.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Auto Clock Radio")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, activeMode.stationTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUri.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artworkUri.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUri.toString())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, ONE_MINUTE_MS)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, now.minute.toLong() + 1L)
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 60L)
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun buildModeDescription(mode: DisplayMode, now: LocalDateTime): MediaDescriptionCompat {
        val previewText = when (mode) {
            DisplayMode.CLOCK -> now.format(timeFormatter)
            DisplayMode.WEATHER -> getWeatherText()
            DisplayMode.BOTH -> "${now.format(timeFormatter)} • ${getWeatherText()}"
        }

        return MediaDescriptionCompat.Builder()
            .setMediaId(mode.mediaId)
            .setTitle(mode.stationTitle)
            .setSubtitle(previewText)
            .setDescription(mode.stationDescription)
            .setIconUri(ClockArtworkProvider.artworkUri(mode.artworkMode, now))
            .build()
    }

    private fun setPlayingState() {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                .setActions(PLAYING_ACTIONS)
                .setActiveQueueItemId(activeMode.queueId)
                .build()
        )
    }

    private fun setPausedState() {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 0f)
                .setActions(PAUSED_ACTIONS)
                .setActiveQueueItemId(activeMode.queueId)
                .build()
        )
    }

    private fun scheduleNextMinuteUpdate() {
        handler.removeCallbacks(updateRunnable)
        val now = LocalDateTime.now()
        val millisUntilNextMinute = ((60 - now.second) * 1000L) - (now.nano / 1_000_000L) + 250L
        handler.postDelayed(updateRunnable, millisUntilNextMinute.coerceAtLeast(1_000L))
    }

    private fun getWeatherText(): String {
        return "--°C"
    }

    private enum class DisplayMode(
        val mediaId: String,
        val stationTitle: String,
        val stationDescription: String,
        val artworkMode: String,
        val queueId: Long
    ) {
        CLOCK(
            mediaId = "station_clock",
            stationTitle = "Clock",
            stationDescription = "Show current time",
            artworkMode = "clock",
            queueId = 1L
        ),
        WEATHER(
            mediaId = "station_weather",
            stationTitle = "Weather",
            stationDescription = "Show weather information",
            artworkMode = "weather",
            queueId = 2L
        ),
        BOTH(
            mediaId = "station_clock_weather",
            stationTitle = "Clock + Weather",
            stationDescription = "Show time and weather together",
            artworkMode = "both",
            queueId = 3L
        );

        fun next(): DisplayMode {
            val modes = entries
            return modes[(ordinal + 1) % modes.size]
        }

        fun previous(): DisplayMode {
            val modes = entries
            return modes[(ordinal - 1 + modes.size) % modes.size]
        }

        companion object {
            fun fromMediaId(mediaId: String?): DisplayMode {
                return entries.firstOrNull { it.mediaId == mediaId } ?: CLOCK
            }
        }
    }

    companion object {
        private const val ROOT_ID = "auto_clock_root"
        private const val ONE_MINUTE_MS = 60_000L

        private const val PAUSED_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID

        private const val PLAYING_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    }
}
