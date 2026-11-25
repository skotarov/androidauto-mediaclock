package com.kotarov.autoclock.media

import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ext.SdkExtensions
import android.os.ParcelFileDescriptor
import android.annotation.TargetApi
import java.io.File
import java.io.InputStream

internal object PdfBookExtractor {
    fun nativeAvailable(): Boolean = Build.VERSION.SDK_INT >= 35 ||
        (Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(31) >= 13)

    @TargetApi(35)
    fun extractNative(input: InputStream, directory: File, check: () -> Unit, emit: (String) -> Unit) {
        require(nativeAvailable()) { "System PDF text unavailable" }
        val temp = File.createTempFile("book-", ".pdf", directory)
        try {
            temp.outputStream().buffered().use { input.copyTo(it, 8192) }
            check()
            val fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            var hasText = false
            fun text(value: String) {
                check()
                if (value.isNotBlank()) { hasText = true; emit(value); emit("\n") }
            }
            if (Build.VERSION.SDK_INT >= 35) {
                val renderer = try { PdfRenderer(fd) } catch (e: Throwable) { fd.close(); throw e }
                renderer.use { pdf ->
                    require(pdf.pageCount in 1..10000)
                    for (index in 0 until pdf.pageCount) {
                        check()
                        pdf.openPage(index).use { page -> page.textContents.forEach { text(it.text) } }
                    }
                }
            } else {
                val renderer = try { PdfRendererPreV(fd) } catch (e: Throwable) { fd.close(); throw e }
                renderer.use { pdf ->
                    require(pdf.pageCount in 1..10000)
                    for (index in 0 until pdf.pageCount) {
                        check()
                        pdf.openPage(index).use { page -> page.textContents.forEach { text(it.text) } }
                    }
                }
            }
            require(hasText) { "No text layer" }
        } finally { temp.delete() }
    }
}
