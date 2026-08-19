package com.example.core.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.core.model.DocumentModel
import com.example.core.model.PageModel
import com.example.core.pdf.PdfRendererEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class SavedDocumentItem(
    val id: String,
    val file: File,
    val title: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val formattedSize: String,
    val formattedDate: String,
    val thumbnailBitmap: Bitmap? = null,
    val isEncrypted: Boolean = false
)

class DocumentRepository(
    private val context: Context,
    private val pdfRenderer: PdfRendererEngine = PdfRendererEngine(context)
) {

    private val docsDir: File get() = File(context.filesDir, "documents").apply { mkdirs() }
    private val thumbsDir: File get() = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    private val _documents = MutableStateFlow<List<SavedDocumentItem>>(emptyList())
    val documents: StateFlow<List<SavedDocumentItem>> = _documents.asStateFlow()

    suspend fun refreshDocuments() = withContext(Dispatchers.IO) {
        val files = docsDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val items = mutableListOf<SavedDocumentItem>()

        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id"))

        for (file in files) {
            val size = file.length()
            val formattedSize = formatFileSize(size)
            val dateStr = sdf.format(Date(file.lastModified()))
            val pageCount = pdfRenderer.getPageCount(file).coerceAtLeast(1)

            // Try load thumbnail from cache or render first page
            val thumbFile = File(thumbsDir, "${file.nameWithoutExtension}_thumb.jpg")
            val thumbBitmap = if (thumbFile.exists()) {
                BitmapFactory.decodeFile(thumbFile.absolutePath)
            } else {
                try {
                    val pages = pdfRenderer.renderPdfPages(file, scale = 0.5f)
                    val first = pages.firstOrNull()
                    first?.let { bmp ->
                        FileOutputStream(thumbFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                        }
                    }
                    first
                } catch (e: Exception) {
                    null
                }
            }

            items.add(
                SavedDocumentItem(
                    id = file.name,
                    file = file,
                    title = file.nameWithoutExtension,
                    pageCount = pageCount,
                    sizeBytes = size,
                    formattedSize = formattedSize,
                    formattedDate = dateStr,
                    thumbnailBitmap = thumbBitmap
                )
            )
        }

        _documents.value = items
    }

    suspend fun savePdf(file: File, newName: String? = null): File = withContext(Dispatchers.IO) {
        val targetName = (newName ?: file.nameWithoutExtension) + ".pdf"
        val destFile = File(docsDir, targetName)
        file.copyTo(destFile, overwrite = true)
        refreshDocuments()
        destFile
    }

    suspend fun createNewDocumentFile(baseTitle: String): File = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanTitle = baseTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        File(docsDir, "${cleanTitle}_$timeStamp.pdf")
    }

    suspend fun deleteDocument(item: SavedDocumentItem): Boolean = withContext(Dispatchers.IO) {
        val deleted = item.file.delete()
        File(thumbsDir, "${item.file.nameWithoutExtension}_thumb.jpg").delete()
        refreshDocuments()
        deleted
    }

    suspend fun renameDocument(item: SavedDocumentItem, newTitle: String): File = withContext(Dispatchers.IO) {
        val clean = newTitle.replace(Regex("[^a-zA-Z0-9_ -]"), "").trim()
        val newFile = File(docsDir, "$clean.pdf")
        item.file.renameTo(newFile)
        refreshDocuments()
        newFile
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }
}
