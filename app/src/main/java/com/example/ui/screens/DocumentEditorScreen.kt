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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.ai.GeminiAiService
import com.example.core.engine.DocumentController
import com.example.core.engine.DocumentEngine
import com.example.core.layout.PageLayoutInfo
import com.example.core.model.*
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

    val documentEngine = remember {
        DocumentEngine(
            context = context,
            initialDoc = initialDocument ?: createDefaultDocument()
        )
    }
    val documentController = remember { DocumentController(documentEngine) }
    val renderEngine = remember { RenderEngine() }
    val aiService = remember { GeminiAiService() }
    val pdfGenerator = remember { PdfGenerator(context) }

    val documentState by documentEngine.documentState.collectAsState()
    val canUndo by documentEngine.commandManager.canUndo.collectAsState()
    val canRedo by documentEngine.commandManager.canRedo.collectAsState()

    var activePageIndex by remember { mutableStateOf(0) }
    var documentTitle by remember { mutableStateOf(initialDocument?.title ?: "Dokumen Baru") }

    // Transformation State for Zoom & Pan Canvas
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 3.5f)
        offset += panChange
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

    // Active page
    val activePage = documentState.pages.getOrNull(activePageIndex)
        ?: documentState.pages.firstOrNull()
        ?: PageModel(pageIndex = 0)

    Scaffold(
        topBar = {
            SleekTopAppBar(
                title = documentTitle,
                subtitle = "Hal ${activePageIndex + 1} dari ${documentState.pages.size} • Canvas Engine",
                onNavigationClick = onBack,
                navigationIcon = Icons.Default.ArrowBack,
                actions = {
                    // Undo
                    IconButton(
                        onClick = { documentController.undo() },
                        enabled = canUndo,
                        modifier = Modifier.testTag("editor_undo_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Undo,
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
                            imageVector = Icons.Default.Redo,
                            contentDescription = "Redo",
                            tint = if (canRedo) SleekBluePrimary else Slate300
                        )
                    }
                    // Export PDF Button
                    IconButton(
                        onClick = {
                            scope.launch {
                                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                val fileName = "${documentTitle.replace(" ", "_")}_$timeStamp.pdf"
                                val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
                                val targetFile = File(exportDir, fileName)

                                val result = pdfGenerator.exportToPdf(documentState, targetFile)
                                if (result.isSuccess) {
                                    Toast.makeText(context, "PDF Berhasil Diekspor: $fileName", Toast.LENGTH_LONG).show()
                                    onDocumentSaved()
                                } else {
                                    Toast.makeText(context, "Gagal mengekspor PDF: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
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
                        .size(340.dp, 480.dp)
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
                    onValueChange = { textInput = it },
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
                            val paragraph = Block.ParagraphBlock(
                                runs = listOf(
                                    TextRun(
                                        text = textInput,
                                        isBold = isBold,
                                        isItalic = isItalic,
                                        isUnderline = isUnderline,
                                        fontSize = selectedFontSize
                                    )
                                ),
                                alignment = selectedAlignment
                            )
                            val updatedBlocks = activePage.blocks + paragraph
                            val updatedPage = activePage.copy(blocks = updatedBlocks)
                            val newPages = documentState.pages.map {
                                if (it.id == activePage.id) updatedPage else it
                            }
                            documentController.reorderPages(newPages)
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
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rows = rowsText.toIntOrNull() ?: 3
                        val cols = colsText.toIntOrNull() ?: 3
                        val table = createSampleTable(rows, cols)
                        val updatedBlocks = activePage.blocks + table
                        val updatedPage = activePage.copy(blocks = updatedBlocks)
                        val newPages = documentState.pages.map {
                            if (it.id == activePage.id) updatedPage else it
                        }
                        documentController.reorderPages(newPages)
                        showTableDialog = false
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
        var senderName by remember { mutableStateOf("Ahmad Fauzi") }
        var recipientName by remember { mutableStateOf("Kepala Divisi SDM") }
        var purpose by remember { mutableStateOf("Izin cuti dinas dan keperluan mendesak keluarga") }
        var isGenerating by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAiLetterDialog = false },
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
                        onValueChange = { letterType = it },
                        label = { Text("Jenis Surat") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = senderName,
                        onValueChange = { senderName = it },
                        label = { Text("Nama Pengirim") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = recipientName,
                        onValueChange = { recipientName = it },
                        label = { Text("Penerima / Instansi") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it },
                        label = { Text("Perihal / Maksud") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isGenerating = true
                        scope.launch {
                            val result = aiService.generateLetter(
                                letterType = letterType,
                                senderName = senderName,
                                recipientName = recipientName,
                                purpose = purpose,
                                additionalDetails = "Format formal baku Bahasa Indonesia"
                            )
                            isGenerating = false
                            if (result.isSuccess) {
                                val generatedText = result.getOrNull() ?: ""
                                val lines = generatedText.lines().filter { it.isNotBlank() }
                                val newBlocks = lines.map { line ->
                                    Block.ParagraphBlock(
                                        runs = listOf(
                                            TextRun(
                                                text = line,
                                                fontSize = 13f
                                            )
                                        ),
                                        alignment = 0
                                    )
                                }
                                val updatedPage = activePage.copy(blocks = activePage.blocks + newBlocks)
                                val newPages = documentState.pages.map {
                                    if (it.id == activePage.id) updatedPage else it
                                }
                                documentController.reorderPages(newPages)
                                showAiLetterDialog = false
                            }
                        }
                    },
                    enabled = !isGenerating
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
                        icon = Icons.Default.FormatAlignLeft,
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
                        icon = Icons.Default.FormatAlignRight,
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
                        leadingIcon = { Icon(Icons.Default.BrandingWatermark, contentDescription = null, modifier = Modifier.size(16.dp)) }
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
    val updatedPage = activePage.copy(blocks = updatedBlocks)
    val newPages = controller.documentState.value.pages.map {
        if (it.id == activePage.id) updatedPage else it
    }
    controller.reorderPages(newPages)
}

private fun createDefaultDocument(): DocumentModel {
    val samplePage = PageModel(
        pageIndex = 0,
        blocks = listOf(
            Block.ParagraphBlock(
                runs = listOf(
                    TextRun(
                        text = "SURAT KEPUTUSAN & PERJANJIAN DOKUPDF",
                        isBold = true,
                        fontSize = 16f
                    )
                ),
                alignment = 1
            ),
            Block.ParagraphBlock(
                runs = listOf(
                    TextRun(
                        text = "Dokumen ini dirender dengan Custom Canvas Engine berkinerja tinggi sesuai arsitektur Clean & MVVM.",
                        fontSize = 12f
                    )
                ),
                alignment = 3
            )
        )
    )
    return DocumentModel(
        title = "Dokumen Resmi",
        pages = listOf(samplePage)
    )
}

private fun createSampleTable(rows: Int, cols: Int): Block.TableBlock {
    val tableCells = (0 until rows).map { r ->
        (0 until cols).map { c ->
            if (r == 0) "Kolom ${c + 1}" else "Data R${r}C${c}"
        }
    }
    return Block.TableBlock(rows = rows, cols = cols, cells = tableCells)
}
