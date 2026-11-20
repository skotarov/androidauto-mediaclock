package com.kotarov.autoclock.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.kotarov.autoclock.weather.WeatherStore

/**
 * Android Auto may request the same album art multiple times.
 * Generate each minute/mode only once, then reuse the cached file.
 * A small capped cache prevents the unbounded disk growth in v1.
 */
class ClockArtworkProvider : ContentProvider() {
    private val lock = Any()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only")
        val appContext = context ?: throw FileNotFoundException("No application context")
        val path = uri.pathSegments
        val clockMode = path.getOrNull(0) ?: throw FileNotFoundException("Missing mode")
        val name = path.getOrNull(1) ?: throw FileNotFoundException("Missing timestamp")
        if (clockMode !in VALID_MODES || !name.startsWith("clock_") || !name.endsWith(".png")) {
            throw FileNotFoundException("Invalid artwork path")
        }

        val key = name.removePrefix("clock_").removeSuffix(".png")
        val requestedMinute = try {
            LocalDateTime.parse(key, KEY_FORMAT)
        } catch (e: Exception) {
            throw FileNotFoundException("Invalid artwork timestamp")
        }

        val directory = File(appContext.cacheDir, "clock-artwork")
        if (!directory.exists() && !directory.mkdirs()) {
            throw FileNotFoundException("Cannot create artwork cache")
        }
        val weatherRevision = uri.getQueryParameter("rev")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val destination = File(directory, "$clockMode-$key-$weatherRevision.png")

        synchronized(lock) {
            if (!destination.isFile || destination.length() == 0L) {
                val temp = File(directory, "inflight-${System.nanoTime()}.tmp")
                val weather = if (clockMode == "weather" || clockMode == "both") {
                    WeatherStore.current(appContext)
                } else null
                val bitmap = ClockRenderer.render(
                    clockMode, requestedMinute,
                    weather?.weatherCode,
                    weather?.isDay ?: true
                )
                try {
                    FileOutputStream(temp).use { out ->
                        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                            throw FileNotFoundException("Could not encode artwork")
                        }
                    }
                    if (!temp.renameTo(destination)) {
                        throw FileNotFoundException("Could not save artwork")
                    }
                    pruneCache(directory)
                } finally {
                    bitmap.recycle()
                    temp.delete()
                }
            }
        }
        return ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun pruneCache(directory: File) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
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
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.kotarov.autoclock2.artwork"
        private const val MAX_FILES = 6
        private val VALID_MODES = setOf("clock", "analog", "weather", "both")
        private val KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

        fun artworkUri(
            mode: String,
            now: LocalDateTime = LocalDateTime.now(),
            weatherRevision: Long = 0L
        ): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(mode)
            .appendPath("clock_${now.format(KEY_FORMAT)}.png")
            .appendQueryParameter("rev", weatherRevision.coerceAtLeast(0L).toString())
            .build()
    }
}
