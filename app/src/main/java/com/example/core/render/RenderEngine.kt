package com.example.core.render

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.core.layout.PageLayoutInfo
import com.example.core.model.*

/**
 * Custom Canvas Render Engine for rendering document sheets, bitmaps, text, annotations, signatures, and watermarks
 */
class RenderEngine {

    fun renderDocument(
        drawScope: DrawScope,
        pages: List<PageModel>,
        pageLayouts: List<PageLayoutInfo>,
        selectedPageIndex: Int = 0,
        showSelectionBorder: Boolean = true
    ) {
        for (layoutInfo in pageLayouts) {
            val page = pages.getOrNull(layoutInfo.pageIndex) ?: continue
            renderPage(
                drawScope = drawScope,
                page = page,
                layoutInfo = layoutInfo,
                isSelected = layoutInfo.pageIndex == selectedPageIndex && showSelectionBorder
            )
        }
    }

    fun renderPage(
        drawScope: DrawScope,
        page: PageModel,
        layoutInfo: PageLayoutInfo,
        isSelected: Boolean = false
    ) {
        val bounds = layoutInfo.bounds

        // 1. Draw Paper Shadow & Sheet
        drawScope.drawRect(
            color = Color(0x18000000),
            topLeft = Offset(bounds.left + 4f, bounds.top + 6f),
            size = bounds.size
        )
        drawScope.drawRect(
            color = Color.White,
            topLeft = bounds.topLeft,
            size = bounds.size
        )
        drawScope.drawRect(
            color = Color(0xFFE2E8F0),
            topLeft = bounds.topLeft,
            size = bounds.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
        )

        // 2. Draw Page Content (Bitmap or Vector blocks)
        val bitmap = page.processedBitmap ?: page.originalBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            drawScope.drawIntoCanvas { canvas ->
                val matrix = android.graphics.Matrix()
                // Rotate if needed
                if (page.rotationDegrees != 0) {
                    matrix.postRotate(
                        page.rotationDegrees.toFloat(),
                        bitmap.width / 2f,
                        bitmap.height / 2f
                    )
                }

                val scaleX = bounds.width / bitmap.width.toFloat()
                val scaleY = bounds.height / bitmap.height.toFloat()
                matrix.postScale(scaleX, scaleY)
                matrix.postTranslate(bounds.left, bounds.top)

                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                canvas.nativeCanvas.drawBitmap(bitmap, matrix, paint)
            }
        } else {
            // Draw vector blocks if any
            renderVectorBlocks(drawScope, page.blocks, bounds)
        }

