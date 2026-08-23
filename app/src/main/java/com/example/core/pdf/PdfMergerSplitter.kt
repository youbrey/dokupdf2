package com.example.core.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Handles merging multiple PDF files and splitting PDF pages into individual documents
 */
class PdfMergerSplitter(context: Context) {

    private val rendererEngine = PdfRendererEngine(context)

    /**
     * Merges multiple PDF files into one combined PDF
     */
    suspend fun mergePdfs(
        pdfFiles: List<File>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            require(pdfFiles.size >= 2) { "Pilih setidaknya dua PDF untuk digabungkan" }
            val distinctFiles = pdfFiles.distinctBy { it.canonicalPath }
            require(distinctFiles.size >= 2) { "Pilih dua berkas PDF yang berbeda" }
            distinctFiles.forEach { file ->
                PdfFileUtils.requirePdf(file, "Berkas '${file.name}'")
                PdfFileUtils.requireDistinct(file, outputFile)
            }

            PdfFileUtils.writeAtomically(outputFile, minimumBytes = 5L) { temporaryOutput ->
              // [Audit fix] Diserialkan lewat PdfFileUtils.pdfDocumentMutex -- lihat
              // PdfFileUtils.kt untuk alasan (PdfDocument didokumentasikan "not thread safe").
              PdfFileUtils.pdfDocumentMutex.withLock {
                val mergedPdf = PdfDocument()
                var globalPageIndex = 1
                try {
                    for (file in distinctFiles) {
                        val dimensions = rendererEngine.getPageDimensions(file)
                        require(dimensions.isNotEmpty()) { "Tidak ada halaman yang dapat dibaca dari '${file.name}'" }
                        rendererEngine.forEachRenderedPage(file, scale = 1.6f) { pageIndex, bitmap ->
                            val originalPage = dimensions[pageIndex]
                            val pageInfo = PdfDocument.PageInfo.Builder(
                                originalPage.width,
                                originalPage.height,
                                globalPageIndex++
                            ).create()

                            val pdfPage = mergedPdf.startPage(pageInfo)
                            pdfPage.canvas.drawBitmap(
                                bitmap,
                                null,
                                Rect(0, 0, originalPage.width, originalPage.height),
                                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                            )
                            mergedPdf.finishPage(pdfPage)
                        }
                    }

                    require(globalPageIndex > 1) { "Tidak ada halaman yang berhasil digabungkan" }
                    FileOutputStream(temporaryOutput).use { out -> mergedPdf.writeTo(out) }
                } finally {
                    mergedPdf.close()
                }
              }
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Memori tidak cukup untuk menggabungkan PDF", oom))
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
      // [Audit fix] Diserialkan lewat PdfFileUtils.pdfDocumentMutex -- fungsi ini membuka
      // beberapa PdfDocument berurutan (satu per bagian hasil split) sepanjang eksekusinya,
      // jadi seluruh badan fungsi dikunci, bukan cuma satu writeAtomically saja.
      PdfFileUtils.pdfDocumentMutex.withLock {
        val resultFiles = mutableListOf<File>()
        var activeDocument: PdfDocument? = null
        try {
            require(pagesPerSplit > 0) { "Jumlah halaman per bagian harus lebih dari nol" }
            PdfFileUtils.requirePdf(sourcePdf)
            require(outputDir.exists() || outputDir.mkdirs()) { "Direktori keluaran tidak dapat dibuat" }
            val dimensions = rendererEngine.getPageDimensions(sourcePdf)
            require(dimensions.isNotEmpty()) { "PDF tidak memiliki halaman yang dapat dipisahkan" }
            val baseName = sourcePdf.nameWithoutExtension
            var currentChunkIndex = -1
            var currentChunkFile: File? = null

            rendererEngine.forEachRenderedPage(sourcePdf, scale = 1.6f) { sourcePageIndex, bitmap ->
                val chunkIndex = sourcePageIndex / pagesPerSplit
                if (chunkIndex != currentChunkIndex) {
                    activeDocument?.let { previousDocument ->
                        val completedFile = requireNotNull(currentChunkFile)
                        PdfFileUtils.writeAtomically(completedFile, minimumBytes = 5L) { temporary ->
                            FileOutputStream(temporary).use { previousDocument.writeTo(it) }
                        }
                        previousDocument.close()
                        activeDocument = null
                        resultFiles += completedFile
                    }
                    currentChunkIndex = chunkIndex
                    currentChunkFile = PdfFileUtils.uniqueFile(
                        outputDir,
                        "${baseName}_bagian_${chunkIndex + 1}",
                        "pdf"
                    )
                    activeDocument = PdfDocument()
                }

                val splitDoc = requireNotNull(activeDocument)
                val originalPage = dimensions[sourcePageIndex]
                val pageNumberInChunk = (sourcePageIndex % pagesPerSplit) + 1
                val pageInfo = PdfDocument.PageInfo.Builder(
                    originalPage.width,
                    originalPage.height,
                    pageNumberInChunk
                ).create()

                val page = splitDoc.startPage(pageInfo)
                page.canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, originalPage.width, originalPage.height),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                splitDoc.finishPage(page)
            }

            activeDocument?.let { finalDocument ->
                val finalFile = requireNotNull(currentChunkFile)
                PdfFileUtils.writeAtomically(finalFile, minimumBytes = 5L) { temporary ->
                    FileOutputStream(temporary).use { finalDocument.writeTo(it) }
                }
                finalDocument.close()
                activeDocument = null
                resultFiles += finalFile
            }

            require(resultFiles.isNotEmpty()) { "Tidak ada bagian PDF yang berhasil dibuat" }
            Result.success(resultFiles)
        } catch (e: Exception) {
            activeDocument?.close()
            resultFiles.forEach { it.delete() }
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            activeDocument?.close()
            resultFiles.forEach { it.delete() }
            Result.failure(IllegalStateException("Memori tidak cukup untuk memisahkan PDF", oom))
        }
      }
    }
}
