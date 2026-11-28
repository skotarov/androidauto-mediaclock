package com.kotarov.autoclock.media

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Cache only a few selected voice descriptors, never an initialized TTS engine. */
internal object OfflineVoiceCache {
    private data class Key(val engine: String?, val language: String, val locale: Locale, val notice: Boolean)
    private val cache = LinkedHashMap<Key, Voice>(4, 0.75f, true)
    @Synchronized fun select(tts: TextToSpeech, language: String, notice: Boolean): Boolean {
        val key = Key(tts.defaultEngine, language, Locale.getDefault(), notice)
        cache[key]?.let {
            if (runCatching { tts.setVoice(it) == TextToSpeech.SUCCESS }.getOrDefault(false)) return true
            cache.remove(key)
        }
        val voice = tts.voices.orEmpty().filter {
            it.locale.language == language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }.sortedWith(compareByDescending<Voice> {
            if (notice) it.locale.country == "US" || it.locale.country == "GB" else it.locale == key.locale
        }.thenByDescending { it.quality }.thenBy { it.latency }).firstOrNull() ?: return false
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
        cache[key] = voice
        while (cache.size > 4) cache.remove(cache.keys.first())
        return true
    }
}