        // 3. Draw Smart Eraser / Whiteout Strokes
        for (eraser in page.eraserStrokes) {
            if (eraser.points.size > 1) {
                val path = Path()
                val first = toAbsoluteCoord(eraser.points.first(), bounds)
                path.moveTo(first.x, first.y)
                for (pt in eraser.points.drop(1)) {
                    val abs = toAbsoluteCoord(pt, bounds)
                    path.lineTo(abs.x, abs.y)
                }
                drawScope.drawPath(
                    path = path,
                    color = Color.White,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = eraser.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // 4. Draw Freehand Pen Annotations & Highlighters
        for (drawPath in page.drawPaths) {
            if (drawPath.points.size > 1) {
                val path = Path()
                val first = toAbsoluteCoord(drawPath.points.first(), bounds)
                path.moveTo(first.x, first.y)
                for (pt in drawPath.points.drop(1)) {
                    val abs = toAbsoluteCoord(pt, bounds)
                    path.lineTo(abs.x, abs.y)
                }
                val color = if (drawPath.isHighlight) {
                    Color(drawPath.color).copy(alpha = 0.4f)
                } else {
                    Color(drawPath.color)
                }
                drawScope.drawPath(
                    path = path,
                    color = color,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = drawPath.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // 5. Draw Signatures
        for (sig in page.signatures) {
            val sigBitmap = sig.bitmap
            if (sigBitmap != null && !sigBitmap.isRecycled) {
                val sigWidth = bounds.width * sig.widthFraction
                val sigHeight = bounds.height * sig.heightFraction
                val sigLeft = bounds.left + (bounds.width * sig.normalizedX) - (sigWidth / 2f)
                val sigTop = bounds.top + (bounds.height * sig.normalizedY) - (sigHeight / 2f)

                drawScope.drawIntoCanvas { canvas ->
                    val src = android.graphics.Rect(0, 0, sigBitmap.width, sigBitmap.height)
                    val dst = android.graphics.RectF(sigLeft, sigTop, sigLeft + sigWidth, sigTop + sigHeight)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                    canvas.nativeCanvas.drawBitmap(sigBitmap, src, dst, paint)
                }
            }
        }

        // 6. Draw Watermarks
        for (wm in page.watermarks) {
            renderWatermark(drawScope, wm, bounds)
        }

        // 7. Draw Selection Border if Active
        if (isSelected) {
            drawScope.drawRect(
                color = Color(0xFF00897B),
                topLeft = bounds.topLeft,
                size = bounds.size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
            )
        }
    }

    private fun renderWatermark(drawScope: DrawScope, watermark: WatermarkAnnotation, bounds: Rect) {
        drawScope.drawIntoCanvas { canvas ->
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.GRAY
                alpha = (watermark.opacity * 255).toInt().coerceIn(0, 255)
                textSize = watermark.fontSize * 1.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = AndroidPaint.Align.CENTER
            }

            canvas.nativeCanvas.save()
            if (watermark.isTiled) {
                // Diagonal multi-line repeating watermark
                val stepX = bounds.width / 2f
                val stepY = bounds.height / 3f
                for (row in 0..3) {
                    for (col in 0..2) {
                        canvas.nativeCanvas.save()
                        val posX = bounds.left + col * stepX + (stepX / 2f)
                        val posY = bounds.top + row * stepY + (stepY / 2f)
                        canvas.nativeCanvas.rotate(watermark.rotationDegrees, posX, posY)
                        canvas.nativeCanvas.drawText(watermark.text, posX, posY, paint)
                        canvas.nativeCanvas.restore()
                    }
                }
            } else {
                // Single large center watermark
                val centerX = bounds.center.x
                val centerY = bounds.center.y
                canvas.nativeCanvas.rotate(watermark.rotationDegrees, centerX, centerY)
                canvas.nativeCanvas.drawText(watermark.text, centerX, centerY, paint)
            }
            canvas.nativeCanvas.restore()
        }
    }

    private fun renderVectorBlocks(drawScope: DrawScope, blocks: List<Block>, bounds: Rect) {
        drawScope.drawIntoCanvas { canvas ->
            var currentY = bounds.top + 32f
            val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.DKGRAY
                textSize = 14f * 2.5f
                typeface = Typeface.SANS_SERIF
            }

            for (block in blocks) {
                when (block) {
                    is Block.ParagraphBlock -> {
                        val fullText = block.runs.joinToString("") { it.text }
                        if (fullText.isNotBlank()) {
                            canvas.nativeCanvas.drawText(fullText, bounds.left + 32f, currentY, textPaint)
                            currentY += 40f
                        }
                    }
                    is Block.TableBlock -> {
                        // Render grid
                        val cellW = (bounds.width - 64f) / block.cols.coerceAtLeast(1)
                        val cellH = 32f
                        val borderPaint = AndroidPaint().apply {
                            color = android.graphics.Color.LTGRAY
                            style = AndroidPaint.Style.STROKE
                            strokeWidth = 1.5f
                        }
                        for (r in 0 until block.rows) {
                            for (c in 0 until block.cols) {
                                val left = bounds.left + 32f + c * cellW
                                val top = currentY + r * cellH
                                canvas.nativeCanvas.drawRect(left, top, left + cellW, top + cellH, borderPaint)
                                val cellText = block.cells.getOrNull(r)?.getOrNull(c) ?: ""
                                if (cellText.isNotBlank()) {
                                    canvas.nativeCanvas.drawText(cellText, left + 8f, top + 22f, textPaint)
                                }
                            }
                        }
                        currentY += (block.rows * cellH) + 24f
                    }
                    is Block.ImageBlock -> {
                        block.bitmap?.let { img ->
                            canvas.nativeCanvas.drawBitmap(img, bounds.left + 32f, currentY, textPaint)
                            currentY += img.height + 24f
                        }
                    }
                }
            }
        }
    }

    private fun toAbsoluteCoord(normPoint: Offset, bounds: Rect): Offset {
        return Offset(
            bounds.left + (normPoint.x * bounds.width),
            bounds.top + (normPoint.y * bounds.height)
        )
    }
}
