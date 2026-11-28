package com.kotarov.autoclock.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/** Experimental HFP keepalive: one small PCM silence buffer loops natively.
 * No writer thread, timer, wake lock, microphone or disk I/O. */
class PhoneAudioKeepAlive {
    private var track: AudioTrack? = null

    fun start(): Boolean {
        if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) return true
        stop()
        return try {
            val samples = ShortArray(16000 / 2)
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track = player
            if (player.write(samples, 0, samples.size) != samples.size ||
                player.state != AudioTrack.STATE_INITIALIZED ||
                player.setLoopPoints(0, samples.size, -1) != AudioTrack.SUCCESS) {
                stop()
                false
            } else {
                player.play()
                if (player.playState == AudioTrack.PLAYSTATE_PLAYING) true
                else { stop(); false }
            }
        } catch (e: Exception) {
            Log.w("AutoClock", "Phone audio keepalive unavailable", e)
            stop()
            false
        }
    }

    fun stop() {
        val old = track ?: return
        track = null
        runCatching { old.stop() }
        runCatching { old.release() }
    }
}
