package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.core.pdf.ExportUtils

/**
 * [Refactor] Sebelumnya `PdfToolsScreen.kt` dan `ScannerScreen.kt` masing-masing punya
 * `savePermissionLauncher` + state "pending file(s)" sendiri-sendiri untuk fitur
 * "Simpan ke Perangkat" -- implementasinya identik kecuali tipe data yang ditunda
 * (`List<File>?` vs `File?`). Composable ini menyatukan HANYA bagian yang benar-benar
 * duplikat (dance permintaan izin runtime API 24-28 -> tunda aksi -> lanjutkan setelah
 * izin diberikan). Logika ekspor & pesan toast/hasil di masing-masing layar TIDAK
 * disentuh -- itu tetap spesifik per layar lewat callback [onReady].
 *
 * Pemakaian:
 * ```
 * val requestExportPermission = rememberStorageExportGate(
 *     onPermissionDenied = { Toast.makeText(context, "...", Toast.LENGTH_LONG).show() }
 * )
 * requestExportPermission { /* aksi yang butuh izin storage sudah aman dijalankan */ }
 * ```
 */
@Composable
fun rememberStorageExportGate(onPermissionDenied: () -> Unit): (onReady: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted && action != null) {
            action()
        } else if (!granted) {
            onPermissionDenied()
        }
    }

    return { onReady ->
        if (ExportUtils.requiresLegacyPermission() && !ExportUtils.hasLegacyStoragePermission(context)) {
            pendingAction = onReady
            permissionLauncher.launch(ExportUtils.LEGACY_WRITE_PERMISSION)
        } else {
            onReady()
        }
    }
}
