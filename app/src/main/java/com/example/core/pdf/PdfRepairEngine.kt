package com.example.core.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class RepairReport(
    val outputFile: File,
    val issuesFixed: List<String>,
    val wasSuccessful: Boolean
)

class PdfRepairEngine(
    context: Context,
    // [Audit fix -- babak 2] Lihat catatan lengkap di PdfConverterEngine.kt. Dipindah
    // sebelum rendererEngine supaya defaultnya bisa meneruskan dispatcher yang sama.
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val rendererEngine: PdfRendererEngine = PdfRendererEngine(context, ioDispatcher)
) {
    private val cacheDir = context.applicationContext.cacheDir

    suspend fun repairPdf(
        sourcePdf: File,
        outputPdf: File
    ): Result<RepairReport> = withContext(ioDispatcher) {
        val fixedIssues = mutableListOf<String>()
        var tempFile: File? = null
        try {
            PdfFileUtils.requireReadableFile(sourcePdf, "Berkas PDF sumber")
            PdfFileUtils.requireDistinct(sourcePdf, outputPdf)
            val headerOffset = findPdfHeaderOffset(sourcePdf)
            val hasTrailer = hasEofTrailer(sourcePdf)

            val candidateFile = File.createTempFile("repair_candidate_", ".pdf", cacheDir)
            tempFile = candidateFile
            FileInputStream(sourcePdf).use { input ->
                FileOutputStream(candidateFile).use { output ->
                    if (headerOffset < 0) {
                        output.write("%PDF-1.7\n".toByteArray(Charsets.US_ASCII))
                        fixedIssues += "Menambahkan header PDF yang hilang"
                    } else if (headerOffset > 0) {
                        input.skipExactly(headerOffset.toLong())
                        fixedIssues += "Menghapus $headerOffset byte sampah sebelum header PDF"
                    }
                    input.copyTo(output)
                    if (!hasTrailer) {
                        output.write("\n%%EOF\n".toByteArray(Charsets.US_ASCII))
                        fixedIssues += "Menambahkan marker %%EOF yang hilang"
                    }
                }
            }

            val candidatePageCount = rendererEngine.getPageCount(candidateFile)
            val originalPageCount = if (candidatePageCount == 0) rendererEngine.getPageCount(sourcePdf) else 0
            val renderSource = when {
                candidatePageCount > 0 -> candidateFile
                originalPageCount > 0 -> sourcePdf
                else -> throw IllegalArgumentException(
                "Tidak ada halaman PDF yang dapat dirender; perbaikan dihentikan agar tidak menghasilkan berkas rusak palsu"
                )
            }
            val dimensions = rendererEngine.getPageDimensions(renderSource)

            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
              // [Audit fix] Diserialkan lewat PdfFileUtils.pdfDocumentMutex -- lihat
              // PdfFileUtils.kt untuk alasan (PdfDocument didokumentasikan "not thread safe").
              PdfFileUtils.pdfDocumentMutex.withLock {
                val cleanDocument = PdfDocument()
                try {
                    rendererEngine.forEachRenderedPage(renderSource, scale = 1.6f) { pageIndex, bitmap ->
                        val originalPage = dimensions[pageIndex]
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            originalPage.width,
                            originalPage.height,
                            pageIndex + 1
                        ).create()
                        val page = cleanDocument.startPage(pageInfo)
                        page.canvas.drawBitmap(
                            bitmap,
                            null,
                            Rect(0, 0, originalPage.width, originalPage.height),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        )
                        cleanDocument.finishPage(page)
                    }
                    FileOutputStream(temporaryOutput).use { cleanDocument.writeTo(it) }
                } finally {
                    cleanDocument.close()
                }
              }
            }
            fixedIssues += "Merender ulang setiap halaman sebagai gambar ke dokumen PDF baru (${dimensions.size} halaman)"
            // [Audit fix] Pesan sebelumnya ("Membangun ulang struktur xref dan object
            // dictionary") menyiratkan perbaikan struktural PDF asli (mis. xref table, object
            // stream) tetap dipertahankan -- itu TIDAK BENAR. Proses ini merender ulang setiap
            // halaman menjadi bitmap lalu menuliskannya sebagai halaman gambar baru (sama
            // seperti fungsi lain di kelas ini). Konsekuensi nyata bagi pengguna: teks yang
            // bisa diseleksi/dicari di PDF asli akan HILANG (jadi gambar), ukuran berkas bisa
            // membesar signifikan. Ini bukan bug baru -- perilakunya sama sejak awal -- tapi
            // pesannya sebelumnya menyesatkan tentang APA yang sebenarnya terjadi. TODO 🟡:
            // pertimbangkan perbaikan xref/object dictionary asli (parsing PDF level rendah)
            // sebagai fitur terpisah untuk kasus PDF besar bertext yang ingin dipertahankan.

            Result.success(
                RepairReport(
                    outputFile = outputPdf,
                    issuesFixed = fixedIssues,
                    wasSuccessful = true
                )
            )
        } catch (error: Exception) {
            Result.failure(error)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk memperbaiki PDF", oom))
        } finally {
            tempFile?.delete()
        }
    }

    private fun findPdfHeaderOffset(file: File): Int {
        val header = "%PDF-".toByteArray(Charsets.US_ASCII)
        val prefix = ByteArray(minOf(4096L, file.length()).toInt())
        val read = FileInputStream(file).use { it.read(prefix) }
        for (start in 0..(read - header.size)) {
            if (header.indices.all { offset -> prefix[start + offset] == header[offset] }) return start
        }
        return -1
    }

    private fun hasEofTrailer(file: File): Boolean {
        val bytesToRead = minOf(4096L, file.length()).toInt()
        if (bytesToRead == 0) return false
        val tail = ByteArray(bytesToRead)
        RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - bytesToRead)
            input.readFully(tail)
        }
        return String(tail, Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF")
    }

    private fun java.io.InputStream.skipExactly(length: Long) {
        var remaining = length
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else {
                require(read() >= 0) { "Berkas PDF terpotong saat memperbaiki header" }
                remaining--
            }
        }
    }
}
