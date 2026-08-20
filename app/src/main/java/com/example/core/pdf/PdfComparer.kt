package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
                val pagesA = rendererEngine.renderPdfPages(fileA, scale = 1.5f)
                val pagesB = rendererEngine.renderPdfPages(fileB, scale = 1.5f)
                try {
                    val maxPages = max(pagesA.size, pagesB.size)
                    require(maxPages > 0) { "Tidak ada halaman PDF yang dapat dibandingkan" }
                    val results = ArrayList<PageComparisonResult>(maxPages)
                    var totalDifference = 0f

                    for (index in 0 until maxPages) {
                        val generatedA = pagesA.getOrNull(index) == null
                        val generatedB = pagesB.getOrNull(index) == null
                        val bitmapA = pagesA.getOrNull(index) ?: createBlankBitmap(600, 800)
                        val bitmapB = pagesB.getOrNull(index) ?: createBlankBitmap(600, 800)
                        try {
                            val difference = calculatePixelDifference(bitmapA, bitmapB)
                            totalDifference += difference
                            results += PageComparisonResult(
                                pageIndex = index,
                                differencePercentage = difference,
                                hasDifferences = difference > 0.05f
                            )
                        } finally {
                            if (generatedA && !bitmapA.isRecycled) bitmapA.recycle()
                            if (generatedB && !bitmapB.isRecycled) bitmapB.recycle()
                        }
                    }

                    DocumentComparisonResult(
                        fileA = fileA,
                        fileB = fileB,
                        pageResults = results,
                        overallSimilarityPercentage = (100f - totalDifference / maxPages).coerceIn(0f, 100f)
                    )
                } finally {
                    pagesA.forEach { if (!it.isRecycled) it.recycle() }
                    pagesB.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
        }

    private fun calculatePixelDifference(first: Bitmap, second: Bitmap): Float {
        val width = min(first.width, second.width)
        val height = min(first.height, second.height)
        require(width > 0 && height > 0) { "Ukuran halaman tidak valid" }

        val firstPixels = IntArray(width * height)
        val secondPixels = IntArray(width * height)
        first.getPixels(firstPixels, 0, width, 0, 0, width, height)
        second.getPixels(secondPixels, 0, width, 0, 0, width, height)

        var differentPixels = 0
        for (index in firstPixels.indices) {
            val firstColor = firstPixels[index]
            val secondColor = secondPixels[index]
            val colorDifference =
                abs(Color.red(firstColor) - Color.red(secondColor)) +
                    abs(Color.green(firstColor) - Color.green(secondColor)) +
                    abs(Color.blue(firstColor) - Color.blue(secondColor))
            if (colorDifference > 45) differentPixels++
        }
        return differentPixels.toFloat() * 100f / firstPixels.size
    }

    private fun createBlankBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
        }
}
