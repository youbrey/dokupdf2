package com.example.core.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.core.model.DocumentModel
import com.example.core.model.PageModel
import com.example.core.pdf.PdfRendererEngine
import com.example.core.pdf.PdfFileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    context: Context,
    private val pdfRenderer: PdfRendererEngine = PdfRendererEngine(context)
) {
    private val context = context.applicationContext

    private val docsDir: File get() = File(context.filesDir, "documents").apply { mkdirs() }
    private val thumbsDir: File get() = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    private val _documents = MutableStateFlow<List<SavedDocumentItem>>(emptyList())
    val documents: StateFlow<List<SavedDocumentItem>> = _documents.asStateFlow()
    private val refreshMutex = Mutex()

    suspend fun refreshDocuments() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
        val files = docsDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val items = mutableListOf<SavedDocumentItem>()

        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID"))

        for (file in files) {
            val size = file.length()
            val formattedSize = PdfFileUtils.formatBytes(size)
            val dateStr = sdf.format(Date(file.lastModified()))
            val pageCount = pdfRenderer.getPageCount(file)

            // Try load thumbnail from cache or render first page
            val thumbnailKey = "${file.nameWithoutExtension}_${file.lastModified()}_${file.length()}_thumb.jpg"
            val thumbFile = File(thumbsDir, PdfFileUtils.sanitizeFileName(thumbnailKey, "thumbnail.jpg"))
            val cachedThumbnail = if (thumbFile.exists()) {
                try {
                    BitmapFactory.decodeFile(thumbFile.absolutePath).also { decoded ->
                        if (decoded == null) thumbFile.delete()
                    }
                } catch (_: OutOfMemoryError) {
                    null
                }
            } else null
            val thumbBitmap = cachedThumbnail ?: run {
                try {
                    val firstPage = pdfRenderer.renderSinglePage(file, pageIndex = 0, scale = 0.5f)
                    firstPage?.let { bitmap ->
                        FileOutputStream(thumbFile).use { out ->
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)) {
                                thumbFile.delete()
                            }
                        }
                    }
                    firstPage
                } catch (_: OutOfMemoryError) {
                    null
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
        val validThumbnailNames = items.mapNotNull { item ->
            val source = item.file
            "${source.nameWithoutExtension}_${source.lastModified()}_${source.length()}_thumb.jpg"
                .let { PdfFileUtils.sanitizeFileName(it, "thumbnail.jpg") }
        }.toSet()
        thumbsDir.listFiles()?.forEach { cached ->
            if (cached.name !in validThumbnailNames) cached.delete()
        }
        }
    }

    suspend fun savePdf(file: File, newName: String? = null): File = withContext(Dispatchers.IO) {
        PdfFileUtils.requirePdf(file)
        val safeName = PdfFileUtils.sanitizeFileName(newName ?: file.nameWithoutExtension, "dokumen")
            .let { name -> if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name }
            .ifBlank { "dokumen" }
        val requestedDestination = File(docsDir, "$safeName.pdf")
        val destFile = when {
            file.canonicalFile == requestedDestination.canonicalFile -> requestedDestination
            requestedDestination.exists() -> PdfFileUtils.uniqueFile(docsDir, safeName, "pdf")
            else -> requestedDestination
        }
        if (file.canonicalFile != destFile.canonicalFile) {
            PdfFileUtils.writeAtomically(destFile, minimumBytes = 5L) { temporary ->
                file.copyTo(temporary, overwrite = true)
            }
        }
        refreshDocuments()
        destFile
    }

    suspend fun createNewDocumentFile(baseTitle: String): File = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanTitle = PdfFileUtils.sanitizeFileName(baseTitle, "Dokumen").ifBlank { "Dokumen" }
        PdfFileUtils.uniqueFile(docsDir, "${cleanTitle}_$timeStamp", "pdf")
    }

    suspend fun deleteDocument(item: SavedDocumentItem): Boolean = withContext(Dispatchers.IO) {
        requireManagedDocument(item.file)
        val thumbnailKey = "${item.file.nameWithoutExtension}_${item.file.lastModified()}_${item.file.length()}_thumb.jpg"
        File(thumbsDir, PdfFileUtils.sanitizeFileName(thumbnailKey, "thumbnail.jpg")).delete()
        val deleted = item.file.delete()
        refreshDocuments()
        deleted
    }

    suspend fun renameDocument(item: SavedDocumentItem, newTitle: String): File = withContext(Dispatchers.IO) {
        requireManagedDocument(item.file)
        val clean = PdfFileUtils.sanitizeFileName(newTitle, "")
            .let { name -> if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name }
        require(clean.isNotBlank()) { "Nama dokumen tidak boleh kosong" }
        val newFile = File(docsDir, "$clean.pdf")
        require(item.file.canonicalFile == newFile.canonicalFile || !newFile.exists()) {
            "Dokumen bernama '$clean' sudah ada"
        }
        if (item.file.canonicalFile != newFile.canonicalFile) {
            require(item.file.renameTo(newFile)) { "Dokumen gagal diganti nama" }
        }
        refreshDocuments()
        newFile
    }

    private fun requireManagedDocument(file: File) {
        require(file.canonicalFile.parentFile == docsDir.canonicalFile) {
            "Operasi hanya diizinkan untuk dokumen di pustaka aplikasi"
        }
    }
}
