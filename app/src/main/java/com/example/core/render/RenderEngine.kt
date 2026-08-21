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

    private data class StyledFragment(val text: String, val run: TextRun)

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
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                val nativeCanvas = canvas.nativeCanvas
                val normalizedRotation = ((page.rotationDegrees % 360) + 360) % 360
                val swapsAxes = normalizedRotation == 90 || normalizedRotation == 270
                val availableWidth = if (swapsAxes) bounds.height else bounds.width
                val availableHeight = if (swapsAxes) bounds.width else bounds.height
                val scale = minOf(
                    availableWidth / bitmap.width.toFloat(),
                    availableHeight / bitmap.height.toFloat()
                )
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                nativeCanvas.save()
                nativeCanvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                nativeCanvas.translate(bounds.center.x, bounds.center.y)
                nativeCanvas.rotate(normalizedRotation.toFloat())
                nativeCanvas.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(-drawWidth / 2f, -drawHeight / 2f, drawWidth / 2f, drawHeight / 2f),
                    paint
                )
                nativeCanvas.restore()
            }
        }
        // Vector blocks are overlays for scanned/imported pages and primary content for blank pages.
        if (page.blocks.isNotEmpty()) renderVectorBlocks(drawScope, page.blocks, bounds)

        // 3. Draw Smart Eraser / Whiteout Strokes
        for (eraser in page.eraserStrokes) {
            val validPoints = eraser.points.filter { it.x.isFinite() && it.y.isFinite() }
            if (validPoints.size > 1) {
                val path = Path()
                val first = toAbsoluteCoord(validPoints.first(), bounds)
                path.moveTo(first.x, first.y)
                for (pt in validPoints.drop(1)) {
                    val abs = toAbsoluteCoord(pt, bounds)
                    path.lineTo(abs.x, abs.y)
                }
                drawScope.drawPath(
                    path = path,
                    color = Color.White,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = eraser.strokeWidth.coerceIn(1f, 200f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // 4. Draw Freehand Pen Annotations & Highlighters
        for (drawPath in page.drawPaths) {
            val validPoints = drawPath.points.filter { it.x.isFinite() && it.y.isFinite() }
            if (validPoints.size > 1) {
                val path = Path()
                val first = toAbsoluteCoord(validPoints.first(), bounds)
                path.moveTo(first.x, first.y)
                for (pt in validPoints.drop(1)) {
                    val abs = toAbsoluteCoord(pt, bounds)
                    path.lineTo(abs.x, abs.y)
                }
                val color = if (drawPath.isEraser) {
                    Color.White
                } else if (drawPath.isHighlight) {
                    Color(drawPath.color).copy(alpha = 0.4f)
                } else {
                    Color(drawPath.color)
                }
                drawScope.drawPath(
                    path = path,
                    color = color,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = drawPath.strokeWidth.coerceIn(1f, 200f),
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
                val sigWidth = bounds.width * sig.widthFraction.coerceIn(0.01f, 1f)
                val sigHeight = bounds.height * sig.heightFraction.coerceIn(0.01f, 1f)
                val centerX = bounds.left + bounds.width * sig.normalizedX.coerceIn(0f, 1f)
                val centerY = bounds.top + bounds.height * sig.normalizedY.coerceIn(0f, 1f)
                val sigLeft = (centerX - sigWidth / 2f).coerceIn(bounds.left, bounds.right - sigWidth)
                val sigTop = (centerY - sigHeight / 2f).coerceIn(bounds.top, bounds.bottom - sigHeight)

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
                color = watermark.color.toInt()
                alpha = (watermark.opacity * 255).toInt().coerceIn(0, 255)
                textSize = watermark.fontSize.coerceIn(8f, 160f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = AndroidPaint.Align.CENTER
            }

            canvas.nativeCanvas.save()
            if (watermark.isTiled) {
                // Diagonal multi-line repeating watermark
                val stepX = bounds.width / 2f
                val stepY = bounds.height / 3f
                for (row in 0 until 3) {
                    for (col in 0 until 2) {
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
            val left = bounds.left + 32f
            val maximumWidth = (bounds.width - 64f).coerceAtLeast(1f)

            for (block in blocks) {
                if (currentY >= bounds.bottom - 32f) break
                when (block) {
                    is Block.ParagraphBlock -> {
                        currentY = drawRichParagraph(
                            canvas.nativeCanvas,
                            block,
                            currentY,
                            left,
                            maximumWidth,
                            bounds.bottom - 32f
                        )
                    }
                    is Block.TableBlock -> {
                        val rows = block.rows.coerceIn(1, 500)
                        val columns = block.cols.coerceIn(1, 32)
                        val cellW = maximumWidth / columns
                        val cellH = 32f
                        val borderPaint = AndroidPaint().apply {
                            color = android.graphics.Color.LTGRAY
                            style = AndroidPaint.Style.STROKE
                            strokeWidth = 1.5f
                        }
                        val cellPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.DKGRAY
                            textSize = 10f
                            typeface = Typeface.SANS_SERIF
                        }
                        var renderedRows = 0
                        for (r in 0 until rows) {
                            val top = currentY + r * cellH
                            if (top + cellH > bounds.bottom - 32f) break
                            for (c in 0 until columns) {
                                val cellLeft = left + c * cellW
                                canvas.nativeCanvas.drawRect(cellLeft, top, cellLeft + cellW, top + cellH, borderPaint)
                                val cellText = block.cells.getOrNull(r)?.getOrNull(c) ?: ""
                                if (cellText.isNotBlank()) {
                                    canvas.nativeCanvas.drawText(
                                        ellipsize(cellText.replace('\n', ' '), cellPaint, (cellW - 10f).coerceAtLeast(1f)),
                                        cellLeft + 5f,
                                        top + 21f,
                                        cellPaint
                                    )
                                }
                            }
                            renderedRows++
                        }
                        currentY += (renderedRows * cellH) + 20f
                    }
                    is Block.ImageBlock -> {
                        block.bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.let { img ->
                            val requestedWidth = block.width.takeIf { it.isFinite() && it > 0f } ?: img.width.toFloat()
                            val requestedHeight = block.height.takeIf { it.isFinite() && it > 0f } ?: img.height.toFloat()
                            val availableWidth = (bounds.width - 64f).coerceAtLeast(1f)
                            val availableHeight = (bounds.bottom - currentY - 32f).coerceAtLeast(1f)
                            val scale = minOf(1f, availableWidth / requestedWidth, availableHeight / requestedHeight)
                            val width = requestedWidth * scale
                            val height = requestedHeight * scale
                            canvas.nativeCanvas.drawBitmap(
                                img,
                                null,
                                android.graphics.RectF(
                                    bounds.left + 32f,
                                    currentY,
                                    bounds.left + 32f + width,
                                    currentY + height
                                ),
                                AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
                            )
                            currentY += height + 24f
                        }
                    }
                }
            }
        }
    }

    private fun drawRichParagraph(
        canvas: android.graphics.Canvas,
        block: Block.ParagraphBlock,
        startY: Float,
        left: Float,
        maximumWidth: Float,
        bottom: Float
    ): Float {
        val lines = mutableListOf<MutableList<StyledFragment>>(mutableListOf())
        var currentLineWidth = 0f

        fun startNewLine() {
            if (lines.last().isNotEmpty()) lines.add(mutableListOf())
            currentLineWidth = 0f
        }

        block.runs.forEach { run ->
            val paint = paintFor(run)
            Regex("\\n|[^\\S\\n]+|[^\\s]+")
                .findAll(run.text)
                .map { it.value }
                .forEach { rawToken ->
                    if (rawToken == "\n") {
                        startNewLine()
                    } else {
                        var token = rawToken
                        if (currentLineWidth == 0f && token.isBlank()) token = ""
                        while (token.isNotEmpty()) {
                            val available = (maximumWidth - currentLineWidth).coerceAtLeast(1f)
                            var count = paint.breakText(token, true, available, null)
                            if (count <= 0 && lines.last().isNotEmpty()) {
                                startNewLine()
                                continue
                            }
                            count = count.coerceAtLeast(1)
                            val piece = token.take(count)
                            lines.last().add(StyledFragment(piece, run))
                            currentLineWidth += paint.measureText(piece)
                            token = token.drop(count)
                            if (token.isNotEmpty()) startNewLine()
                        }
                    }
                }
        }

        if (lines.lastOrNull()?.isEmpty() == true && lines.size > 1) lines.removeAt(lines.lastIndex)
        var baseline = startY
        for ((lineIndex, line) in lines.withIndex()) {
            if (line.isEmpty()) {
                baseline += 18f
                continue
            }
            val lineHeight = line.maxOf { it.run.fontSize.coerceIn(8f, 72f) } * 1.35f
            baseline += lineHeight
            if (baseline > bottom) break
            val measuredWidth = line.sumOf {
                paintFor(it.run).measureText(it.text).toDouble()
            }.toFloat()
            var x = when (block.alignment) {
                1 -> left + (maximumWidth - measuredWidth) / 2f
                2 -> left + maximumWidth - measuredWidth
                else -> left
            }.coerceAtLeast(left)
            val expandableSpaces = line.count {
                it.text.isNotEmpty() && it.text.all { character -> character.isWhitespace() }
            }
            val extraSpace = if (
                block.alignment == 3 && lineIndex < lines.lastIndex && expandableSpaces > 0
            ) {
                ((maximumWidth - measuredWidth) / expandableSpaces).coerceAtLeast(0f)
            } else 0f
            line.forEach { fragment ->
                val paint = paintFor(fragment.run)
                canvas.drawText(fragment.text, x, baseline, paint)
                x += paint.measureText(fragment.text)
                if (
                    fragment.text.isNotEmpty() &&
                    fragment.text.all { character -> character.isWhitespace() }
                ) x += extraSpace
            }
        }
        return (baseline + 18f).coerceAtMost(bottom)
    }

    private fun paintFor(run: TextRun): AndroidPaint {
        val style = when {
            run.isBold && run.isItalic -> Typeface.BOLD_ITALIC
            run.isBold -> Typeface.BOLD
            run.isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = run.color.toInt()
            textSize = run.fontSize.coerceIn(8f, 72f)
            typeface = Typeface.create(Typeface.SANS_SERIF, style)
            isUnderlineText = run.isUnderline
        }
    }

    private fun ellipsize(value: String, paint: AndroidPaint, maximumWidth: Float): String {
        if (paint.measureText(value) <= maximumWidth) return value
        val ellipsis = "…"
        val available = (maximumWidth - paint.measureText(ellipsis)).coerceAtLeast(1f)
        val count = paint.breakText(value, true, available, null).coerceAtLeast(0)
        return value.take(count).trimEnd() + ellipsis
    }

    private fun toAbsoluteCoord(normPoint: Offset, bounds: Rect): Offset {
        return Offset(
            bounds.left + (normPoint.x * bounds.width),
            bounds.top + (normPoint.y * bounds.height)
        )
    }
}
