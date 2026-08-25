package com.example.core.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.example.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Generates standards-compliant PDF documents from DocumentModel state
 */
class PdfGenerator(
    @Suppress("UNUSED_PARAMETER") context: Context,
    // [Audit fix -- babak 2] Lihat catatan lengkap di PdfConverterEngine.kt: mutex TIDAK
    // memperbaiki "document is closed!" di Robolectric, sehingga dispatcher di-inject agar
    // test bisa menjaga kode PdfDocument tetap di thread yang sama dengan test-nya.
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    private data class StyledFragment(val text: String, val run: TextRun)

    suspend fun exportToPdf(
        document: DocumentModel,
        outputFile: File,
        quality: Float = 1.0f
    ): Result<File> = withContext(ioDispatcher) {
        try {
            require(document.pages.isNotEmpty()) { "Dokumen tidak memiliki halaman" }
            require(quality.isFinite() && quality in 0.5f..2f) { "Kualitas PDF harus berada pada rentang 0.5-2.0" }

            PdfFileUtils.writeAtomically(outputFile, minimumBytes = 5L) { temporaryOutput ->
              // [Audit fix] Diserialkan lewat PdfFileUtils.pdfDocumentMutex -- lihat
              // PdfFileUtils.kt untuk alasan (PdfDocument didokumentasikan "not thread safe").
              PdfFileUtils.pdfDocumentMutex.withLock {
                val pdfDoc = PdfDocument()
                try {
                    for ((index, page) in document.pages.withIndex()) {
                require(
                    page.width.isFinite() && page.height.isFinite() &&
                        page.width in 1f..20_000f && page.height in 1f..20_000f
                ) {
                    "Ukuran halaman ${index + 1} tidak valid"
                }
                val pageInfo = PdfDocument.PageInfo.Builder(
                    page.width.toInt().coerceAtLeast(1),
                    page.height.toInt().coerceAtLeast(1),
                    index + 1
                ).create()

                val pdfPage = pdfDoc.startPage(pageInfo)
                val canvas = pdfPage.canvas

                // Draw paper background
                canvas.drawColor(Color.WHITE)

                // 1. Draw Page Bitmap if available
                val rawBitmap = page.processedBitmap ?: page.originalBitmap
                if (rawBitmap != null && !rawBitmap.isRecycled) {
                    var workingBitmap = rawBitmap
                    var ownsWorkingBitmap = false
                    try {
                        if (page.rotationDegrees % 360 != 0) {
                            val rotated = Bitmap.createBitmap(
                                workingBitmap,
                                0,
                                0,
                                workingBitmap.width,
                                workingBitmap.height,
                                Matrix().apply { postRotate(page.rotationDegrees.toFloat()) },
                                true
                            )
                            workingBitmap = rotated
                            ownsWorkingBitmap = rotated !== rawBitmap
                        }

                        // Keep the editor export path aligned with scanner export quality.
                        // 3008px is approximately 257 DPI on A4 and avoids the visibly soft
                        // 137-212 DPI ceiling used by older builds.
                        val maximumDimension = 3008f * quality
                        val longest = maxOf(workingBitmap.width, workingBitmap.height).toFloat()
                        if (longest > maximumDimension) {
                            val scale = maximumDimension / longest
                            val scaled = Bitmap.createScaledBitmap(
                                workingBitmap,
                                (workingBitmap.width * scale).toInt().coerceAtLeast(1),
                                (workingBitmap.height * scale).toInt().coerceAtLeast(1),
                                true
                            )
                            if (ownsWorkingBitmap && workingBitmap !== scaled && !workingBitmap.isRecycled) {
                                workingBitmap.recycle()
                            }
                            workingBitmap = scaled
                            ownsWorkingBitmap = scaled !== rawBitmap
                        }

                        val fitScale = minOf(
                            page.width / workingBitmap.width.toFloat(),
                            page.height / workingBitmap.height.toFloat()
                        )
                        val drawWidth = workingBitmap.width * fitScale
                        val drawHeight = workingBitmap.height * fitScale
                        val left = (page.width - drawWidth) / 2f
                        val top = (page.height - drawHeight) / 2f
                        val destination = RectF(left, top, left + drawWidth, top + drawHeight)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        canvas.drawBitmap(workingBitmap, null, destination, paint)
                    } finally {
                        if (ownsWorkingBitmap && workingBitmap !== rawBitmap && !workingBitmap.isRecycled) {
                            workingBitmap.recycle()
                        }
                    }
                }

                // 2. Draw Vector Blocks if any
                drawBlocks(canvas, page.blocks, page.width, page.height)

                // 3. Draw Smart Eraser Strokes
                for (eraser in page.eraserStrokes) {
                    val validPoints = eraser.points.filter { it.x.isFinite() && it.y.isFinite() }
                    if (validPoints.size > 1) {
                        val path = android.graphics.Path()
                        val first = validPoints.first()
                        path.moveTo(first.x * page.width, first.y * page.height)
                        for (pt in validPoints.drop(1)) {
                            path.lineTo(pt.x * page.width, pt.y * page.height)
                        }
                        val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = eraser.strokeWidth.coerceIn(1f, 200f)
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                        }
                        canvas.drawPath(path, eraserPaint)
                    }
                }

                // 4. Draw Pen & Highlight Annotations
                for (drawPath in page.drawPaths) {
                    val validPoints = drawPath.points.filter { it.x.isFinite() && it.y.isFinite() }
                    if (validPoints.size > 1) {
                        val path = android.graphics.Path()
                        val first = validPoints.first()
                        path.moveTo(first.x * page.width, first.y * page.height)
                        for (pt in validPoints.drop(1)) {
                            path.lineTo(pt.x * page.width, pt.y * page.height)
                        }
                        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = if (drawPath.isEraser) Color.WHITE else drawPath.color.toInt()
                            if (drawPath.isHighlight && !drawPath.isEraser) alpha = 100
                            style = Paint.Style.STROKE
                            strokeWidth = drawPath.strokeWidth.coerceIn(1f, 200f)
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
                        val sigW = page.width * sig.widthFraction.coerceIn(0.01f, 1f)
                        val sigH = page.height * sig.heightFraction.coerceIn(0.01f, 1f)
                        val centerX = page.width * sig.normalizedX.coerceIn(0f, 1f)
                        val centerY = page.height * sig.normalizedY.coerceIn(0f, 1f)
                        val sigLeft = (centerX - sigW / 2f).coerceIn(0f, page.width - sigW)
                        val sigTop = (centerY - sigH / 2f).coerceIn(0f, page.height - sigH)

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

                    FileOutputStream(temporaryOutput).use { out -> pdfDoc.writeTo(out) }
                } finally {
                    pdfDoc.close()
                }
              }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk mengekspor dokumen ke PDF", oom))
        }
    }

    private fun drawWatermark(canvas: Canvas, wm: WatermarkAnnotation, width: Float, height: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = wm.color.toInt()
            alpha = (wm.opacity * 255).toInt().coerceIn(0, 255)
            textSize = wm.fontSize.coerceIn(8f, 160f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        canvas.save()
        if (wm.isTiled) {
            val stepX = width / 2f
            val stepY = height / 3f
            for (r in 0 until 3) {
                for (c in 0 until 2) {
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
        val leftMargin = 48f
        val rightMargin = 48f
        val maximumWidth = (width - leftMargin - rightMargin).coerceAtLeast(1f)

        for (block in blocks) {
            if (currentY >= height - 48f) break
            when (block) {
                is Block.ParagraphBlock -> {
                    currentY = drawRichParagraph(
                        canvas = canvas,
                        block = block,
                        startY = currentY,
                        left = leftMargin,
                        maximumWidth = maximumWidth,
                        bottom = height - 48f
                    )
                }
                is Block.TableBlock -> {
                    val rows = block.rows.coerceIn(1, 500)
                    val columns = block.cols.coerceIn(1, 32)
                    val cellW = maximumWidth / columns
                    val cellH = 36f
                    val borderPaint = Paint().apply {
                        color = Color.LTGRAY
                        style = Paint.Style.STROKE
                        strokeWidth = 1.5f
                    }
                    val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.DKGRAY
                        textSize = 10f
                        typeface = Typeface.SANS_SERIF
                    }
                    var renderedRows = 0
                    for (r in 0 until rows) {
                        val top = currentY + r * cellH
                        if (top + cellH > height - 48f) break
                        for (c in 0 until columns) {
                            val cellLeft = leftMargin + c * cellW
                            canvas.drawRect(cellLeft, top, cellLeft + cellW, top + cellH, borderPaint)
                            val txt = block.cells.getOrNull(r)?.getOrNull(c) ?: ""
                            if (txt.isNotBlank()) {
                                canvas.drawText(
                                    ellipsize(txt.replace('\n', ' '), cellPaint, (cellW - 12f).coerceAtLeast(1f)),
                                    cellLeft + 6f,
                                    top + 23f,
                                    cellPaint
                                )
                            }
                        }
                        renderedRows++
                    }
                    currentY += (renderedRows * cellH) + 24f
                }
                is Block.ImageBlock -> {
                    block.bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.let { image ->
                        val requestedWidth = block.width.takeIf { it.isFinite() && it > 0f } ?: image.width.toFloat()
                        val requestedHeight = block.height.takeIf { it.isFinite() && it > 0f } ?: image.height.toFloat()
                        val fitScale = minOf(
                            1f,
                            maximumWidth / requestedWidth,
                            (height - 48f - currentY).coerceAtLeast(1f) / requestedHeight
                        )
                        val drawWidth = requestedWidth * fitScale
                        val drawHeight = requestedHeight * fitScale
                        val destination = RectF(
                            leftMargin,
                            currentY,
                            leftMargin + drawWidth,
                            currentY + drawHeight
                        )
                        canvas.drawBitmap(
                            image,
                            null,
                            destination,
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        )
                        currentY += drawHeight + 24f
                    }
                }
            }
        }
    }

    private fun drawRichParagraph(
        canvas: Canvas,
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

        for (run in block.runs) {
            val paint = paintFor(run)
            val tokens = Regex("\\n|[^\\S\\n]+|[^\\s]+")
                .findAll(run.text)
                .map { it.value }
            for (rawToken in tokens) {
                if (rawToken == "\n") {
                    startNewLine()
                    continue
                }
                var token = rawToken
                if (currentLineWidth == 0f && token.isBlank()) continue
                while (token.isNotEmpty()) {
                    val available = (maximumWidth - currentLineWidth).coerceAtLeast(1f)
                    var count = paint.breakText(token, true, available, null)
                    if (count <= 0 && lines.last().isNotEmpty()) {
                        startNewLine()
                        continue
                    }
                    count = count.coerceAtLeast(1)
                    val piece = token.take(count)
                    if (piece.isNotBlank() || lines.last().isNotEmpty()) {
                        lines.last() += StyledFragment(piece, run)
                        currentLineWidth += paint.measureText(piece)
                    }
                    token = token.drop(count)
                    if (token.isNotEmpty()) startNewLine()
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
            val measuredWidth = line.sumOf { fragment ->
                paintFor(fragment.run).measureText(fragment.text).toDouble()
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
            } else {
                0f
            }
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

    private fun paintFor(run: TextRun): Paint {
        val style = when {
            run.isBold && run.isItalic -> Typeface.BOLD_ITALIC
            run.isBold -> Typeface.BOLD
            run.isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = run.color.toInt()
            textSize = run.fontSize.coerceIn(8f, 72f)
            typeface = Typeface.create(Typeface.SANS_SERIF, style)
            isUnderlineText = run.isUnderline
        }
    }

    private fun ellipsize(value: String, paint: Paint, maximumWidth: Float): String {
        if (paint.measureText(value) <= maximumWidth) return value
        val ellipsis = "…"
        val available = (maximumWidth - paint.measureText(ellipsis)).coerceAtLeast(1f)
        val count = paint.breakText(value, true, available, null).coerceAtLeast(0)
        return value.take(count).trimEnd() + ellipsis
    }
}
