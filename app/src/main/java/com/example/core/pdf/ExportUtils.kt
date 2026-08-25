package com.example.core.pdf

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * [Fitur baru] Ekspor file ke folder Download/ publik perangkat -- mis. "Simpan ke Perangkat"
 * seperti fitur "Simpan ke Galeri"/"Simpan ke perangkat" di CamScanner.
 *
 * ROOT CAUSE yang diperbaiki fitur ini: sebelumnya seluruh proyek TIDAK PERNAH memakai
 * MediaStore atau DownloadManager (dikonfirmasi lewat grep menyeluruh). PDF hasil scan/PDF
 * Tools hanya tersimpan di sandbox privat aplikasi (context.filesDir/..., context.cacheDir/...)
 * yang TIDAK bisa diakses aplikasi File Manager/Galeri lain, dan satu-satunya jalan keluar
 * adalah Intent.ACTION_SEND (share sheet) -- bukan aksi "simpan ke perangkat" mandiri.
 * Fungsi di sini menambahkan jalur kedua: menyalin file dari sandbox privat ke folder
 * Download/ publik, memakai API yang benar sesuai versi Android:
 *
 * - API 29+ (Android 10/Q ke atas): `MediaStore.Downloads` -- TIDAK butuh izin runtime sama
 *   sekali (scoped storage). Ini jalur utama untuk mayoritas perangkat saat ini.
 * - API 24-28 (di bawah scoped storage, sesuai minSdk=24 proyek ini): akses langsung ke
 *   `Environment.DIRECTORY_DOWNLOADS` via java.io.File, yang mengharuskan izin runtime
 *   `WRITE_EXTERNAL_STORAGE` diberikan pengguna lebih dulu.
 *
 * Semua file DokuPDF disimpan di dalam sub-folder "DokuPDF" di dalam Download/, supaya tidak
 * bercampur acak dengan unduhan lain dan mudah ditemukan pengguna -- meniru pola folder khusus
 * aplikasi (mis. "CamScanner", "WhatsApp") yang lazim dipakai aplikasi sejenis.
 */
object ExportUtils {

    /** Nama sub-folder di dalam Download/ tempat semua hasil DokuPDF disimpan. */
    const val EXPORT_SUBFOLDER = "DokuPDF"

    /** Izin yang WAJIB diminta secara runtime hanya di API 24-28. Di API 29+ tidak diperlukan. */
    const val LEGACY_WRITE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

    /** True jika perangkat ini butuh izin runtime WRITE_EXTERNAL_STORAGE untuk menyimpan ke Download/. */
    fun requiresLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Cek apakah izin legacy sudah diberikan. Selalu true di API 29+ (tidak relevan/tidak dibutuhkan). */
    fun hasLegacyStoragePermission(context: Context): Boolean {
        if (!requiresLegacyPermission()) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            LEGACY_WRITE_PERMISSION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    sealed class ExportResult {
        /** [displayPath] contoh: "Download/DokuPDF/hasil-scan.pdf" -- untuk ditampilkan ke pengguna. */
        data class Success(val displayPath: String, val uri: Uri?) : ExportResult()
        /** Dilempar ke caller supaya UI bisa memicu permintaan izin, BUKAN dianggap gagal permanen. */
        object PermissionRequired : ExportResult()
        data class Failure(val message: String) : ExportResult()
    }

    /**
     * Simpan satu [sourceFile] (dari sandbox privat aplikasi) ke Download/DokuPDF/ publik.
     * Aman dipanggil dari thread mana pun -- pekerjaan I/O berjalan di [Dispatchers.IO].
     */
    suspend fun exportToDownloads(
        context: Context,
        sourceFile: File,
        subFolder: String = EXPORT_SUBFOLDER
    ): ExportResult = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            return@withContext ExportResult.Failure("Berkas sumber tidak ditemukan atau kosong: ${sourceFile.name}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, sourceFile, subFolder)
        } else {
            if (!hasLegacyStoragePermission(context)) {
                return@withContext ExportResult.PermissionRequired
            }
            exportViaLegacyFile(context, sourceFile, subFolder)
        }
    }

