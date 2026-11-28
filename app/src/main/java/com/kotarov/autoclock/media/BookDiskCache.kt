package com.kotarov.autoclock.media

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Private, bounded extracted text cache. Never keeps audio or complete text in RAM. */
internal object BookDiskCache {
    private const val LIMIT = 64L * 1024 * 1024
    private const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
    data class Hit(val file: File, val length: Int, val sample: String)
    private val pinned = HashMap<File, Int>()
    private val removeOnRelease = HashSet<File>()
    private fun pin(file: File) { pinned[file] = (pinned[file] ?: 0) + 1 }
    @Synchronized fun release(file: File) {
        val count = pinned[file] ?: return
        if (count <= 1) {
            pinned.remove(file)
            if (removeOnRelease.remove(file)) file.delete()
        } else pinned[file] = count - 1
    }
    private fun valid(file: File): Boolean = file.isFile && file.length() > 0 && file.length() % 2L == 0L && file.length() <= LIMIT
    @Synchronized fun contains(dir: File, key: String): Boolean {
        val file = File(dir, "cache-$key.utf16")
        if (!valid(file)) return false
        file.setLastModified(System.currentTimeMillis())
        return true
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun key(source: String, backend: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest((source + ":" + backend).toByteArray()))
    fun fingerprint(context: Context, uri: Uri, signal: CancellationSignal): String? {
        // Only trust providers which expose both size and modification date.
        // Other providers still work, but are not cached across opens.
        return runCatching {
            context.contentResolver.query(uri, arrayOf("_size", "last_modified"), null, null, null, signal)?.use { c ->
                if (!c.moveToFirst() || c.isNull(0) || c.isNull(1)) null
                else {
                    val size = c.getLong(0); val modified = c.getLong(1)
                    if (size < 0 || modified <= 0) null else "$uri:$size:$modified"
                }
            }
        }.getOrNull()
    }
    fun hasSource(context: Context, url: String): Boolean {
        val dir = File(context.cacheDir, "text-reading")
        if (dir.listFiles().orEmpty().none { it.name.startsWith("cache-") }) return false
        val fingerprint = fingerprint(context, Uri.parse(url), CancellationSignal()) ?: return false
        val keys = BookFormat.values().map { key(fingerprint, it.name + ":v2") } +
            listOf(key(fingerprint, "PDF:native"), key(fingerprint, "PDF:pdfbox"))
        synchronized(this) {
            return keys.any { k ->
                val file = File(dir, "cache-$k.utf16")
                valid(file) && file !in removeOnRelease
            }
        }
    }

    fun removeSource(context: Context, url: String) {
        val fingerprint = fingerprint(context, Uri.parse(url), CancellationSignal()) ?: return
        val dir = File(context.cacheDir, "text-reading")
        val keys = BookFormat.values().map { key(fingerprint, it.name + ":v2") } +
            listOf(key(fingerprint, "PDF:native"), key(fingerprint, "PDF:pdfbox"))
        synchronized(this) {
            keys.forEach { k ->
                val file = File(dir, "cache-$k.utf16")
                if (pinned.containsKey(file)) removeOnRelease.add(file) else file.delete()
            }
        }
    }

    @Synchronized fun read(dir: File, key: String, pinActive: Boolean = false): Hit? {
        prune(dir)
        val file = File(dir, "cache-$key.utf16")
        if (!valid(file) || file in removeOnRelease) return null
        return runCatching {
            val length = (file.length() / 2).toInt()
            val sample = RandomAccessFile(file, "r").use { input -> String(CharArray(minOf(4000, length)) { input.readChar() }) }
            file.setLastModified(System.currentTimeMillis())
            if (pinActive) pin(file)
            Hit(file, length, sample)
        }.getOrNull()
    }
    @Synchronized fun store(dir: File, key: String, temp: File, pinActive: Boolean = false): File? {
        if (temp.length() <= 0 || temp.length() > LIMIT) return null
        val target = File(dir, "cache-$key.utf16")
        // A concurrent chapter index may have already stored the identical source.
        if (valid(target)) temp.delete()
        else if (!temp.renameTo(target)) return null
        if (pinActive) pin(target)
        target.setLastModified(System.currentTimeMillis())
        prune(dir, target)
        return target
    }
    private fun prune(dir: File, keep: File? = null) {
        val cutoff = System.currentTimeMillis() - MAX_AGE
        var total = 0L
        var count = 0
        for (file in dir.listFiles().orEmpty().filter { it.name.startsWith("cache-") && it.extension == "utf16" }
            .sortedByDescending { if (it == keep) Long.MAX_VALUE else it.lastModified() }) {
            if (file != keep && !pinned.containsKey(file) && (file.lastModified() < cutoff || count >= 3 || total + file.length() > LIMIT)) file.delete()
            else { total += file.length(); count++ }
        }
    }
}
