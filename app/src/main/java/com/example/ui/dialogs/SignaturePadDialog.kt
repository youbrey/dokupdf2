package com.example.ui.dialogs

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onSaveSignature: (Bitmap) -> Unit
) {
    val paths = remember { mutableStateListOf<List<Offset>>() }
    var currentPath = remember { mutableStateListOf<Offset>() }
    var selectedColor by remember { mutableStateOf(Color.Black) }
    var strokeWidth by remember { mutableStateOf(5f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("signature_pad_dialog")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Buat Tanda Tangan",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Goreskan tanda tangan Anda pada area di bawah ini",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Canvas Pad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFAFAFA))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentPath = mutableStateListOf(offset)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentPath.add(change.position)
                                },
                                onDragEnd = {
                                    if (currentPath.isNotEmpty()) {
                                        paths.add(currentPath.toList())
                                    }
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Draw previous paths
                        for (pathPoints in paths) {
                            if (pathPoints.size > 1) {
                                val p = Path()
                                p.moveTo(pathPoints.first().x, pathPoints.first().y)
                                for (pt in pathPoints.drop(1)) {
                                    p.lineTo(pt.x, pt.y)
                                }
                                drawPath(
                                    path = p,
                                    color = selectedColor,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }
                        // Draw current active stroke
                        if (currentPath.size > 1) {
                            val p = Path()
                            p.moveTo(currentPath.first().x, currentPath.first().y)
                            for (pt in currentPath.drop(1)) {
                                p.lineTo(pt.x, pt.y)
                            }
                            drawPath(
                                path = p,
                                color = selectedColor,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }

                    if (paths.isEmpty() && currentPath.isEmpty()) {
                        Text(
                            text = "Tanda Tangan Di Sini",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFCBD5E1),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Color picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Color.Black, Color(0xFF1E40AF), Color(0xFFDC2626)).forEach { col ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(
                                        width = if (selectedColor == col) 2.5.dp else 1.dp,
                                        color = if (selectedColor == col) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .pointerInput(Unit) {
                                        detectDragGestures(onDragStart = { selectedColor = col }, onDrag = { _, _ -> })
                                    }
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            paths.clear()
                            currentPath.clear()
                        },
                        modifier = Modifier.testTag("clear_signature_button")
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Hapus", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bersihkan")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Batal")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val bitmap = renderToBitmap(paths, selectedColor, strokeWidth)
                            onSaveSignature(bitmap)
                        },
                        enabled = paths.isNotEmpty() || currentPath.isNotEmpty(),
                        modifier = Modifier.testTag("save_signature_button")
                    ) {
                        Icon(Icons.Default.Done, contentDescription = "Simpan", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gunakan")
                    }
                }
            }
        }
    }
}

private fun renderToBitmap(paths: List<List<Offset>>, color: Color, strokeWidth: Float): Bitmap {
    val width = 400
    val height = 200
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        this.style = AndroidPaint.Style.STROKE
        this.strokeWidth = strokeWidth
        this.strokeCap = AndroidPaint.Cap.ROUND
        this.strokeJoin = AndroidPaint.Join.ROUND
    }

    for (pts in paths) {
        if (pts.size > 1) {
            val p = android.graphics.Path()
            p.moveTo(pts.first().x, pts.first().y)
            for (pt in pts.drop(1)) {
                p.lineTo(pt.x, pt.y)
            }
            canvas.drawPath(p, paint)
        }
    }
    return bitmap
}
