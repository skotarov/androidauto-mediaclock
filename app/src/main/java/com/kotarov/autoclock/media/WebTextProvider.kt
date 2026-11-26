package com.kotarov.autoclock.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Private read-only provider so imported articles reuse the existing local document reader. */
class WebTextProvider : ContentProvider() {
    override fun onCreate() = true
    private fun requireFile(uri: Uri): File = file(requireNotNull(context), uri)
        ?.takeIf { it.isFile } ?: throw FileNotFoundException("Article unavailable")
    override fun getType(uri: Uri): String? = if (owns(uri.toString())) "text/plain" else null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(requireFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = requireFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> "article.txt"
                OpenableColumns.SIZE -> f.length()
                "last_modified" -> f.lastModified()
                else -> null
            } })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private const val AUTHORITY = "com.kotarov.autoclock2.webtext"
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private fun id(uri: Uri): String? = uri.lastPathSegment?.takeIf {
            uri.scheme == "content" && uri.authority == AUTHORITY && uri.pathSegments.size == 1 && idPattern.matches(it)
        }
        internal fun owns(url: String): Boolean = id(Uri.parse(url)) != null
        private fun file(context: Context, uri: Uri): File? =
            id(uri)?.let { File(File(context.filesDir, "web-reader"), "$it.txt") }
        internal fun save(context: Context, text: String): String {
            val dir = File(context.filesDir, "web-reader").apply { mkdirs() }
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(dir.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= 64L * 1024 * 1024) { "Article library full" }
            val id = UUID.randomUUID().toString()
            val uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(id).build()
            val target = requireNotNull(file(context, uri))
            val temp = File.createTempFile("article-", ".tmp", dir)
            try {
                temp.writeBytes(bytes)
                require(temp.renameTo(target))
            } finally { temp.delete() }
            return uri.toString()
        }
        internal fun delete(context: Context, url: String) {
            file(context, Uri.parse(url))?.delete()
        }
    }
}
