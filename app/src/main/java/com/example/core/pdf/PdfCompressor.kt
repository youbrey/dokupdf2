package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

enum class CompressionLevel(
    val title: String,
    val description: String,
    val scaleFactor: Float,
    val jpegQuality: Int
) {
    EXTREME("Kompresi Ekstrem", "Ukuran terkecil, cocok untuk email / kuota terbatas", 1.0f, 45),
    HIGH("Kompresi Tinggi", "Keseimbangan ideal antara kualitas & ukuran kecil", 1.25f, 65),
    BALANCED("Kompresi Sedang", "Kualitas teks & gambar tajam dengan ukuran hemat", 1.5f, 80),
    LOW("Kompresi Ringan", "Mempertahankan kualitas gambar mendekati aslinya", 1.8f, 90)
}

data class CompressionResult(
    val outputFile: File,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val savedPercentage: Int
)

class PdfCompressor(
    context: Context,
    // [Audit fix -- babak 2] Lihat catatan lengkap di PdfConverterEngine.kt. Dipindah
    // sebelum rendererEngine supaya defaultnya bisa meneruskan dispatcher yang sama.
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val rendererEngine: PdfRendererEngine = PdfRendererEngine(context, ioDispatcher)
) {

    suspend fun compressPdf(
        sourcePdf: File,
        outputPdf: File,
        level: CompressionLevel = CompressionLevel.HIGH
    ): Result<CompressionResult> = withContext(ioDispatcher) {
        try {
            PdfFileUtils.requirePdf(sourcePdf)
            PdfFileUtils.requireDistinct(sourcePdf, outputPdf)
            val originalSize = sourcePdf.length()
            val dimensions = rendererEngine.getPageDimensions(sourcePdf)
            require(dimensions.isNotEmpty()) { "PDF tidak memiliki halaman yang dapat dikompresi" }

            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
              // [Audit fix] Diserialkan lewat PdfFileUtils.pdfDocumentMutex -- lihat
              // PdfFileUtils.kt untuk alasan (PdfDocument didokumentasikan "not thread safe").
              PdfFileUtils.pdfDocumentMutex.withLock {
                val pdfDoc = PdfDocument()
                try {
                    rendererEngine.forEachRenderedPage(sourcePdf, scale = level.scaleFactor) { index, bmp ->
                        val compressedBmp = ByteArrayOutputStream().use { stream ->
                            require(bmp.compress(Bitmap.CompressFormat.JPEG, level.jpegQuality, stream)) {
                                "Gagal mengompresi halaman ${index + 1}"
                            }
                            val bytes = stream.toByteArray()
                            requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
                                "Gagal membaca ulang halaman terkompresi ${index + 1}"
                            }
                        }

                        try {
                            val originalPage = dimensions[index]
                            val pageInfo = PdfDocument.PageInfo.Builder(
                                originalPage.width,
                                originalPage.height,
                                index + 1
                            ).create()

                            val page = pdfDoc.startPage(pageInfo)
                            page.canvas.drawBitmap(
                                compressedBmp,
                                null,
                                Rect(0, 0, originalPage.width, originalPage.height),
                                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                            )
                            pdfDoc.finishPage(page)
                        } finally {
                            if (!compressedBmp.isRecycled) compressedBmp.recycle()
                        }
                    }

                    FileOutputStream(temporaryOutput).use { out -> pdfDoc.writeTo(out) }
                } finally {
                    pdfDoc.close()
                }

                // Some already-optimized/vector PDFs become larger after raster compression.
                // Preserve the original instead of reporting a misleading negative saving.
                if (temporaryOutput.length() >= originalSize) {
                    sourcePdf.copyTo(temporaryOutput, overwrite = true)
                }
              }
            }

            val compressedSize = outputPdf.length()
            val savedBytes = (originalSize - compressedSize).coerceAtLeast(0)
            val savedPercent = if (originalSize > 0) ((savedBytes * 100) / originalSize).toInt() else 0

            Result.success(
                CompressionResult(
                    outputFile = outputPdf,
                    originalSizeBytes = originalSize,
                    compressedSizeBytes = compressedSize,
                    savedPercentage = savedPercent
                )
            )
        } catch (error: Exception) {
            Result.failure(error)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk mengompresi PDF", oom))
        }
    }
}
