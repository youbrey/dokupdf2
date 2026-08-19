package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PageComparisonResult(
    val pageIndex: Int,
    val bitmapA: Bitmap,
    val bitmapB: Bitmap,
    val diffHeatmapBitmap: Bitmap,
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
    private val context: Context,
    private val rendererEngine: PdfRendererEngine = PdfRendererEngine(context)
) {

    suspend fun comparePdfs(
        fileA: File,
        fileB: File
    ): Result<DocumentComparisonResult> = withContext(Dispatchers.IO) {
        try {
            val pagesA = rendererEngine.renderPdfPages(fileA, scale = 1.5f)
            val pagesB = rendererEngine.renderPdfPages(fileB, scale = 1.5f)

            val maxPages = max(pagesA.size, pagesB.size)
            val results = mutableListOf<PageComparisonResult>()
            var totalDiff = 0f

            for (i in 0 until maxPages) {
                val bmA = pagesA.getOrNull(i) ?: createBlankBitmap(600, 800)
                val bmB = pagesB.getOrNull(i) ?: createBlankBitmap(600, 800)

                val (diffBmp, diffPercent) = generatePixelDiff(bmA, bmB)
                totalDiff += diffPercent

                results.add(
                    PageComparisonResult(
                        pageIndex = i,
                        bitmapA = bmA,
                        bitmapB = bmB,
                        diffHeatmapBitmap = diffBmp,
                        differencePercentage = diffPercent,
                        hasDifferences = diffPercent > 0.05f
                    )
                )
            }

            val avgDiff = if (maxPages > 0) totalDiff / maxPages else 0f
            val similarity = (100f - avgDiff).coerceIn(0f, 100f)

            Result.success(
                DocumentComparisonResult(
                    fileA = fileA,
                    fileB = fileB,
                    pageResults = results,
                    overallSimilarityPercentage = similarity
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generatePixelDiff(bmp1: Bitmap, bmp2: Bitmap): Pair<Bitmap, Float> {
        val w = min(bmp1.width, bmp2.width)
        val h = min(bmp1.height, bmp2.height)

        val diffBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(diffBitmap)

        // Draw dim base image
        val basePaint = Paint().apply { alpha = 130 }
        canvas.drawBitmap(bmp2, 0f, 0f, basePaint)

        val pixels1 = IntArray(w * h)
        val pixels2 = IntArray(w * h)
        bmp1.getPixels(pixels1, 0, w, 0, 0, w, h)
        bmp2.getPixels(pixels2, 0, w, 0, 0, w, h)

        var diffPixelCount = 0
        val diffPixels = IntArray(w * h)

        for (i in 0 until w * h) {
            val c1 = pixels1[i]
            val c2 = pixels2[i]

            val rDiff = abs(Color.red(c1) - Color.red(c2))
            val gDiff = abs(Color.green(c1) - Color.green(c2))
            val bDiff = abs(Color.blue(c1) - Color.blue(c2))

            if (rDiff + gDiff + bDiff > 45) {
                diffPixelCount++
                // Highlight difference in bold semi-transparent red/magenta
                diffPixels[i] = Color.argb(200, 239, 68, 68)
            } else {
                diffPixels[i] = Color.TRANSPARENT
            }
        }

        val overlayBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        overlayBmp.setPixels(diffPixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(overlayBmp, 0f, 0f, null)

        val diffPercent = (diffPixelCount.toFloat() / (w * h)) * 100f
        return Pair(diffBitmap, diffPercent)
    }

    private fun createBlankBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        return bmp
    }
}
