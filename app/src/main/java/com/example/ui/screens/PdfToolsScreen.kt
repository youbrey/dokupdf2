package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.core.ai.GeminiAiService
import com.example.core.pdf.*
import com.example.core.repository.SavedDocumentItem
import com.example.ui.components.SleekTopAppBar
import com.example.ui.components.rememberStorageExportGate
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class ToolDefinition(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val iconColor: Color,
    val bgColor: Color,
    val category: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfToolsScreen(
    documents: List<SavedDocumentItem>,
    initialToolId: String? = null,
    initialCategory: String? = null,
    onBack: () -> Unit,
    onRefreshDocuments: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mergerSplitter = remember { PdfMergerSplitter(context) }
    val compressor = remember { PdfCompressor(context) }
    val repairEngine = remember { PdfRepairEngine(context) }
    val converterEngine = remember { PdfConverterEngine(context) }
    val pdfSecurity = remember { PdfSecurity(context) }
    val pdfComparer = remember { PdfComparer(context) }
    val aiService = remember { GeminiAiService() }

    var selectedCategory by remember { mutableStateOf("Semua") }
    val categories = listOf("Semua", "Konversi", "Organisir", "Optimasi", "AI Pro")

    // Active tool state for dialog / action
    var activeActionTool by remember { mutableStateOf<ToolDefinition?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var copyableResultText by remember { mutableStateOf<String?>(null) }
    var resultFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var resultIsError by remember { mutableStateOf(false) }

    // [Fitur baru] "Simpan ke Perangkat" -- lihat ExportUtils.kt untuk penjelasan lengkap
    // kenapa fitur ini belum pernah ada sebelumnya (proyek hanya punya jalur share sheet).
    var isSavingToDevice by remember { mutableStateOf(false) }

    // [Refactor] Dance permintaan izin runtime (API 24-28) diekstrak ke
    // rememberStorageExportGate -- lihat ui/components/ExportPermissionGate.kt untuk alasan.
    val requestExportPermission = rememberStorageExportGate(
        onPermissionDenied = {
            Toast.makeText(
                context,
                "Izin penyimpanan diperlukan untuk menyimpan berkas ke Download",
                Toast.LENGTH_LONG
            ).show()
        }
    )

    fun saveResultFilesToDevice(files: List<File>) {
        if (files.isEmpty()) return
        requestExportPermission {
            scope.launch { performSaveToDevice(context, files) { isSavingToDevice = it } }
        }
    }

    // Dynamic parameter states
    val inAppPdfDocs = remember(documents) { documents.filter { it.file.extension.equals("pdf", ignoreCase = true) } }
    var selectedPdfIndex by remember { mutableStateOf(0) }
    var selectedSecondPdfIndex by remember { mutableStateOf(1.coerceAtMost(inAppPdfDocs.lastIndex)) }

    LaunchedEffect(inAppPdfDocs.size) {
        if (inAppPdfDocs.isEmpty()) {
            selectedPdfIndex = 0
            selectedSecondPdfIndex = 0
        } else {
            selectedPdfIndex = selectedPdfIndex.coerceIn(inAppPdfDocs.indices)
            selectedSecondPdfIndex = selectedSecondPdfIndex.coerceIn(inAppPdfDocs.indices)
            if (inAppPdfDocs.size > 1 && selectedSecondPdfIndex == selectedPdfIndex) {
                selectedSecondPdfIndex = (selectedPdfIndex + 1) % inAppPdfDocs.size
            }
        }
    }
    
    // Custom Picked Files from Device Storage
    var customPrimaryPdfFile by remember { mutableStateOf<File?>(null) }
    var customSecondPdfFile by remember { mutableStateOf<File?>(null) }
    val customMultiplePdfFiles = remember { mutableStateListOf<File>() }
    
    var passwordInput by remember { mutableStateOf("") }
    var rotationAngle by remember { mutableStateOf(90) }
    var pagesPerSplit by remember { mutableStateOf(1) }
    var targetLanguage by remember { mutableStateOf("English") }
    var compressionTier by remember { mutableStateOf(CompressionLevel.HIGH) }

    val documentsOutputDir = remember {
        File(context.filesDir, "documents")
    }
    val toolsOutputDir = remember {
        File(context.filesDir, "tools_output")
    }

    fun reportSuccess(message: String, files: List<File> = emptyList(), copyText: String? = null) {
        resultMessage = message
        resultFiles = files.filter { it.exists() && it.length() > 0L }
        copyableResultText = copyText
        resultIsError = false
    }

    fun reportFailure(message: String) {
        resultMessage = message
        resultFiles = emptyList()
        copyableResultText = null
        resultIsError = true
    }

    fun previewText(text: String, maximumCharacters: Int = 6_000): String =
        if (text.length <= maximumCharacters) text
        else text.take(maximumCharacters) + "\n\n… Pratinjau dipotong; gunakan Salin Teks untuk hasil lengkap."

    fun clearTemporaryInputs() {
        listOfNotNull(customPrimaryPdfFile, customSecondPdfFile)
            .filter { it.parentFile == context.cacheDir }
            .forEach { it.delete() }
        customMultiplePdfFiles.filter { it.parentFile == context.cacheDir }.forEach { it.delete() }
        customPrimaryPdfFile = null
        customSecondPdfFile = null
        customMultiplePdfFiles.clear()
        passwordInput = ""
    }

    fun clearPrimaryInput() {
        customPrimaryPdfFile?.takeIf { it.parentFile == context.cacheDir }?.delete()
        customPrimaryPdfFile = null
    }

    fun clearSecondInput() {
        customSecondPdfFile?.takeIf { it.parentFile == context.cacheDir }?.delete()
        customSecondPdfFile = null
    }

    fun shareResultFiles(files: List<File>) {
        if (files.isEmpty()) return
        runCatching {
            val uris = ArrayList(files.map { file ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeTypeFor(files.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }.apply {
                clipData = ClipData.newUri(context.contentResolver, "Hasil DokuPDF", uris.first()).also { clips ->
                    uris.drop(1).forEach { uri -> clips.addItem(ClipData.Item(uri)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Bagikan hasil DokuPDF"))
        }.onFailure { error ->
            Toast.makeText(context, "Hasil tidak dapat dibagikan: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Copy SAF content off the main thread, sanitize its name, and enforce a storage limit.
    suspend fun copyUriToCache(
        uri: Uri,
        prefix: String,
        validatePdf: Boolean = true,
        fallbackExtension: String = if (validatePdf) "pdf" else "bin",
        maximumBytes: Long = if (validatePdf) {
            PdfFileUtils.MAX_PDF_INPUT_BYTES
        } else {
            PdfFileUtils.MAX_OFFICE_INPUT_BYTES
        }
    ): File? =
        withContext(Dispatchers.IO) {
            try {
                var fileName = "$prefix-${System.currentTimeMillis()}.$fallbackExtension"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) fileName = cursor.getString(nameIndex).orEmpty()
                }
                fileName = PdfFileUtils.sanitizeFileName(
                    fileName,
                    "$prefix-${System.currentTimeMillis()}.$fallbackExtension"
                )
                val lastDot = fileName.lastIndexOf('.')
                val hasUsableExtension = lastDot in 1 until fileName.lastIndex
                val requestedExtension = if (hasUsableExtension) {
                    fileName.substring(lastDot + 1)
                } else {
                    fallbackExtension
                }
                val extension = requestedExtension
                    .lowercase(Locale.ROOT)
                    .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
                    ?: fallbackExtension
                val baseName = if (hasUsableExtension) fileName.substring(0, lastDot) else fileName
                val temporary = PdfFileUtils.uniqueFile(
                    context.cacheDir,
                    baseName,
                    extension
                )
                try {
                    val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Berkas tidak dapat dibuka" }
                    input.use { source ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                copied += read
                                require(copied <= maximumBytes) {
                                    "Berkas melebihi batas ${PdfFileUtils.formatBytes(maximumBytes)}"
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    PdfFileUtils.requireReadableFile(temporary)
                    if (validatePdf) PdfFileUtils.requirePdf(temporary)
                    temporary
                } catch (oom: OutOfMemoryError) {
                    temporary.delete()
                    throw oom
                } catch (error: Exception) {
                    temporary.delete()
                    throw error
                }
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (error: Exception) {
                Log.e("DokuPdfTools", "Berkas SAF gagal disalin", error)
                null
            }
        }

    DisposableEffect(Unit) {
        onDispose {
            listOfNotNull(customPrimaryPdfFile, customSecondPdfFile)
                .filter { it.parentFile == context.cacheDir }
                .forEach { it.delete() }
            customMultiplePdfFiles.filter { it.parentFile == context.cacheDir }.forEach { it.delete() }
            converterEngine.close()
        }
    }

    // External Device Pickers
    val singlePdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            scope.launch {
                try {
                    val isEncryptedContainer = activeActionTool?.id == "unlock_pdf"
                    val file = copyUriToCache(
                        uri,
                        "device_pdf",
                        validatePdf = !isEncryptedContainer,
                        fallbackExtension = if (isEncryptedContainer) "dokupdf" else "pdf",
                        maximumBytes = PdfFileUtils.MAX_PDF_INPUT_BYTES
                    )
                    customPrimaryPdfFile?.takeIf { it.parentFile == context.cacheDir }?.delete()
                    customPrimaryPdfFile = file
                    Toast.makeText(
                        context,
                        file?.let { "Berkas dipilih: ${it.name}" } ?: "Gagal memuat atau memvalidasi berkas",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: OutOfMemoryError) {
                    Toast.makeText(context, "Memori tidak cukup untuk memuat berkas ini.", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val secondPdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            scope.launch {
                try {
                    val file = copyUriToCache(uri, "device_pdf_2")
                    customSecondPdfFile?.takeIf { it.parentFile == context.cacheDir }?.delete()
                    customSecondPdfFile = file
                    Toast.makeText(
                        context,
                        file?.let { "Berkas kedua dipilih: ${it.name}" } ?: "Berkas kedua tidak valid",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: OutOfMemoryError) {
                    Toast.makeText(context, "Memori tidak cukup untuk memuat berkas kedua.", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val multiPdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isProcessing = true
            scope.launch {
                val loaded = mutableListOf<File>()
                try {
                    for ((i, uri) in uris.withIndex()) {
                        copyUriToCache(uri, "merge_$i")?.let { loaded.add(it) }
                    }
                    customMultiplePdfFiles.filter { it.parentFile == context.cacheDir }.forEach { it.delete() }
                    customMultiplePdfFiles.clear()
                    customMultiplePdfFiles.addAll(loaded)
                    Toast.makeText(context, "${loaded.size} PDF valid dipilih dari perangkat", Toast.LENGTH_SHORT).show()
                } catch (_: OutOfMemoryError) {
                    loaded.forEach { it.delete() }
                    Toast.makeText(context, "Memori tidak cukup untuk memuat seluruh PDF.", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isProcessing = true
            scope.launch {
                val tempImages = mutableListOf<File>()
                try {
                    for ((idx, uri) in uris.withIndex()) {
                        copyUriToCache(uri, "picked_img_$idx", validatePdf = false, fallbackExtension = "img")
                            ?.let(tempImages::add)
                    }

                    if (tempImages.isNotEmpty()) {
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val outFile = PdfFileUtils.uniqueFile(documentsOutputDir, "Foto_Ke_PDF_$timeStamp", "pdf")
                        val res = converterEngine.imagesToPdf(tempImages, outFile)
                        if (res.isSuccess) {
                            reportSuccess(
                                "Berhasil mengonversi ${tempImages.size} foto menjadi PDF:\n${outFile.name}",
                                listOf(outFile)
                            )
                        } else {
                            reportFailure("Gagal konversi gambar ke PDF: ${res.exceptionOrNull()?.message}")
                        }
                    } else {
                        reportFailure("Tidak ada gambar yang berhasil dimuat.")
                    }
                } catch (_: OutOfMemoryError) {
                    reportFailure("Memori tidak cukup untuk mengonversi kumpulan gambar ini.")
                } catch (e: Exception) {
                    reportFailure("Terjadi kesalahan: ${e.message}")
                } finally {
                    tempImages.forEach { it.delete() }
                    isProcessing = false
                    onRefreshDocuments()
                }
            }
        }
    }

    val docxFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            scope.launch {
                var tempDocument: File? = null
                try {
                    val fallbackExtension = if (
                        context.contentResolver.getType(uri).orEmpty().startsWith("text/")
                    ) "txt" else "docx"
                    val selectedDocument = copyUriToCache(
                        uri,
                        "picked_doc",
                        validatePdf = false,
                        fallbackExtension = fallbackExtension
                    ) ?: error("Berkas tidak dapat disalin")
                    tempDocument = selectedDocument
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val outFile = PdfFileUtils.uniqueFile(documentsOutputDir, "Word_Ke_PDF_$timeStamp", "pdf")
                    val res = converterEngine.wordToPdf(selectedDocument, outFile)
                    if (res.isSuccess) {
                        reportSuccess("Berhasil mengonversi dokumen Word ke PDF:\n${outFile.name}", listOf(outFile))
                    } else {
                        reportFailure("Gagal konversi Word ke PDF: ${res.exceptionOrNull()?.message}")
                    }
                } catch (_: OutOfMemoryError) {
                    reportFailure("Memori tidak cukup untuk mengonversi dokumen Word/Teks ini.")
                } catch (e: Exception) {
                    reportFailure("Terjadi kesalahan: ${e.message}")
                } finally {
                    tempDocument?.delete()
                    isProcessing = false
                    onRefreshDocuments()
                }
            }
        }
    }

    val csvFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            scope.launch {
                var tempSpreadsheet: File? = null
                try {
                    val mimeType = context.contentResolver.getType(uri).orEmpty()
                    val fallbackExtension = if (mimeType == "text/csv" || mimeType.startsWith("text/")) {
                        "csv"
                    } else {
                        "xlsx"
                    }
                    val selectedSpreadsheet = copyUriToCache(
                        uri,
                        "picked_spreadsheet",
                        validatePdf = false,
                        fallbackExtension = fallbackExtension
                    ) ?: error("Berkas tidak dapat disalin")
                    tempSpreadsheet = selectedSpreadsheet
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val outFile = PdfFileUtils.uniqueFile(documentsOutputDir, "Excel_Ke_PDF_$timeStamp", "pdf")
                    val res = converterEngine.excelToPdf(selectedSpreadsheet, outFile)
                    if (res.isSuccess) {
                        reportSuccess("Berhasil mengonversi data Excel/CSV ke PDF:\n${outFile.name}", listOf(outFile))
                    } else {
                        reportFailure("Gagal konversi Excel ke PDF: ${res.exceptionOrNull()?.message}")
                    }
                } catch (_: OutOfMemoryError) {
                    reportFailure("Memori tidak cukup untuk mengonversi spreadsheet ini.")
                } catch (e: Exception) {
                    reportFailure("Terjadi kesalahan: ${e.message}")
                } finally {
                    tempSpreadsheet?.delete()
                    isProcessing = false
                    onRefreshDocuments()
                }
            }
        }
    }

    val tools = remember {
        listOf(
            ToolDefinition(
                id = "merge_pdf",
                title = "Gabungkan PDF",
                description = "Gabungkan 2 atau lebih berkas PDF menjadi satu dokumen",
                icon = Icons.AutoMirrored.Outlined.CallMerge,
                iconColor = SleekBluePrimary,
                bgColor = SleekBlueLight,
                category = "Organisir"
            ),
            ToolDefinition(
                id = "split_pdf",
                title = "Pisahkan PDF",
                description = "Bagi halaman PDF per halaman atau bagian terpisah",
                icon = Icons.AutoMirrored.Outlined.CallSplit,
                iconColor = AccentIndigo,
                bgColor = AccentIndigoBg,
                category = "Organisir"
            ),
            ToolDefinition(
                id = "rotate_pdf",
                title = "Putar PDF",
                description = "Putar orientasi halaman PDF 90°, 180°, atau 270°",
                icon = Icons.AutoMirrored.Outlined.RotateRight,
                iconColor = SleekBluePrimary,
                bgColor = SleekBlueLight,
                category = "Organisir"
            ),
            ToolDefinition(
                id = "compare_pdf",
                title = "Bandingkan PDF",
                description = "Bandingkan 2 dokumen & temukan perbedaan secara presisi",
                icon = Icons.Outlined.Compare,
                iconColor = AccentIndigo,
                bgColor = AccentIndigoBg,
                category = "Organisir"
            ),
            ToolDefinition(
                id = "compress_pdf",
                title = "Kompres PDF",
                description = "Optimalkan ukuran PDF dan pertahankan hasil asli bila sudah lebih kecil",
                icon = Icons.Outlined.Compress,
                iconColor = AccentEmerald,
                bgColor = AccentEmeraldBg,
                category = "Optimasi"
            ),
            ToolDefinition(
                id = "lock_pdf",
                title = "Enkripsi Dokumen",
                description = "Buat kontainer .dokupdf terenkripsi AES-256-GCM",
                icon = Icons.Outlined.Lock,
                iconColor = Color(0xFFDC2626),
                bgColor = Color(0xFFFEE2E2),
                category = "Optimasi"
            ),
            ToolDefinition(
                id = "unlock_pdf",
                title = "Dekripsi Dokumen",
                description = "Pulihkan kontainer .dokupdf menjadi PDF",
                icon = Icons.Outlined.LockOpen,
                iconColor = AccentEmerald,
                bgColor = AccentEmeraldBg,
                category = "Optimasi"
            ),
            ToolDefinition(
                id = "repair_pdf",
                title = "Perbaiki PDF Rusak",
                description = "Pulihkan struktur internal berkas PDF yang korup",
                icon = Icons.Outlined.Build,
                iconColor = Color(0xFF64748B),
                bgColor = Color(0xFFF1F5F9),
                category = "Optimasi"
            ),
            ToolDefinition(
                id = "pdf_to_word",
                title = "PDF ke Word (DOCX)",
                description = "Ekstrak teks semua halaman dengan OCR ke DOCX yang dapat diedit",
                icon = Icons.Outlined.Description,
                iconColor = SleekBluePrimary,
                bgColor = SleekBlueLight,
                category = "Konversi"
            ),
            ToolDefinition(
                id = "word_to_pdf",
                title = "Word ke PDF",
                description = "Pilih berkas DOCX/TXT dan ubah menjadi dokumen PDF",
                icon = Icons.Outlined.PictureAsPdf,
                iconColor = Color(0xFFDC2626),
                bgColor = Color(0xFFFEE2E2),
                category = "Konversi"
            ),
            ToolDefinition(
                id = "image_to_pdf",
                title = "Foto ke PDF",
                description = "Pilih beberapa foto dari galeri menjadi satu dokumen PDF",
                icon = Icons.Outlined.Image,
                iconColor = AccentAmber,
                bgColor = AccentAmberBg,
                category = "Konversi"
            ),
            ToolDefinition(
                id = "pdf_to_image",
                title = "PDF ke Gambar",
                description = "Ekstrak setiap halaman PDF menjadi berkas gambar JPEG",
                icon = Icons.Outlined.PhotoLibrary,
                iconColor = AccentOrange,
                bgColor = AccentOrangeBg,
                category = "Konversi"
            ),
            ToolDefinition(
                id = "pdf_to_long_image",
                title = "PDF ke Gambar Panjang",
                description = "Jahit seluruh halaman PDF menjadi satu gambar panjang utuh",
                icon = Icons.Outlined.VerticalSplit,
                iconColor = AccentIndigo,
                bgColor = AccentIndigoBg,
                category = "Konversi"
            ),
            ToolDefinition(
                id = "excel_to_pdf",
                title = "Excel ke PDF",
                description = "Pilih berkas CSV/XLSX dan ubah semua lembar menjadi tabel PDF",
                icon = Icons.Outlined.TableChart,
                iconColor = AccentEmerald,
                bgColor = AccentEmeraldBg,
                category = "Konversi"
            ),
            ToolDefinition(
                id = "pdf_to_excel",
                title = "PDF ke Excel (CSV)",
                description = "Ekstrak teks per halaman dengan OCR ke spreadsheet CSV",
                icon = Icons.Outlined.GridOn,
                iconColor = AccentEmerald,
                bgColor = AccentEmeraldBg,
                category = "Konversi"
            ),
            ToolDefinition(
                id = "ocr_text",
                title = "OCR Ekstraksi Teks",
                description = "Pindai & ekstrak teks nyata dari dokumen via On-Device ML Kit",
                icon = Icons.Outlined.TextFields,
                iconColor = AccentIndigo,
                bgColor = AccentIndigoBg,
                category = "AI Pro"
            ),
            ToolDefinition(
                id = "ai_translate",
                title = "Terjemahkan PDF",
                description = "Ekstrak teks dokumen lalu terjemahkan via AI cerdas",
                icon = Icons.Outlined.Translate,
                iconColor = SleekBluePrimary,
                bgColor = SleekBlueLight,
                category = "AI Pro"
            ),
            ToolDefinition(
                id = "ai_spellcheck",
                title = "Pemeriksa Ejaan AI",
                description = "Periksa kesalahan tata bahasa & ejaan dokumen via AI",
                icon = Icons.Outlined.Spellcheck,
                iconColor = AccentAmber,
                bgColor = AccentAmberBg,
                category = "AI Pro"
            )
        )
    }

    val filteredTools = remember(selectedCategory, tools) {
        if (selectedCategory == "Semua") tools
        else tools.filter { it.category == selectedCategory }
    }

    LaunchedEffect(initialToolId, initialCategory) {
        val requestedTool = tools.firstOrNull { it.id == initialToolId }
        if (requestedTool != null) {
            clearTemporaryInputs()
            resultMessage = null
            copyableResultText = null
            resultFiles = emptyList()
            resultIsError = false
            selectedCategory = requestedTool.category
            activeActionTool = requestedTool
        } else {
            initialCategory
                ?.takeIf { it in categories }
                ?.let { selectedCategory = it }
        }
    }

    Scaffold(
        topBar = {
            SleekTopAppBar(
                title = "Pusat Alat PDF & AI",
                subtitle = "18 alat pengolah dokumen yang berfungsi nyata",
                onNavigationClick = onBack,
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack
            )
        },
        containerColor = SleekBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Category Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { selectedCategory = cat }
                            .border(
                                1.dp,
                                if (isSelected) SleekBluePrimary else Slate200,
                                RoundedCornerShape(20.dp)
                            )
                            .testTag("tool_filter_$cat"),
                        color = if (isSelected) SleekBluePrimary else SleekSurface
                    ) {
                        Text(
                            text = cat,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected) Color.White else Slate700,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Grid of tools
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(filteredTools) { tool ->
                    ToolGridCard(
                        tool = tool,
                        onClick = {
                            clearTemporaryInputs()
                            resultMessage = null
                            copyableResultText = null
                            resultFiles = emptyList()
                            resultIsError = false
                            passwordInput = ""
                            activeActionTool = tool
                        }
                    )
                }
            }
        }
    }

    // Tool Execution Dialog
    activeActionTool?.let { tool ->
        // Effective primary PDF: custom device file OR chosen in-app document
        val targetPdf = if (tool.id == "unlock_pdf") {
            customPrimaryPdfFile
        } else {
            customPrimaryPdfFile ?: inAppPdfDocs.getOrNull(selectedPdfIndex)?.file
        }
        val targetSecondPdf = customSecondPdfFile ?: inAppPdfDocs.getOrNull(selectedSecondPdfIndex)?.file

        AlertDialog(
            onDismissRequest = {
                if (!isProcessing) {
                    clearTemporaryInputs()
                    activeActionTool = null
                    resultMessage = null
                    copyableResultText = null
                    resultFiles = emptyList()
                    resultIsError = false
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tool.bgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = tool.icon, contentDescription = null, tint = tool.iconColor, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = tool.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(text = tool.description, style = MaterialTheme.typography.bodySmall, color = Slate600)
                    }

                    // Result message view
                    resultMessage?.let { message ->
                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (resultIsError) Color(0xFFFEE2E2) else SleekBlueLight,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = if (resultIsError) Color(0xFF991B1B) else SleekBlueDark
                                    )

                                    if (copyableResultText != null) {
                                        Button(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("DokuPDF Result", copyableResultText)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Teks disalin ke papan klip", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = SleekBluePrimary),
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Salin Teks", fontSize = 12.sp)
                                        }
                                    }

                                    if (resultFiles.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            // [Fitur baru] "Simpan ke Perangkat" -- ExportUtils.kt.
                                            // Sebelumnya hanya ada "Bagikan Hasil" (share sheet);
                                            // tombol ini menyalin berkas ke Download/DokuPDF/ publik
                                            // supaya bisa dibuka lewat aplikasi File Manager/Galeri
                                            // tanpa harus melalui aplikasi perantara lain.
                                            OutlinedButton(
                                                onClick = { saveResultFilesToDevice(resultFiles) },
                                                enabled = !isSavingToDevice,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentEmerald)
                                            ) {
                                                if (isSavingToDevice) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Simpan ke Perangkat", fontSize = 12.sp)
                                            }
                                            Button(
                                                onClick = { shareResultFiles(resultFiles) },
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    if (resultFiles.size == 1) "Bagikan Hasil" else "Bagikan ${resultFiles.size} Hasil",
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }

                                    if (resultIsError) {
                                        OutlinedButton(
                                            onClick = {
                                                resultMessage = null
                                                resultIsError = false
                                                resultFiles = emptyList()
                                                copyableResultText = null
                                            },
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Coba Lagi", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Input Pickers & Selectors
                    if (resultMessage == null && !isProcessing) {
                        when (tool.id) {
                            "image_to_pdf" -> {
                                item {
                                    Button(
                                        onClick = { imagePickerLauncher.launch("image/*") },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Pilih Foto dari Galeri / Perangkat")
                                    }
                                }
                            }
                            "word_to_pdf" -> {
                                item {
                                    Button(
                                        onClick = { docxFilePicker.launch("*/*") },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.UploadFile, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Pilih Berkas DOCX / TXT dari Perangkat")
                                    }
                                }
                            }
                            "excel_to_pdf" -> {
                                item {
                                    Button(
                                        onClick = { csvFilePicker.launch("*/*") },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.TableChart, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Pilih Berkas CSV / XLSX dari Perangkat")
                                    }
                                }
                            }
                            "merge_pdf" -> {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "Pilih berkas PDF yang ingin digabungkan:",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate700
                                        )
                                        
                                        Button(
                                            onClick = { multiPdfPicker.launch("application/pdf") },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = SleekBluePrimary)
                                        ) {
                                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Unggah PDF dari Direktori Perangkat")
                                        }

                                        if (customMultiplePdfFiles.isNotEmpty()) {
                                            Surface(
                                                color = Color(0xFFF1F5F9),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Text(
                                                        "Berkas dipilih dari perangkat (${customMultiplePdfFiles.size}):",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = SleekBlueDark
                                                    )
                                                    customMultiplePdfFiles.forEachIndexed { i, f ->
                                                        Text("${i + 1}. ${f.name}", style = MaterialTheme.typography.bodySmall, color = Slate800)
                                                    }
                                                }
                                            }
                                        } else if (inAppPdfDocs.isNotEmpty()) {
                                            Text(
                                                "Atau gabungkan semua dokumen yang ada di aplikasi (${inAppPdfDocs.size} berkas):",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Slate500
                                            )
                                            inAppPdfDocs.take(4).forEach { doc ->
                                                Text("• ${doc.title}", style = MaterialTheme.typography.bodySmall, color = Slate700)
                                            }
                                        }
                                    }
                                }
                            }
                            "lock_pdf", "unlock_pdf" -> {
                                item {
                                    DocumentSelector(
                                        label = if (tool.id == "unlock_pdf") "Pilih kontainer .dokupdf:" else "Pilih Dokumen PDF:",
                                        pdfDocs = if (tool.id == "unlock_pdf") emptyList() else inAppPdfDocs,
                                        customFile = customPrimaryPdfFile,
                                        selectedIndex = selectedPdfIndex,
                                        onSelectInApp = {
                                            selectedPdfIndex = it
                                            clearPrimaryInput()
                                        },
                                        onPickFromDevice = {
                                            singlePdfPicker.launch(if (tool.id == "unlock_pdf") "*/*" else "application/pdf")
                                        },
                                        onClearCustomFile = ::clearPrimaryInput
                                    )
                                }
                                item {
                                    OutlinedTextField(
                                        value = passwordInput,
                                        onValueChange = { if (it.length <= 256) passwordInput = it },
                                        label = { Text("Kata Sandi") },
                                        placeholder = { Text("Masukkan kata sandi pengaman") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            "split_pdf" -> {
                                item {
                                    DocumentSelector(
                                        label = "Pilih Dokumen PDF:",
                                        pdfDocs = inAppPdfDocs,
                                        customFile = customPrimaryPdfFile,
                                        selectedIndex = selectedPdfIndex,
                                        onSelectInApp = {
                                            selectedPdfIndex = it
                                            clearPrimaryInput()
                                        },
                                        onPickFromDevice = { singlePdfPicker.launch("application/pdf") },
                                        onClearCustomFile = ::clearPrimaryInput
                                    )
                                }
                                item {
                                    Text(
                                        "Jumlah halaman per hasil:",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(1, 2, 5, 10).forEach { count ->
                                            FilterChip(
                                                selected = pagesPerSplit == count,
                                                onClick = { pagesPerSplit = count },
                                                label = { Text("$count halaman") }
                                            )
                                        }
                                    }
                                }
                            }
                            "rotate_pdf" -> {
                                item {
                                    DocumentSelector(
                                        label = "Pilih Dokumen PDF:",
                                        pdfDocs = inAppPdfDocs,
                                        customFile = customPrimaryPdfFile,
                                        selectedIndex = selectedPdfIndex,
                                        onSelectInApp = {
                                            selectedPdfIndex = it
                                            clearPrimaryInput()
                                        },
                                        onPickFromDevice = { singlePdfPicker.launch("application/pdf") },
                                        onClearCustomFile = ::clearPrimaryInput
                                    )
                                }
                                item {
                                    Text("Sudut Putaran:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(90, 180, 270).forEach { deg ->
                                            FilterChip(
                                                selected = rotationAngle == deg,
                                                onClick = { rotationAngle = deg },
                                                label = { Text("$deg°") }
                                            )
                                        }
                                    }
                                }
                            }
                            "compress_pdf" -> {
                                item {
                                    DocumentSelector(
                                        label = "Pilih Dokumen PDF:",
                                        pdfDocs = inAppPdfDocs,
                                        customFile = customPrimaryPdfFile,
                                        selectedIndex = selectedPdfIndex,
                                        onSelectInApp = {
                                            selectedPdfIndex = it
                                            clearPrimaryInput()
                                        },
                                        onPickFromDevice = { singlePdfPicker.launch("application/pdf") },
                                        onClearCustomFile = ::clearPrimaryInput
                                    )
                                }
                                item {
                                    Text("Tingkat Kompresi:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            CompressionLevel.LOW to "Rendah",
                                            CompressionLevel.BALANCED to "Sedang",
                                            CompressionLevel.HIGH to "Tinggi",
                                            CompressionLevel.EXTREME to "Ekstrem"
                                        ).forEach { (level, label) ->
                                            FilterChip(
                                                selected = compressionTier == level,
                                                onClick = { compressionTier = level },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                }
                            }
                            "compare_pdf" -> {
                                item {
                                    DocumentSelector(
                                        label = "Dokumen A (Utama):",
                                        pdfDocs = inAppPdfDocs,
                                        customFile = customPrimaryPdfFile,
                                        selectedIndex = selectedPdfIndex,
                                        onSelectInApp = {
                                            selectedPdfIndex = it
                                            clearPrimaryInput()
                                        },
                                        onPickFromDevice = { singlePdfPicker.launch("application/pdf") },
                                        onClearCustomFile = ::clearPrimaryInput
                                    )
                                }
                                item {
                                    DocumentSelector(
                                        label = "Dokumen B (Pembanding):",
                                        pdfDocs = inAppPdfDocs,
                                        customFile = customSecondPdfFile,
                                        selectedIndex = selectedSecondPdfIndex,
                                        onSelectInApp = {
                                            selectedSecondPdfIndex = it
                                            clearSecondInput()
                                        },
                                        onPickFromDevice = { secondPdfPicker.launch("application/pdf") },
                                        onClearCustomFile = ::clearSecondInput
                                    )
                                }
                            }
                            "ai_translate" -> {
                                item {
                                    DocumentSelector(
                                        label = "Pilih Dokumen PDF:",
                                        pdfDocs = inAppPdfDocs,
                                        customFile = customPrimaryPdfFile,
                                        selectedIndex = selectedPdfIndex,
                                        onSelectInApp = {
                                            selectedPdfIndex = it
                                            clearPrimaryInput()
                                        },
                                        onPickFromDevice = { singlePdfPicker.launch("application/pdf") },
                                        onClearCustomFile = ::clearPrimaryInput
                                    )
                                }
                                item {
                                    Text("Bahasa Tujuan:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    val languages = listOf("English", "Indonesian", "Japanese", "Arabic", "Mandarin", "French", "Spanish", "German")
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        languages.forEach { lang ->
                                            FilterChip(
                                                selected = targetLanguage == lang,
                                                onClick = { targetLanguage = lang },
                                                label = { Text(lang, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                            else -> {
                                item {
                                    DocumentSelector(
                                        label = "Pilih Dokumen PDF:",
                                        pdfDocs = inAppPdfDocs,
                                        customFile = customPrimaryPdfFile,
                                        selectedIndex = selectedPdfIndex,
                                        onSelectInApp = {
                                            selectedPdfIndex = it
                                            clearPrimaryInput()
                                        },
                                        onPickFromDevice = { singlePdfPicker.launch("application/pdf") },
                                        onClearCustomFile = ::clearPrimaryInput
                                    )
                                }
                            }
                        }
                    }

                    if (isProcessing) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = SleekBluePrimary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Memproses operasi dokumen secara nyata...", style = MaterialTheme.typography.bodySmall, color = Slate700)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (resultMessage != null) {
                    Button(
                        onClick = {
                            clearTemporaryInputs()
                            activeActionTool = null
                            resultMessage = null
                            copyableResultText = null
                            resultFiles = emptyList()
                            resultIsError = false
                            onRefreshDocuments()
                        }
                    ) {
                        Text("Selesai")
                    }
                } else if (tool.id != "image_to_pdf" && tool.id != "word_to_pdf" && tool.id != "excel_to_pdf") {
                    Button(
                        onClick = {
                            isProcessing = true
                            resultFiles = emptyList()
                            resultIsError = false
                            copyableResultText = null
                            scope.launch {
                                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

                                try {
                                    when (tool.id) {
                                        "merge_pdf" -> {
                                            val filesToMerge = if (customMultiplePdfFiles.isNotEmpty()) {
                                                customMultiplePdfFiles.toList()
                                            } else {
                                                inAppPdfDocs.map { it.file }
                                            }
                                            if (filesToMerge.size >= 2) {
                                                val outFile = PdfFileUtils.uniqueFile(
                                                    documentsOutputDir,
                                                    "PDF_Gabungan_$timeStamp",
                                                    "pdf"
                                                )
                                                val res = mergerSplitter.mergePdfs(filesToMerge, outFile)
                                                if (res.isSuccess) {
                                                    reportSuccess(
                                                        "Berhasil menggabungkan ${filesToMerge.size} PDF ke:\n${outFile.name}",
                                                        listOf(outFile)
                                                    )
                                                } else {
                                                    reportFailure("Gagal menggabungkan PDF: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih setidaknya 2 berkas PDF untuk digabungkan.")
                                            }
                                        }
                                        "split_pdf" -> {
                                            if (targetPdf != null) {
                                                val res = mergerSplitter.splitPdf(
                                                    targetPdf,
                                                    documentsOutputDir,
                                                    pagesPerSplit = pagesPerSplit
                                                )
                                                if (res.isSuccess) {
                                                    val outputs = res.getOrThrow()
                                                    reportSuccess(
                                                        "Berhasil memisahkan '${targetPdf.name}' menjadi ${outputs.size} PDF " +
                                                            "($pagesPerSplit halaman per berkas).",
                                                        outputs
                                                    )
                                                } else {
                                                    reportFailure("Gagal memisahkan PDF: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "rotate_pdf" -> {
                                            if (targetPdf != null) {
                                                val outFile = PdfFileUtils.uniqueFile(
                                                    documentsOutputDir,
                                                    "Rotasi_${rotationAngle}_${targetPdf.nameWithoutExtension}",
                                                    "pdf"
                                                )
                                                val res = converterEngine.rotatePdf(targetPdf, outFile, rotationAngle)
                                                if (res.isSuccess) {
                                                    reportSuccess(
                                                        "Berhasil memutar seluruh halaman ${rotationAngle}°:\n${outFile.name}",
                                                        listOf(outFile)
                                                    )
                                                } else {
                                                    reportFailure("Gagal memutar PDF: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "compress_pdf" -> {
                                            if (targetPdf != null) {
                                                val outFile = PdfFileUtils.uniqueFile(
                                                    documentsOutputDir,
                                                    "Kompres_${targetPdf.nameWithoutExtension}",
                                                    "pdf"
                                                )
                                                val res = compressor.compressPdf(targetPdf, outFile, compressionTier)
                                                if (res.isSuccess) {
                                                    val comp = res.getOrThrow()
                                                    val summary = if (comp.savedPercentage > 0) {
                                                        "Ukuran berkurang ${comp.savedPercentage}% menjadi ${PdfFileUtils.formatBytes(comp.compressedSizeBytes)}."
                                                    } else {
                                                        "Dokumen sudah optimal; salinan asli dipertahankan agar hasil tidak membesar."
                                                    }
                                                    reportSuccess("$summary\nDisimpan sebagai: ${outFile.name}", listOf(outFile))
                                                } else {
                                                    reportFailure("Gagal mengompresi PDF: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "lock_pdf" -> {
                                            if (targetPdf != null) {
                                                if (passwordInput.length < 8) {
                                                    reportFailure("Kata sandi minimal 8 karakter.")
                                                } else {
                                                    val outFile = PdfFileUtils.uniqueFile(
                                                        toolsOutputDir,
                                                        "Terkunci_${targetPdf.nameWithoutExtension}",
                                                        "dokupdf"
                                                    )
                                                    val res = pdfSecurity.lockPdf(targetPdf, outFile, passwordInput)
                                                    if (res.isSuccess) {
                                                        reportSuccess(
                                                            "Dokumen diamankan dengan AES-256-GCM dan PBKDF2:\n${outFile.name}\n\n" +
                                                                "Ini kontainer DokuPDF, bukan PDF yang dapat dibuka langsung.",
                                                            listOf(outFile)
                                                        )
                                                    } else {
                                                        reportFailure("Gagal mengenkripsi PDF: ${res.exceptionOrNull()?.message}")
                                                    }
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "unlock_pdf" -> {
                                            if (targetPdf != null) {
                                                if (passwordInput.isBlank()) {
                                                    reportFailure("Masukkan kata sandi pembuka kunci.")
                                                } else {
                                                    val outFile = PdfFileUtils.uniqueFile(
                                                        documentsOutputDir,
                                                        "Terbuka_${targetPdf.nameWithoutExtension}",
                                                        "pdf"
                                                    )
                                                    val res = pdfSecurity.unlockPdf(targetPdf, outFile, passwordInput)
                                                    if (res.isSuccess) {
                                                        reportSuccess(
                                                            "Kontainer berhasil didekripsi menjadi PDF:\n${outFile.name}",
                                                            listOf(outFile)
                                                        )
                                                    } else {
                                                        reportFailure("Gagal mendekripsi kontainer: ${res.exceptionOrNull()?.message}")
                                                    }
                                                }
                                            } else {
                                                reportFailure("Pilih kontainer .dokupdf terlebih dahulu.")
                                            }
                                        }
                                        "repair_pdf" -> {
                                            if (targetPdf != null) {
                                                val outFile = PdfFileUtils.uniqueFile(
                                                    documentsOutputDir,
                                                    "Dipulihkan_${targetPdf.nameWithoutExtension}",
                                                    "pdf"
                                                )
                                                val res = repairEngine.repairPdf(targetPdf, outFile)
                                                if (res.isSuccess) {
                                                    val report = res.getOrThrow()
                                                    reportSuccess(
                                                        "PDF berhasil divalidasi dan dibangun ulang.\nPerbaikan:\n" +
                                                            report.issuesFixed.joinToString("\n• ", prefix = "• "),
                                                        listOf(outFile)
                                                    )
                                                } else {
                                                    reportFailure("Gagal memperbaiki PDF: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "compare_pdf" -> {
                                            if (targetPdf != null && targetSecondPdf != null) {
                                                val res = pdfComparer.comparePdfs(targetPdf, targetSecondPdf)
                                                if (res.isSuccess) {
                                                    val comp = res.getOrThrow()
                                                    val differingPages = comp.pageResults.filter { it.hasDifferences }
                                                    val pageDetails = differingPages
                                                        .sortedByDescending { it.differencePercentage }
                                                        .take(12)
                                                        .joinToString("\n") { page ->
                                                            "• Halaman ${page.pageIndex + 1}: ${"%.1f".format(page.differencePercentage)}% berbeda"
                                                        }
                                                    val omitted = (differingPages.size - 12).coerceAtLeast(0)
                                                    reportSuccess(
                                                        "Tingkat kemiripan visual: ${"%.1f".format(comp.overallSimilarityPercentage)}%\n" +
                                                            "Halaman dibandingkan: ${comp.pageResults.size}\n" +
                                                            "Halaman berbeda: ${differingPages.size}" +
                                                            if (pageDetails.isBlank()) {
                                                                "\nTidak ada perbedaan visual signifikan."
                                                            } else {
                                                                "\n\nRincian per halaman:\n$pageDetails" +
                                                                    if (omitted > 0) "\n… dan $omitted halaman lain" else ""
                                                            }
                                                    )
                                                } else {
                                                    reportFailure("Gagal membandingkan PDF: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih 2 dokumen PDF untuk dibandingkan.")
                                            }
                                        }
                                        "pdf_to_word" -> {
                                            if (targetPdf != null) {
                                                val outFile = PdfFileUtils.uniqueFile(
                                                    toolsOutputDir,
                                                    targetPdf.nameWithoutExtension,
                                                    "docx"
                                                )
                                                val res = converterEngine.pdfToDocx(targetPdf, outFile)
                                                if (res.isSuccess) {
                                                    reportSuccess(
                                                        "Teks semua halaman diekstrak dengan OCR ke DOCX:\n${outFile.name}",
                                                        listOf(outFile)
                                                    )
                                                } else {
                                                    reportFailure("Gagal membuat DOCX: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "pdf_to_image" -> {
                                            if (targetPdf != null) {
                                                val imageDir = PdfFileUtils.uniqueDirectory(
                                                    toolsOutputDir,
                                                    PdfFileUtils.sanitizeFileName(
                                                        "${targetPdf.nameWithoutExtension}_gambar_$timeStamp",
                                                        "gambar_$timeStamp"
                                                    )
                                                )
                                                val res = converterEngine.pdfToImages(targetPdf, imageDir)
                                                if (res.isSuccess) {
                                                    val outputs = res.getOrThrow()
                                                    reportSuccess(
                                                        "Berhasil mengekstrak ${outputs.size} halaman JPEG. Tekan Bagikan untuk mengekspor.",
                                                        outputs
                                                    )
                                                } else {
                                                    imageDir.delete()
                                                    reportFailure("Gagal mengekstrak gambar: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "pdf_to_long_image" -> {
                                            if (targetPdf != null) {
                                                val outFile = PdfFileUtils.uniqueFile(
                                                    toolsOutputDir,
                                                    "Panjang_${targetPdf.nameWithoutExtension}",
                                                    "jpg"
                                                )
                                                val res = converterEngine.pdfToLongImage(targetPdf, outFile)
                                                if (res.isSuccess) {
                                                    reportSuccess(
                                                        "Seluruh halaman berhasil dijahit menjadi gambar panjang:\n${outFile.name}",
                                                        listOf(outFile)
                                                    )
                                                } else {
                                                    reportFailure("Gagal membuat gambar panjang: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "pdf_to_excel" -> {
                                            if (targetPdf != null) {
                                                val outFile = PdfFileUtils.uniqueFile(
                                                    toolsOutputDir,
                                                    targetPdf.nameWithoutExtension,
                                                    "csv"
                                                )
                                                val res = converterEngine.pdfToExcel(targetPdf, outFile)
                                                if (res.isSuccess) {
                                                    reportSuccess(
                                                        "Teks semua halaman diekstrak dengan OCR ke CSV:\n${outFile.name}",
                                                        listOf(outFile)
                                                    )
                                                } else {
                                                    reportFailure("Gagal membuat CSV: ${res.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "ocr_text" -> {
                                            if (targetPdf != null) {
                                                val extraction = converterEngine.extractTextFromPdf(targetPdf)
                                                if (extraction.isSuccess) {
                                                    val extractedText = extraction.getOrThrow()
                                                    reportSuccess(
                                                        "Hasil OCR hingga batas aman:\n\n${previewText(extractedText)}",
                                                        copyText = extractedText
                                                    )
                                                } else {
                                                    reportFailure("OCR gagal: ${extraction.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "ai_translate" -> {
                                            if (targetPdf != null) {
                                                val extraction = converterEngine.extractTextFromPdf(
                                                    targetPdf,
                                                    includePageHeaders = true,
                                                    maximumCharacters = 40_000
                                                )
                                                if (extraction.isSuccess) {
                                                    val translated = aiService.translateText(
                                                        extraction.getOrThrow(),
                                                        targetLanguage
                                                    )
                                                    if (translated.isSuccess) {
                                                        val text = translated.getOrThrow()
                                                        reportSuccess(
                                                            "Hasil terjemahan ($targetLanguage):\n\n${previewText(text)}",
                                                            copyText = text
                                                        )
                                                    } else {
                                                        reportFailure("Gagal menerjemahkan via AI: ${translated.exceptionOrNull()?.message}")
                                                    }
                                                } else {
                                                    reportFailure("Teks dokumen tidak dapat diekstrak: ${extraction.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        "ai_spellcheck" -> {
                                            if (targetPdf != null) {
                                                val extraction = converterEngine.extractTextFromPdf(
                                                    targetPdf,
                                                    includePageHeaders = true,
                                                    maximumCharacters = 40_000
                                                )
                                                if (extraction.isSuccess) {
                                                    val checked = aiService.checkSpellingAndGrammar(extraction.getOrThrow())
                                                    if (checked.isSuccess) {
                                                        val text = checked.getOrThrow()
                                                        reportSuccess(
                                                            "Analisis ejaan dan tata bahasa:\n\n${previewText(text)}",
                                                            copyText = text
                                                        )
                                                    } else {
                                                        reportFailure("Gagal memeriksa ejaan via AI: ${checked.exceptionOrNull()?.message}")
                                                    }
                                                } else {
                                                    reportFailure("Teks dokumen tidak dapat diekstrak: ${extraction.exceptionOrNull()?.message}")
                                                }
                                            } else {
                                                reportFailure("Pilih berkas PDF terlebih dahulu.")
                                            }
                                        }
                                        else -> {
                                            reportFailure("Handler alat '${tool.title}' tidak dikenali.")
                                        }
                                    }
                                } catch (e: Exception) {
                                    reportFailure("Terjadi kesalahan saat memproses: ${e.message ?: e::class.java.simpleName}")
                                } catch (oom: OutOfMemoryError) {
                                    reportFailure("Memori perangkat tidak cukup. Coba pisahkan dokumen atau gunakan kualitas lebih rendah.")
                                } finally {
                                    isProcessing = false
                                    onRefreshDocuments()
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.testTag("execute_tool_btn")
                    ) {
                        Text("Jalankan")
                    }
                }
            },
            dismissButton = {
                if (resultMessage == null) {
                    TextButton(
                        onClick = {
                            clearTemporaryInputs()
                            activeActionTool = null
                            resultFiles = emptyList()
                            resultIsError = false
                        },
                        enabled = !isProcessing
                    ) {
                        Text("Batal")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentSelector(
    label: String,
    pdfDocs: List<SavedDocumentItem>,
    customFile: File?,
    selectedIndex: Int,
    onSelectInApp: (Int) -> Unit,
    onPickFromDevice: () -> Unit,
    onClearCustomFile: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate700)

        // 1. Device File Picker Button
        OutlinedButton(
            onClick = onPickFromDevice,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (customFile != null) SleekBlueLight else Color.Transparent
            )
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = SleekBluePrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (customFile != null) "Ganti Berkas dari Perangkat" else "Cari / Unggah Berkas dari Direktori Perangkat",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 2. Custom File Selected Badge
        if (customFile != null) {
            Surface(
                color = SleekBlueLight,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SleekBluePrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SleekBluePrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = customFile.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = SleekBlueDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Dari Memori Perangkat • ${(customFile.length() / 1024).coerceAtLeast(1)} KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate600
                        )
                    }
                    IconButton(onClick = onClearCustomFile, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Batal", tint = Slate500, modifier = Modifier.size(16.dp))
                    }
                }
            }
        } else {
            // 3. Or Select from In-App saved documents
            if (pdfDocs.isNotEmpty()) {
                Text(
                    text = "Atau pilih dokumen yang tersimpan di aplikasi:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate500
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = pdfDocs.getOrNull(selectedIndex)?.title ?: pdfDocs.first().title,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFDC2626))
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        pdfDocs.forEachIndexed { index, document ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            document.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            "${document.pageCount} halaman • ${document.formattedSize}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Slate500
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectInApp(index)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolGridCard(
    tool: ToolDefinition,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Slate200, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("tool_card_${tool.id}"),
        color = SleekSurface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tool.bgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.title,
                    tint = tool.iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = tool.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Slate900,
                maxLines = 1
            )

            Text(
                text = tool.description,
                style = MaterialTheme.typography.labelSmall,
                color = Slate500,
                maxLines = 2,
                lineHeight = 14.sp
            )
        }
    }
}

// [Refactor] fungsi mimeTypeFor() dipindahkan ke com.example.core.pdf.ExportUtils.kt supaya
// satu sumber kebenaran dipakai bersama oleh layar ini dan ExportUtils (fitur "Simpan ke
// Perangkat"). Lihat import mimeTypeFor di atas.

/**
 * [Fitur baru] Menyimpan satu atau banyak berkas hasil PDF Tools ke Download/DokuPDF/ publik
 * lewat [ExportUtils]. Dipanggil dari [PdfToolsScreen] tombol "Simpan ke Perangkat".
 * Menampilkan Toast ringkasan hasil -- jujur soal berkas mana yang gagal jika ada, bukan
 * pesan sukses generik ketika sebagian ekspor sebenarnya gagal.
 */
private suspend fun performSaveToDevice(
    context: Context,
    files: List<File>,
    onBusyChange: (Boolean) -> Unit
) {
    onBusyChange(true)
    try {
        val results = ExportUtils.exportAllToDownloads(context, files)
        val successes = results.count { it.second is ExportUtils.ExportResult.Success }
        val permissionDenied = results.any { it.second is ExportUtils.ExportResult.PermissionRequired }
        val failures = results.mapNotNull { (file, result) ->
            (result as? ExportUtils.ExportResult.Failure)?.let { "${file.name}: ${it.message}" }
        }

        val message = when {
            permissionDenied -> "Izin penyimpanan belum diberikan -- berkas tidak disimpan."
            successes == results.size -> if (successes == 1) {
                val path = (results.first().second as ExportUtils.ExportResult.Success).displayPath
                "Tersimpan ke $path"
            } else {
                "$successes berkas tersimpan ke Download/${ExportUtils.EXPORT_SUBFOLDER}/"
            }
            successes > 0 -> "$successes dari ${results.size} berkas tersimpan. Gagal: ${failures.joinToString("; ")}"
            else -> "Gagal menyimpan berkas: ${failures.joinToString("; ")}"
        }

        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    } catch (t: Throwable) {
        Log.e("PdfToolsScreen", "Gagal menyimpan ke perangkat: ${t.message}", t)
        Toast.makeText(context, "Gagal menyimpan ke perangkat: ${t.message}", Toast.LENGTH_LONG).show()
    } finally {
        onBusyChange(false)
    }
}
