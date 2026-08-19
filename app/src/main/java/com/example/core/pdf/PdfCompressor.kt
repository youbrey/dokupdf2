package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
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
    private val context: Context,
    private val rendererEngine: PdfRendererEngine = PdfRendererEngine(context)
) {

    suspend fun compressPdf(
        sourcePdf: File,
        outputPdf: File,
        level: CompressionLevel = CompressionLevel.HIGH
    ): Result<CompressionResult> = withContext(Dispatchers.IO) {
        val originalSize = sourcePdf.length()
        val pdfDoc = PdfDocument()

        try {
            val bitmaps = rendererEngine.renderPdfPages(sourcePdf, scale = level.scaleFactor)
            for ((index, bmp) in bitmaps.withIndex()) {
                // Compress bitmap with JPEG stream to reduce raster density
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, level.jpegQuality, stream)
                val compressedBmpBytes = stream.toByteArray()
                val compressedBmp = android.graphics.BitmapFactory.decodeByteArray(
                    compressedBmpBytes, 0, compressedBmpBytes.size
                )

                val pageInfo = PdfDocument.PageInfo.Builder(
                    compressedBmp.width,
                    compressedBmp.height,
                    index + 1
                ).create()

                val page = pdfDoc.startPage(pageInfo)
                page.canvas.drawBitmap(compressedBmp, 0f, 0f, null)
                pdfDoc.finishPage(page)
            }

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { out ->
                pdfDoc.writeTo(out)
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
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDoc.close()
        }
    }
}
