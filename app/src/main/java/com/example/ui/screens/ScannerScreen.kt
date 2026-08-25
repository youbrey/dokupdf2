package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.crop.AutoCropDetector
import com.example.core.filter.FilterProcessor
import com.example.core.model.CropGeometry
import com.example.core.model.FilterSettings
import com.example.core.model.FilterType
import com.example.core.ocr.OcrEngine
import com.example.core.pdf.ExportUtils
import com.example.core.pdf.PdfConverterEngine
import com.example.core.pdf.PdfFileUtils
import com.example.core.pdf.PdfRendererEngine
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Data item representing a single scanned page in a session
 */
data class ScannedPageItem(
    val id: String = UUID.randomUUID().toString(),
    val originalBitmap: Bitmap,
    val cropGeometry: CropGeometry? = null,
    val croppedBitmap: Bitmap? = null,
    val rotationDegrees: Int = 0,
    val filterType: FilterType = FilterType.AUTO,
    val filterSettings: FilterSettings = FilterSettings(),
    val watermarkText: String? = null,
    val ocrText: String? = null
) {
    fun getRenderedBitmap(maxDimension: Int? = null): Bitmap {
        require(!originalBitmap.isRecycled && originalBitmap.width > 0 && originalBitmap.height > 0) {
            "Bitmap sumber halaman tidak valid"
        }
        require(croppedBitmap == null || (!croppedBitmap.isRecycled && croppedBitmap.width > 0 && croppedBitmap.height > 0)) {
            "Bitmap crop halaman tidak valid"
        }
        // 1. Use cropped perspective bitmap if present, otherwise crop with geometry or use original.
        // Preview downsampling happens before perspective/filter passes to keep peak memory bounded.
        var ownsWorkingBitmap = false
        var workingBitmap: Bitmap = croppedBitmap ?: originalBitmap
        var renderedBitmap: Bitmap? = null
        var ownsRenderedBitmap = false

        try {
            if (maxDimension != null && maxDimension > 0) {
                val longest = maxOf(workingBitmap.width, workingBitmap.height)
                if (longest > maxDimension) {
                    val scale = maxDimension.toFloat() / longest
                    workingBitmap = Bitmap.createScaledBitmap(
                        workingBitmap,
                        (workingBitmap.width * scale).toInt().coerceAtLeast(1),
                        (workingBitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    ownsWorkingBitmap = workingBitmap !== originalBitmap && workingBitmap !== croppedBitmap
                }
            }

            if (croppedBitmap == null && cropGeometry != null) {
                val cropSource = workingBitmap
                try {
                    val cropped = FilterProcessor.cropPerspective(cropSource, cropGeometry)
                    if (ownsWorkingBitmap && cropped !== cropSource && !cropSource.isRecycled) {
                        cropSource.recycle()
                    }
                    workingBitmap = cropped
                    ownsWorkingBitmap = cropped !== originalBitmap && cropped !== croppedBitmap
                } catch (e: Exception) {
                    // Invalid geometry degrades to the still-valid preview source. OOM is deliberately
                    // not swallowed so callers can report an honest memory error.
                    workingBitmap = cropSource
                }
            }

            // 2. Ensure a pre-cropped bitmap supplied by a caller is also within the preview budget.
            if (maxDimension != null && maxDimension > 0) {
                val longest = maxOf(workingBitmap.width, workingBitmap.height)
                if (longest > maxDimension) {
                    val scale = maxDimension.toFloat() / longest
                    val scaled = Bitmap.createScaledBitmap(
                        workingBitmap,
                        (workingBitmap.width * scale).toInt().coerceAtLeast(1),
                        (workingBitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (ownsWorkingBitmap && workingBitmap !== scaled && !workingBitmap.isRecycled) workingBitmap.recycle()
                    workingBitmap = scaled
                    ownsWorkingBitmap = true
                }
            }

            // 3. Apply rotation if any
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true
                )
                if (ownsWorkingBitmap && workingBitmap !== rotated && !workingBitmap.isRecycled) workingBitmap.recycle()
                workingBitmap = rotated
                ownsWorkingBitmap = true
            }

            // 4. Apply scanner enhancement preset and professional fine controls.
            var rendered = FilterProcessor.applyFilter(workingBitmap, filterType, filterSettings)
            var ownsRendered = ownsWorkingBitmap || rendered !== workingBitmap
            renderedBitmap = rendered
            ownsRenderedBitmap = ownsRendered
            if (rendered !== workingBitmap && ownsWorkingBitmap && !workingBitmap.isRecycled) workingBitmap.recycle()

            // 5. Apply watermark if present
            if (!watermarkText.isNullOrBlank()) {
                val watermarked = drawWatermark(rendered, watermarkText)
                if (ownsRendered && rendered !== watermarked && !rendered.isRecycled) rendered.recycle()
                rendered = watermarked
                ownsRendered = true
                renderedBitmap = rendered
                ownsRenderedBitmap = true
            }

            return if (!ownsRendered || rendered === originalBitmap || rendered === croppedBitmap) {
                rendered.copy(rendered.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                // Ownership is transferred to the caller; the failure cleanup below must not run.
                ownsRenderedBitmap = false
                rendered
            }
        } catch (error: Throwable) {
            val ownedRendered = renderedBitmap
            if (ownsRenderedBitmap && ownedRendered != null && !ownedRendered.isRecycled) {
                ownedRendered.recycle()
            } else if (ownsWorkingBitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            throw error
        }
    }

    private fun drawWatermark(src: Bitmap, text: String): Bitmap {
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(70, 0, 0, 0)
            textSize = (result.width * 0.05f).coerceIn(24f, 72f)
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.save()
        canvas.rotate(-35f, result.width / 2f, result.height / 2f)
        val watermarkStep = (result.height * 0.25f).toInt().coerceAtLeast(1)
        for (y in (result.height * 0.2f).toInt()..(result.height * 0.9f).toInt() step watermarkStep) {
            canvas.drawText(text, result.width / 2f, y.toFloat(), paint)
        }
        canvas.restore()
        return result
    }
}

/**
 * CamScanner Mode Enums
 */
enum class ScanMode(val title: String) {
    SINGLE_PAGE("Satu Halaman"),
    MULTI_PAGE("Banyak Halaman"),
    SMART_CLEAN("Hapus Cerdas"),
    ID_CARD("Kartu ID"),
    OCR_TEXT("OCR Teks")
}

private const val MAX_SCANNER_SESSION_PAGES = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onScanSaved: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    val converter = remember { PdfConverterEngine(context) }
    val ocrEngine = remember { OcrEngine(context) }
    val pdfRenderer = remember { PdfRendererEngine(context) }

    // Camera permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Izin kamera diperlukan untuk memindai dokumen", Toast.LENGTH_SHORT).show()
        }
    }

    // Scanned Pages in Current Session
    val scannedPages = remember { mutableStateListOf<ScannedPageItem>() }
    // Reserve most of the heap for Compose, CameraX, filters, OCR, and transient output copies.
    // Four ARGB bytes per retained pixel means maxMemory/10 keeps originals near 40% of the heap.
    val maximumSessionPixels = remember {
        (Runtime.getRuntime().maxMemory() / 10L).coerceIn(10_000_000L, 40_000_000L)
    }

    // Navigation & UI States
    var isReviewMode by remember { mutableStateOf(false) }
    var cropTargetPageIndex by remember { mutableStateOf<Int?>(null) }
    var selectedScanMode by remember { mutableStateOf(ScanMode.MULTI_PAGE) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var showGrid by remember { mutableStateOf(false) }
    var isHdQuality by remember { mutableStateOf(true) }
    var showAllFeaturesSheet by remember { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var watermarkInputText by remember { mutableStateOf("") }
    var activeWatermarkPageIndex by remember { mutableStateOf(0) }
    var ocrDialogText by remember { mutableStateOf("") }
    var isSavingPdf by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    // Success dialog state
    var savedPdfFile by remember { mutableStateOf<File?>(null) }

    // [Fitur baru] "Simpan ke Perangkat" -- lihat ExportUtils.kt & PdfToolsScreen.kt
    // (fungsi performSaveToDevice) untuk penjelasan lengkap kenapa fitur ini ditambahkan.
    var isSavingToDevice by remember { mutableStateOf(false) }
    var pendingDeviceSaveFile by remember { mutableStateOf<File?>(null) }

    suspend fun doExportToDevice(file: File) {
        isSavingToDevice = true
        try {
            when (val result = ExportUtils.exportToDownloads(context, file)) {
                is ExportUtils.ExportResult.Success ->
                    Toast.makeText(context, "Tersimpan ke ${result.displayPath}", Toast.LENGTH_LONG).show()
                is ExportUtils.ExportResult.Failure ->
                    Toast.makeText(context, "Gagal menyimpan: ${result.message}", Toast.LENGTH_LONG).show()
                ExportUtils.ExportResult.PermissionRequired ->
                    Toast.makeText(context, "Izin penyimpanan diperlukan", Toast.LENGTH_SHORT).show()
            }
        } finally {
            isSavingToDevice = false
        }
    }

    val savePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val fileToSave = pendingDeviceSaveFile
        pendingDeviceSaveFile = null
        if (granted && fileToSave != null) {
            scope.launch { doExportToDevice(fileToSave) }
        } else if (!granted) {
            Toast.makeText(context, "Izin penyimpanan diperlukan untuk menyimpan ke Download", Toast.LENGTH_LONG).show()
        }
    }

    fun saveScannedPdfToDevice(file: File) {
        if (ExportUtils.requiresLegacyPermission() && !ExportUtils.hasLegacyStoragePermission(context)) {
            pendingDeviceSaveFile = file
            savePermissionLauncher.launch(ExportUtils.LEGACY_WRITE_PERMISSION)
            return
        }
        scope.launch { doExportToDevice(file) }
    }

    // CameraX controllers
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var boundCamera: Camera? by remember { mutableStateOf(null) }
    var previewViewInstance: PreviewView? by remember { mutableStateOf(null) }
    var cameraProviderInstance: ProcessCameraProvider? by remember { mutableStateOf(null) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderInstance?.unbindAll() }
                .onFailure { Log.w("DokuPdfCamera", "Gagal melepas CameraX", it) }
            cameraExecutor.shutdown()
            runCatching { converter.close() }
                .onFailure { Log.w("DokuPdfScanner", "Gagal menutup converter", it) }
            runCatching { ocrEngine.close() }
                .onFailure { Log.w("DokuPdfScanner", "Gagal menutup OCR", it) }
            // Never recycle a Bitmap that has been published to Compose. ImageBitmap.asImageBitmap()
            // shares the same pixel storage and the hardware renderer may still replay a recorded
            // display list after this composable leaves the tree. Dropping the state references lets
            // Android reclaim the bitmaps safely without a use-after-recycle crash.
        }
    }

    // Gallery Picker for Multi-Image import
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val importMode = selectedScanMode
            scope.launch(Dispatchers.IO) {
                var skippedImages = 0
                for (uri in uris) {
                    var decodedBitmap: Bitmap? = null
                    var ownershipTransferred = false
                    try {
                        val bmp = decodeUriBitmap(context, uri)
                        decodedBitmap = bmp
                        if (bmp != null) {
                            val page = createAutoCroppedPage(bmp, importMode)
                            withContext(Dispatchers.Main) {
                                val retainedPixels = scannedPages.sumOf { retained ->
                                    retained.originalBitmap.width.toLong() * retained.originalBitmap.height.toLong()
                                }
                                val addedPixels = bmp.width.toLong() * bmp.height.toLong()
                                require(scannedPages.size < MAX_SCANNER_SESSION_PAGES) {
                                    "Sesi scanner mencapai batas $MAX_SCANNER_SESSION_PAGES halaman"
                                }
                                require(retainedPixels + addedPixels <= maximumSessionPixels) {
                                    "Batas memori sesi tercapai; simpan PDF saat ini sebelum menambah gambar"
                                }
                                scannedPages.add(page)
                                ownershipTransferred = true
                            }
                        } else {
                            skippedImages++
                        }
                    } catch (e: Exception) {
                        skippedImages++
                        Log.e("DokuPdfGallery", "Gambar galeri dilewati", e)
                    } catch (_: OutOfMemoryError) {
                        // The successfully imported pages remain usable; only this URI is skipped.
                        skippedImages++
                    } finally {
                        if (!ownershipTransferred) {
                            decodedBitmap?.let { bitmap ->
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (scannedPages.isNotEmpty()) {
                        isReviewMode = true
                    }
                    if (skippedImages > 0) {
                        Toast.makeText(
                            context,
                            "$skippedImages gambar dilewati karena format tidak valid atau batas memori sesi tercapai.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    // PDF picker: render actual PDF pages instead of sending PDF bytes to BitmapFactory.
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val existingPageCount = scannedPages.size
            val existingPixels = scannedPages.sumOf { page ->
                page.originalBitmap.width.toLong() * page.originalBitmap.height.toLong()
            }
            scope.launch(Dispatchers.IO) {
                var temporaryPdf: File? = null
                var renderedPages: List<Bitmap> = emptyList()
                var ownershipTransferred = false
                try {
                    val inputFile = PdfFileUtils.uniqueFile(
                        context.cacheDir,
                        "scanner_pdf_${System.currentTimeMillis()}",
                        "pdf"
                    )
                    temporaryPdf = inputFile
                    val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                        "Berkas PDF tidak dapat dibuka"
                    }
                    input.use { source ->
                        inputFile.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                copied += read
                                require(copied <= PdfFileUtils.MAX_PDF_INPUT_BYTES) {
                                    "PDF melebihi batas ${PdfFileUtils.formatBytes(PdfFileUtils.MAX_PDF_INPUT_BYTES)}"
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    PdfFileUtils.requirePdf(inputFile)
                    val dimensions = pdfRenderer.getPageDimensions(inputFile)
                    require(dimensions.isNotEmpty()) { "PDF tidak mempunyai halaman yang dapat dibaca" }
                    require(existingPageCount + dimensions.size <= MAX_SCANNER_SESSION_PAGES) {
                        "Jumlah halaman melebihi batas sesi $MAX_SCANNER_SESSION_PAGES halaman"
                    }
                    val totalPixels = dimensions.sumOf {
                        it.width.toDouble() * it.height.toDouble()
                    }.coerceAtLeast(1.0)
                    val importedPixelBudget = (maximumSessionPixels - existingPixels)
                        .coerceAtMost(12_000_000L)
                    require(importedPixelBudget >= 1_000_000L) {
                        "Sesi scanner sudah terlalu besar; simpan PDF saat ini sebelum mengimpor dokumen lain"
                    }
                    val requiredScale = kotlin.math.sqrt(importedPixelBudget / totalPixels).toFloat()
                    require(requiredScale >= 0.05f) {
                        "PDF memiliki terlalu banyak halaman untuk satu sesi scanner; pisahkan PDF terlebih dahulu"
                    }
                    val renderScale = requiredScale.coerceIn(0.05f, 1.6f)
                    renderedPages = pdfRenderer.renderPdfPages(inputFile, renderScale)
                    require(renderedPages.size == dimensions.size) { "Tidak semua halaman PDF berhasil dirender" }
                    val importedPages = renderedPages.map { bitmap ->
                        ScannedPageItem(
                            originalBitmap = bitmap,
                            cropGeometry = AutoCropDetector.fullGeometry(),
                            filterType = FilterType.ORIGINAL
                        )
                    }
                    withContext(Dispatchers.Main) {
                        val currentPixels = scannedPages.sumOf { page ->
                            page.originalBitmap.width.toLong() * page.originalBitmap.height.toLong()
                        }
                        val addedPixels = renderedPages.sumOf { bitmap ->
                            bitmap.width.toLong() * bitmap.height.toLong()
                        }
                        require(scannedPages.size + importedPages.size <= MAX_SCANNER_SESSION_PAGES) {
                            "Jumlah halaman melebihi batas sesi $MAX_SCANNER_SESSION_PAGES halaman"
                        }
                        require(currentPixels + addedPixels <= maximumSessionPixels) {
                            "Batas memori sesi tercapai; simpan PDF saat ini sebelum mengimpor lagi"
                        }
                        scannedPages.addAll(importedPages)
                        ownershipTransferred = true
                        isReviewMode = true
                    }
                } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Memori tidak cukup untuk mengimpor semua halaman PDF.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e("DokuPdfImport", "Impor PDF gagal", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Gagal mengimpor PDF: ${e.message ?: "berkas tidak valid"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    if (!ownershipTransferred) {
                        renderedPages.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                    }
                    temporaryPdf?.delete()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Camera permission is only required for the live viewfinder. Gallery imports must remain
    // reviewable/editable even when the user deliberately denies camera access.
    if (!hasCameraPermission && !(isReviewMode && scannedPages.isNotEmpty())) {
        // Permission Request UI
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Slate900
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = SleekBluePrimary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Akses Kamera Diperlukan",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DokuPDF memerlukan izin kamera untuk memindai dokumen fisik beresolusi tinggi dan mengekspornya ke PDF.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate400,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = SleekBluePrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(48.dp)
                ) {
                    Text("Izinkan Akses Kamera", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Impor dari Galeri")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { fileLauncher.launch("application/pdf") },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Impor berkas PDF")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onBack) {
                    Text("Kembali ke Beranda", color = Slate400)
                }
            }
        }
        return
    }

    // Interactive crop screen for an existing page in review.
    val activeCropPageIndex = cropTargetPageIndex
    if (activeCropPageIndex != null && activeCropPageIndex in scannedPages.indices) {
        val targetPage = scannedPages[activeCropPageIndex]
        InteractiveCropScreen(
            initialBitmap = targetPage.originalBitmap,
            initialGeometry = targetPage.cropGeometry,
            onBack = { cropTargetPageIndex = null },
            onCropConfirmed = { croppedBmp, geometry, rotatedBmp ->
                val idx = activeCropPageIndex
                if (idx in scannedPages.indices) {
                    val previous = scannedPages[idx]
                    scannedPages[idx] = previous.copy(
                        originalBitmap = rotatedBmp,
                        cropGeometry = geometry,
                        // Render the crop lazily to avoid retaining two full-resolution bitmaps/page.
                        croppedBitmap = null
                    )
                    if (croppedBmp !== rotatedBmp && !croppedBmp.isRecycled) croppedBmp.recycle()
                    // BUG FIX (leaked full-resolution bitmap per re-crop -> cumulative OOM crash on
                    // a later capture): the page's old originalBitmap/croppedBitmap are fully
                    // replaced above but were never recycled, so every "Potong Ulang" confirm held
                    // onto an extra full-resolution ARGB_8888 copy for the rest of the session.
                    // Only recycle bitmaps that are genuinely orphaned by this update -- never one
                    // that's still referenced by the new page state (rotatedBmp/geometry above), and
                    // never a bitmap another page might still share a reference to.
                    val orphanedOriginal = previous.originalBitmap
                    if (orphanedOriginal !== rotatedBmp &&
                        orphanedOriginal !== croppedBmp &&
                        !orphanedOriginal.isRecycled &&
                        scannedPages.none { it.originalBitmap === orphanedOriginal }
                    ) {
                        orphanedOriginal.recycle()
                    }
                    val orphanedCropped = previous.croppedBitmap
                    if (orphanedCropped != null &&
                        orphanedCropped !== rotatedBmp &&
                        orphanedCropped !== croppedBmp &&
                        !orphanedCropped.isRecycled &&
                        scannedPages.none { it.croppedBitmap === orphanedCropped }
                    ) {
                        orphanedCropped.recycle()
                    }
                }
                cropTargetPageIndex = null
            }
        )
        return
    }

    if (isReviewMode && scannedPages.isNotEmpty()) {
        // Multi-Page Review & Edit Screen
        MultiPageReviewScreen(
            pages = scannedPages,
            onBackToCamera = { isReviewMode = false },
            onOpenCrop = { index -> cropTargetPageIndex = index },
            onDeletePage = { index ->
                if (index in scannedPages.indices) {
                    scannedPages.removeAt(index)
                    if (scannedPages.isEmpty()) {
                        isReviewMode = false
                    }
                }
            },
            onUpdateFilter = { index, filter ->
                if (index in scannedPages.indices) {
                    scannedPages[index] = scannedPages[index].copy(filterType = filter)
                }
            },
            onUpdateFilterSettings = { index, settings ->
                if (index in scannedPages.indices) {
                    scannedPages[index] = scannedPages[index].copy(filterSettings = settings.normalized())
                }
            },
            onRotatePage = { index, deltaDegrees ->
                if (index in scannedPages.indices) {
                    val currentRot = scannedPages[index].rotationDegrees
                    val newRot = (currentRot + deltaDegrees + 360) % 360
                    scannedPages[index] = scannedPages[index].copy(rotationDegrees = newRot)
                }
            },
            onOpenWatermark = { index ->
                if (index in scannedPages.indices) {
                    activeWatermarkPageIndex = index
                    watermarkInputText = scannedPages[index].watermarkText ?: ""
                    showWatermarkDialog = true
                }
            },
            onExtractOcr = { index ->
                if (index in scannedPages.indices) {
                    scope.launch(Dispatchers.IO) {
                        var rendered: Bitmap? = null
                        try {
                            rendered = scannedPages[index].getRenderedBitmap()
                            val text = ocrEngine.extractTextFromBitmap(requireNotNull(rendered))
                            require(text.isNotBlank()) { "Tidak ada teks yang terdeteksi pada halaman ini" }
                            withContext(Dispatchers.Main) {
                                ocrDialogText = text
                                showOcrDialog = true
                            }
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "OCR gagal: ${error.message ?: "halaman tidak dapat diproses"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (_: OutOfMemoryError) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Memori tidak cukup untuk OCR halaman ini.", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            rendered?.let { if (!it.isRecycled) it.recycle() }
                        }
                    }
                }
            },
            onSavePdf = {
                scope.launch {
                    isSavingPdf = true
                    try {
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val docsDir = File(context.filesDir, "documents").apply {
                            require(exists() || mkdirs()) { "Direktori dokumen tidak dapat dibuat" }
                        }
                        val destFile = PdfFileUtils.uniqueFile(docsDir, "Scan_Doc_$timeStamp", "pdf")
                        val pagesSnapshot = scannedPages.toList()
                        require(pagesSnapshot.isNotEmpty()) { "Tidak ada halaman yang akan disimpan" }
                        val result = converter.generatedBitmapsToPdf(
                            pageCount = pagesSnapshot.size,
                            outputPdf = destFile
                        ) { pageIndex ->
                            pagesSnapshot[pageIndex].getRenderedBitmap()
                        }

                        if (result.isSuccess) {
                            savedPdfFile = destFile
                        } else {
                            Toast.makeText(context, "Gagal membuat PDF: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (oom: OutOfMemoryError) {
                        Toast.makeText(
                            context,
                                "Memori tidak cukup untuk menyimpan PDF. Coba kurangi jumlah halaman per sesi atau matikan mode kualitas HD.",
                                Toast.LENGTH_LONG
                            ).show()
                    } catch (error: Exception) {
                        Toast.makeText(
                            context,
                            "Gagal menyimpan PDF: ${error.message ?: "penyimpanan tidak tersedia"}",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        isSavingPdf = false
                    }
                }
            },
            isSavingPdf = isSavingPdf
        )
    } else {
        // Live Camera Scanner Screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // CameraX Viewfinder with TextureView COMPATIBLE implementation to prevent BufferQueue abandoned errors
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val camera = boundCamera
                            if (camera != null) {
                                val zoomState = camera.cameraInfo.zoomState.value
                                val currentRatio = zoomState?.zoomRatio ?: 1f
                                val minRatio = zoomState?.minZoomRatio ?: 1f
                                val maxRatio = zoomState?.maxZoomRatio ?: currentRatio
                                camera.cameraControl.setZoomRatio(
                                    (currentRatio * zoom).coerceIn(minRatio, maxRatio)
                                )
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val previewView = previewViewInstance
                            cameraControl?.let { control ->
                                val factory = previewView?.meteringPointFactory ?: return@let
                                val point = factory.createPoint(offset.x, offset.y)
                                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                control.startFocusAndMetering(action)
                            }
                        }
                    },
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // CRITICAL: COMPATIBLE mode uses TextureView, preventing SurfaceView BLAST Consumer abandoned BufferQueue errors in Compose
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    previewViewInstance = previewView

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProviderInstance = cameraProvider

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setFlashMode(flashMode)
                                .build()
                            imageCapture = capture

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                capture
                            )
                            cameraControl = cam.cameraControl
                            boundCamera = cam
                            cameraErrorMessage = null
                        } catch (exc: Exception) {
                            Log.e("DokuPdfCamera", "Kamera tidak dapat dibuka", exc)
                            imageCapture = null
                            cameraControl = null
                            boundCamera = null
                            cameraErrorMessage = exc.message ?: "Kamera belakang tidak tersedia"
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            // Document Viewfinder Framing Overlay
            DocumentFrameOverlay(showGrid = showGrid)

            cameraErrorMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 28.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Slate900.copy(alpha = 0.94f),
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.NoPhotography, contentDescription = null, tint = AccentAmber)
                        Text("Kamera tidak tersedia", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            message,
                            color = Slate300,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(onClick = { galleryLauncher.launch("image/*") }) {
                            Text("Impor dari Galeri")
                        }
                        OutlinedButton(onClick = { fileLauncher.launch("application/pdf") }) {
                            Text("Impor PDF")
                        }
                    }
                }
            }

            // Top Bar Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Exit / Close
                IconButton(
                    onClick = {
                        if (scannedPages.isNotEmpty()) {
                            isReviewMode = true
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .testTag("scanner_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Tutup",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flash Toggle
                    IconButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                            imageCapture?.flashMode = flashMode
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .testTag("scanner_flash_btn")
                    ) {
                        Icon(
                            imageVector = when (flashMode) {
                                ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                                ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                                else -> Icons.Default.FlashOff
                            },
                            contentDescription = "Flash",
                            tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) Color(0xFFFBBF24) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // HD Quality Toggle Badge
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isHdQuality) SleekBluePrimary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier
                            .clickable { isHdQuality = !isHdQuality }
                            .testTag("scanner_hd_badge")
                    ) {
                        Text(
                            text = if (isHdQuality) "HD" else "Cepat",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    // Grid lines toggle
                    IconButton(
                        onClick = { showGrid = !showGrid },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (showGrid) SleekBluePrimary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.45f))
                            .testTag("scanner_grid_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Grid",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Bottom Scanning Controls Section
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.95f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                // Horizontal Mode Selector
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    items(ScanMode.values()) { mode ->
                        val isSelected = selectedScanMode == mode
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .clickable { selectedScanMode = mode }
                                .testTag("scan_mode_${mode.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = if (isSelected) 14.sp else 13.sp
                                ),
                                color = if (isSelected) Color(0xFF06B6D4) else Slate400
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .offset(y = 6.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF06B6D4))
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action & Shutter Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: "Semua Fitur" Grid Icon
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showAllFeaturesSheet = true }
                            .padding(8.dp)
                            .testTag("scanner_all_features_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "Semua Fitur",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Fitur", style = MaterialTheme.typography.labelSmall, color = Slate300)
                    }

                    // Center: Shutter Button with Cyan / Teal Glowing Ring
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF06B6D4).copy(alpha = 0.35f))
                            .clickable(enabled = !isCapturing && imageCapture != null) {
                                val capture = imageCapture ?: return@clickable
                                val captureMode = selectedScanMode
                                isCapturing = true

                                capture.takePicture(
                                    cameraExecutor,
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                            val bmp = try {
                                                imageProxyToBitmap(
                                                    imageProxy,
                                                    maxDimension = if (isHdQuality) 2600 else 1600
                                                )
                                            } finally {
                                                imageProxy.close()
                                            }

                                            scope.launch(Dispatchers.Default) {
                                                var ownershipTransferred = false
                                                try {
                                                    if (bmp == null) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(
                                                                context,
                                                                "Gagal memproses foto (memori tidak cukup). Coba tutup aplikasi lain lalu ulangi.",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        return@launch
                                                    }
                                                    val page = createAutoCroppedPage(bmp, captureMode)
                                                    withContext(Dispatchers.Main) {
                                                        val retainedPixels = scannedPages.sumOf { retained ->
                                                            retained.originalBitmap.width.toLong() * retained.originalBitmap.height.toLong()
                                                        }
                                                        val addedPixels = bmp.width.toLong() * bmp.height.toLong()
                                                        if (
                                                            scannedPages.size >= MAX_SCANNER_SESSION_PAGES ||
                                                            retainedPixels + addedPixels > maximumSessionPixels
                                                        ) {
                                                            Toast.makeText(
                                                                context,
                                                                "Batas memori sesi tercapai. Simpan PDF saat ini sebelum mengambil foto lagi.",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        } else {
                                                            scannedPages.add(page)
                                                            ownershipTransferred = true
                                                            if (captureMode == ScanMode.SINGLE_PAGE) {
                                                                isReviewMode = true
                                                            }
                                                        }
                                                    }
                                                } catch (_: OutOfMemoryError) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            context,
                                                            "Memori tidak cukup untuk menganalisis hasil foto.",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                } catch (error: Exception) {
                                                    Log.e("DokuPdfCamera", "Hasil foto gagal dianalisis", error)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            context,
                                                            "Gagal menganalisis hasil foto: ${error.message ?: "format tidak didukung"}",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                } finally {
                                                    if (!ownershipTransferred) {
                                                        bmp?.let { bitmap ->
                                                            if (!bitmap.isRecycled) bitmap.recycle()
                                                        }
                                                    }
                                                    withContext(Dispatchers.Main) { isCapturing = false }
                                                }
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("DokuPdfCamera", "Pengambilan gambar gagal", exception)
                                            scope.launch(Dispatchers.Main) {
                                                isCapturing = false
                                                Toast.makeText(context, "Gagal mengambil foto: ${exception.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                            .testTag("scanner_shutter_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(3.dp, Color(0xFF06B6D4), CircleShape)
                        ) {
                            if (isCapturing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.Center),
                                    color = Color(0xFF06B6D4),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }

                    // Right Group: Gallery Import or Batch Pages Indicator
                    if (scannedPages.isNotEmpty()) {
                        // Multi-Page Badge Indicator -> Click to Review & Edit
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { isReviewMode = true }
                                .padding(8.dp)
                                .testTag("scanner_review_badge")
                        ) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = Color(0xFF10B981),
                                        contentColor = Color.White
                                    ) {
                                        Text("${scannedPages.size}")
                                    }
                                }
                            ) {
                                val latestBmp = scannedPages.last().originalBitmap
                                Image(
                                    bitmap = latestBmp.asImageBitmap(),
                                    contentDescription = "Halaman Terakhir",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.5.dp, Color(0xFF06B6D4), RoundedCornerShape(6.dp))
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Selesai >", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF06B6D4))
                        }
                    } else {
                        // Import from Gallery
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { galleryLauncher.launch("image/*") }
                                .padding(8.dp)
                                .testTag("scanner_import_gallery_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Impor Galeri",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Impor", style = MaterialTheme.typography.labelSmall, color = Slate300)
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet: All Features
    if (showAllFeaturesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAllFeaturesSheet = false },
            containerColor = SleekSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Semua Mode & Fitur Pemindaian",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Slate900
                )
                Spacer(modifier = Modifier.height(16.dp))

                val features = listOf(
                    Triple(Icons.Default.DocumentScanner, "Pindai Dokumen", "Pindai dokumen teks, surat, atau nota."),
                    Triple(Icons.Default.CreditCard, "Kartu ID / KTP", "Pertahankan warna kartu; pindai sisi depan dan belakang sebagai halaman terpisah."),
                    Triple(Icons.AutoMirrored.Filled.MenuBook, "Pindai Batch", "Ambil beberapa halaman buku atau majalah dalam satu sesi."),
                    Triple(Icons.Default.AutoFixHigh, "Kurangi Bayangan", "Optimalkan pencahayaan dan kontras kertas secara otomatis."),
                    Triple(Icons.Default.FontDownload, "Ekstrak Teks (OCR)", "Gunakan filter teks lalu ekstrak OCR dari layar review."),
                    Triple(Icons.AutoMirrored.Filled.InsertDriveFile, "Impor Berkas PDF", "Render seluruh halaman PDF ke sesi review dengan batas memori aman.")
                )

                features.forEach { (icon, title, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showAllFeaturesSheet = false
                                if (title.contains("PDF")) {
                                    fileLauncher.launch("application/pdf")
                                } else if (title.contains("OCR")) {
                                    selectedScanMode = ScanMode.OCR_TEXT
                                } else if (title.contains("Kartu")) {
                                    selectedScanMode = ScanMode.ID_CARD
                                } else if (title.contains("Bayangan")) {
                                    selectedScanMode = ScanMode.SMART_CLEAN
                                } else {
                                    selectedScanMode = ScanMode.MULTI_PAGE
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(SleekBlueLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = icon, contentDescription = null, tint = SleekBluePrimary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = Slate900)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = Slate500)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // OCR Result Dialog
    if (showOcrDialog) {
        AlertDialog(
            onDismissRequest = { showOcrDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TextFields, contentDescription = null, tint = SleekBluePrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Hasil Ekstraksi OCR Teks", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    OutlinedTextField(
                        value = ocrDialogText,
                        onValueChange = { ocrDialogText = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setPrimaryClip(
                            ClipData.newPlainText("DokuPDF OCR", ocrDialogText)
                        )
                        Toast.makeText(context, "Teks disalin ke clipboard", Toast.LENGTH_SHORT).show()
                        showOcrDialog = false
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Salin Teks")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOcrDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }

    // Per-page scanner watermark editor. This state previously had no rendered dialog,
    // leaving the visible "Tandai" action as a no-op.
    if (showWatermarkDialog) {
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("Tanda Air Halaman", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = watermarkInputText,
                    onValueChange = { if (it.length <= 200) watermarkInputText = it },
                    label = { Text("Teks tanda air") },
                    supportingText = { Text("Maksimal 200 karakter; kosongkan untuk menghapus.") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scanner_watermark_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pageIndex = activeWatermarkPageIndex
                        if (pageIndex in scannedPages.indices) {
                            scannedPages[pageIndex] = scannedPages[pageIndex].copy(
                                watermarkText = watermarkInputText.trim().takeIf { it.isNotEmpty() }
                            )
                        }
                        showWatermarkDialog = false
                    },
                    enabled = activeWatermarkPageIndex in scannedPages.indices,
                    modifier = Modifier.testTag("scanner_watermark_apply")
                ) {
                    Text("Terapkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // PDF Saved Success Dialog
    savedPdfFile?.let { file ->
        AlertDialog(
            onDismissRequest = {
                savedPdfFile = null
                onScanSaved()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PDF Berhasil Dibuat!", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column {
                    Text("Nama Berkas: ${file.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Total: ${scannedPages.size} Halaman", style = MaterialTheme.typography.bodySmall, color = Slate500)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Lokasi: ${file.parentFile?.name}/${file.name}", style = MaterialTheme.typography.labelSmall, color = Slate400)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        savedPdfFile = null
                        onScanSaved()
                    }
                ) {
                    Text("Selesai")
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // [Fitur baru] "Simpan ke Perangkat" -- lihat ExportUtils.kt. Sebelumnya
                    // dialog ini hanya punya "Bagikan" (share sheet); tombol ini menyalin PDF
                    // ke Download/DokuPDF/ publik supaya bisa dibuka lewat File Manager/Galeri
                    // tanpa aplikasi perantara -- setara "Simpan ke Galeri" di CamScanner.
                    TextButton(
                        onClick = { saveScannedPdfToDevice(file) },
                        enabled = !isSavingToDevice
                    ) {
                        if (isSavingToDevice) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Simpan ke Perangkat")
                    }
                    TextButton(
                        onClick = {
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Bagikan PDF"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bagikan")
                    }
                }
            }
        )
    }
}

/**
 * Viewfinder Framing Overlay
 */
@Composable
fun DocumentFrameOverlay(showGrid: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        // Document Guide Border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.70f)
                .border(2.dp, Color(0xFF06B6D4).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
        ) {
            // Corner Bracket Visuals
            Box(modifier = Modifier.align(Alignment.TopStart).size(20.dp).border(3.dp, Color.White, RoundedCornerShape(topStart = 16.dp)))
            Box(modifier = Modifier.align(Alignment.TopEnd).size(20.dp).border(3.dp, Color.White, RoundedCornerShape(topEnd = 16.dp)))
            Box(modifier = Modifier.align(Alignment.BottomStart).size(20.dp).border(3.dp, Color.White, RoundedCornerShape(bottomStart = 16.dp)))
            Box(modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).border(3.dp, Color.White, RoundedCornerShape(bottomEnd = 16.dp)))

            // Grid lines
            if (showGrid) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.3f)))
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.3f)))
                    Spacer(modifier = Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.3f)))
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.3f)))
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Multi-Page Review & Edit Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPageReviewScreen(
    pages: List<ScannedPageItem>,
    onBackToCamera: () -> Unit,
    onOpenCrop: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onUpdateFilter: (Int, FilterType) -> Unit,
    onUpdateFilterSettings: (Int, FilterSettings) -> Unit,
    onRotatePage: (Int, Int) -> Unit,
    onOpenWatermark: (Int) -> Unit,
    onExtractOcr: (Int) -> Unit,
    onSavePdf: () -> Unit,
    isSavingPdf: Boolean
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val reviewScope = rememberCoroutineScope()
    val currentPageIndex = pagerState.currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val currentPage = pages.getOrNull(currentPageIndex)
    var isComparingOriginal by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var pendingDeletePage by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pages.size) {
        val lastPageIndex = pages.lastIndex
        if (lastPageIndex >= 0 && pagerState.currentPage > lastPageIndex) {
            pagerState.scrollToPage(lastPageIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        isComparingOriginal = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pratinjau Pindaian", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            "Halaman ${currentPageIndex + 1} dari ${pages.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackToCamera) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali ke Kamera")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenCrop(currentPageIndex) }) {
                        Icon(Icons.Default.Crop, contentDescription = "Potong & Ratakan", tint = SleekBluePrimary)
                    }
                    IconButton(onClick = { onExtractOcr(currentPageIndex) }) {
                        Icon(Icons.Default.TextFields, contentDescription = "OCR Teks", tint = SleekBluePrimary)
                    }
                    IconButton(onClick = { onRotatePage(currentPageIndex, 90) }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Putar 90°", tint = Slate700)
                    }
                    IconButton(onClick = { pendingDeletePage = currentPageIndex }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus Halaman", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SleekSurface)
            )
        },
        bottomBar = {
            Surface(
                color = SleekSurface,
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 10.dp)
                ) {
                    // Filter selection row for active page
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Peningkatan & Filter Hasil Scan",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Slate800
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentPage?.filterType?.displayName ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = SleekBluePrimary,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { showAdjustments = !showAdjustments }) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (showAdjustments) "Tutup" else "Atur", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(FilterType.values()) { filter ->
                            val isSelected = currentPage?.filterType == filter
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) SleekBlueLight else SleekSurface,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) SleekBluePrimary else Slate200
                                ),
                                modifier = Modifier.clickable { onUpdateFilter(currentPageIndex, filter) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when (filter) {
                                                    FilterType.AUTO -> SleekBluePrimary
                                                    FilterType.ORIGINAL -> Slate400
                                                    FilterType.MAGIC_COLOR -> Color(0xFF06B6D4)
                                                    FilterType.NO_SHADOW -> Color(0xFF10B981)
                                                    FilterType.MAGIC_BW_HP -> Color.Black
                                                    FilterType.GRAYSCALE -> Color.Gray
                                                    FilterType.LIGHTEN -> Color(0xFFF59E0B)
                                                    FilterType.SHARPEN -> Color(0xFF6366F1)
                                                    FilterType.PHOTO_ENHANCE -> Color(0xFF8B5CF6)
                                                    FilterType.INVERT -> Color(0xFFEC4899)
                                                }
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = filter.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) SleekBluePrimary else Slate700
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = showAdjustments && currentPage != null) {
                        currentPage?.let { page ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                FilterAdjustmentSlider("Cerah", page.filterSettings.brightness, 0.65f..1.35f) {
                                    onUpdateFilterSettings(currentPageIndex, page.filterSettings.copy(brightness = it))
                                }
                                FilterAdjustmentSlider("Kontras", page.filterSettings.contrast, 0.65f..1.8f) {
                                    onUpdateFilterSettings(currentPageIndex, page.filterSettings.copy(contrast = it))
                                }
                                FilterAdjustmentSlider("Warna", page.filterSettings.saturation, 0f..2f) {
                                    onUpdateFilterSettings(currentPageIndex, page.filterSettings.copy(saturation = it))
                                }
                                FilterAdjustmentSlider("Hangat", page.filterSettings.warmth, -1f..1f) {
                                    onUpdateFilterSettings(currentPageIndex, page.filterSettings.copy(warmth = it))
                                }
                                FilterAdjustmentSlider("Tajam", page.filterSettings.sharpness, 0f..1.5f) {
                                    onUpdateFilterSettings(currentPageIndex, page.filterSettings.copy(sharpness = it))
                                }
                                TextButton(
                                    onClick = { onUpdateFilterSettings(currentPageIndex, FilterSettings()) },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Reset penyesuaian", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secondary Tools Action Strip (Rotate Left, Rotate Right, Crop, Watermark)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { onRotatePage(currentPageIndex, -90) }) {
                            Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp), tint = Slate700)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kiri", fontSize = 12.sp, color = Slate700)
                        }

                        TextButton(onClick = { onRotatePage(currentPageIndex, 90) }) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = Slate700)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kanan", fontSize = 12.sp, color = Slate700)
                        }

                        TextButton(onClick = { onOpenCrop(currentPageIndex) }) {
                            Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp), tint = SleekBluePrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Potong", fontSize = 12.sp, color = SleekBluePrimary, fontWeight = FontWeight.Bold)
                        }

                        TextButton(onClick = { onOpenWatermark(currentPageIndex) }) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp), tint = Slate700)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Tandai", fontSize = 12.sp, color = Slate700)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Bottom Action Row: Add More Pages / Save PDF
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onBackToCamera,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("+ Tambah", fontSize = 13.sp)
                        }

                        Button(
                            onClick = onSavePdf,
                            enabled = !isSavingPdf && pages.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SleekBluePrimary),
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("review_save_pdf_btn")
                        ) {
                            if (isSavingPdf) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Menyimpan...", fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Simpan PDF (${pages.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFFF1F5F9)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Swiper Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    val pageItem = pages[pageIdx]
                    val rendered by produceState<Bitmap?>(
                        null,
                        pageItem.rotationDegrees,
                        pageItem.filterType,
                        pageItem.filterSettings,
                        pageItem.originalBitmap,
                        pageItem.croppedBitmap,
                        pageItem.cropGeometry,
                        pageItem.watermarkText
                    ) {
                        value = withContext(Dispatchers.Default) {
                            try {
                                pageItem.getRenderedBitmap(maxDimension = 1600)
                            } catch (oom: OutOfMemoryError) {
                                Log.e("DokuPdfPreview", "Memori tidak cukup untuk preview halaman ${pageIdx + 1}", oom)
                                pageItem.originalBitmap
                            } catch (error: Exception) {
                                Log.e("DokuPdfPreview", "Preview halaman ${pageIdx + 1} gagal", error)
                                pageItem.originalBitmap
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxHeight(0.95f)
                                .aspectRatio(0.70f)
                                .clip(RoundedCornerShape(10.dp)),
                            shadowElevation = 6.dp,
                            color = Color.White
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val displayBitmap = if (isComparingOriginal) pageItem.originalBitmap else rendered
                                if (displayBitmap != null) {
                                    Image(
                                        bitmap = displayBitmap.asImageBitmap(),
                                        contentDescription = "Halaman ${pageIdx + 1}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center),
                                        color = SleekBluePrimary
                                    )
                                }

                                // Floating Compare (Bandingkan) Pill Button
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isComparingOriginal) SleekBluePrimary else Color.Black.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(12.dp)
                                        .clickable { isComparingOriginal = !isComparingOriginal }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.CompareArrows,
                                            contentDescription = "Bandingkan",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isComparingOriginal) "Asli (Menampilkan)" else "Bandingkan",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Floating Quick Crop Overlay Action
                                FloatingActionButton(
                                    onClick = { onOpenCrop(pageIdx) },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp)
                                        .size(42.dp),
                                    containerColor = SleekBluePrimary,
                                    contentColor = Color.White
                                ) {
                                    Icon(Icons.Default.Crop, contentDescription = "Sesuaikan Sudut", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Thumbnail Strip at Bottom
            if (pages.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SleekSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(pages) { idx, item ->
                        val isSelected = idx == currentPageIndex
                        Box(
                            modifier = Modifier
                                .size(50.dp, 66.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    reviewScope.launch { pagerState.animateScrollToPage(idx) }
                                }
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) SleekBluePrimary else Slate300,
                                    shape = RoundedCornerShape(6.dp)
                                )
                        ) {
                            Image(
                                bitmap = item.originalBitmap.asImageBitmap(),
                                contentDescription = "Thumb ${idx + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("${idx + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeletePage?.let { pageIndex ->
        AlertDialog(
            onDismissRequest = { pendingDeletePage = null },
            title = { Text("Hapus halaman?") },
            text = { Text("Halaman ${pageIndex + 1} akan dihapus dari sesi pindai ini.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeletePage = null
                        if (pageIndex in pages.indices) onDeletePage(pageIndex)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePage = null }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun FilterAdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(62.dp), fontSize = 11.sp, color = Slate700)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            String.format(Locale.getDefault(), "%.2f", value),
            modifier = Modifier.width(42.dp),
            fontSize = 10.sp,
            color = Slate500
        )
    }
}

private fun filterForScanMode(mode: ScanMode): FilterType = when (mode) {
    ScanMode.SMART_CLEAN -> FilterType.NO_SHADOW
    ScanMode.ID_CARD -> FilterType.PHOTO_ENHANCE
    ScanMode.OCR_TEXT -> FilterType.MAGIC_BW_HP
    else -> FilterType.AUTO
}

private fun createAutoCroppedPage(bitmap: Bitmap, mode: ScanMode): ScannedPageItem {
    val detection = AutoCropDetector.detect(bitmap)
    if (detection.usedFallback) {
        Log.w(
            "DokuPdfAutoCrop",
            "Scanner fallback: reason=${detection.failureReason}, size=${bitmap.width}x${bitmap.height}"
        )
    }
    return ScannedPageItem(
        originalBitmap = bitmap,
        cropGeometry = detection.geometry,
        filterType = filterForScanMode(mode)
    )
}

private fun decodeUriBitmap(context: Context, uri: Uri, maxDimension: Int = 2600): Bitmap? {
    var decodedBitmap: Bitmap? = null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        decodedBitmap = decoded

        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val orientationMatrix = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(270f) }
            else -> null
        }
        val oriented = orientationMatrix?.let { matrix ->
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } ?: decoded
        if (oriented !== decoded && !decoded.isRecycled) decoded.recycle()
        decodedBitmap = null
        oriented
    } catch (_: OutOfMemoryError) {
        decodedBitmap?.let { if (!it.isRecycled) it.recycle() }
        null
    } catch (error: Exception) {
        decodedBitmap?.let { if (!it.isRecycled) it.recycle() }
        Log.e("DokuPdfGallery", "Decode gambar galeri gagal", error)
        null
    }
}

/**
 * Utility helper to convert ImageProxy into a properly oriented Bitmap.
 *
 * BUG FIX (force close on capture): this previously decoded the full sensor-resolution JPEG
 * (often 12-108MP -> 50-400MB+ as an ARGB_8888 bitmap) with no bounds check, no downsampling,
 * and no try/catch. `BitmapFactory.decodeByteArray` throws `OutOfMemoryError`, which is an
 * `Error`, not an `Exception` -- none of the `catch (e: Exception)` blocks elsewhere in this
 * file could ever catch it. Since this runs on `onCaptureSuccess`, which executes on
 * `cameraExecutor` (a background thread), an uncaught OOM there crashes the whole process
 * immediately, which matches the reported "force close saat pengambilan gambar". It got worse
 * page after page in Multi-Page mode because every previous page's full-res bitmap was still
 * held in memory (never downsampled, never recycled), so later captures were increasingly
 * likely to be the one that finally pushes the heap over the edge -- explaining why it was
 * intermittent rather than 100% reproducible.
 *
 * Fix: read the JPEG's dimensions first (cheap, no pixel allocation), compute an inSampleSize
 * that caps the long edge at [maxDimension] px (2600px is already well beyond what's needed for
 * a sharp document scan / OCR pass), decode at that size, and catch OutOfMemoryError explicitly
 * so a failed capture degrades to "gagal, coba lagi" instead of killing the app.
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy, maxDimension: Int = 2600): Bitmap? {
    return try {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val srcWidth = boundsOptions.outWidth
        val srcHeight = boundsOptions.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var sampleSize = 1
        while ((srcWidth / sampleSize) > maxDimension || (srcHeight / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null

        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated !== bmp) bmp.recycle()
            rotated
        } else {
            bmp
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        Log.e("DokuPdfCamera", "Konversi ImageProxy gagal", e)
        null
    }
}
