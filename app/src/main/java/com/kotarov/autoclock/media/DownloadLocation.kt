package com.kotarov.autoclock.media

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.util.Locale

internal object DownloadLocation {
    const val INTERNAL = "internal"
    const val CARD = "card"
    const val KEY = "download_location_v161"
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val FOLDER = "Travel to Awaken"
    private const val MUSIC = "Music"
    val MODES = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun selected(prefs: SharedPreferences): String =
        if (prefs.getString(KEY, INTERNAL) == CARD) CARD else INTERNAL

    fun tree(prefs: SharedPreferences, mode: String): Uri? =
        prefs.getString("download_tree_v161:" + mode, null)?.let(Uri::parse)

    fun remember(prefs: SharedPreferences, mode: String, uri: Uri) {
        prefs.edit().putString(KEY, mode).putString("download_tree_v161:" + mode, uri.toString()).apply()
    }

    fun initialUri(volume: String): Uri =
        DocumentsContract.buildDocumentUri(AUTHORITY, "$volume:$MUSIC")

    // Only the public Music directory on the intended storage volume, or our
    // own subdirectory, is accepted. Never write into Android/data or a cloud provider.
    fun validTree(uri: Uri, mode: String): Boolean = runCatching {
        if (uri.authority != AUTHORITY || !DocumentsContract.isTreeUri(uri)) return@runCatching false
        val id = DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val matches = if (mode == INTERNAL) volume.equals("primary", true)
            else !volume.equals("primary", true) && volume.isNotBlank() && !volume.equals("home", true)
        matches && (path.equals(MUSIC, true) || path.equals("$MUSIC/$FOLDER", true))
    }.getOrDefault(false)

    fun hasAccess(resolver: ContentResolver, tree: Uri, mode: String): Boolean =
        validTree(tree, mode) && resolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }

    private fun childDirectory(resolver: ContentResolver, tree: Uri): Uri {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        if (id.substringAfter(':').equals("$MUSIC/$FOLDER", true)) return root
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1).equals(FOLDER, true) && cursor.getString(2) == Document.MIME_TYPE_DIR)
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        }
        return requireNotNull(DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, FOLDER)) {
            "Cannot create download folder"
        }
    }

    fun create(resolver: ContentResolver, tree: Uri, spec: MediaDownload.Spec): Uri {
        val folder = childDirectory(resolver, tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val names = mutableSetOf<String>()
        resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) names.add(it.getString(0).orEmpty().lowercase(Locale.ROOT))
        }
        val name = uniqueName(spec.name, names)
        return requireNotNull(DocumentsContract.createDocument(resolver, folder, spec.mime, name)) {
            "Cannot create media file"
        }
    }

    internal fun uniqueName(name: String, existing: Set<String>): String {
        if (name.lowercase(Locale.ROOT) !in existing) return name
        val ext = name.substringAfterLast('.', "")
        val base = if (ext.isEmpty()) name else name.substringBeforeLast('.')
        for (n in 2..10000) {
            val next = "$base ($n)" + if (ext.isEmpty()) "" else ".$ext"
            if (next.lowercase(Locale.ROOT) !in existing) return next
        }
        error("Too many files with the same name")
    }
}
