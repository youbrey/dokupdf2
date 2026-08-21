package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.DocumentModel
import com.example.core.model.PageModel
import com.example.core.pdf.PdfRendererEngine
import com.example.core.repository.DocumentRepository
import com.example.core.repository.SavedDocumentItem
import com.example.ui.screens.DocumentEditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PdfToolsScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME,
    EDITOR,
    SCANNER,
    TOOLS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                DokuPdfApp()
            }
        }
    }

}

@Composable
fun DokuPdfApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DocumentRepository(context) }
    val pdfRenderer = remember { PdfRendererEngine(context) }

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var selectedDocumentModel by remember { mutableStateOf<DocumentModel?>(null) }
    var requestedToolId by remember { mutableStateOf<String?>(null) }
    var requestedToolCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isOpeningDocument by remember { mutableStateOf(false) }
    val documents by repository.documents.collectAsState()

    LaunchedEffect(Unit) {
        repository.refreshDocuments()
    }

    Scaffold(
        bottomBar = {
            if (currentScreen == AppScreen.HOME || currentScreen == AppScreen.TOOLS) {
                SleekBottomNavigationBar(
                    currentScreen = currentScreen,
                    onNavigate = { screen ->
                        if (screen == AppScreen.TOOLS) {
                            requestedToolId = null
                            requestedToolCategory = null
                        }
                        currentScreen = screen
                    },
                    onOpenScanner = { currentScreen = AppScreen.SCANNER }
                )
            }
        },
        containerColor = SleekBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (currentScreen == AppScreen.HOME || currentScreen == AppScreen.TOOLS) innerPadding.calculateBottomPadding() else 0.dp)
        ) {
            when (currentScreen) {
                AppScreen.HOME -> {
                    HomeScreen(
                        documents = documents,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onOpenDocument = { doc ->
                            // BUG FIX: this previously created `PageModel(pageIndex = 0)` with
                            // every field at its default (originalBitmap = null, blocks = empty).
                            // RenderEngine.renderPage() only draws page.processedBitmap /
                            // originalBitmap, falling back to page.blocks if both are null --
                            // since a scanned PDF has neither, the Canvas Engine editor always
                            // opened to a blank page regardless of what the PDF actually
                            // contained. Fix: actually rasterize the saved PDF's pages (the app
                            // already does this correctly for the Home screen thumbnail via the
                            // same PdfRendererEngine) and hand them to the editor as real page
                            // content.
                            if (!isOpeningDocument) {
                                isOpeningDocument = true
                                scope.launch {
                                    var renderedBitmaps: List<android.graphics.Bitmap> = emptyList()
                                    var ownershipTransferredToEditor = false
                                    try {
                                        val dimensions = pdfRenderer.getPageDimensions(doc.file)
                                        require(dimensions.isNotEmpty()) { "PDF tidak memiliki halaman yang dapat dibaca" }
                                        val totalPagePixels = dimensions.sumOf {
                                            it.width.toDouble() * it.height.toDouble()
                                        }.coerceAtLeast(1.0)
                                        // Keep the complete document editable while bounding its total
                                        // decoded bitmap budget to roughly 48 MB (12M ARGB pixels).
                                        val requiredScale = kotlin.math.sqrt(12_000_000.0 / totalPagePixels)
                                            .toFloat()
                                        require(requiredScale >= 0.02f) {
                                            "Dokumen terlalu besar untuk dibuka sekaligus; pisahkan PDF terlebih dahulu"
                                        }
                                        val renderScale = requiredScale.coerceAtMost(1.6f)
                                        renderedBitmaps = pdfRenderer.renderPdfPages(doc.file, scale = renderScale)
                                        require(renderedBitmaps.size == dimensions.size) {
                                            "Tidak semua halaman PDF berhasil dirender"
                                        }
                                        val pages = renderedBitmaps.mapIndexed { index, bitmap ->
                                            PageModel(
                                                pageIndex = index,
                                                originalBitmap = bitmap,
                                                width = dimensions[index].width.toFloat(),
                                                height = dimensions[index].height.toFloat()
                                            )
                                        }
                                        selectedDocumentModel = DocumentModel(
                                            title = doc.title,
                                            filePath = doc.file.absolutePath,
                                            pages = pages
                                        )
                                        ownershipTransferredToEditor = true
                                        currentScreen = AppScreen.EDITOR
                                    } catch (error: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Gagal membuka dokumen: ${error.message ?: "PDF tidak dapat dibaca"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (_: OutOfMemoryError) {
                                        Toast.makeText(
                                            context,
                                            "Memori tidak cukup untuk membuka seluruh dokumen. Pisahkan PDF terlebih dahulu.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        if (!ownershipTransferredToEditor) {
                                            renderedBitmaps.forEach { bitmap ->
                                                if (!bitmap.isRecycled) bitmap.recycle()
                                            }
                                        }
                                        isOpeningDocument = false
                                    }
                                }
                            }
                        },
                        onOpenEditor = {
                            selectedDocumentModel = DocumentModel(
                                title = "Dokumen Baru",
                                pages = listOf(PageModel(pageIndex = 0))
                            )
                            currentScreen = AppScreen.EDITOR
                        },
                        onOpenScanner = {
                            currentScreen = AppScreen.SCANNER
                        },
                        onOpenPdfTools = {
                            requestedToolId = null
                            requestedToolCategory = null
                            currentScreen = AppScreen.TOOLS
                        },
                        onOpenAiTools = {
                            requestedToolId = null
                            requestedToolCategory = "AI Pro"
                            currentScreen = AppScreen.TOOLS
                        },
                        onDeleteDocument = { doc ->
                            scope.launch {
                                try {
                                    if (!repository.deleteDocument(doc)) {
                                        Toast.makeText(
                                            context,
                                            "Dokumen gagal dihapus dari penyimpanan.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (error: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Gagal menghapus dokumen: ${error.message ?: "penyimpanan tidak tersedia"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onQuickAction = { action ->
                            requestedToolId = when (action) {
                                "ocr" -> "ocr_text"
                                else -> null
                            }
                            requestedToolCategory = null
                            currentScreen = AppScreen.TOOLS
                        }
                    )
                }

                AppScreen.EDITOR -> {
                    DocumentEditorScreen(
                        initialDocument = selectedDocumentModel,
                        onBack = {
                            // Release the root-level reference to rasterized PDF pages after the
                            // editor leaves composition. Do not recycle them manually: Compose may
                            // still replay the previous display list for one frame.
                            selectedDocumentModel = null
                            currentScreen = AppScreen.HOME
                            scope.launch { repository.refreshDocuments() }
                        },
                        onDocumentSaved = {
                            selectedDocumentModel = null
                            scope.launch { repository.refreshDocuments() }
                            currentScreen = AppScreen.HOME
                        }
                    )
                }

                AppScreen.SCANNER -> {
                    ScannerScreen(
                        onBack = {
                            currentScreen = AppScreen.HOME
                            scope.launch { repository.refreshDocuments() }
                        },
                        onScanSaved = {
                            scope.launch { repository.refreshDocuments() }
                            currentScreen = AppScreen.HOME
                        }
                    )
                }

                AppScreen.TOOLS -> {
                    PdfToolsScreen(
                        documents = documents,
                        initialToolId = requestedToolId,
                        initialCategory = requestedToolCategory,
                        onBack = {
                            requestedToolId = null
                            requestedToolCategory = null
                            currentScreen = AppScreen.HOME
                        },
                        onRefreshDocuments = {
                            scope.launch { repository.refreshDocuments() }
                        }
                    )
                }
            }

            if (isOpeningDocument) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
fun SleekBottomNavigationBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    onOpenScanner: () -> Unit
) {
    Surface(
        color = SleekSurface,
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Slate100, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SleekNavItem(
                icon = if (currentScreen == AppScreen.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                label = "Beranda",
                isSelected = currentScreen == AppScreen.HOME,
                onClick = { onNavigate(AppScreen.HOME) },
                testTag = "nav_home"
            )

            // Center Floating Scanner Action
            Box(
                modifier = Modifier
                    .offset(y = (-10).dp)
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(SleekBluePrimary, SleekBlueDark)
                        )
                    )
                    .clickable(onClick = onOpenScanner)
                    .testTag("nav_scanner_fab"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DocumentScanner,
                    contentDescription = "Pindai",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            SleekNavItem(
                icon = if (currentScreen == AppScreen.TOOLS) Icons.Filled.Handyman else Icons.Outlined.Handyman,
                label = "Alat PDF",
                isSelected = currentScreen == AppScreen.TOOLS,
                onClick = { onNavigate(AppScreen.TOOLS) },
                testTag = "nav_tools"
            )
        }
    }
}

@Composable
fun SleekNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) SleekBluePrimary else Slate400,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (isSelected) SleekBluePrimary else Slate500
        )
    }
}
