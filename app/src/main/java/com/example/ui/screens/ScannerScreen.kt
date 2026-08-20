package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.core.crop.AutoCropDetector
import com.example.core.filter.FilterProcessor
import com.example.core.model.CropGeometry
import com.example.core.model.FilterSettings
import com.example.core.model.FilterType
import com.example.core.ocr.OcrEngine
import com.example.core.pdf.PdfConverterEngine
import com.example.core.repository.DocumentRepository
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
        // 1. Use cropped perspective bitmap if present, otherwise crop with geometry or use original
        var ownsWorkingBitmap = false
        var workingBitmap: Bitmap = if (croppedBitmap != null) {
            croppedBitmap
        } else if (cropGeometry != null) {
            try {
                FilterProcessor.cropPerspective(originalBitmap, cropGeometry).also {
                    ownsWorkingBitmap = it !== originalBitmap
                }
            } catch (e: Exception) {
                originalBitmap
            }
        } else {
            originalBitmap
        }

        // 2. Downscale previews before expensive filters; PDF export leaves maxDimension null.
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
        if (rendered !== workingBitmap && ownsWorkingBitmap && !workingBitmap.isRecycled) workingBitmap.recycle()

        // 5. Apply watermark if present
        if (!watermarkText.isNullOrBlank()) {
            val watermarked = drawWatermark(rendered, watermarkText)
            if (ownsRendered && rendered !== watermarked && !rendered.isRecycled) rendered.recycle()
            rendered = watermarked
            ownsRendered = true
        }

        return if (!ownsRendered || rendered === originalBitmap || rendered === croppedBitmap) {
            rendered.copy(rendered.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            rendered
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onScanSaved: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val repository = remember { DocumentRepository(context) }
    val converter = remember { PdfConverterEngine(context) }
    val ocrEngine = remember { OcrEngine(context) }

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

    // CameraX controllers
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var boundCamera: Camera? by remember { mutableStateOf(null) }
    var previewViewInstance: PreviewView? by remember { mutableStateOf(null) }
    var cameraProviderInstance: ProcessCameraProvider? by remember { mutableStateOf(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraProviderInstance?.unbindAll()
                cameraExecutor.shutdown()
                scannedPages.forEach { page ->
                    page.croppedBitmap?.let { if (!it.isRecycled) it.recycle() }
                    if (!page.originalBitmap.isRecycled) page.originalBitmap.recycle()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Gallery Picker for Multi-Image import
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val bmp = decodeUriBitmap(context, uri)
                        if (bmp != null) {
                            val page = createAutoCroppedPage(bmp, selectedScanMode)
                            withContext(Dispatchers.Main) {
                                scannedPages.add(
                                    page
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                withContext(Dispatchers.Main) {
                    if (scannedPages.isNotEmpty()) {
                        isReviewMode = true
                    }
                }
            }
        }
    }

    // PDF / File Picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bmp = decodeUriBitmap(context, uri)
                    if (bmp != null) {
                        val page = createAutoCroppedPage(bmp, selectedScanMode)
                        withContext(Dispatchers.Main) {
                            scannedPages.add(page)
                            isReviewMode = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
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
                TextButton(onClick = onBack) {
                    Text("Kembali ke Beranda", color = Slate400)
                }
            }
        }
        return
    }

    // Interactive crop screen for an existing page in review.
    if (cropTargetPageIndex != null && cropTargetPageIndex in scannedPages.indices) {
        val targetPage = scannedPages[cropTargetPageIndex!!]
        InteractiveCropScreen(
            initialBitmap = targetPage.originalBitmap,
            initialGeometry = targetPage.cropGeometry,
            onBack = { cropTargetPageIndex = null },
            onCropConfirmed = { croppedBmp, geometry, rotatedBmp ->
                val idx = cropTargetPageIndex!!
                if (idx in scannedPages.indices) {
                    val previous = scannedPages[idx]
                    scannedPages[idx] = previous.copy(
                        originalBitmap = rotatedBmp,
                        cropGeometry = geometry,
                        // Render the crop lazily to avoid retaining two full-resolution bitmaps/page.
                        croppedBitmap = null
                    )
                    if (croppedBmp !== rotatedBmp && !croppedBmp.isRecycled) croppedBmp.recycle()
                    previous.croppedBitmap?.let { if (it !== croppedBmp && !it.isRecycled) it.recycle() }
                    if (previous.originalBitmap !== rotatedBmp && !previous.originalBitmap.isRecycled) {
                        previous.originalBitmap.recycle()
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
                activeWatermarkPageIndex = index
                watermarkInputText = scannedPages[index].watermarkText ?: ""
                showWatermarkDialog = true
            },
            onExtractOcr = { index ->
                if (index in scannedPages.indices) {
                    scope.launch(Dispatchers.IO) {
                        val rendered = scannedPages[index].getRenderedBitmap()
                        try {
                            val text = ocrEngine.extractTextFromBitmap(rendered)
                            withContext(Dispatchers.Main) {
                                ocrDialogText = text
                                showOcrDialog = true
                            }
                        } finally {
                            if (!rendered.isRecycled) rendered.recycle()
                        }
                    }
                }
            },
            onSavePdf = {
                scope.launch {
                    isSavingPdf = true
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = "Scan_Doc_$timeStamp.pdf"
                    val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
                    val destFile = File(docsDir, fileName)
                    var renderedBitmaps: List<Bitmap> = emptyList()

                    try {
                        renderedBitmaps = withContext(Dispatchers.Default) {
                            scannedPages.map { it.getRenderedBitmap() }
                        }

                        val result = converter.bitmapsToPdf(renderedBitmaps, destFile, recycleSource = false)

                        if (result.isSuccess) {
                            repository.refreshDocuments()
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
                    } finally {
                        renderedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
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
                        } catch (exc: Exception) {
                            exc.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            // Document Viewfinder Framing Overlay
            DocumentFrameOverlay(showGrid = showGrid)

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
                            .clickable(enabled = !isCapturing) {
                                val capture = imageCapture ?: return@clickable
                                isCapturing = true

                                capture.takePicture(
                                    cameraExecutor,
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                            val bmp = imageProxyToBitmap(
                                                imageProxy,
                                                maxDimension = if (isHdQuality) 3200 else 1800
                                            )
                                            imageProxy.close()

                                            scope.launch(Dispatchers.Default) {
                                                if (bmp != null) {
                                                    val page = createAutoCroppedPage(bmp, selectedScanMode)
                                                    withContext(Dispatchers.Main) {
                                                        scannedPages.add(page)
                                                        isCapturing = false
                                                        if (selectedScanMode == ScanMode.SINGLE_PAGE) {
                                                            isReviewMode = true
                                                        }
                                                    }
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        isCapturing = false
                                                        Toast.makeText(
                                                            context,
                                                            "Gagal memproses foto (memori tidak cukup). Coba tutup aplikasi lain lalu ulangi.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            exception.printStackTrace()
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
                    Triple(Icons.Default.CreditCard, "Kartu ID / KTP", "Gabungkan foto depan dan belakang kartu ID."),
                    Triple(Icons.Default.MenuBook, "Buku / Majalah", "Otomatis pisahkan halaman kiri & kanan."),
                    Triple(Icons.Default.AutoFixHigh, "Hapus Bayangan Cerdas", "Hilangkan bayangan jari & lipatan kertas."),
                    Triple(Icons.Default.FontDownload, "Ekstrak Teks (OCR)", "Ekstrak teks gambar ke dokumen yang dapat diedit."),
                    Triple(Icons.Default.InsertDriveFile, "Impor Berkas PDF", "Impor dokumen PDF dan lakukan peningkatan kualitas.")
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
                        clipboardManager.setText(AnnotatedString(ocrDialogText))
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
                    Text("Selesai & Buka")
                }
            },
            dismissButton = {
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali ke Kamera")
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
                        Icon(Icons.Default.RotateRight, contentDescription = "Putar 90°", tint = Slate700)
                    }
                    IconButton(onClick = { onDeletePage(currentPageIndex) }) {
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
                            Icon(Icons.Default.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp), tint = Slate700)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kiri", fontSize = 12.sp, color = Slate700)
                        }

                        TextButton(onClick = { onRotatePage(currentPageIndex, 90) }) {
                            Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = Slate700)
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
                            pageItem.getRenderedBitmap(maxDimension = 1600)
                        }
                    }
                    DisposableEffect(rendered) {
                        onDispose {
                            rendered?.let { if (!it.isRecycled) it.recycle() }
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
                                            Icons.Default.CompareArrows,
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
    return ScannedPageItem(
        originalBitmap = bitmap,
        cropGeometry = detection.geometry,
        filterType = filterForScanMode(mode)
    )
}

private fun decodeUriBitmap(context: Context, uri: Uri, maxDimension: Int = 2600): Bitmap? {
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
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (oom: OutOfMemoryError) {
        null
    } catch (error: Exception) {
        error.printStackTrace()
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
    } catch (oom: OutOfMemoryError) {
        System.gc()
        null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
