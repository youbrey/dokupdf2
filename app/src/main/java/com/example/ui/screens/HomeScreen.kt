package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.repository.SavedDocumentItem
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    documents: List<SavedDocumentItem>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenDocument: (SavedDocumentItem) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenPdfTools: () -> Unit,
    onOpenAiTools: () -> Unit,
    onDeleteDocument: (SavedDocumentItem) -> Unit,
    onQuickAction: (String) -> Unit
) {
    var selectedFilter by remember { mutableStateOf("Semua") }
    val filters = listOf("Semua", "PDF", "Scan", "Tersimpan")

    val filteredDocs = remember(documents, searchQuery, selectedFilter) {
        documents.filter { doc ->
            val matchesSearch = searchQuery.isEmpty() ||
                    doc.title.contains(searchQuery, ignoreCase = true)
            val matchesCategory = when (selectedFilter) {
                "PDF" -> doc.file.extension.equals("pdf", ignoreCase = true)
                "Scan" -> doc.title.contains("Scan", ignoreCase = true)
                else -> true
            }
            matchesSearch && matchesCategory
        }
    }

    Scaffold(
        topBar = {
            SleekTopAppBar(
                title = "DokuPDF",
                subtitle = "Document Editor & PDF Scanner Pro",
                actions = {
                    IconButton(
                        onClick = onOpenAiTools,
                        modifier = Modifier.testTag("ai_assistant_top_btn")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(AccentIndigoBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Assistant",
                                tint = AccentIndigo,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )
        },
        containerColor = SleekBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 90.dp, top = 8.dp)
        ) {
            // Search Bar
            item {
                SleekSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    placeholder = "Cari berkas, dokumen, atau pindaian..."
                )
            }

            // Hero Banner: Custom Canvas Word-Class Editor
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenEditor() }
                        .testTag("hero_editor_banner"),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.horizontalGradient(
                                    listOf(SleekBlueDark, SleekBluePrimary, Color(0xFF3B82F6))
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color.White.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "CANVAS RENDERING ENGINE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        ),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Buat & Edit Dokumen",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Rich Text, Tabel, Multi-halaman, Tanda Tangan, Undo/Redo setara Word & Docs",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color.White.copy(alpha = 0.9f)
                                    ),
                                    maxLines = 2
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "New Document",
                                    tint = SleekBluePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Quick Power Actions Grid
            item {
                Text(
                    text = "Aksi Cepat",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Slate900
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SleekToolGridItem(
                        title = "Pindai PDF",
                        icon = Icons.Outlined.DocumentScanner,
                        color = SleekBluePrimary,
                        bgColor = SleekBlueLight,
                        onClick = onOpenScanner,
                        modifier = Modifier.weight(1f)
                    )
                    SleekToolGridItem(
                        title = "Alat PDF",
                        icon = Icons.Outlined.Handyman,
                        color = AccentIndigo,
                        bgColor = AccentIndigoBg,
                        onClick = onOpenPdfTools,
                        modifier = Modifier.weight(1f)
                    )
                    SleekToolGridItem(
                        title = "AI Dokumen",
                        icon = Icons.Outlined.AutoAwesome,
                        color = AccentOrange,
                        bgColor = AccentOrangeBg,
                        onClick = onOpenAiTools,
                        modifier = Modifier.weight(1f)
                    )
                    SleekToolGridItem(
                        title = "OCR Teks",
                        icon = Icons.Outlined.TextFields,
                        color = AccentEmerald,
                        bgColor = AccentEmeraldBg,
                        onClick = { onQuickAction("ocr") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Category Filter Pills
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(filters) { filter ->
                        val isSelected = selectedFilter == filter
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { selectedFilter = filter }
                                .border(
                                    1.dp,
                                    if (isSelected) SleekBluePrimary else Slate200,
                                    RoundedCornerShape(20.dp)
                                )
                                .testTag("filter_chip_$filter"),
                            color = if (isSelected) SleekBluePrimary else SleekSurface
                        ) {
                            Text(
                                text = filter,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) Color.White else Slate700,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Recent Documents Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Berkas Dokumen (${filteredDocs.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Slate900
                    )
                    if (filteredDocs.isNotEmpty()) {
                        Text(
                            text = "Tersimpan Lokal",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500
                        )
                    }
                }
            }

            if (filteredDocs.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Slate200, RoundedCornerShape(16.dp)),
                        color = SleekSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "Empty",
                                tint = Slate400,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "Tidak ada berkas cocok dengan '$searchQuery'" else "Belum ada dokumen",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Slate700
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Mulai dengan membuat dokumen baru atau memindai berkas",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate500
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onOpenEditor,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SleekBluePrimary)
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Buat Dokumen Sekarang")
                            }
                        }
                    }
                }
            } else {
                items(filteredDocs) { doc ->
                    DocumentItemRow(
                        doc = doc,
                        onOpen = { onOpenDocument(doc) },
                        onDelete = { onDeleteDocument(doc) }
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentItemRow(
    doc: SavedDocumentItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val isPdf = doc.file.extension.equals("pdf", ignoreCase = true)

    val iconColor = if (isPdf) Color(0xFFEF4444) else SleekBluePrimary
    val iconBg = if (isPdf) Color(0xFFFEE2E2) else SleekBlueLight

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Slate200, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .testTag("doc_item_${doc.id}"),
        color = SleekSurface
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = doc.file.extension,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Slate900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = doc.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate400
                    )
                    Text(
                        text = doc.formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500
                    )
                    if (doc.pageCount > 0) {
                        Text(
                            text = " • ${doc.pageCount} Hal",
                            style = MaterialTheme.typography.labelSmall,
                            color = SleekBluePrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Hapus",
                    tint = Slate400,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
