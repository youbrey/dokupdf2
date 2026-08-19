package com.example.core.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.example.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Generates standards-compliant PDF documents from DocumentModel state
 */
class PdfGenerator(private val context: Context) {

    suspend fun exportToPdf(
        document: DocumentModel,
        outputFile: File,
        quality: Float = 1.0f
    ): Result<File> = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()
        try {
            for ((index, page) in document.pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    page.width.toInt().coerceAtLeast(100),
                    page.height.toInt().coerceAtLeast(100),
                    index + 1
                ).create()

                val pdfPage = pdfDoc.startPage(pageInfo)
                val canvas = pdfPage.canvas

                // Draw paper background
                canvas.drawColor(Color.WHITE)

                // 1. Draw Page Bitmap if available
                val rawBitmap = page.processedBitmap ?: page.originalBitmap
                if (rawBitmap != null && !rawBitmap.isRecycled) {
                    val bitmap = if (rawBitmap.width > 1600 || rawBitmap.height > 1600) {
                        val maxDim = 1600f
                        val longest = maxOf(rawBitmap.width, rawBitmap.height).toFloat()
                        val scale = maxDim / longest
                        Bitmap.createScaledBitmap(
                            rawBitmap,
                            (rawBitmap.width * scale).toInt().coerceAtLeast(100),
                            (rawBitmap.height * scale).toInt().coerceAtLeast(100),
                            true
                        )
                    } else rawBitmap

                    val matrix = Matrix()
                    if (page.rotationDegrees != 0) {
                        matrix.postRotate(
                            page.rotationDegrees.toFloat(),
                            bitmap.width / 2f,
                            bitmap.height / 2f
                        )
                    }
                    val scaleX = page.width / bitmap.width.toFloat()
                    val scaleY = page.height / bitmap.height.toFloat()
                    matrix.postScale(scaleX, scaleY)

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(bitmap, matrix, paint)

                    if (bitmap !== rawBitmap && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }

                // 2. Draw Vector Blocks if any
                drawBlocks(canvas, page.blocks, page.width, page.height)

                // 3. Draw Smart Eraser Strokes
                for (eraser in page.eraserStrokes) {
                    if (eraser.points.size > 1) {
                        val path = android.graphics.Path()
                        val first = eraser.points.first()
                        path.moveTo(first.x * page.width, first.y * page.height)
                        for (pt in eraser.points.drop(1)) {
                            path.lineTo(pt.x * page.width, pt.y * page.height)
                        }
                        val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = eraser.strokeWidth
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                        }
                        canvas.drawPath(path, eraserPaint)
                    }
                }

                // 4. Draw Pen & Highlight Annotations
                for (drawPath in page.drawPaths) {
                    if (drawPath.points.size > 1) {
                        val path = android.graphics.Path()
                        val first = drawPath.points.first()
                        path.moveTo(first.x * page.width, first.y * page.height)
                        for (pt in drawPath.points.drop(1)) {
                            path.lineTo(pt.x * page.width, pt.y * page.height)
                        }
                        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = drawPath.color.toInt()
                            if (drawPath.isHighlight) alpha = 100
                            style = Paint.Style.STROKE
                            strokeWidth = drawPath.strokeWidth
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                        }
                        canvas.drawPath(path, drawPaint)
                    }
                }

                // 5. Draw Signatures
                for (sig in page.signatures) {
                    val sigBitmap = sig.bitmap
                    if (sigBitmap != null && !sigBitmap.isRecycled) {
                        val sigW = page.width * sig.widthFraction
                        val sigH = page.height * sig.heightFraction
                        val sigLeft = (page.width * sig.normalizedX) - (sigW / 2f)
                        val sigTop = (page.height * sig.normalizedY) - (sigH / 2f)

                        val src = Rect(0, 0, sigBitmap.width, sigBitmap.height)
                        val dst = RectF(sigLeft, sigTop, sigLeft + sigW, sigTop + sigH)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        canvas.drawBitmap(sigBitmap, src, dst, paint)
                    }
                }

                // 6. Draw Watermarks
                for (wm in page.watermarks) {
                    drawWatermark(canvas, wm, page.width, page.height)
                }

                pdfDoc.finishPage(pdfPage)
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDoc.close()
        }
    }

    private fun drawWatermark(canvas: Canvas, wm: WatermarkAnnotation, width: Float, height: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            alpha = (wm.opacity * 255).toInt().coerceIn(0, 255)
            textSize = wm.fontSize * 1.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        canvas.save()
        if (wm.isTiled) {
            val stepX = width / 2f
            val stepY = height / 3f
            for (r in 0..3) {
                for (c in 0..2) {
                    canvas.save()
                    val px = c * stepX + (stepX / 2f)
                    val py = r * stepY + (stepY / 2f)
                    canvas.rotate(wm.rotationDegrees, px, py)
                    canvas.drawText(wm.text, px, py, paint)
                    canvas.restore()
                }
            }
        } else {
            val cx = width / 2f
            val cy = height / 2f
            canvas.rotate(wm.rotationDegrees, cx, cy)
            canvas.drawText(wm.text, cx, cy, paint)
        }
        canvas.restore()
    }

    private fun drawBlocks(canvas: Canvas, blocks: List<Block>, width: Float, height: Float) {
        var currentY = 48f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 14f * 2.2f
            typeface = Typeface.SANS_SERIF
        }

        for (block in blocks) {
            when (block) {
                is Block.ParagraphBlock -> {
                    val fullText = block.runs.joinToString("") { it.text }
                    if (fullText.isNotBlank()) {
                        canvas.drawText(fullText, 48f, currentY, textPaint)
                        currentY += 36f
                    }
                }
                is Block.TableBlock -> {
                    val cellW = (width - 96f) / block.cols.coerceAtLeast(1)
                    val cellH = 36f
                    val borderPaint = Paint().apply {
                        color = Color.LTGRAY
                        style = Paint.Style.STROKE
                        strokeWidth = 1.5f
                    }
                    for (r in 0 until block.rows) {
                        for (c in 0 until block.cols) {
                            val left = 48f + c * cellW
                            val top = currentY + r * cellH
                            canvas.drawRect(left, top, left + cellW, top + cellH, borderPaint)
                            val txt = block.cells.getOrNull(r)?.getOrNull(c) ?: ""
                            if (txt.isNotBlank()) {
                                canvas.drawText(txt, left + 8f, top + 24f, textPaint)
                            }
                        }
                    }
                    currentY += (block.rows * cellH) + 32f
                }
                is Block.ImageBlock -> {
                    block.bitmap?.let { img ->
                        canvas.drawBitmap(img, 48f, currentY, textPaint)
                        currentY += img.height + 32f
                    }
                }
            }
        }
    }
}
