package com.kotarov.autoclock.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ClockArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val now = LocalDateTime.now()
        val modeSegment = uri.pathSegments.firstOrNull() ?: "digital"
        val bitmap = when (modeSegment) {
            "analog" -> ClockRenderer.renderAnalog(now)
            else -> ClockRenderer.renderDigital(now)
        }

        val outputDir = File(context.cacheDir, "clock-artwork").apply { mkdirs() }
        val fileName = "clock_${modeSegment}_${now.format(FILE_FORMATTER)}.png"
        val file = File(outputDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.kotarov.autoclock.artwork"
        private val FILE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

        fun artworkUri(mode: String, now: LocalDateTime = LocalDateTime.now()): Uri {
            val minuteKey = now.format(FILE_FORMATTER)
            return Uri.parse("content://$AUTHORITY/$mode/clock_$minuteKey.png")
        }
    }
}
