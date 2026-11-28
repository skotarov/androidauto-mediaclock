package com.kotarov.autoclock.media
import android.content.Context
import java.io.File
import java.io.InputStream

internal object LegacyPdfExtractor {
    const val available = false
    fun init(context: Context) = Unit
    fun extract(input: InputStream, directory: File, check: () -> Unit, emit: (String) -> Unit): Unit =
        error("PDF needs Android 15 or Android 12–14 with S Extension 13; install Universal for legacy PDF support")
}
