package com.kotarov.autoclock.media

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.io.Writer
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.annotation.TargetApi

/** Text only; scratch streams go to disk, not a whole-book byte array.
 * PDFBox still needs per-page objects/fonts in memory. */
internal object PdfBookExtractor {
    /** On Android 15+ the OS provides text extraction without loading PDFBox.
     * The input is already bounded by BookExtractor. Use a private seekable file. */
    @TargetApi(35)
    fun extractNative(input: InputStream, directory: File, check: () -> Unit, emit: (String) -> Unit) {
        val temp = File.createTempFile("book-", ".pdf", directory)
        try {
            temp.outputStream().buffered().use { input.copyTo(it, 8192) }
            check()
            val fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(fd) } catch (e: Throwable) { fd.close(); throw e }
            renderer.use { pdf ->
                require(pdf.pageCount in 1..10000)
                var hasText = false
                for (index in 0 until pdf.pageCount) {
                    check()
                    pdf.openPage(index).use { page ->
                        for (part in page.textContents) {
                            check()
                            val text = part.text
                            if (text.isNotBlank()) {
                                hasText = true
                                emit(text)
                                emit("\n")
                            }
                        }
                    }
                }
                require(hasText) { "No text layer" }
            }
        } finally { temp.delete() }
    }

    fun extract(input: InputStream, directory: File, check: () -> Unit, emit: (String) -> Unit) {
        val scratch = MemoryUsageSetting.setupTempFileOnly(256L * 1024 * 1024).setTempDir(directory)
        PDDocument.load(input, scratch).use { doc ->
            require(!doc.isEncrypted && doc.currentAccessPermission.canExtractContent()) { "Protected PDF" }
            require(doc.numberOfPages in 1..10000) { "Unsupported PDF size" }
            var hasText = false
            val stripper = object : PDFTextStripper() {
                override fun startPage(page: PDPage) { check(); super.startPage(page) }
                override fun endPage(page: PDPage) { super.endPage(page); check() }
            }
            stripper.sortByPosition = true
            stripper.writeText(doc, object : Writer() {
                override fun write(cbuf: CharArray, off: Int, len: Int) {
                    check()
                    if (!hasText) {
                        if ((off until off + len).any { !cbuf[it].isWhitespace() }) hasText = true
                        else return
                    }
                    emit(String(cbuf, off, len))
                }
                override fun flush() = Unit
                override fun close() = Unit
            })
            require(hasText) { "No text layer" }
        }
    }
}
