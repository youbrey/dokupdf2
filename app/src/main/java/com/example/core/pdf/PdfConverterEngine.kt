package com.example.core.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.example.core.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Universal document converter engine supporting Word (DOCX/TXT), Excel (CSV/XLS), and Image pipelines.
 * Creates standard OOXML ZIP packages for DOCX and renders pristine PDF documents.
 */
class PdfConverterEngine(
    private val context: Context,
    private val pdfRenderer: PdfRendererEngine = PdfRendererEngine(context),
    private val ocrEngine: OcrEngine = OcrEngine(context)
) {

    /**
     * Maps a bitmap's raw pixel dimensions to sane PDF page dimensions, in points.
     */
    private fun pdfPageSizePt(bitmap: Bitmap): Pair<Int, Int> {
        val a4LongEdgePt = 841.89f
        val longestPx = maxOf(bitmap.width, bitmap.height).toFloat().coerceAtLeast(1f)
        val scale = a4LongEdgePt / longestPx
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    private val pageDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Resamples and compresses a bitmap into a memory-efficient and compact JPEG stream
     * for high-clarity, ultra-low byte size PDF embedding.
     */
    private fun prepareOptimizedBitmapForPdf(source: Bitmap): Bitmap {
        val maxDim = 1600f
        val longest = maxOf(source.width, source.height).toFloat()
        val scaled = if (longest > maxDim) {
            val scale = maxDim / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(100),
                (source.height * scale).toInt().coerceAtLeast(100),
                true
            )
        } else {
            source
        }

        // Compress via JPEG stream to strip 32-bit uncompressed raster bloat
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)
        val bytes = stream.toByteArray()
        val optimized = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        if (scaled !== source && !scaled.isRecycled) {
            scaled.recycle()
        }
        return optimized ?: source
    }

    /**
     * Converts a list of image files into a single unified PDF
     */
    suspend fun imagesToPdf(
        imageFiles: List<File>,
        outputPdf: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()
        try {
            var pageIndex = 1
            for (file in imageFiles) {
                val rawBmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val bitmap = prepareOptimizedBitmapForPdf(rawBmp)
                if (rawBmp !== bitmap && !rawBmp.isRecycled) {
                    rawBmp.recycle()
                }

                val (pageW, pageH) = pdfPageSizePt(bitmap)
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageW,
                    pageH,
                    pageIndex++
                ).create()

                val page = pdfDoc.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, null, Rect(0, 0, pageW, pageH), pageDrawPaint)
                pdfDoc.finishPage(page)

                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }

            if (pageIndex == 1) {
                return@withContext Result.failure(Exception("Tidak ada gambar valid yang dapat diproses"))
            }

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { out ->
                pdfDoc.writeTo(out)
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDoc.close()
        }
    }

    /**
     * Converts a list of Bitmaps directly into a single multi-page PDF.
     * Compresses rasters efficiently so multi-page PDFs are crisp yet only a few hundred KBs.
     */
    suspend fun bitmapsToPdf(
        bitmaps: List<Bitmap>,
        outputPdf: File,
        recycleSource: Boolean = true
    ): Result<File> = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()
        try {
            if (bitmaps.isEmpty()) {
                return@withContext Result.failure(Exception("Daftar gambar kosong"))
            }

            for ((index, sourceBmp) in bitmaps.withIndex()) {
                val optimizedBmp = prepareOptimizedBitmapForPdf(sourceBmp)
                val (pageW, pageH) = pdfPageSizePt(optimizedBmp)
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageW,
                    pageH,
                    index + 1
                ).create()

                val page = pdfDoc.startPage(pageInfo)
                page.canvas.drawBitmap(optimizedBmp, null, Rect(0, 0, pageW, pageH), pageDrawPaint)
                pdfDoc.finishPage(page)

                if (optimizedBmp !== sourceBmp && !optimizedBmp.isRecycled) {
                    optimizedBmp.recycle()
                }
                if (recycleSource && !sourceBmp.isRecycled) {
                    sourceBmp.recycle()
                }
            }

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { out ->
                pdfDoc.writeTo(out)
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(Exception("Memori tidak cukup untuk membuat PDF. Coba kurangi jumlah halaman atau nonaktifkan mode kualitas HD.", oom))
        } finally {
            pdfDoc.close()
        }
    }

    /**
     * Converts PDF pages into individual image files (PNG/JPEG)
     */
    suspend fun pdfToImages(
        sourcePdf: File,
        outputDir: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 90
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()
            val bitmaps = pdfRenderer.renderPdfPages(sourcePdf, scale = 2.0f)
            if (bitmaps.isEmpty()) {
                return@withContext Result.failure(Exception("Tidak ada halaman yang dapat dirender dari PDF"))
            }

            val files = mutableListOf<File>()
            val ext = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
            val baseName = sourcePdf.nameWithoutExtension

            for ((i, bmp) in bitmaps.withIndex()) {
                val file = File(outputDir, "${baseName}_page_${i + 1}.$ext")
                FileOutputStream(file).use { out ->
                    bmp.compress(format, quality, out)
                }
                files.add(file)
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Stitches all PDF pages vertically into one continuous long image (CamScanner 'PDF ke Gambar Panjang')
     */
    suspend fun pdfToLongImage(
        sourcePdf: File,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val bitmaps = pdfRenderer.renderPdfPages(sourcePdf, scale = 1.5f)
            if (bitmaps.isEmpty()) {
                return@withContext Result.failure(Exception("PDF tidak memiliki halaman"))
            }

            val totalWidth = bitmaps.maxOf { it.width }
            val totalHeight = bitmaps.sumOf { it.height }

            val stitched = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stitched)
            canvas.drawColor(Color.WHITE)

            var currentY = 0f
            for (bmp in bitmaps) {
                val left = (totalWidth - bmp.width) / 2f
                canvas.drawBitmap(bmp, left, currentY, null)
                currentY += bmp.height
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                stitched.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rotates all pages in a PDF by a specified angle (90, 180, 270)
     */
    suspend fun rotatePdf(
        sourcePdf: File,
        outputPdf: File,
        degrees: Int
    ): Result<File> = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()
        try {
            val bitmaps = pdfRenderer.renderPdfPages(sourcePdf, scale = 2.0f)
            if (bitmaps.isEmpty()) {
                return@withContext Result.failure(Exception("PDF tidak memiliki halaman untuk diputar"))
            }

            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }

            for ((index, bmp) in bitmaps.withIndex()) {
                val rotatedBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                val (pageW, pageH) = pdfPageSizePt(rotatedBmp)
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageW,
                    pageH,
                    index + 1
                ).create()

                val page = pdfDoc.startPage(pageInfo)
                page.canvas.drawBitmap(rotatedBmp, null, Rect(0, 0, pageW, pageH), pageDrawPaint)
                pdfDoc.finishPage(page)
                rotatedBmp.recycle()
            }

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { out ->
                pdfDoc.writeTo(out)
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDoc.close()
        }
    }

    /**
     * Converts PDF to a genuine, standard OpenXML Word Document (.docx ZIP format)
     */
    suspend fun pdfToDocx(
        sourcePdf: File,
        outputDocxFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val bitmaps = pdfRenderer.renderPdfPages(sourcePdf, scale = 2.0f)
            val extractedTextList = mutableListOf<String>()

            for (bmp in bitmaps) {
                val text = ocrEngine.extractTextFromBitmap(bmp)
                extractedTextList.add(text)
            }

            writeValidDocxZip(outputDocxFile, sourcePdf.nameWithoutExtension, extractedTextList)
            Result.success(outputDocxFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pdfToWord(sourcePdf: File, outputDocxFile: File): Result<File> = pdfToDocx(sourcePdf, outputDocxFile)

    /**
     * Generates a fully valid OpenXML (.docx) ZIP structure compatible with Word, Google Docs, WPS, and LibreOffice.
     */
    private fun writeValidDocxZip(outFile: File, title: String, pageTexts: List<String>) {
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->

            // 1. [Content_Types].xml
            val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".trimIndent()
            addZipEntry(zip, "[Content_Types].xml", contentTypes)

            // 2. _rels/.rels
            val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".trimIndent()
            addZipEntry(zip, "_rels/.rels", rootRels)

            // 3. word/document.xml with escaped paragraphs
            val documentXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
                append("<w:body>")

                // Title
                append("<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr><w:t>")
                append(escapeXml(title))
                append("</w:t></w:r></w:p>")

                // Subtitle
                append("<w:p><w:r><w:rPr><w:i/><w:color w:val=\"555555\"/><w:sz w:val=\"20\"/></w:rPr><w:t>Dikonversi secara presisi oleh DokuPDF Universal Engine</w:t></w:r></w:p>")
                append("<w:p/>")

                // Page contents
                for ((pageIdx, text) in pageTexts.withIndex()) {
                    append("<w:p><w:r><w:rPr><w:b/><w:color w:val=\"2563EB\"/><w:sz w:val=\"24\"/></w:rPr><w:t>--- Halaman ${pageIdx + 1} ---</w:t></w:r></w:p>")
                    val lines = text.lines()
                    for (line in lines) {
                        if (line.isNotBlank()) {
                            append("<w:p><w:r><w:sz w:val=\"22\"/><w:t>")
                            append(escapeXml(line))
                            append("</w:t></w:r></w:p>")
                        }
                    }
                    append("<w:p/>")
                }

                // Section properties
                append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>""")
                append("</w:body></w:document>")
            }
            addZipEntry(zip, "word/document.xml", documentXml)
        }
    }

    private fun addZipEntry(zip: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zip.putNextEntry(entry)
        val bytes = content.toByteArray(Charsets.UTF_8)
        zip.write(bytes, 0, bytes.size)
        zip.closeEntry()
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Converts Word (DOCX or TXT) content into a styled PDF document
     */
    suspend fun wordToPdf(
        sourceFile: File,
        outputPdf: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val lines = extractTextLinesFromFile(sourceFile)
            val title = sourceFile.nameWithoutExtension
            wordLinesToPdf(lines, title, outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts plain text lines from either a .docx ZIP or standard text file
     */
    private fun extractTextLinesFromFile(file: File): List<String> {
        if (file.extension.equals("docx", ignoreCase = true)) {
            try {
                ZipFile(file).use { zip ->
                    val docEntry = zip.getEntry("word/document.xml")
                    if (docEntry != null) {
                        val xml = zip.getInputStream(docEntry).bufferedReader().use { it.readText() }
                        // Extract text inside <w:t> tags
                        val regex = Regex("<w:t[^>]*>(.*?)</w:t>")
                        val matches = regex.findAll(xml).map { it.groupValues[1] }.toList()
                        if (matches.isNotEmpty()) {
                            return matches
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Fallback: Read as text lines
        return file.readLines(Charsets.UTF_8)
    }

    suspend fun wordLinesToPdf(
        textLines: List<String>,
        title: String,
        outputPdf: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842
            val margin = 54f
            val maxLinesPerPage = 30
            val chunked = if (textLines.isEmpty()) listOf(listOf("Dokumen kosong.")) else textLines.chunked(maxLinesPerPage)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 12f
                typeface = Typeface.SERIF
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 18f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }

            for ((pageIdx, lines) in chunked.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIdx + 1).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                var y = margin
                if (pageIdx == 0) {
                    canvas.drawText(title, margin, y + 20f, titlePaint)
                    y += 50f
                }

                for (line in lines) {
                    canvas.drawText(line, margin, y, textPaint)
                    y += 22f
                }

                pdfDoc.finishPage(page)
            }

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { out ->
                pdfDoc.writeTo(out)
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDoc.close()
        }
    }

    /**
     * Converts PDF tabular content to CSV / Excel spreadsheet
     */
    suspend fun pdfToExcel(
        sourcePdf: File,
        outputCsvFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val bitmaps = pdfRenderer.renderPdfPages(sourcePdf, scale = 2.0f)
            val csvBuilder = StringBuilder()
            csvBuilder.append("Halaman,Baris,Teks Kolom 1,Teks Kolom 2,Teks Kolom 3\n")

            for ((pageIdx, bmp) in bitmaps.withIndex()) {
                val text = ocrEngine.extractTextFromBitmap(bmp)
                val lines = text.lines().filter { it.isNotBlank() }
                for ((lineIdx, line) in lines.withIndex()) {
                    val parts = line.split(Regex("\\s{2,}|\t")).map { "\"${it.replace("\"", "\"\"")}\"" }
                    val col1 = parts.getOrNull(0) ?: "\"\""
                    val col2 = parts.getOrNull(1) ?: "\"\""
                    val col3 = parts.drop(2).joinToString(" ")
                    val col3Clean = if (col3.isNotBlank()) "\"${col3.replace("\"", "\"\"")}\"" else "\"\""

                    csvBuilder.append("${pageIdx + 1},${lineIdx + 1},$col1,$col2,$col3Clean\n")
                }
            }

            outputCsvFile.parentFile?.mkdirs()
            FileWriter(outputCsvFile).use { writer ->
                writer.write(csvBuilder.toString())
            }
            Result.success(outputCsvFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Converts CSV / Excel spreadsheet file into a formatted PDF
     */
    suspend fun excelToPdf(
        sourceFile: File,
        outputPdf: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val lines = sourceFile.readLines(Charsets.UTF_8)
            val tableRows = lines.map { line ->
                line.split(",").map { it.trim().trim('\"') }
            }
            val title = sourceFile.nameWithoutExtension
            excelRowsToPdf(tableRows, title, outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Converts Excel / CSV spreadsheet tabular data into a formatted PDF
     */
    suspend fun excelRowsToPdf(
        tableRows: List<List<String>>,
        title: String,
        outputPdf: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val pdfDoc = PdfDocument()
        try {
            val pageWidth = 842 // Landscape A4 for wide tables
            val pageHeight = 595
            val margin = 40f
            val cellHeight = 28f

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDoc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(title, margin, margin + 15f, titlePaint)

            var currentY = margin + 40f
            val cols = tableRows.maxOfOrNull { it.size } ?: 1
            val colWidth = (pageWidth - margin * 2) / cols.coerceAtLeast(1)

            val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10f
                color = Color.DKGRAY
            }
            val borderPaint = Paint().apply {
                color = Color.LTGRAY
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val headerBg = Paint().apply {
                color = Color.parseColor("#E0F2FE")
                style = Paint.Style.FILL
            }

            for ((rowIdx, row) in tableRows.withIndex()) {
                if (currentY + cellHeight > pageHeight - margin) break
                for (c in 0 until cols) {
                    val left = margin + c * colWidth
                    val top = currentY
                    if (rowIdx == 0) {
                        canvas.drawRect(left, top, left + colWidth, top + cellHeight, headerBg)
                    }
                    canvas.drawRect(left, top, left + colWidth, top + cellHeight, borderPaint)
                    val txt = row.getOrNull(c) ?: ""
                    canvas.drawText(txt.take(24), left + 6f, top + 18f, cellPaint)
                }
                currentY += cellHeight
            }

            pdfDoc.finishPage(page)

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { out ->
                pdfDoc.writeTo(out)
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDoc.close()
        }
    }
}
