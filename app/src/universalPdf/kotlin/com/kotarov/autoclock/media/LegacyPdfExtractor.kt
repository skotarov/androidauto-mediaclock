package com.kotarov.autoclock.media
import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.io.Writer

internal object LegacyPdfExtractor {
    const val available = true
    fun init(context: Context) { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context) }
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
