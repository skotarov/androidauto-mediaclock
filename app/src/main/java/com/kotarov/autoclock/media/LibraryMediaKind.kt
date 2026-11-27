package com.kotarov.autoclock.media

import com.kotarov.autoclock.R
import java.util.Locale

/** Presentation only: never filter a source or change playback based on its icon. */
internal enum class LibraryMediaKind(val label: String, val icon: Int) {
    FOLDER("Folder", R.drawable.ic_source_folder),
    WEB("Web media", R.drawable.ic_source_web),
    ARTICLE("Article", R.drawable.ic_media_article),
    BOOK("E-book", R.drawable.ic_media_book),
    AUDIOBOOK("Audiobook", R.drawable.ic_media_book),
    DOCUMENT("Document", R.drawable.ic_media_document),
    AUDIO("Audio", R.drawable.ic_media_audio),
    VIDEO("Video audio", R.drawable.ic_media_video),
    STREAM("Stream", R.drawable.ic_source_podcast),
    FILE("Media file", R.drawable.ic_source_file);

    companion object {
        private val audio = setOf("mp3", "mp2", "m4a", "m4b", "aac", "wav", "flac", "ogg", "opus", "oga", "wma", "aiff", "amr", "mpeg", "x-wav", "x-flac")
        private val video = setOf("mp4", "m4v", "webm", "mkv", "avi", "mov", "3gp", "mpeg4", "quicktime")
        private val stream = setOf("m3u", "m3u8", "pls", "stream", "mpegurl", "x-mpegurl")
        fun station(s: StreamLibrary.Station): LibraryMediaKind = when {
            s.localFolder -> FOLDER
            s.mediaPage -> WEB
            s.textFile && WebTextProvider.owns(s.url) -> ARTICLE
            else -> classify(s.name, s.url, "", s.textFile, s.local)
        }

        fun item(i: MediaPageParser.Item, local: Boolean): LibraryMediaKind =
            classify(i.name, i.url, i.format, i.textFile, local)

        private fun classify(name: String, url: String, format: String, text: Boolean, local: Boolean): LibraryMediaKind {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val urlExt = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            val hint = format.lowercase(Locale.ROOT)
            val formats = setOf(ext, urlExt, hint)
            if (text) {
                if (formats.any { it == "epub" || it == "fb2" }) return BOOK
                if (formats.any { it == "html" || it == "htm" || it == "xhtml" }) return ARTICLE
                return DOCUMENT
            }
            if (formats.any { it in video }) return VIDEO
            if (formats.any { it == "m4b" }) return AUDIOBOOK
            if (formats.any { it in audio } || hint.startsWith("audio/")) return AUDIO
            if (!local && (formats.any { it in stream } || hint.isBlank())) return STREAM
            return if (local) FILE else STREAM
        }
    }
}
