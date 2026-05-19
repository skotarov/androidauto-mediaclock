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

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateClockMetadata()
            scheduleNextMinuteUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "AutoClockMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    startClock()
                }

                override fun onPause() {
                    pauseClock()
                }

                override fun onStop() {
                    pauseClock()
                }
            })
            isActive = true
        }

        sessionToken = mediaSession.sessionToken
        updateClockMetadata()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startClock()
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
        val now = LocalDateTime.now()
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(MEDIA_ID_CLOCK)
            .setTitle("Auto Clock")
            .setSubtitle(now.format(timeFormatter))
            .setDescription("Android Auto radio clock")
            .setIconUri(ClockArtworkProvider.artworkUri(DEFAULT_MODE, now))
            .build()

        val item = MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
        result.sendResult(mutableListOf(item))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaSession.release()
        super.onDestroy()
    }

    private fun startClock() {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )
        updateClockMetadata()
        scheduleNextMinuteUpdate()
    }

    private fun pauseClock() {
        handler.removeCallbacks(updateRunnable)
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY)
                .build()
        )
    }

    private fun updateClockMetadata() {
        val now = LocalDateTime.now()
        val artworkUri = ClockArtworkProvider.artworkUri(DEFAULT_MODE, now)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ID_CLOCK)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, now.format(timeFormatter))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, now.format(dateFormatter))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Auto Clock")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, now.format(timeFormatter))
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Auto Clock")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, now.format(dateFormatter))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUri.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artworkUri.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUri.toString())
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun scheduleNextMinuteUpdate() {
        handler.removeCallbacks(updateRunnable)
        val now = LocalDateTime.now()
        val millisUntilNextMinute = ((60 - now.second) * 1000L) - (now.nano / 1_000_000L) + 250L
        handler.postDelayed(updateRunnable, millisUntilNextMinute.coerceAtLeast(1_000L))
    }

    companion object {
        private const val ROOT_ID = "auto_clock_root"
        private const val MEDIA_ID_CLOCK = "auto_clock_media_item"
        private const val DEFAULT_MODE = "digital"
    }
}
