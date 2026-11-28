package com.kotarov.autoclock.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Play distribution uses normal media focus, never a simulated phone call. */
internal class PlayAudioFocus(context: Context, changed: (Int) -> Unit) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private var request: AudioFocusRequest? = null
    private val listener = AudioManager.OnAudioFocusChangeListener { value ->
        handler.post { if (held) changed(value) }
    }
    @Suppress("DEPRECATION")
    fun request(): Boolean {
        if (held) return true
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(listener, handler).build()
                request = r
                audio.requestAudioFocus(r)
            } else audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!held) request = null
        return held
    }
    @Suppress("DEPRECATION")
    fun abandon() {
        if (!held) return
        held = false
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) request?.let { audio.abandonAudioFocusRequest(it) }
            else audio.abandonAudioFocus(listener)
        }
        request = null
    }
}
