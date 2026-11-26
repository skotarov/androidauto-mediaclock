package com.kotarov.autoclock.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.net.URI
import java.util.Locale

/** Created only for explicit AAC/HLS or a single failed native network preparation.
 * Uses Android's codecs, never a second audio engine running alongside MediaPlayer. */
@UnstableApi
internal class NetworkAudioPlayer(context: Context, phone: Boolean) {
    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(
            DefaultHttpDataSource.Factory()
                .setUserAgent("SmartTripClock/1.0")
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000)
        ))
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 12000, 1000, 2000)
            // Audio-only budget; allow larger HLS segments when required to keep
            // the minimum buffered duration instead of forcing a rebuffer.
            .setTargetBufferBytes(1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true).build())
        .build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (phone) C.USAGE_VOICE_COMMUNICATION else C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        }
    private var released = false
    fun open(url: String, onReady: () -> Unit, onChanged: () -> Unit, onEnded: () -> Unit, onError: () -> Unit) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                when (state) {
                    Player.STATE_READY -> onReady()
                    Player.STATE_ENDED -> onEnded()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!released) onChanged() }
            override fun onPlayerError(error: PlaybackException) { if (!released) onError() }
        })
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()
    }
    fun position(): Int? = seekState()?.position
    fun seekState(): CustomStreamPlayer.SeekState? {
        if (released || !player.isCurrentMediaItemSeekable || player.isCurrentMediaItemLive) return null
        val duration = player.duration
        if (duration <= 0L || duration > Int.MAX_VALUE) return null
        return CustomStreamPlayer.SeekState(player.currentPosition.coerceIn(0L, duration).toInt(), duration.toInt())
    }
    fun seek(ms: Int): Boolean = !released && runCatching {
        val duration = seekState()?.duration ?: return false
        player.seekTo(ms.coerceIn(0, duration).toLong())
        true
    }.getOrDefault(false)
    fun isPlaying(): Boolean = !released && player.isPlaying
    fun speed(): Float = if (released) 1f else player.playbackParameters.speed
    fun setSpeed(value: Float) {
        if (!released && kotlin.math.abs(player.playbackParameters.speed - value) > 0.001f)
            runCatching { player.playbackParameters = PlaybackParameters(value, 1f) }
    }
    fun play() { if (!released) player.play() }
    fun pause(): Boolean = !released && runCatching { player.pause(); true }.getOrDefault(false)
    fun release() { if (!released) { released = true; runCatching { player.release() } } }
    companion object {
        fun preferred(url: String): Boolean = runCatching {
            val path = URI(url).path.orEmpty().lowercase(Locale.ROOT)
            path.endsWith(".aac") || path.endsWith(".adts") || path.endsWith(".m3u8")
        }.getOrDefault(false)
    }
}
