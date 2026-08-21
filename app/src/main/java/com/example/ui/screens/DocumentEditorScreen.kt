package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.ai.GeminiAiService
import com.example.core.engine.DocumentController
import com.example.core.engine.DocumentEngine
import com.example.core.layout.PageLayoutInfo
import com.example.core.model.*
import com.example.core.pdf.OfficeFileParser
import com.example.core.pdf.PdfFileUtils
import com.example.core.pdf.PdfGenerator
import com.example.core.render.RenderEngine
import com.example.ui.components.SleekTopAppBar
import com.example.ui.dialogs.SignaturePadDialog
import com.example.ui.dialogs.WatermarkDialog
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DocumentEditorScreen(
    initialDocument: DocumentModel?,
    onBack: () -> Unit,
    onDocumentSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val editorInitialDocument = remember(initialDocument) {
        val candidate = initialDocument ?: createDefaultDocument()
        val pages = candidate.pages
            .ifEmpty { listOf(PageModel(pageIndex = 0)) }
            .mapIndexed { index, page -> page.copy(pageIndex = index) }
        candidate.copy(pages = pages)
    }
    val documentEngine = remember(editorInitialDocument) { DocumentEngine(editorInitialDocument) }
    val documentController = remember { DocumentController(documentEngine) }
    val renderEngine = remember { RenderEngine() }
    val aiService = remember { GeminiAiService() }
    val pdfGenerator = remember { PdfGenerator(context) }

    val documentState by documentEngine.documentState.collectAsState()
    val canUndo by documentEngine.commandManager.canUndo.collectAsState()
    val canRedo by documentEngine.commandManager.canRedo.collectAsState()

    var activePageIndex by remember { mutableStateOf(0) }
    var documentTitle by remember(editorInitialDocument.id) { mutableStateOf(editorInitialDocument.title) }
    var isExportingPdf by remember { mutableStateOf(false) }

    // Transformation State for Zoom & Pan Canvas
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val updatedScale = (scale * zoomChange).coerceIn(0.5f, 3.5f)
        val appliedZoom = updatedScale / scale
        val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val pivotFromCenter = if (centroid.x.isFinite() && centroid.y.isFinite()) {
            centroid - viewportCenter
        } else {
            Offset.Zero
        }
        offset = offset * appliedZoom + pivotFromCenter * (1f - appliedZoom) + panChange
        scale = updatedScale
    }

    // Dialog & Tool States
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showTableDialog by remember { mutableStateOf(false) }
    var showAiLetterDialog by remember { mutableStateOf(false) }
    var showInsertTextDialog by remember { mutableStateOf(false) }

    // Selected Formatting Options
    var isBold by remember { mutableStateOf(false) }
    var isItalic by remember { mutableStateOf(false) }
    var isUnderline by remember { mutableStateOf(false) }
    var selectedFontSize by remember { mutableStateOf(14f) }
    var selectedAlignment by remember { mutableStateOf(0) } // 0: Left, 1: Center, 2: Right, 3: Justify

    LaunchedEffect(documentState.pages.size) {
        activePageIndex = activePageIndex.coerceIn(0, documentState.pages.lastIndex.coerceAtLeast(0))
    }

    // Active page
    val activePage = documentState.pages.getOrNull(activePageIndex)
        ?: documentState.pages.first()
    val pageAspectRatio = (activePage.width / activePage.height)
        .takeIf { it.isFinite() && it > 0f }
        ?: (595.28f / 841.89f)
    val maximumCanvasWidth = 340f
    val maximumCanvasHeight = 480f
    val maximumCanvasAspect = maximumCanvasWidth / maximumCanvasHeight
    val pageCanvasWidth = if (pageAspectRatio >= maximumCanvasAspect) {
        maximumCanvasWidth
    } else {
        (maximumCanvasHeight * pageAspectRatio).coerceAtLeast(80f)
    }
    val pageCanvasHeight = if (pageAspectRatio >= maximumCanvasAspect) {
        (maximumCanvasWidth / pageAspectRatio).coerceAtLeast(80f)
    } else {
        maximumCanvasHeight
    }

    Scaffold(
        topBar = {
            SleekTopAppBar(
                title = documentTitle,
                subtitle = "Hal ${activePageIndex + 1} dari ${documentState.pages.size} • Canvas Engine",
                onNavigationClick = onBack,
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                actions = {
                    // Undo
                    IconButton(
                        onClick = { documentController.undo() },
                        enabled = canUndo,
                        modifier = Modifier.testTag("editor_undo_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (canUndo) SleekBluePrimary else Slate300
                        )
                    }
                    // Redo
                    IconButton(
                        onClick = { documentController.redo() },
                        enabled = canRedo,
                        modifier = Modifier.testTag("editor_redo_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (canRedo) SleekBluePrimary else Slate300
                        )
                    }
                    // Export PDF Button
                    IconButton(
                        onClick = {
                            if (isExportingPdf) return@IconButton
                            scope.launch {
                                isExportingPdf = true
                                try {
                                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    val documentsDir = File(context.filesDir, "documents").apply { mkdirs() }
                                    val safeTitle = PdfFileUtils.sanitizeFileName(documentTitle, "Dokumen")
                                    val targetFile = PdfFileUtils.uniqueFile(
                                        documentsDir,
                                        "${safeTitle}_$timeStamp",
                                        "pdf"
                                    )

                                    val result = pdfGenerator.exportToPdf(documentState, targetFile)
                                    if (result.isSuccess) {
                                        Toast.makeText(
                                            context,
                                            "PDF tersimpan: ${targetFile.name}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        onDocumentSaved()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Gagal mengekspor PDF: ${result.exceptionOrNull()?.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (error: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Gagal menyiapkan ekspor: ${error.message ?: "penyimpanan tidak tersedia"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (_: OutOfMemoryError) {
                                    Toast.makeText(
                                        context,
                                        "Memori tidak cukup untuk mengekspor dokumen ini.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isExportingPdf = false
                                }
                            }
                        },
                        enabled = !isExportingPdf,
                        modifier = Modifier.testTag("editor_export_pdf_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Simpan PDF",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }
            )
        },
        bottomBar = {
            // Formatting & Insert Toolbar
            EditorBottomToolbar(
                isBold = isBold,
                isItalic = isItalic,
                isUnderline = isUnderline,
                alignment = selectedAlignment,
                onToggleBold = {
                    isBold = !isBold
                    applyFormatting(documentController, activePage, isBold, isItalic, isUnderline, selectedAlignment, selectedFontSize)
                },
                onToggleItalic = {
                    isItalic = !isItalic
                    applyFormatting(documentController, activePage, isBold, isItalic, isUnderline, selectedAlignment, selectedFontSize)
                },
                onToggleUnderline = {
                    isUnderline = !isUnderline
                    applyFormatting(documentController, activePage, isBold, isItalic, isUnderline, selectedAlignment, selectedFontSize)
                },
                onChangeAlignment = { align ->
                    selectedAlignment = align
                    applyFormatting(documentController, activePage, isBold, isItalic, isUnderline, selectedAlignment, selectedFontSize)
                },
                onInsertText = { showInsertTextDialog = true },
                onInsertTable = { showTableDialog = true },
                onAddSignature = { showSignatureDialog = true },
                onAddWatermark = { showWatermarkDialog = true },
                onAiLetter = { showAiLetterDialog = true },
                onAddPage = {
                    val newPage = PageModel(pageIndex = documentState.pages.size)
                    documentController.addPage(newPage)
                    activePageIndex = documentState.pages.size
                }
            )
        },
        containerColor = Color(0xFFE2E8F0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Multi-page Navigation Bar
            if (documentState.pages.size > 1) {
                Surface(
                    color = SleekSurface,
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 2.dp
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(documentState.pages) { page ->
                            val isCurrent = page.pageIndex == activePageIndex
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCurrent) SleekBluePrimary else Slate100,
                                modifier = Modifier
                                    .clickable { activePageIndex = page.pageIndex }
                                    .testTag("page_tab_${page.pageIndex}")
                            ) {
                                Text(
                                    text = "Hal ${page.pageIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isCurrent) Color.White else Slate700,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Custom Canvas Document Viewport
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .transformable(state = transformState)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            }
                        )
                    }
                    .testTag("document_canvas_container"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .size(pageCanvasWidth.dp, pageCanvasHeight.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .testTag("document_page_canvas")
                ) {
                    val layoutInfo = PageLayoutInfo(
                        pageIndex = activePage.pageIndex,
                        bounds = Rect(0f, 0f, size.width, size.height),
                        pageSize = size
                    )
                    renderEngine.renderPage(
                        drawScope = this,
                        page = activePage,
                        layoutInfo = layoutInfo,
                        isSelected = false
                    )
                }
            }
        }
    }

    // Insert Text Dialog
    if (showInsertTextDialog) {
        var textInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showInsertTextDialog = false },
            title = { Text("Sisipkan Teks / Paragraf", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { if (it.length <= 100_000) textInput = it },
                    label = { Text("Tulis teks di sini...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .testTag("insert_text_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            val paragraphs = paragraphBlocksFromText(
                                text = textInput,
                                isBold = isBold,
                                isItalic = isItalic,
                                isUnderline = isUnderline,
                                fontSize = selectedFontSize,
                                alignment = selectedAlignment
                            )
                            appendParagraphsAcrossPages(documentController, activePage, paragraphs)
                            showInsertTextDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_insert_text_btn")
                ) {
                    Text("Sisipkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInsertTextDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Insert Table Dialog
    if (showTableDialog) {
        var rowsText by remember { mutableStateOf("3") }
        var colsText by remember { mutableStateOf("3") }
        var tableData by remember { mutableStateOf("") }
        var tableError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showTableDialog = false },
            title = { Text("Sisipkan Tabel", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it },
                        label = { Text("Jumlah Baris") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = colsText,
                        onValueChange = { colsText = it },
                        label = { Text("Jumlah Kolom") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tableData,
                        onValueChange = {
                            if (it.length <= 20_000) tableData = it
                            tableError = null
                        },
                        label = { Text("Isi tabel (opsional)") },
                        supportingText = { Text("Pisahkan kolom dengan koma, titik koma, atau tab; baris dengan Enter.") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    tableError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rows = rowsText.toIntOrNull()
                        val columns = colsText.toIntOrNull()
                        when {
                            rows == null || rows !in 1..12 -> tableError = "Jumlah baris harus 1–12 per tabel."
                            columns == null || columns !in 1..12 -> tableError = "Jumlah kolom harus 1–12."
                            else -> runCatching {
                                val inputRows = if (tableData.isBlank()) {
                                    emptyList()
                                } else {
                                    OfficeFileParser.parseCsv(tableData)
                                }
                                createTable(rows, columns, inputRows)
                            }.onSuccess { table ->
                                val updatedBlocks = activePage.blocks + table
                                val updatedPage = activePage.copy(blocks = updatedBlocks)
                                val newPages = documentState.pages.map {
                                    if (it.id == activePage.id) updatedPage else it
                                }
                                documentController.reorderPages(newPages)
                                showTableDialog = false
                            }.onFailure { error ->
                                tableError = error.message ?: "Isi tabel tidak valid."
                            }
                        }
                    }
                ) {
                    Text("Buat Tabel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTableDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // AI Auto-Generate Letter Dialog
    if (showAiLetterDialog) {
        var letterType by remember { mutableStateOf("Surat Permohonan Izin Resmi") }
        var senderName by remember { mutableStateOf("") }
        var recipientName by remember { mutableStateOf("") }
        var purpose by remember { mutableStateOf("") }
        var additionalDetails by remember { mutableStateOf("") }
        var isGenerating by remember { mutableStateOf(false) }
        var generationError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isGenerating) showAiLetterDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = AccentIndigo)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Auto-Generate Surat", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = letterType,
                        onValueChange = { if (it.length <= 300) letterType = it },
                        label = { Text("Jenis Surat") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = senderName,
                        onValueChange = { if (it.length <= 300) senderName = it },
                        label = { Text("Nama Pengirim") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = recipientName,
                        onValueChange = { if (it.length <= 300) recipientName = it },
                        label = { Text("Penerima / Instansi") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { if (it.length <= 2_000) purpose = it },
                        label = { Text("Perihal / Maksud") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = additionalDetails,
                        onValueChange = { if (it.length <= 4_000) additionalDetails = it },
                        label = { Text("Rincian tambahan (opsional)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    generationError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isGenerating = true
                        scope.launch {
                            try {
                                val result = aiService.generateLetter(
                                    letterType = letterType.trim(),
                                    senderName = senderName.trim(),
                                    recipientName = recipientName.trim(),
                                    purpose = purpose.trim(),
                                    additionalDetails = additionalDetails.trim()
                                )
                                if (result.isSuccess) {
                                    val generatedText = result.getOrThrow()
                                    require(generatedText.isNotBlank()) { "Generator tidak menghasilkan isi surat" }
                                    require(generatedText.length <= 200_000) {
                                        "Isi surat terlalu panjang untuk editor"
                                    }
                                    val newBlocks = paragraphBlocksFromText(
                                        text = generatedText,
                                        fontSize = 13f,
                                        alignment = 0
                                    )
                                    appendParagraphsAcrossPages(documentController, activePage, newBlocks)
                                    showAiLetterDialog = false
                                } else {
                                    generationError = result.exceptionOrNull()?.message ?: "Surat gagal dibuat."
                                }
                            } catch (_: OutOfMemoryError) {
                                generationError = "Memori tidak cukup untuk memasukkan hasil surat ke editor."
                            } catch (error: Exception) {
                                generationError = error.message ?: "Surat gagal dibuat."
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = !isGenerating && letterType.isNotBlank() && senderName.isNotBlank() &&
                        recipientName.isNotBlank() && purpose.isNotBlank()
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Buat Sekarang")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAiLetterDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Signature Pad Dialog
    if (showSignatureDialog) {
        SignaturePadDialog(
            onDismiss = { showSignatureDialog = false },
            onSaveSignature = { bmp ->
                val sigAnnotation = SignatureAnnotation(
                    bitmap = bmp,
                    normalizedX = 0.5f,
                    normalizedY = 0.8f,
                    widthFraction = 0.35f,
                    heightFraction = 0.12f
                )
                documentController.addSignature(activePage.id, sigAnnotation)
                showSignatureDialog = false
            }
        )
    }

    // Watermark Dialog
    if (showWatermarkDialog) {
        WatermarkDialog(
            onDismiss = { showWatermarkDialog = false },
            onApplyWatermark = { wm ->
                documentController.addWatermark(wm)
                showWatermarkDialog = false
            }
        )
    }
}

@Composable
fun EditorBottomToolbar(
    isBold: Boolean,
    isItalic: Boolean,
    isUnderline: Boolean,
    alignment: Int,
    onToggleBold: () -> Unit,
    onToggleItalic: () -> Unit,
    onToggleUnderline: () -> Unit,
    onChangeAlignment: (Int) -> Unit,
    onInsertText: () -> Unit,
    onInsertTable: () -> Unit,
    onAddSignature: () -> Unit,
    onAddWatermark: () -> Unit,
    onAiLetter: () -> Unit,
    onAddPage: () -> Unit
) {
    Surface(
        color = SleekSurface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Slate200, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Row 1: Formatting Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolbarIconToggle(
                        icon = Icons.Default.FormatBold,
                        isSelected = isBold,
                        onClick = onToggleBold,
                        tag = "tool_bold"
                    )
                    ToolbarIconToggle(
                        icon = Icons.Default.FormatItalic,
                        isSelected = isItalic,
                        onClick = onToggleItalic,
                        tag = "tool_italic"
                    )
                    ToolbarIconToggle(
                        icon = Icons.Default.FormatUnderlined,
                        isSelected = isUnderline,
                        onClick = onToggleUnderline,
                        tag = "tool_underline"
                    )
                }

                // Alignments (0: Left, 1: Center, 2: Right, 3: Justify)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolbarIconToggle(
                        icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
                        isSelected = alignment == 0,
                        onClick = { onChangeAlignment(0) },
                        tag = "tool_align_left"
                    )
                    ToolbarIconToggle(
                        icon = Icons.Default.FormatAlignCenter,
                        isSelected = alignment == 1,
                        onClick = { onChangeAlignment(1) },
                        tag = "tool_align_center"
                    )
                    ToolbarIconToggle(
                        icon = Icons.AutoMirrored.Filled.FormatAlignRight,
                        isSelected = alignment == 2,
                        onClick = { onChangeAlignment(2) },
                        tag = "tool_align_right"
                    )
                    ToolbarIconToggle(
                        icon = Icons.Default.FormatAlignJustify,
                        isSelected = alignment == 3,
                        onClick = { onChangeAlignment(3) },
                        tag = "tool_align_justify"
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Row 2: Insert Features
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    AssistChip(
                        onClick = onInsertText,
                        label = { Text("Teks") },
                        leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = onInsertTable,
                        label = { Text("Tabel") },
                        leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddSignature,
                        label = { Text("Tanda Tangan") },
                        leadingIcon = { Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddWatermark,
                        label = { Text("Tanda Air") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.BrandingWatermark, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAiLetter,
                        label = { Text("AI Surat") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentIndigo) }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddPage,
                        label = { Text("+ Halaman") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }
    }
}

@Composable
fun ToolbarIconToggle(
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) SleekBlueLight else Color.Transparent,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, SleekBluePrimary) else null,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) SleekBluePrimary else Slate700,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun applyFormatting(
    controller: DocumentController,
    activePage: PageModel,
    isBold: Boolean,
    isItalic: Boolean,
    isUnderline: Boolean,
    alignment: Int,
    fontSize: Float
) {
    val updatedBlocks = activePage.blocks.map { block ->
        if (block is Block.ParagraphBlock) {
            val updatedRuns = block.runs.map { run ->
                run.copy(
                    isBold = isBold,
                    isItalic = isItalic,
                    isUnderline = isUnderline,
                    fontSize = fontSize
                )
            }
            block.copy(runs = updatedRuns, alignment = alignment)
        } else block
    }
    if (updatedBlocks == activePage.blocks) return
    val updatedPage = activePage.copy(blocks = updatedBlocks)
    val newPages = controller.documentState.value.pages.map {
        if (it.id == activePage.id) updatedPage else it
    }
    controller.reorderPages(newPages)
}

private fun createDefaultDocument(): DocumentModel {
    return DocumentModel(
        title = "Dokumen Baru",
        pages = listOf(PageModel(pageIndex = 0))
    )
}

private fun createTable(rows: Int, columns: Int, inputRows: List<List<String>>): Block.TableBlock {
    require(rows in 1..12 && columns in 1..12) { "Ukuran tabel tidak valid" }
    val tableCells = List(rows) { row ->
        List(columns) { column ->
            inputRows.getOrNull(row)?.getOrNull(column).orEmpty()
        }
    }
    return Block.TableBlock(rows = rows, cols = columns, cells = tableCells)
}

private fun paragraphBlocksFromText(
    text: String,
    isBold: Boolean = false,
    isItalic: Boolean = false,
    isUnderline: Boolean = false,
    fontSize: Float,
    alignment: Int,
    maximumCharactersPerBlock: Int = 500
): List<Block.ParagraphBlock> {
    require(maximumCharactersPerBlock in 100..2_000) { "Batas potongan paragraf tidak valid" }
    return text.lines().flatMap { originalLine ->
        val chunks = if (originalLine.isBlank()) {
            listOf(" ")
        } else {
            val result = mutableListOf<String>()
            var remaining = originalLine.trimEnd()
            while (remaining.length > maximumCharactersPerBlock) {
                val naturalBreak = remaining.lastIndexOf(' ', maximumCharactersPerBlock)
                    .takeIf { it >= maximumCharactersPerBlock / 2 }
                    ?: maximumCharactersPerBlock
                result += remaining.substring(0, naturalBreak).trimEnd()
                remaining = remaining.substring(naturalBreak).trimStart()
            }
            if (remaining.isNotEmpty()) result += remaining
            result
        }
        chunks.map { chunk ->
            Block.ParagraphBlock(
                runs = listOf(
                    TextRun(
                        text = chunk,
                        isBold = isBold,
                        isItalic = isItalic,
                        isUnderline = isUnderline,
                        fontSize = fontSize
                    )
                ),
                alignment = alignment
            )
        }
    }
}

private fun appendParagraphsAcrossPages(
    controller: DocumentController,
    activePage: PageModel,
    paragraphs: List<Block.ParagraphBlock>,
    maximumBlocksPerPage: Int = 24
) {
    if (paragraphs.isEmpty()) return
    val currentDocument = controller.documentState.value
    val activeIndex = currentDocument.pages.indexOfFirst { it.id == activePage.id }
    if (activeIndex < 0) return

    val updatedPages = currentDocument.pages.toMutableList()
    var destinationIndex = activeIndex
    var destinationPage = updatedPages[destinationIndex]
    var occupiedHeight = destinationPage.blocks.sumOf { block ->
        estimatedBlockHeight(block, destinationPage.width).toDouble()
    }.toFloat()

    paragraphs.forEach { paragraph ->
        val usableHeight = (destinationPage.height - 96f).coerceAtLeast(80f)
        val paragraphHeight = estimatedBlockHeight(paragraph, destinationPage.width)
        val fitsCurrentPage = destinationPage.blocks.size < maximumBlocksPerPage &&
            occupiedHeight + paragraphHeight <= usableHeight

        if (fitsCurrentPage) {
            destinationPage = destinationPage.copy(blocks = destinationPage.blocks + paragraph)
            updatedPages[destinationIndex] = destinationPage
            occupiedHeight += paragraphHeight
        } else {
            // Overflow text is inserted on a normal A4 page instead of inheriting a tiny imported
            // card/receipt size that could never contain an editable paragraph.
            destinationPage = PageModel(blocks = listOf(paragraph))
            destinationIndex++
            updatedPages.add(destinationIndex, destinationPage)
            occupiedHeight = estimatedBlockHeight(paragraph, destinationPage.width)
        }
    }
    controller.reorderPages(updatedPages)
}

private fun estimatedBlockHeight(block: Block, pageWidth: Float): Float = when (block) {
    is Block.ParagraphBlock -> {
        val fontSize = block.runs.maxOfOrNull { it.fontSize.coerceIn(8f, 72f) } ?: 14f
        val usableWidth = (pageWidth - 96f).coerceAtLeast(fontSize)
        val charactersPerLine = (usableWidth / (fontSize * 0.55f)).toInt().coerceAtLeast(1)
        val text = block.runs.joinToString(separator = "") { it.text }
        val lineCount = text.split('\n').sumOf { line ->
            maxOf(1, kotlin.math.ceil(line.length.toDouble() / charactersPerLine).toInt())
        }
        lineCount * fontSize * 1.35f + 18f
    }
    is Block.TableBlock -> block.rows.coerceIn(1, 500) * 36f + 24f
    is Block.ImageBlock -> {
        val requestedHeight = block.height.takeIf { it.isFinite() && it > 0f }
            ?: block.bitmap?.height?.toFloat()
            ?: 100f
        requestedHeight + 24f
    }
}
