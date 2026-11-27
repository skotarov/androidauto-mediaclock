package com.kotarov.autoclock.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.security.MessageDigest
import java.util.Locale

/** List every direct file in the user-selected SAF folder; format detection is not a visibility filter. */
internal object LocalMediaFolder {
    private data class Candidate(val id: String, val name: String, val ext: String, val sortName: String, val book: BookFormat?)
    private val order = compareBy<Candidate> { it.sortName }.thenBy { it.id }
    fun list(context: Context, folder: StreamLibrary.Station): List<MediaPageParser.Item> {
        val tree = Uri.parse(folder.url)
        require(DocumentsContract.isTreeUri(tree))
        val root = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val candidates = ArrayList<Candidate>()
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: id.substringAfterLast('/').substringAfterLast(':').ifBlank { id }
                val mime = cursor.getString(2).orEmpty().lowercase(Locale.ROOT)
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (mime == Document.MIME_TYPE_DIR) continue
                val book = BookFormat.detect(name, mime)
                candidates.add(Candidate(id, name, ext, name.lowercase(Locale.ROOT), book))
            }
        } ?: error("Folder unavailable")
        return candidates.sortedWith(order).map { item ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.id).toString()
            val hash = MessageDigest.getInstance("SHA-256").digest((folder.id + "|" + uri).toByteArray())
                .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
            val format = item.ext.uppercase(Locale.ROOT).ifBlank { item.book?.name ?: "MEDIA" }
            MediaPageParser.Item("foldermedia:" + hash, item.name, uri, format, textFile = item.book != null)
        }
    }
}
