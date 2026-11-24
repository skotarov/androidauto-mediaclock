package com.kotarov.autoclock.media

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** The six original demo melodies, reconstructed from their PCM samples.
 * Generate only the selected short clip on demand; no bundled audio assets. */
enum class NoticeSound(val id: String, val label: String, val durationMs: Int) {
    SILENT("silent", "Silent", 0),
    SOFT_PING("soft_ping", "1. Soft ping", 470),
    DOUBLE_PING("double_ping", "2. Double ping", 585),
    CAR_CHIME("car_chime", "3. Car chime", 660),
    SOFT_KNOCK("soft_knock", "4. Soft knock", 500),
    ATTENTION("attention", "5. Attention", 710),
    WARNING("warning", "6. Warning", 900);

    private data class Tone(val start: Int, val duration: Int, val hz: Double,
        val amp: Double, val decay: Double, val harmonic: Double, val bell: Boolean = false)

    fun pcm(): ShortArray {
        val tones = when (this) {
            SILENT -> emptyList()
            SOFT_PING -> listOf(Tone(40, 350, 880.0, 0.64, 10.5831, 0.25, true))
            DOUBLE_PING -> listOf(Tone(40, 160, 740.0, 0.65, 13.8899, 0.12),
                Tone(265, 240, 988.0, 0.65, 9.2599, 0.12))
            CAR_CHIME -> listOf(Tone(40, 160, 659.0, 0.6683, 23.1505, 0.25, true),
                Tone(240, 340, 880.0, 0.7082, 10.8942, 0.25, true))
            SOFT_KNOCK -> listOf(Tone(40, 110, 540.0, 0.68, 13.9868, 0.14),
                Tone(250, 170, 540.0, 0.68, 9.0503, 0.14))
            ATTENTION -> listOf(Tone(40, 130, 1046.0, 0.70, 11.8350, 0.14),
                Tone(240, 130, 784.0, 0.70, 11.8351, 0.14),
                Tone(440, 190, 1046.0, 0.70, 8.0976, 0.14))
            WARNING -> listOf(Tone(40, 180, 820.0, 0.72, 8.5474, 0.14),
                Tone(310, 180, 820.0, 0.72, 8.5474, 0.14),
                Tone(580, 240, 820.0, 0.72, 6.4106, 0.14))
        }
        val samples = ShortArray(durationMs * SAMPLE_RATE / 1000)
        for (tone in tones) {
            val start = tone.start * SAMPLE_RATE / 1000
            val count = tone.duration * SAMPLE_RATE / 1000
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = minOf(1.0, t / 0.009) *
                    minOf(1.0, (tone.duration / 1000.0 - t) / 0.025) * exp(-tone.decay * t)
                val a = 2.0 * PI * tone.hz * t
                val wave = sin(a) + tone.harmonic * sin(a * (if (tone.bell) 2.01 else 2.0)) +
                    (if (tone.bell) 0.09 * sin(a * 3.98) else 0.0)
                samples[start + i] = (32767 * tone.amp * envelope * wave)
                    .roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return samples
    }

    companion object {
        const val KEY = "notice_sound"
        const val SAMPLE_RATE = 44100
        val DEFAULT = CAR_CHIME
        fun fromId(id: String?): NoticeSound = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
