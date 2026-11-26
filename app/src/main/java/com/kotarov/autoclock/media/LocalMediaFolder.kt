package com.kotarov.autoclock.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.security.MessageDigest
import java.util.Locale

/** SAF tree listing, only direct audio/video children, bounded and sorted by name. */
internal object LocalMediaFolder {
    private val extensions = setOf("mp3","aac","m4a","mp4","m4v","ogg","oga","opus","wav","flac","webm","3gp","amr","mkv","wma")
    private const val MAX_ITEMS = 100
    private data class Candidate(val id: String, val name: String, val ext: String, val sortName: String)
    private val order = compareBy<Candidate> { it.sortName }.thenBy { it.id }
    fun list(context: Context, folder: StreamLibrary.Station): List<MediaPageParser.Item> {
        val tree = Uri.parse(folder.url)
        require(DocumentsContract.isTreeUri(tree))
        val root = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val candidates = BoundedFolderOrder<Candidate>(MAX_ITEMS, order)
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            var scanned = 0
            while (cursor.moveToNext() && scanned++ < 10_000) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty().lowercase(Locale.ROOT)
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (mime == Document.MIME_TYPE_DIR || (!mime.startsWith("audio/") && !mime.startsWith("video/") && ext !in extensions)) continue
                candidates.add(Candidate(id, name, ext, name.lowercase(Locale.ROOT)))
            }
        } ?: error("Folder unavailable")
        return candidates.sorted().map { item ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.id).toString()
            val hash = MessageDigest.getInstance("SHA-256").digest((folder.id + "|" + uri).toByteArray())
                .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
            MediaPageParser.Item("foldermedia:" + hash, item.name, uri, item.ext.uppercase(Locale.ROOT).ifBlank { "MEDIA" })
        }
    }
}
