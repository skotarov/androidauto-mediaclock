package com.kotarov.autoclock.media

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** On-demand indexing without starting TTS, no full text retained. */
internal object BookNavigation {
    const val BOOK_PREFIX = "book:"
    private const val CHAPTER_PREFIX = "chapter:"
    private const val BEGIN_PREFIX = "bookbegin:"
    data class Choice(val id: String, val title: String)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(3))
    // Only the worker accesses this bounded cache; source modification invalidates it.
    private val cache = LinkedHashMap<String, List<BookContentsIndex.Chapter>>(8, 0.75f, true)
    // Owner-thread requests from Auto and the phone share one extraction.
    private val pending = HashMap<String, MutableList<(List<BookContentsIndex.Chapter>) -> Unit>>()
    fun mayHaveContents(name: String): Boolean = BookFormat.detect(name, null) != BookFormat.PDF
    fun choices(book: StreamLibrary.Station, chapters: List<BookContentsIndex.Chapter>): List<Choice> =
        listOf(Choice(book.id, "Continue reading"), Choice(BEGIN_PREFIX + book.id, "From beginning")) +
            chapters.map { Choice(CHAPTER_PREFIX + book.id + ":" + it.offset, it.title) }
    fun parseChoice(id: String?): Pair<String, Int>? {
        if (id == null) return null
        if (id.startsWith(BEGIN_PREFIX)) return id.removePrefix(BEGIN_PREFIX).takeIf { it.isNotBlank() }?.let { it to 0 }
        if (!id.startsWith(CHAPTER_PREFIX)) return null
        val value = id.removePrefix(CHAPTER_PREFIX)
        val book = value.substringBeforeLast(':', "")
        val offset = value.substringAfterLast(':').toIntOrNull() ?: return null
        return if (book.isNotBlank() && offset in 0..TextReading.MAX_CHARS) book to offset else null
    }
    fun load(context: Context, book: StreamLibrary.Station, done: (List<BookContentsIndex.Chapter>) -> Unit) {
        val app = context.applicationContext
        val requestKey = book.url
        pending[requestKey]?.let { it.add(done); return }
        pending[requestKey] = mutableListOf(done)
        try {
            worker.execute {
                val result = runCatching {
                    val uri = Uri.parse(book.url)
                    val format = BookFormat.detect(book.name, app.contentResolver.getType(uri)) ?: BookFormat.PLAIN
                    if (format == BookFormat.PDF) return@runCatching emptyList<BookContentsIndex.Chapter>()
                    val signal = CancellationSignal()
                    val fingerprint = BookDiskCache.fingerprint(app, uri, signal)
                    if (fingerprint != null) cache[fingerprint]?.let { return@runCatching it }
                    val dir = File(app.cacheDir, "text-reading").apply { mkdirs() }
                    val index = BookContentsIndex(format == BookFormat.PLAIN || format == BookFormat.RTF)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 45_000L
                    // Produce the same UTF-16 cache while indexing, so first Play need not extract again.
                    val key = fingerprint?.let { BookDiskCache.key(it, format.name + ":v2") }
                    val temp = if (key != null && !BookDiskCache.contains(dir, key))
                        File.createTempFile("book-", ".utf16", dir) else null
                    try {
                        val output = temp?.outputStream()?.buffered()?.writer(Charsets.UTF_16BE)
                        try {
                            val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: error("No file")
                            descriptor.use { afd ->
                                afd.createInputStream().use { input ->
                                    BookExtractor.extract(format, input, dir, {
                                        require(!Thread.currentThread().isInterrupted && android.os.SystemClock.elapsedRealtime() < deadline)
                                    }, index = index) { text ->
                                        index.append(text)
                                        output?.write(text)
                                    }
                                }
                            }
                        } finally { output?.close() }
                        if (temp != null && key != null && temp.length() == index.offset.toLong() * 2L)
                            BookDiskCache.store(dir, key, temp)
                    } finally { temp?.delete() }
                    val chapters = index.chapters()
                    if (fingerprint != null) {
                        cache[fingerprint] = chapters
                        while (cache.size > 8) cache.remove(cache.keys.first())
                    }
                    chapters
                }.getOrDefault(emptyList())
                handler.post { pending.remove(requestKey).orEmpty().forEach { it(result) } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.remove(requestKey).orEmpty().forEach { it(emptyList()) }
        }
    }
}
