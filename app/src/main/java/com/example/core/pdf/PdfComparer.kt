package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max

data class PageComparisonResult(
    val pageIndex: Int,
    val differencePercentage: Float,
    val hasDifferences: Boolean
)

data class DocumentComparisonResult(
    val fileA: File,
    val fileB: File,
    val pageResults: List<PageComparisonResult>,
    val overallSimilarityPercentage: Float
)

class PdfComparer(
    context: Context,
    private val rendererEngine: PdfRendererEngine = PdfRendererEngine(context)
) {

    suspend fun comparePdfs(fileA: File, fileB: File): Result<DocumentComparisonResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(fileA.isFile && fileB.isFile) { "Kedua berkas PDF harus valid" }
                PdfFileUtils.requirePdf(fileA, "Dokumen A")
                PdfFileUtils.requirePdf(fileB, "Dokumen B")
                require(fileA.canonicalFile != fileB.canonicalFile) { "Pilih dua dokumen PDF yang berbeda" }
                val pageCountA = rendererEngine.getPageCount(fileA)
                val pageCountB = rendererEngine.getPageCount(fileB)
                val maxPages = max(pageCountA, pageCountB)
                require(maxPages > 0) { "Tidak ada halaman PDF yang dapat dibandingkan" }
                val results = ArrayList<PageComparisonResult>(maxPages)
                var totalDifference = 0f

                for (index in 0 until maxPages) {
                    var bitmapA: Bitmap? = null
                    var bitmapB: Bitmap? = null
                    try {
                        bitmapA = if (index < pageCountA) {
                            requireNotNull(rendererEngine.renderSinglePage(fileA, index, scale = 1f)) {
                                "Halaman ${index + 1} Dokumen A gagal dirender"
                            }
                        } else null
                        bitmapB = if (index < pageCountB) {
                            requireNotNull(rendererEngine.renderSinglePage(fileB, index, scale = 1f)) {
                                "Halaman ${index + 1} Dokumen B gagal dirender"
                            }
                        } else null
                        val difference = when {
                            bitmapA == null && bitmapB == null -> 0f
                            bitmapA == null || bitmapB == null -> 100f
                            else -> calculatePixelDifference(requireNotNull(bitmapA), requireNotNull(bitmapB))
                        }
                        totalDifference += difference
                        results += PageComparisonResult(
                            pageIndex = index,
                            differencePercentage = difference,
                            hasDifferences = difference > 0.5f
                        )
                    } finally {
                        bitmapA?.let { if (!it.isRecycled) it.recycle() }
                        bitmapB?.let { if (!it.isRecycled) it.recycle() }
                    }
                }

                DocumentComparisonResult(
                    fileA = fileA,
                    fileB = fileB,
                    pageResults = results,
                    overallSimilarityPercentage = (100f - totalDifference / maxPages).coerceIn(0f, 100f)
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }.recoverCatching { error ->
                if (error is OutOfMemoryError) {
                    throw IllegalStateException("Memori tidak cukup untuk membandingkan PDF", error)
                }
                throw error
            }
        }

    private fun calculatePixelDifference(first: Bitmap, second: Bitmap): Float {
        require(first.width > 0 && first.height > 0 && second.width > 0 && second.height > 0) {
            "Ukuran halaman tidak valid"
        }
        val comparisonSize = 512
        var normalizedFirst: Bitmap? = null
        var normalizedSecond: Bitmap? = null
        try {
            normalizedFirst = normalizePage(first, comparisonSize)
            normalizedSecond = normalizePage(second, comparisonSize)
            val pixelCount = comparisonSize * comparisonSize
            val firstPixels = IntArray(pixelCount)
            val secondPixels = IntArray(pixelCount)
            requireNotNull(normalizedFirst).getPixels(firstPixels, 0, comparisonSize, 0, 0, comparisonSize, comparisonSize)
            requireNotNull(normalizedSecond).getPixels(secondPixels, 0, comparisonSize, 0, 0, comparisonSize, comparisonSize)

            var totalChannelDifference = 0L
            for (index in firstPixels.indices) {
                val firstColor = firstPixels[index]
                val secondColor = secondPixels[index]
                totalChannelDifference +=
                    abs(Color.red(firstColor) - Color.red(secondColor)) +
                        abs(Color.green(firstColor) - Color.green(secondColor)) +
                        abs(Color.blue(firstColor) - Color.blue(secondColor))
            }
            return (totalChannelDifference * 100.0 / (pixelCount.toLong() * 3L * 255L))
                .toFloat()
                .coerceIn(0f, 100f)
        } finally {
            normalizedFirst?.let { if (!it.isRecycled) it.recycle() }
            normalizedSecond?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun normalizePage(source: Bitmap, size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val scale = minOf(size.toFloat() / source.width, size.toFloat() / source.height)
            val width = source.width * scale
            val height = source.height * scale
            val left = (size - width) / 2f
            val top = (size - height) / 2f
            canvas.drawBitmap(
                source,
                null,
                RectF(left, top, left + width, top + height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        }
}
