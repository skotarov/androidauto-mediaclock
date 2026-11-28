package com.kotarov.autoclock.media

/** Small utterances limit repetitions after interruptions and TTS buffer size. */
internal object TextReading {
    const val MAX_CHARS = 50_000_000
    fun chunkEnd(text: String, start: Int, limit: Int = 350): Int {
        val end = (start + limit).coerceAtMost(text.length)
        if (end == text.length) return end
        for (i in end - 1 downTo (start + limit / 2).coerceAtMost(end - 1)) {
            if (text[i] in ".!?\n") return i + 1
        }
        for (i in end - 1 downTo start + 1) {
            if (text[i].isWhitespace()) return i + 1
        }
        return if (end > start && Character.isHighSurrogate(text[end - 1])) end - 1 else end
    }
    fun language(text: String, choice: String, device: String): String {
        if (choice != "auto") return choice
        val sample = text.take(4000).lowercase(java.util.Locale.ROOT)
        val cyrillic = sample.count { it in '\u0400'..'\u052f' }
        val latin = sample.count { it in 'a'..'z' }
        if (cyrillic > latin) {
            if (sample.any { it in "іїєґ" }) return "uk"
            if (sample.any { it in "ыэё" }) return "ru"
            val words = sample.split(Regex("[^\\p{L}]+")).toSet()
            val bg = words.count { it in setOf("със", "във", "това", "които", "беше", "няма", "какво", "защото", "трябва", "съм") }
            val ru = words.count { it in setOf("это", "который", "которые", "что", "было", "нет", "потому", "нужно", "если", "такой") }
            if (bg != ru) return if (bg > ru) "bg" else "ru"
            return if (device in listOf("bg", "ru", "uk", "mk", "sr")) device else "bg"
        }
        return if (device in listOf("en", "de", "fr", "es", "it", "pt")) device else "en"
    }
    const val ESTIMATED_CHARS_PER_SECOND = 15L

    /** Estimated speech time is a scale for the exact character fraction.
     * Long arithmetic supports books exceeding 24 days of estimated speech. */
    data class Timeline(val positionMs: Long, val durationMs: Long)
    fun timeline(offset: Int, length: Int): Timeline? {
        if (length <= 0) return null
        return Timeline(offset.coerceIn(0, length).toLong() * 1000 / ESTIMATED_CHARS_PER_SECOND,
            length.toLong() * 1000 / ESTIMATED_CHARS_PER_SECOND)
    }
    fun offsetAtTime(positionMs: Long, length: Int): Int {
        if (length <= 0) return 0
        val end = timeline(length, length)!!.durationMs
        if (positionMs >= end) return length
        return ((positionMs.coerceAtLeast(0) * ESTIMATED_CHARS_PER_SECOND + 500) / 1000)
            .coerceIn(0, length.toLong()).toInt()
    }

    /** About 150 characters per ten seconds at normal speech speed. */
    fun skipOffset(offset: Int, length: Int, deltaMs: Int): Int {
        if (length <= 0) return 0
        val delta = deltaMs.toLong() * ESTIMATED_CHARS_PER_SECOND / 1000L
        return (offset.toLong() + delta).coerceIn(0L, length.toLong()).toInt()
    }

    fun wordStart(text: String, index: Int): Int {
        val pos = index.coerceIn(0, text.length)
        if (pos == 0 || pos == text.length || text[pos].isWhitespace() || text[pos - 1].isWhitespace()) return pos
        var start = pos
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        return if (start == 0 && pos >= 64) pos else start
    }

    fun percent(offset: Int, length: Int): Int =
        if (length <= 0) 0 else (offset.toLong() * 100 / length).toInt().coerceIn(0, 100)
}
