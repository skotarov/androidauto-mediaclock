package com.kotarov.autoclock.media

/** Character offsets refer to exactly the same extractor output as TTS bookmarks. */
internal class BookContentsIndex(private val inferLines: Boolean = false) {
    data class Chapter(val title: String, val offset: Int)
    var offset = 0
        private set
    private val headings = ArrayList<Chapter>()
    private val targets = LinkedHashMap<String, String>()
    private val anchors = HashMap<String, Int>()
    private var headingStart = -1
    private val heading = StringBuilder()
    private var lineStart = 0
    private val line = StringBuilder()
    private val whitespace = Regex("\\s+")
    private val chapterLine = Regex("(?iu)^(?:#{1,6}\\s+.+|(?:глава|chapter|част|part|раздел|section|урок|lesson)\\s+(?:[0-9]+|[IVXLCDM]+)(?:\\b.*|[.:].*)|предговор|въведение|заключение|preface|introduction|conclusion)$")
    fun beginHeading(at: Int = offset) { headingStart = at; heading.setLength(0) }
    fun endHeading() {
        if (headingStart >= 0) addHeading(heading.toString(), headingStart)
        headingStart = -1
        heading.setLength(0)
    }
    private fun addHeading(title: String, at: Int) {
        if (headings.size >= 98) return
        val clean = title.trim().replace(whitespace, " ").take(160)
        if (clean.isNotBlank() && headings.size < 98) headings.add(Chapter(clean, at))
    }
    fun navigation(title: String, target: String?) {
        if (target != null && title.isNotBlank() && targets.size < 98) targets.putIfAbsent(target, title.trim().take(160))
    }
    fun hasNavigation() = targets.isNotEmpty()
    fun anchor(target: String) {
        if (targets.containsKey(target)) anchors.putIfAbsent(target, offset)
    }
    fun append(text: String) {
        require(offset.toLong() + text.length <= TextReading.MAX_CHARS)
        if (headingStart >= 0 && heading.length < 512) heading.append(text.take(512 - heading.length))
        if (inferLines && headings.size < 98) {
            for ((i, c) in text.withIndex()) {
                if (headings.size >= 98) break
                if (c == '\n' || c == '\r') {
                    finishLine()
                    lineStart = offset + i + 1
                } else if (line.length < 512) line.append(c)
            }
        }
        offset += text.length
    }
    private fun finishLine() {
        val value = line.toString().trim()
        if (chapterLine.matches(value)) addHeading(value.trimStart('#').trim(), lineStart)
        line.setLength(0)
    }
    fun chapters(): List<Chapter> {
        if (inferLines) finishLine()
        endHeading()
        val mapped = targets.mapNotNull { (target, title) -> anchors[target]?.let { Chapter(title, it) } }
        return (if (mapped.isNotEmpty()) mapped else headings).filter { it.offset < offset }
            .distinctBy { it.offset }.take(98)
    }
}
