package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.crop.AutoCropDetector
import com.example.core.filter.FilterProcessor
import com.example.core.model.CropGeometry
import com.example.ui.theme.*
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class HandleType {
    NONE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    EDGE_TOP,
    EDGE_RIGHT,
    EDGE_BOTTOM,
    EDGE_LEFT
}

/**
 * CamScanner-grade Interactive 8-Point Quadrilateral Crop & Perspective Straightening Screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveCropScreen(
    initialBitmap: Bitmap,
    initialGeometry: CropGeometry? = null,
    onBack: () -> Unit,
    onCropConfirmed: (croppedBitmap: Bitmap, geometry: CropGeometry, rotatedBitmap: Bitmap) -> Unit
) {
    var workingBitmap by remember { mutableStateOf(initialBitmap) }
    var cropGeometry by remember {
        mutableStateOf(
            initialGeometry ?: AutoCropDetector.detectDocumentCorners(initialBitmap)
        )
    }

    var activeHandle by remember { mutableStateOf(HandleType.NONE) }
    var displayedImageBounds by remember { mutableStateOf(Rect.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Memotong",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "Sesuaikan 8 titik sudut atau ratakan otomatis",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate400
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("crop_back_btn")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // Auto detect again
                            cropGeometry = AutoCropDetector.detectDocumentCorners(workingBitmap)
                        },
                        modifier = Modifier.testTag("crop_auto_detect_btn")
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "Deteksi Otomatis", tint = AccentEmerald)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900)
            )
        },
        bottomBar = {
            Surface(
                color = Slate900,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rotate Left
                    CropActionButton(
                        icon = Icons.Default.RotateLeft,
                        label = "Kiri",
                        onClick = {
                            val matrix = Matrix().apply { postRotate(-90f) }
                            workingBitmap = Bitmap.createBitmap(
                                workingBitmap, 0, 0,
                                workingBitmap.width, workingBitmap.height, matrix, true
                            )
                            cropGeometry = AutoCropDetector.detectDocumentCorners(workingBitmap)
                        },
                        testTag = "crop_rotate_left_btn"
                    )

                    // Rotate Right
                    CropActionButton(
                        icon = Icons.Default.RotateRight,
                        label = "Kanan",
                        onClick = {
                            val matrix = Matrix().apply { postRotate(90f) }
                            workingBitmap = Bitmap.createBitmap(
                                workingBitmap, 0, 0,
                                workingBitmap.width, workingBitmap.height, matrix, true
                            )
                            cropGeometry = AutoCropDetector.detectDocumentCorners(workingBitmap)
                        },
                        testTag = "crop_rotate_right_btn"
                    )

                    // Select All / Full Bounds
                    CropActionButton(
                        icon = Icons.Default.Fullscreen,
                        label = "Semua",
                        onClick = {
                            cropGeometry = AutoCropDetector.fullGeometry()
                        },
                        testTag = "crop_select_all_btn"
                    )

                    // Confirm & Next
                    Button(
                        onClick = {
                            val cropped = FilterProcessor.cropPerspective(workingBitmap, cropGeometry)
                            onCropConfirmed(cropped, cropGeometry, workingBitmap)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekBluePrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("crop_confirm_next_btn")
                    ) {
                        Text(
                            text = "Berikutnya",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0F172A)),
            contentAlignment = Alignment.Center
        ) {
            val imageBitmap = remember(workingBitmap) { workingBitmap.asImageBitmap() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .pointerInput(displayedImageBounds, cropGeometry) {
                        detectDragGestures(
                            onDragStart = { touchOffset ->
                                if (displayedImageBounds.isEmpty) return@detectDragGestures
                                activeHandle = findNearestHandle(
                                    touchOffset,
                                    cropGeometry,
                                    displayedImageBounds
                                )
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (activeHandle == HandleType.NONE || displayedImageBounds.isEmpty) return@detectDragGestures

                                val normDx = dragAmount.x / displayedImageBounds.width
                                val normDy = dragAmount.y / displayedImageBounds.height

                                cropGeometry = updateGeometry(
                                    cropGeometry,
                                    activeHandle,
                                    normDx,
                                    normDy
                                )
                            },
                            onDragEnd = {
                                activeHandle = HandleType.NONE
                            },
                            onDragCancel = {
                                activeHandle = HandleType.NONE
                            }
                        )
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val imgWidth = workingBitmap.width.toFloat()
                val imgHeight = workingBitmap.height.toFloat()

                val imgAspect = imgWidth / imgHeight
                val canvasAspect = canvasWidth / canvasHeight

                val drawWidth: Float
                val drawHeight: Float
                val drawLeft: Float
                val drawTop: Float

                if (imgAspect > canvasAspect) {
                    drawWidth = canvasWidth
                    drawHeight = canvasWidth / imgAspect
                    drawLeft = 0f
                    drawTop = (canvasHeight - drawHeight) / 2f
                } else {
                    drawHeight = canvasHeight
                    drawWidth = canvasHeight * imgAspect
                    drawTop = 0f
                    drawLeft = (canvasWidth - drawWidth) / 2f
                }

                val imgRect = Rect(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight)
                displayedImageBounds = imgRect

                // 1. Draw source image
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(drawLeft.roundToInt(), drawTop.roundToInt()),
                    dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt())
                )

                // 2. Compute absolute screen coordinates of crop points
                val tl = toScreenCoord(cropGeometry.topLeft, imgRect)
                val tr = toScreenCoord(cropGeometry.topRight, imgRect)
                val br = toScreenCoord(cropGeometry.bottomRight, imgRect)
                val bl = toScreenCoord(cropGeometry.bottomLeft, imgRect)

                // Midpoints
                val midTop = Offset((tl.x + tr.x) / 2f, (tl.y + tr.y) / 2f)
                val midRight = Offset((tr.x + br.x) / 2f, (tr.y + br.y) / 2f)
                val midBottom = Offset((bl.x + br.x) / 2f, (bl.y + br.y) / 2f)
                val midLeft = Offset((tl.x + bl.x) / 2f, (tl.y + bl.y) / 2f)

                // 3. Draw translucent dimmed overlay outside quadrilateral
                val fullPath = Path().apply {
                    addRect(imgRect)
                }
                val quadPath = Path().apply {
                    moveTo(tl.x, tl.y)
                    lineTo(tr.x, tr.y)
                    lineTo(br.x, br.y)
                    lineTo(bl.x, bl.y)
                    close()
                }

                drawPath(
                    path = fullPath,
                    color = Color.Black.copy(alpha = 0.55f)
                )
                // Cut out quadrilateral by re-clearing or drawing quad with blend
                drawPath(
                    path = quadPath,
                    color = Color.Transparent,
                    blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                )
                // Redraw image under quad
                // Draw connecting border line
                val strokeColor = Color(0xFF06B6D4) // Vibrant Cyan
                drawPath(
                    path = quadPath,
                    color = strokeColor,
                    style = Stroke(width = 3.dp.toPx())
                )

                // Inner grid lines (Rule of Thirds)
                drawGridLines(tl, tr, br, bl, strokeColor.copy(alpha = 0.35f))

                // 4. Draw 4 Corner Handles
                drawCornerHandle(tl, activeHandle == HandleType.TOP_LEFT)
                drawCornerHandle(tr, activeHandle == HandleType.TOP_RIGHT)
                drawCornerHandle(br, activeHandle == HandleType.BOTTOM_RIGHT)
                drawCornerHandle(bl, activeHandle == HandleType.BOTTOM_LEFT)

                // 5. Draw 4 Edge Midpoint Handles
                drawEdgeHandle(midTop, isHorizontal = true, activeHandle == HandleType.EDGE_TOP)
                drawEdgeHandle(midRight, isHorizontal = false, activeHandle == HandleType.EDGE_RIGHT)
                drawEdgeHandle(midBottom, isHorizontal = true, activeHandle == HandleType.EDGE_BOTTOM)
                drawEdgeHandle(midLeft, isHorizontal = false, activeHandle == HandleType.EDGE_LEFT)
            }
        }
    }
}

private fun DrawScope.drawGridLines(tl: Offset, tr: Offset, br: Offset, bl: Offset, color: Color) {
    val stroke = Stroke(width = 1.dp.toPx())

    for (i in 1..2) {
        val f = i / 3f
        // Vertical lines
        val pTop = Offset(tl.x + (tr.x - tl.x) * f, tl.y + (tr.y - tl.y) * f)
        val pBot = Offset(bl.x + (br.x - bl.x) * f, bl.y + (br.y - bl.y) * f)
        drawLine(color = color, start = pTop, end = pBot, strokeWidth = 1.dp.toPx())

        // Horizontal lines
        val pLeft = Offset(tl.x + (bl.x - tl.x) * f, tl.y + (bl.y - tl.y) * f)
        val pRight = Offset(tr.x + (br.x - tr.x) * f, tr.y + (br.y - tr.y) * f)
        drawLine(color = color, start = pLeft, end = pRight, strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.drawCornerHandle(center: Offset, isActive: Boolean) {
    val outerRadius = if (isActive) 16.dp.toPx() else 13.dp.toPx()
    val innerRadius = if (isActive) 9.dp.toPx() else 7.dp.toPx()

    // Outer glow
    drawCircle(
        color = Color(0xFF06B6D4).copy(alpha = if (isActive) 0.6f else 0.35f),
        radius = outerRadius + 4.dp.toPx(),
        center = center
    )
    // White ring
    drawCircle(
        color = Color.White,
        radius = outerRadius,
        center = center
    )
    // Cyan center
    drawCircle(
        color = Color(0xFF0284C7),
        radius = innerRadius,
        center = center
    )
}

private fun DrawScope.drawEdgeHandle(center: Offset, isHorizontal: Boolean, isActive: Boolean) {
    val length = 24.dp.toPx()
    val thickness = 6.dp.toPx()

    val rect = if (isHorizontal) {
        Rect(center.x - length / 2f, center.y - thickness / 2f, center.x + length / 2f, center.y + thickness / 2f)
    } else {
        Rect(center.x - thickness / 2f, center.y - length / 2f, center.x + thickness / 2f, center.y + length / 2f)
    }

    drawRoundRect(
        color = Color.White,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx())
    )
    drawRoundRect(
        color = Color(0xFF0284C7),
        topLeft = Offset(rect.left + 1.dp.toPx(), rect.top + 1.dp.toPx()),
        size = Size(rect.width - 2.dp.toPx(), rect.height - 2.dp.toPx()),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
    )
}

private fun toScreenCoord(norm: Offset, bounds: Rect): Offset {
    return Offset(
        bounds.left + norm.x * bounds.width,
        bounds.top + norm.y * bounds.height
    )
}

private fun findNearestHandle(touch: Offset, geometry: CropGeometry, bounds: Rect): HandleType {
    val tl = toScreenCoord(geometry.topLeft, bounds)
    val tr = toScreenCoord(geometry.topRight, bounds)
    val br = toScreenCoord(geometry.bottomRight, bounds)
    val bl = toScreenCoord(geometry.bottomLeft, bounds)

    val midTop = Offset((tl.x + tr.x) / 2f, (tl.y + tr.y) / 2f)
    val midRight = Offset((tr.x + br.x) / 2f, (tr.y + br.y) / 2f)
    val midBottom = Offset((bl.x + br.x) / 2f, (bl.y + br.y) / 2f)
    val midLeft = Offset((tl.x + bl.x) / 2f, (tl.y + bl.y) / 2f)

    val touchRadius = 42f * 3f // Generous touch target

    val candidates = listOf(
        Pair(HandleType.TOP_LEFT, hypot((touch.x - tl.x).toDouble(), (touch.y - tl.y).toDouble()).toFloat()),
        Pair(HandleType.TOP_RIGHT, hypot((touch.x - tr.x).toDouble(), (touch.y - tr.y).toDouble()).toFloat()),
        Pair(HandleType.BOTTOM_RIGHT, hypot((touch.x - br.x).toDouble(), (touch.y - br.y).toDouble()).toFloat()),
        Pair(HandleType.BOTTOM_LEFT, hypot((touch.x - bl.x).toDouble(), (touch.y - bl.y).toDouble()).toFloat()),
        Pair(HandleType.EDGE_TOP, hypot((touch.x - midTop.x).toDouble(), (touch.y - midTop.y).toDouble()).toFloat()),
        Pair(HandleType.EDGE_RIGHT, hypot((touch.x - midRight.x).toDouble(), (touch.y - midRight.y).toDouble()).toFloat()),
        Pair(HandleType.EDGE_BOTTOM, hypot((touch.x - midBottom.x).toDouble(), (touch.y - midBottom.y).toDouble()).toFloat()),
        Pair(HandleType.EDGE_LEFT, hypot((touch.x - midLeft.x).toDouble(), (touch.y - midLeft.y).toDouble()).toFloat())
    )

    val closest = candidates.minByOrNull { it.second }
    return if (closest != null && closest.second < touchRadius) closest.first else HandleType.NONE
}

private fun updateGeometry(
    current: CropGeometry,
    handle: HandleType,
    dx: Float,
    dy: Float
): CropGeometry {
    return when (handle) {
        HandleType.TOP_LEFT -> {
            val newX = (current.topLeft.x + dx).coerceIn(0f, current.topRight.x - 0.05f)
            val newY = (current.topLeft.y + dy).coerceIn(0f, current.bottomLeft.y - 0.05f)
            current.copy(topLeft = Offset(newX, newY))
        }
        HandleType.TOP_RIGHT -> {
            val newX = (current.topRight.x + dx).coerceIn(current.topLeft.x + 0.05f, 1f)
            val newY = (current.topRight.y + dy).coerceIn(0f, current.bottomRight.y - 0.05f)
            current.copy(topRight = Offset(newX, newY))
        }
        HandleType.BOTTOM_RIGHT -> {
            val newX = (current.bottomRight.x + dx).coerceIn(current.bottomLeft.x + 0.05f, 1f)
            val newY = (current.bottomRight.y + dy).coerceIn(current.topRight.y + 0.05f, 1f)
            current.copy(bottomRight = Offset(newX, newY))
        }
        HandleType.BOTTOM_LEFT -> {
            val newX = (current.bottomLeft.x + dx).coerceIn(0f, current.bottomRight.x - 0.05f)
            val newY = (current.bottomLeft.y + dy).coerceIn(current.topLeft.y + 0.05f, 1f)
            current.copy(bottomLeft = Offset(newX, newY))
        }
        HandleType.EDGE_TOP -> {
            val newTlY = (current.topLeft.y + dy).coerceIn(0f, current.bottomLeft.y - 0.05f)
            val newTrY = (current.topRight.y + dy).coerceIn(0f, current.bottomRight.y - 0.05f)
            current.copy(
                topLeft = Offset(current.topLeft.x, newTlY),
                topRight = Offset(current.topRight.x, newTrY)
            )
        }
        HandleType.EDGE_BOTTOM -> {
            val newBlY = (current.bottomLeft.y + dy).coerceIn(current.topLeft.y + 0.05f, 1f)
            val newBrY = (current.bottomRight.y + dy).coerceIn(current.topRight.y + 0.05f, 1f)
            current.copy(
                bottomLeft = Offset(current.bottomLeft.x, newBlY),
                bottomRight = Offset(current.bottomRight.x, newBrY)
            )
        }
        HandleType.EDGE_LEFT -> {
            val newTlX = (current.topLeft.x + dx).coerceIn(0f, current.topRight.x - 0.05f)
            val newBlX = (current.bottomLeft.x + dx).coerceIn(0f, current.bottomRight.x - 0.05f)
            current.copy(
                topLeft = Offset(newTlX, current.topLeft.y),
                bottomLeft = Offset(newBlX, current.bottomLeft.y)
            )
        }
        HandleType.EDGE_RIGHT -> {
            val newTrX = (current.topRight.x + dx).coerceIn(current.topLeft.x + 0.05f, 1f)
            val newBrX = (current.bottomRight.x + dx).coerceIn(current.bottomLeft.x + 0.05f, 1f)
            current.copy(
                topRight = Offset(newTrX, current.topRight.y),
                bottomRight = Offset(newBrX, current.bottomRight.y)
            )
        }
        HandleType.NONE -> current
    }
}

@Composable
private fun CropActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Slate300)
    }
}
