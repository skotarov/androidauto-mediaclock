package com.kotarov.autoclock.media

/** Make the existing visual message natural to speak without reading emoji. */
object NoticeSpeechText {
    fun format(body: String): String = body.trim()
        .replace(Regex("^Within 1h:\\s*"), "Within one hour: ")
        .replace(Regex("(\\d+)\\s*%"), "$1 percent")
}
