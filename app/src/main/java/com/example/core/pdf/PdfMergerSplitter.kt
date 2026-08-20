package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Handles merging multiple PDF files and splitting PDF pages into individual documents
 */
class PdfMergerSplitter(private val context: Context) {

    private val rendererEngine = PdfRendererEngine(context)

    /**
     * Merges multiple PDF files into one combined PDF
     */
    suspend fun mergePdfs(
        pdfFiles: List<File>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val mergedPdf = PdfDocument()
        try {
            require(pdfFiles.isNotEmpty()) { "Pilih setidaknya satu PDF untuk digabungkan" }
            var globalPageIndex = 1
            for (file in pdfFiles) {
                require(file.isFile && file.length() > 0L) { "Berkas '${file.name}' tidak valid" }
                val pageBitmaps = rendererEngine.renderPdfPages(file, scale = 2.0f)
                require(pageBitmaps.isNotEmpty()) { "Tidak ada halaman yang dapat dibaca dari '${file.name}'" }
                try {
                    for (bitmap in pageBitmaps) {
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            bitmap.width,
                            bitmap.height,
                            globalPageIndex++
                        ).create()

                        val pdfPage = mergedPdf.startPage(pageInfo)
                        pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        mergedPdf.finishPage(pdfPage)
                    }
                } finally {
                    pageBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                }
            }

            require(globalPageIndex > 1) { "Tidak ada halaman yang berhasil digabungkan" }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                mergedPdf.writeTo(out)
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            if (outputFile.exists() && outputFile.length() == 0L) outputFile.delete()
            Result.failure(e)
        } finally {
            mergedPdf.close()
        }
    }

    /**
     * Splits a PDF into multiple separate PDF files according to page ranges or individual pages
     */
    suspend fun splitPdf(
        sourcePdf: File,
        outputDir: File,
        pagesPerSplit: Int = 1
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            require(pagesPerSplit > 0) { "Jumlah halaman per bagian harus lebih dari nol" }
            require(sourcePdf.isFile && sourcePdf.length() > 0L) { "Berkas PDF sumber tidak valid" }
            outputDir.mkdirs()
            val allBitmaps = rendererEngine.renderPdfPages(sourcePdf, scale = 2.0f)
            require(allBitmaps.isNotEmpty()) { "PDF tidak memiliki halaman yang dapat dipisahkan" }
            val resultFiles = mutableListOf<File>()

            try {
                val chunked = allBitmaps.chunked(pagesPerSplit)
                val baseName = sourcePdf.nameWithoutExtension

                for ((chunkIndex, chunk) in chunked.withIndex()) {
                    val splitDoc = PdfDocument()
                    val chunkFile = File(outputDir, "${baseName}_bagian_${chunkIndex + 1}.pdf")

                    try {
                        for ((pageIdx, bitmap) in chunk.withIndex()) {
                            val pageInfo = PdfDocument.PageInfo.Builder(
                                bitmap.width,
                                bitmap.height,
                                pageIdx + 1
                            ).create()

                            val page = splitDoc.startPage(pageInfo)
                            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                            splitDoc.finishPage(page)
                        }

                        FileOutputStream(chunkFile).use { out -> splitDoc.writeTo(out) }
                        resultFiles.add(chunkFile)
                    } finally {
                        splitDoc.close()
                    }
                }
            } finally {
                allBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            }

            Result.success(resultFiles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
