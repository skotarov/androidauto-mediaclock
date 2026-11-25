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
    fun percent(offset: Int, length: Int): Int =
        if (length <= 0) 0 else (offset.toLong() * 100 / length).toInt().coerceIn(0, 100)
}
