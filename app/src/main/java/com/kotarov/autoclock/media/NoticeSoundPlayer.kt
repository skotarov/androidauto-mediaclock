package com.kotarov.autoclock.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

/** One-shot local media audio, without requesting exclusive audio focus,
 * changing playback state, holding wake locks, or keeping an idle player.
 * The Android/Android Auto host determines the actual output route. */
class NoticeSoundPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private val finish = Runnable { stop() }

    fun play(sound: NoticeSound, phoneChannel: Boolean = false) {
        stop()
        if (sound == NoticeSound.SILENT) return
        try {
            val original = sound.pcm()
            val sampleRate = if (phoneChannel) 16000 else NoticeSound.SAMPLE_RATE
            val samples = if (!phoneChannel) original else ShortArray(sound.durationMs * sampleRate / 1000) { i ->
                original[(i.toLong() * NoticeSound.SAMPLE_RATE / sampleRate).toInt().coerceAtMost(original.lastIndex)]
            }
            val audio = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (phoneChannel) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track = audio
            if (audio.state == AudioTrack.STATE_UNINITIALIZED ||
                audio.write(samples, 0, samples.size) != samples.size) {
                stop()
                return
            }
            audio.setVolume(0.8f)
            audio.play()
            handler.postDelayed(finish, sound.durationMs.toLong() + 200L)
        } catch (e: Exception) {
            Log.w("AutoClock", "Notice sound unavailable", e)
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(finish)
        val old = track ?: return
        track = null
        try { old.stop() } catch (_: Exception) { }
        try { old.release() } catch (_: Exception) { }
    }
}
