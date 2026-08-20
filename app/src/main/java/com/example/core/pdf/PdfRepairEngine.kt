package com.example.core.pdf

import android.content.Context
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class RepairReport(
    val outputFile: File,
    val issuesFixed: List<String>,
    val wasSuccessful: Boolean
)

class PdfRepairEngine(
    private val context: Context,
    private val rendererEngine: PdfRendererEngine = PdfRendererEngine(context)
) {

    suspend fun repairPdf(
        sourcePdf: File,
        outputPdf: File
    ): Result<RepairReport> = withContext(Dispatchers.IO) {
        val fixedIssues = mutableListOf<String>()
        var tempFile: File? = null
        try {
            require(sourcePdf.isFile && sourcePdf.length() > 0L) { "Berkas PDF sumber tidak valid" }
            val bytes = FileInputStream(sourcePdf).use { it.readBytes() }
            var rawText = String(bytes, Charsets.ISO_8859_1)

            // 1. Check & repair PDF Header (%PDF-1.7)
            if (!rawText.startsWith("%PDF-")) {
                val headerIndex = rawText.indexOf("%PDF-")
                if (headerIndex > 0) {
                    rawText = rawText.substring(headerIndex)
                    fixedIssues.add("Memperbaiki header PDF yang bergeser")
                } else {
                    rawText = "%PDF-1.7\n$rawText"
                    fixedIssues.add("Menambahkan header standar %PDF-1.7 yang hilang")
                }
            }

            // 2. Check & repair %%EOF trailer marker
            if (!rawText.trimEnd().endsWith("%%EOF")) {
                rawText = rawText.trimEnd() + "\n%%EOF"
                fixedIssues.add("Memperbaiki penutup stream file (%%EOF marker) yang rusak")
            }

            // 3. Re-rasterize pages through clean rendering pipeline to remove corrupt bytecode streams
            val candidateFile = File(context.cacheDir, "repair_temp_${System.currentTimeMillis()}.pdf")
            tempFile = candidateFile
            FileOutputStream(candidateFile).use { it.write(rawText.toByteArray(Charsets.ISO_8859_1)) }

            val repairedCandidatePages = try {
                rendererEngine.renderPdfPages(candidateFile, scale = 2.0f)
            } catch (_: Exception) {
                emptyList()
            }
            val pageBitmaps = if (repairedCandidatePages.isNotEmpty()) {
                repairedCandidatePages
            } else {
                rendererEngine.renderPdfPages(sourcePdf, scale = 2.0f)
            }

            require(pageBitmaps.isNotEmpty()) {
                "Tidak ada halaman PDF yang dapat dirender; perbaikan dihentikan agar tidak menghasilkan berkas rusak palsu"
            }

            val cleanDoc = PdfDocument()
            try {
                for ((idx, bmp) in pageBitmaps.withIndex()) {
                    val pInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                    val page = cleanDoc.startPage(pInfo)
                    page.canvas.drawBitmap(bmp, 0f, 0f, null)
                    cleanDoc.finishPage(page)
                }

                outputPdf.parentFile?.mkdirs()
                FileOutputStream(outputPdf).use { out ->
                    cleanDoc.writeTo(out)
                }
                fixedIssues.add("Membangun ulang struktur internal tabel xref & object dictionary (${pageBitmaps.size} halaman)")
            } finally {
                cleanDoc.close()
                pageBitmaps.forEach { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }

            Result.success(
                RepairReport(
                    outputFile = outputPdf,
                    issuesFixed = fixedIssues,
                    wasSuccessful = true
                )
            )
        } catch (e: Exception) {
            if (outputPdf.exists() && outputPdf.length() == 0L) outputPdf.delete()
            Result.failure(e)
        } finally {
            tempFile?.delete()
        }
    }
}
