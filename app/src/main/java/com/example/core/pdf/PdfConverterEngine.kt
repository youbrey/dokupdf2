package com.example.core.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import com.example.core.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Universal document converter engine supporting Word (DOCX/TXT), Excel (CSV/XLSX), and image pipelines.
 * Creates standard OOXML ZIP packages for DOCX and renders pristine PDF documents.
 */
class PdfConverterEngine(
    context: Context,
    private val pdfRenderer: PdfRendererEngine = PdfRendererEngine(context),
    suppliedOcrEngine: OcrEngine? = null
) {

    private companion object {
        const val MAX_CONVERTED_TEXT_CHARACTERS = 5_000_000
        const val MAX_SPREADSHEET_ROWS = 100_000
        const val MAX_SPREADSHEET_COLUMNS = 256
    }

    private val applicationContext = context.applicationContext
    private val ocrEngineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        suppliedOcrEngine ?: OcrEngine(applicationContext)
    }
    private val ocrEngine: OcrEngine get() = ocrEngineDelegate.value

    fun close() {
        if (ocrEngineDelegate.isInitialized()) ocrEngine.close()
    }

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
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }

        return try {
            // Compress via JPEG stream to strip 32-bit uncompressed raster bloat.
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)) return source
                stream.toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: source
        } finally {
            if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        }
    }

    /**
     * Converts a list of image files into a single unified PDF
     */
    suspend fun imagesToPdf(
        imageFiles: List<File>,
        outputPdf: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            require(imageFiles.isNotEmpty()) { "Pilih setidaknya satu gambar" }
            imageFiles.forEach { PdfFileUtils.requireReadableFile(it, "Gambar '${it.name}'", PdfFileUtils.MAX_OFFICE_INPUT_BYTES) }

            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
                val pdfDoc = PdfDocument()
                var pageIndex = 1
                try {
                    for (file in imageFiles) {
                        val rawBitmap = decodeImageFile(file) ?: continue
                        try {
                            val bitmap = prepareOptimizedBitmapForPdf(rawBitmap)
                            try {
                                val (pageW, pageH) = pdfPageSizePt(bitmap)
                                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex++).create()
                                val page = pdfDoc.startPage(pageInfo)
                                page.canvas.drawBitmap(bitmap, null, Rect(0, 0, pageW, pageH), pageDrawPaint)
                                pdfDoc.finishPage(page)
                            } finally {
                                if (bitmap !== rawBitmap && !bitmap.isRecycled) bitmap.recycle()
                            }
                        } finally {
                            if (!rawBitmap.isRecycled) rawBitmap.recycle()
                        }
                    }

                    require(pageIndex > 1) { "Tidak ada gambar valid yang dapat diproses" }
                    FileOutputStream(temporaryOutput).use { out -> pdfDoc.writeTo(out) }
                } finally {
                    pdfDoc.close()
                }
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk mengonversi gambar ke PDF", oom))
        }
    }

    /**
     * Converts a list of Bitmaps directly into a single multi-page PDF.
     * Compresses rasters efficiently so multi-page PDFs are crisp yet only a few hundred KBs.
     */
    suspend fun bitmapsToPdf(
        bitmaps: List<Bitmap>,
        outputPdf: File,
        recycleSource: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            require(bitmaps.isNotEmpty()) { "Daftar gambar kosong" }
            require(bitmaps.none { it.isRecycled || it.width <= 0 || it.height <= 0 }) { "Daftar berisi bitmap yang tidak valid" }

            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
                val pdfDoc = PdfDocument()
                try {
                    for ((index, sourceBitmap) in bitmaps.withIndex()) {
                        val optimizedBitmap = prepareOptimizedBitmapForPdf(sourceBitmap)
                        try {
                            val (pageW, pageH) = pdfPageSizePt(optimizedBitmap)
                            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                            val page = pdfDoc.startPage(pageInfo)
                            page.canvas.drawBitmap(optimizedBitmap, null, Rect(0, 0, pageW, pageH), pageDrawPaint)
                            pdfDoc.finishPage(page)
                        } finally {
                            if (optimizedBitmap !== sourceBitmap && !optimizedBitmap.isRecycled) optimizedBitmap.recycle()
                            if (recycleSource && !sourceBitmap.isRecycled) sourceBitmap.recycle()
                        }
                    }
                    FileOutputStream(temporaryOutput).use { out -> pdfDoc.writeTo(out) }
                } finally {
                    pdfDoc.close()
                }
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(Exception("Memori tidak cukup untuk membuat PDF. Coba kurangi jumlah halaman atau nonaktifkan mode kualitas HD.", oom))
        }
    }

    /**
     * Creates a PDF from lazily generated pages so a scan session never retains a second
     * full-resolution bitmap for every page at the same time. The generated bitmap is owned by
     * this method and recycled after its page has been recorded.
     */
    suspend fun generatedBitmapsToPdf(
        pageCount: Int,
        outputPdf: File,
        bitmapProvider: suspend (pageIndex: Int) -> Bitmap
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            require(pageCount > 0) { "Dokumen tidak memiliki halaman" }
            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
                val pdfDoc = PdfDocument()
                try {
                    for (index in 0 until pageCount) {
                        val generated = bitmapProvider(index)
                        require(!generated.isRecycled && generated.width > 0 && generated.height > 0) {
                            "Halaman ${index + 1} menghasilkan bitmap yang tidak valid"
                        }
                        try {
                            val optimized = prepareOptimizedBitmapForPdf(generated)
                            try {
                                val (pageWidth, pageHeight) = pdfPageSizePt(optimized)
                                val pageInfo = PdfDocument.PageInfo.Builder(
                                    pageWidth,
                                    pageHeight,
                                    index + 1
                                ).create()
                                val page = pdfDoc.startPage(pageInfo)
                                page.canvas.drawBitmap(
                                    optimized,
                                    null,
                                    Rect(0, 0, pageWidth, pageHeight),
                                    pageDrawPaint
                                )
                                pdfDoc.finishPage(page)
                            } finally {
                                if (optimized !== generated && !optimized.isRecycled) optimized.recycle()
                            }
                        } finally {
                            if (!generated.isRecycled) generated.recycle()
                        }
                    }
                    FileOutputStream(temporaryOutput).use { output -> pdfDoc.writeTo(output) }
                } finally {
                    pdfDoc.close()
                }
            }
            Result.success(outputPdf)
        } catch (error: Exception) {
            Result.failure(error)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk membuat PDF hasil pindai", oom))
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
        val files = mutableListOf<File>()
        try {
            PdfFileUtils.requirePdf(sourcePdf)
            require(outputDir.exists() || outputDir.mkdirs()) { "Direktori keluaran tidak dapat dibuat" }
            require(quality in 0..100) { "Kualitas gambar harus berada pada rentang 0-100" }
            val ext = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
            val baseName = sourcePdf.nameWithoutExtension

            pdfRenderer.forEachRenderedPage(sourcePdf, scale = 2.0f) { pageIndex, bitmap ->
                val file = PdfFileUtils.uniqueFile(outputDir, "${baseName}_halaman_${pageIndex + 1}", ext)
                PdfFileUtils.writeAtomically(file) { temporary ->
                    FileOutputStream(temporary).use { out ->
                        require(bitmap.compress(format, quality, out)) {
                            "Gagal menulis halaman ${pageIndex + 1}"
                        }
                    }
                }
                files += file
            }
            require(files.isNotEmpty()) { "Tidak ada halaman yang dapat dirender dari PDF" }
            Result.success(files)
        } catch (e: Exception) {
            files.forEach { it.delete() }
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            files.forEach { it.delete() }
            Result.failure(IllegalStateException("Memori tidak cukup untuk mengekstrak halaman PDF", oom))
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
            PdfFileUtils.requirePdf(sourcePdf)
            PdfFileUtils.requireDistinct(sourcePdf, outputFile)
            val dimensions = pdfRenderer.getPageDimensions(sourcePdf)
            require(dimensions.isNotEmpty()) { "PDF tidak memiliki halaman" }

            val widestPage = dimensions.maxOf { it.width }.toDouble()
            val combinedHeight = dimensions.sumOf { it.height.toLong() }.toDouble()
            val maximumPixels = 16_000_000.0
            val renderScale = minOf(
                2.0,
                2000.0 / widestPage,
                30000.0 / combinedHeight,
                kotlin.math.sqrt(maximumPixels / (widestPage * combinedHeight))
            ).toFloat()
            require(renderScale >= 0.05f) {
                "PDF terlalu panjang untuk satu gambar yang masih dapat dibaca; pisahkan PDF terlebih dahulu"
            }

            val totalWidth = (widestPage * renderScale).toInt().coerceAtLeast(1)
            val totalHeight = (combinedHeight * renderScale).toInt().coerceAtLeast(1)

            PdfFileUtils.writeAtomically(outputFile) { temporaryOutput ->
                val stitched = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(stitched)
                    canvas.drawColor(Color.WHITE)
                    var currentY = 0f
                    pdfRenderer.forEachRenderedPage(sourcePdf, scale = renderScale) { _, bitmap ->
                        val left = (totalWidth - bitmap.width) / 2f
                        canvas.drawBitmap(bitmap, left, currentY, pageDrawPaint)
                        currentY += bitmap.height
                    }

                    FileOutputStream(temporaryOutput).use { out ->
                        require(stitched.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                            "Gagal menulis gambar panjang"
                        }
                    }
                } finally {
                    if (!stitched.isRecycled) stitched.recycle()
                }
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk membuat gambar panjang", oom))
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
        try {
            PdfFileUtils.requirePdf(sourcePdf)
            PdfFileUtils.requireDistinct(sourcePdf, outputPdf)
            require(degrees in setOf(90, 180, 270)) { "Sudut rotasi harus 90, 180, atau 270 derajat" }
            val dimensions = pdfRenderer.getPageDimensions(sourcePdf)
            require(dimensions.isNotEmpty()) { "PDF tidak memiliki halaman untuk diputar" }
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }

            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
                val pdfDoc = PdfDocument()
                try {
                    pdfRenderer.forEachRenderedPage(sourcePdf, scale = 1.6f) { index, bitmap ->
                        val rotatedBitmap = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            matrix,
                            true
                        )
                        try {
                            val originalPage = dimensions[index]
                            val pageWidth = if (degrees == 180) originalPage.width else originalPage.height
                            val pageHeight = if (degrees == 180) originalPage.height else originalPage.width
                            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                            val page = pdfDoc.startPage(pageInfo)
                            page.canvas.drawBitmap(
                                rotatedBitmap,
                                null,
                                Rect(0, 0, pageWidth, pageHeight),
                                pageDrawPaint
                            )
                            pdfDoc.finishPage(page)
                        } finally {
                            if (rotatedBitmap !== bitmap && !rotatedBitmap.isRecycled) rotatedBitmap.recycle()
                        }
                    }
                    FileOutputStream(temporaryOutput).use { out -> pdfDoc.writeTo(out) }
                } finally {
                    pdfDoc.close()
                }
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk memutar PDF", oom))
        }
    }

    suspend fun extractTextFromPdf(
        sourcePdf: File,
        includePageHeaders: Boolean = true,
        maximumCharacters: Int = 80_000
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            PdfFileUtils.requirePdf(sourcePdf)
            require(maximumCharacters in 1_000..500_000) { "Batas karakter ekstraksi tidak valid" }
            val extracted = StringBuilder()
            var detectedPages = 0
            var wasTruncated = false
            pdfRenderer.forEachRenderedPage(sourcePdf, scale = 2.0f) { pageIndex, bitmap ->
                if (extracted.length < maximumCharacters) {
                    val pageText = ocrEngine.extractTextFromBitmap(bitmap).trim()
                    if (pageText.isNotBlank()) {
                        detectedPages++
                        val pageSection = buildString {
                            if (includePageHeaders) appendLine("--- Halaman ${pageIndex + 1} ---")
                            appendLine(pageText)
                            appendLine()
                        }
                        val remaining = maximumCharacters - extracted.length
                        extracted.append(pageSection.take(remaining))
                        if (pageSection.length > remaining) wasTruncated = true
                    }
                } else {
                    wasTruncated = true
                }
            }
            require(detectedPages > 0) { "Tidak ada teks yang terdeteksi pada halaman PDF" }
            val resultText = buildString {
                append(extracted.toString().trim())
                if (wasTruncated) {
                    append("\n\n[Hasil OCR dipotong pada batas aman $maximumCharacters karakter]")
                }
            }
            Result.success(resultText)
        } catch (error: Exception) {
            Result.failure(error)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk mengekstrak teks PDF", oom))
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
            PdfFileUtils.requirePdf(sourcePdf)
            PdfFileUtils.requireDistinct(sourcePdf, outputDocxFile)
            require(outputDocxFile.extension.equals("docx", ignoreCase = true)) {
                "Berkas keluaran harus menggunakan ekstensi .docx"
            }
            val extractedTextList = mutableListOf<String>()
            var extractedCharacters = 0
            pdfRenderer.forEachRenderedPage(sourcePdf, scale = 2.0f) { _, bitmap ->
                val pageText = ocrEngine.extractTextFromBitmap(bitmap).trim()
                extractedCharacters += pageText.length
                require(extractedCharacters <= MAX_CONVERTED_TEXT_CHARACTERS) {
                    "Teks hasil OCR terlalu besar untuk satu dokumen DOCX"
                }
                extractedTextList += pageText
            }
            require(extractedTextList.isNotEmpty()) { "PDF tidak memiliki halaman yang dapat dikonversi" }
            require(extractedTextList.any { it.isNotBlank() }) {
                "Tidak ada teks yang terdeteksi; PDF ke Word berbasis OCR tidak dapat menghasilkan dokumen editable"
            }

            PdfFileUtils.writeAtomically(outputDocxFile, minimumBytes = 100L) { temporaryOutput ->
                writeValidDocxZip(temporaryOutput, sourcePdf.nameWithoutExtension, extractedTextList)
            }
            Result.success(outputDocxFile)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk mengonversi PDF ke DOCX", oom))
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
                    if (pageIdx > 0) {
                        append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                    }
                    append("<w:p><w:r><w:rPr><w:b/><w:color w:val=\"2563EB\"/><w:sz w:val=\"24\"/></w:rPr><w:t>--- Halaman ${pageIdx + 1} ---</w:t></w:r></w:p>")
                    val lines = text.lines()
                    for (line in lines) {
                        if (line.isNotBlank()) {
                            append("<w:p><w:r><w:rPr><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">")
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
        return text.filter { character ->
            character == '\t' || character == '\n' || character == '\r' || character >= ' '
        }.replace("&", "&amp;")
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
            PdfFileUtils.requireDistinct(sourceFile, outputPdf)
            val lines = OfficeFileParser.readWordLines(sourceFile)
            val title = sourceFile.nameWithoutExtension
            wordLinesToPdf(lines, title, outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk membaca dokumen Word/Teks", oom))
        }
    }

    suspend fun wordLinesToPdf(
        textLines: List<String>,
        title: String,
        outputPdf: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            require(title.isNotBlank()) { "Judul dokumen tidak boleh kosong" }
            require(textLines.sumOf { it.length.toLong() } <= MAX_CONVERTED_TEXT_CHARACTERS) {
                "Dokumen teks terlalu besar untuk dikonversi"
            }
            val pageWidth = 595
            val pageHeight = 842
            val margin = 54f

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
            val usableWidth = pageWidth - (margin * 2f)
            val wrappedLines = (if (textLines.isEmpty()) listOf("Dokumen kosong.") else textLines)
                .flatMap { wrapTextLine(it, textPaint, usableWidth) }

            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
                val pdfDoc = PdfDocument()
                try {
                    var lineIndex = 0
                    var pageIndex = 0
                    do {
                        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                        val page = pdfDoc.startPage(pageInfo)
                        val canvas = page.canvas
                        canvas.drawColor(Color.WHITE)

                        var y = margin
                        if (pageIndex == 0) {
                            canvas.drawText(title.take(80), margin, y + 20f, titlePaint)
                            y += 50f
                        }

                        while (lineIndex < wrappedLines.size && y <= pageHeight - margin) {
                            val line = wrappedLines[lineIndex++]
                            if (line.isNotEmpty()) canvas.drawText(line, margin, y, textPaint)
                            y += 20f
                        }
                        pdfDoc.finishPage(page)
                        pageIndex++
                    } while (lineIndex < wrappedLines.size)

                    FileOutputStream(temporaryOutput).use { out -> pdfDoc.writeTo(out) }
                } finally {
                    pdfDoc.close()
                }
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk membuat PDF dari dokumen teks", oom))
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
            PdfFileUtils.requirePdf(sourcePdf)
            PdfFileUtils.requireDistinct(sourcePdf, outputCsvFile)
            val extractedRows = mutableListOf<List<String>>()
            var extractedCharacters = 0L
            pdfRenderer.forEachRenderedPage(sourcePdf, scale = 2.0f) { pageIndex, bitmap ->
                val text = ocrEngine.extractTextFromBitmap(bitmap)
                text.lines().filter { it.isNotBlank() }.forEachIndexed { lineIndex, line ->
                    require(extractedRows.size < MAX_SPREADSHEET_ROWS) {
                        "Hasil OCR melebihi batas $MAX_SPREADSHEET_ROWS baris CSV"
                    }
                    extractedCharacters += line.length
                    require(extractedCharacters <= MAX_CONVERTED_TEXT_CHARACTERS) {
                        "Hasil OCR terlalu besar untuk satu berkas CSV"
                    }
                    val detectedColumns = line.trim()
                        .split(Regex("\\t|\\s{2,}"), limit = MAX_SPREADSHEET_COLUMNS + 1)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .ifEmpty { listOf(line.trim()) }
                    require(detectedColumns.size <= MAX_SPREADSHEET_COLUMNS) {
                        "Baris OCR melebihi batas $MAX_SPREADSHEET_COLUMNS kolom CSV"
                    }
                    extractedRows += listOf((pageIndex + 1).toString(), (lineIndex + 1).toString()) + detectedColumns
                }
            }
            require(extractedRows.isNotEmpty()) { "Tidak ada teks atau tabel yang dapat diekstrak dari PDF" }

            val maximumColumns = extractedRows.maxOf { it.size }.coerceAtLeast(3)
            PdfFileUtils.writeAtomically(outputCsvFile) { temporaryOutput ->
                temporaryOutput.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write('\uFEFF'.code)
                    val header = listOf("Halaman", "Baris") +
                        (1..(maximumColumns - 2)).map { "Kolom $it" }
                    writer.appendLine(header.joinToString(",", transform = ::csvEscape))
                    extractedRows.forEach { row ->
                        val padded = row + List(maximumColumns - row.size) { "" }
                        writer.appendLine(padded.joinToString(",", transform = ::csvEscape))
                    }
                }
            }
            Result.success(outputCsvFile)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk mengekstrak PDF ke CSV", oom))
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
            PdfFileUtils.requireDistinct(sourceFile, outputPdf)
            val tableRows = OfficeFileParser.readSpreadsheet(sourceFile)
            val title = sourceFile.nameWithoutExtension
            excelRowsToPdf(tableRows, title, outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk membaca spreadsheet", oom))
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
        try {
            require(tableRows.isNotEmpty() && tableRows.any { row -> row.any { it.isNotBlank() } }) {
                "Spreadsheet tidak memiliki data"
            }
            require(tableRows.size <= MAX_SPREADSHEET_ROWS) {
                "Spreadsheet melebihi batas $MAX_SPREADSHEET_ROWS baris"
            }
            require(tableRows.all { it.size <= MAX_SPREADSHEET_COLUMNS }) {
                "Spreadsheet melebihi batas $MAX_SPREADSHEET_COLUMNS kolom"
            }
            require(tableRows.sumOf { row -> row.sumOf { cell -> cell.length.toLong() } } <= MAX_CONVERTED_TEXT_CHARACTERS) {
                "Isi spreadsheet terlalu besar untuk dikonversi"
            }
            val pageWidth = 842 // Landscape A4 for wide tables
            val pageHeight = 595
            val margin = 40f
            val cellHeight = 28f
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val cols = tableRows.maxOfOrNull { it.size } ?: 1
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
            val header = tableRows.first()
            val dataRows = tableRows.drop(1)
            val rowsPerPage = ((pageHeight - (margin * 2f) - 76f) / cellHeight).toInt().coerceAtLeast(1)
            val columnGroups = (0 until cols).chunked(8)
            val rowGroups: List<List<List<String>>> = if (dataRows.isEmpty()) {
                listOf(emptyList())
            } else {
                dataRows.chunked(rowsPerPage)
            }

            PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporaryOutput ->
                val pdfDoc = PdfDocument()
                try {
                    var pageNumber = 1
                    for (columns in columnGroups) {
                        for (rows in rowGroups) {
                            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                            val page = pdfDoc.startPage(pageInfo)
                            val canvas = page.canvas
                            canvas.drawColor(Color.WHITE)
                            canvas.drawText(title.take(80), margin, margin + 15f, titlePaint)
                            if (columnGroups.size > 1) {
                                canvas.drawText(
                                    "Kolom ${columns.first() + 1}-${columns.last() + 1} • Halaman $pageNumber",
                                    margin,
                                    margin + 35f,
                                    cellPaint
                                )
                            }

                            val colWidth = (pageWidth - margin * 2) / columns.size.coerceAtLeast(1)
                            var currentY = margin + 48f
                            val pageRows = listOf(header) + rows
                            for ((rowIndex, row) in pageRows.withIndex()) {
                                for ((visibleColumnIndex, sourceColumnIndex) in columns.withIndex()) {
                                    val left = margin + visibleColumnIndex * colWidth
                                    val top = currentY
                                    if (rowIndex == 0) {
                                        canvas.drawRect(left, top, left + colWidth, top + cellHeight, headerBg)
                                    }
                                    canvas.drawRect(left, top, left + colWidth, top + cellHeight, borderPaint)
                                    val value = row.getOrNull(sourceColumnIndex).orEmpty()
                                    canvas.drawText(
                                        ellipsize(value, cellPaint, colWidth - 12f),
                                        left + 6f,
                                        top + 18f,
                                        cellPaint
                                    )
                                }
                                currentY += cellHeight
                            }
                            pdfDoc.finishPage(page)
                            pageNumber++
                        }
                    }

                    FileOutputStream(temporaryOutput).use { out -> pdfDoc.writeTo(out) }
                } finally {
                    pdfDoc.close()
                }
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk membuat PDF spreadsheet", oom))
        }
    }

    private fun decodeImageFile(file: File, maximumDimension: Int = 3200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maximumDimension || bounds.outHeight / sampleSize > maximumDimension) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return decoded
        }

        val orientationMatrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setRotate(180f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }

        val rotated = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            orientationMatrix,
            true
        )
        if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
        return rotated
    }

    private fun wrapTextLine(line: String, paint: Paint, maximumWidth: Float): List<String> {
        if (line.isBlank()) return listOf("")
        val result = mutableListOf<String>()
        var remaining = line.trimEnd()
        while (remaining.isNotEmpty()) {
            val fittingCharacters = paint.breakText(remaining, true, maximumWidth, null).coerceAtLeast(1)
            if (fittingCharacters >= remaining.length) {
                result += remaining
                break
            }
            val naturalBreak = remaining.lastIndexOf(' ', startIndex = fittingCharacters - 1)
                .takeIf { it > 0 }
                ?: fittingCharacters
            result += remaining.substring(0, naturalBreak).trimEnd()
            remaining = remaining.substring(naturalBreak).trimStart()
        }
        return result
    }

    private fun ellipsize(value: String, paint: Paint, maximumWidth: Float): String {
        if (paint.measureText(value) <= maximumWidth) return value
        val ellipsis = "…"
        val available = (maximumWidth - paint.measureText(ellipsis)).coerceAtLeast(1f)
        val count = paint.breakText(value, true, available, null).coerceAtLeast(0)
        return value.take(count).trimEnd() + ellipsis
    }

    private fun csvEscape(rawValue: String): String {
        val firstMeaningfulCharacter = rawValue.dropWhile { it == ' ' || it == '\t' }.firstOrNull()
        val value = if (firstMeaningfulCharacter in setOf('=', '+', '-', '@')) "'$rawValue" else rawValue
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            val escaped = value.replace("\"", "\"\"")
            "\"$escaped\""
        } else {
            value
        }
    }
}
