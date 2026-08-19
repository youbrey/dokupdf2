package com.example.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.core.model.WatermarkAnnotation

@Composable
fun WatermarkDialog(
    onDismiss: () -> Unit,
    onApplyWatermark: (WatermarkAnnotation) -> Unit
) {
    var text by remember { mutableStateOf("RAHASIA / DOKUPDF") }
    var opacity by remember { mutableStateOf(0.35f) }
    var rotation by remember { mutableStateOf(-35f) }
    var isTiled by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("watermark_dialog")
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Tambah Tanda Air (Watermark)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Teks Tanda Air") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("watermark_text_field")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick presets
                Text(
                    text = "Preset Cepat:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("RAHASIA", "SALINAN", "DRAFT", "LUNAS").forEach { preset ->
                        AssistChip(
                            onClick = { text = preset },
                            label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Opacity slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Transparansi", style = MaterialTheme.typography.bodyMedium)
                    Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0.1f..0.9f
                )

                // Rotation slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sudut Kemiringan", style = MaterialTheme.typography.bodyMedium)
                    Text("${rotation.toInt()}°", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = rotation,
                    onValueChange = { rotation = it },
                    valueRange = -90f..90f
                )

                // Tiled checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = isTiled,
                        onCheckedChange = { isTiled = it },
                        modifier = Modifier.testTag("watermark_tiled_checkbox")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ulangi teks di seluruh halaman (Tiled)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Batal")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (text.isNotBlank()) {
                                onApplyWatermark(
                                    WatermarkAnnotation(
                                        text = text,
                                        opacity = opacity,
                                        rotationDegrees = rotation,
                                        isTiled = isTiled
                                    )
                                )
                            }
                        },
                        modifier = Modifier.testTag("apply_watermark_button")
                    ) {
                        Text("Terapkan")
                    }
                }
            }
        }
    }
}