    /** Varian multi-file -- dipakai PdfToolsScreen.kt untuk hasil yang terdiri dari beberapa berkas sekaligus. */
    suspend fun exportAllToDownloads(
        context: Context,
        sourceFiles: List<File>,
        subFolder: String = EXPORT_SUBFOLDER
    ): List<Pair<File, ExportResult>> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyStoragePermission(context)) {
            // Cek sekali di depan supaya UI cukup memicu satu permintaan izin, bukan per-file.
            return@withContext sourceFiles.map { it to ExportResult.PermissionRequired }
        }
        sourceFiles.map { file -> file to exportToDownloads(context, file, subFolder) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(context: Context, sourceFile: File, subFolder: String): ExportResult {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$subFolder"
        val extension = sourceFile.extension.ifBlank { "bin" }
        val baseName = sourceFile.nameWithoutExtension.ifBlank { "DokuPDF" }
        val displayName = uniqueMediaStoreDisplayName(context, baseName, extension, relativePath)
        val mimeType = mimeTypeFor(sourceFile)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ExportResult.Failure("Sistem menolak membuat entri di Download/ (MediaStore.insert gagal)")

        return try {
            val opened = resolver.openOutputStream(itemUri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
                true
            } ?: false

            if (!opened) {
                resolver.delete(itemUri, null, null)
                return ExportResult.Failure("Gagal membuka aliran tulis ke Download/ untuk $displayName")
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)

            ExportResult.Success("${Environment.DIRECTORY_DOWNLOADS}/$subFolder/$displayName", itemUri)
        } catch (t: Throwable) {
            // Bersihkan entri MediaStore yang setengah jadi supaya tidak meninggalkan baris
            // "pending" yatim yang tidak pernah selesai ditulis.
            runCatching { resolver.delete(itemUri, null, null) }
            ExportResult.Failure("Gagal menyalin berkas ke Download/: ${t.message}")
        }
    }

    private fun exportViaLegacyFile(context: Context, sourceFile: File, subFolder: String): ExportResult {
        return try {
            @Suppress("DEPRECATION")
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsRoot, subFolder)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return ExportResult.Failure("Tidak bisa membuat folder Download/$subFolder")
            }

            val targetFile = uniqueLegacyFile(targetDir, sourceFile.name)
            sourceFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }

            // Tanpa ini, file baru sering tidak langsung muncul di aplikasi Files/Galeri lain
            // sampai perangkat di-restart -- MediaScannerConnection memaksa index media segera.
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeTypeFor(targetFile)),
                null
            )

            ExportResult.Success("${Environment.DIRECTORY_DOWNLOADS}/$subFolder/${targetFile.name}", Uri.fromFile(targetFile))
        } catch (t: Throwable) {
            ExportResult.Failure("Gagal menyalin berkas ke Download/: ${t.message}")
        }
    }

    /**
     * Cari nama tampilan yang belum dipakai di RELATIVE_PATH tujuan (query MediaStore
     * sungguhan, bukan asumsi) -- supaya "hasil.pdf" yang diekspor dua kali tidak saling
     * menimpa, mengikuti pola "hasil (1).pdf", "hasil (2).pdf", dst.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun uniqueMediaStoreDisplayName(
        context: Context,
        baseName: String,
        extension: String,
        relativePath: String
    ): String {
        val existingNames = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf("$relativePath/"),
                null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) existingNames += cursor.getString(nameColumn)
            }
        }

        var candidate = "$baseName.$extension"
        var suffix = 1
        while (candidate in existingNames && suffix <= 999) {
            candidate = "$baseName ($suffix).$extension"
            suffix++
        }
        return candidate
    }

    /** Padanan [uniqueMediaStoreDisplayName] untuk jalur API 24-28 -- cek langsung ke filesystem. */
    private fun uniqueLegacyFile(directory: File, desiredName: String): File {
        val dotIndex = desiredName.lastIndexOf('.')
        val base = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val ext = if (dotIndex > 0) desiredName.substring(dotIndex) else ""

        var candidate = File(directory, desiredName)
        var suffix = 1
        while (candidate.exists() && suffix <= 999) {
            candidate = File(directory, "$base ($suffix)$ext")
            suffix++
        }
        return candidate
    }
}

/**
 * Tentukan MIME type dari ekstensi berkas. Dipindahkan ke sini dari PdfToolsScreen.kt
 * ([Refactor]) supaya satu sumber kebenaran dipakai bersama oleh ExportUtils, PdfToolsScreen,
 * dan ScannerScreen -- sebelumnya logikanya cuma ada sebagai `private fun` di PdfToolsScreen.kt
 * sehingga ExportUtils.kt (di package berbeda) tidak bisa memakainya.
 */
internal fun mimeTypeFor(file: File): String = when (file.extension.lowercase(java.util.Locale.ROOT)) {
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "csv" -> "text/csv"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "dokupdf" -> "application/octet-stream"
    else -> "application/octet-stream"
}
