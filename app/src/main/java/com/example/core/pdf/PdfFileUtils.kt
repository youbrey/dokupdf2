package com.example.core.pdf

import java.io.File
import java.io.FileInputStream
import java.util.Locale

internal object PdfFileUtils {
    const val MAX_PDF_INPUT_BYTES: Long = 250L * 1024L * 1024L
    const val MAX_OFFICE_INPUT_BYTES: Long = 50L * 1024L * 1024L

    private val invalidFileNameCharacters = Regex("[^a-zA-Z0-9._ -]")

    fun requireReadableFile(
        file: File,
        label: String = "Berkas",
        maximumBytes: Long = MAX_PDF_INPUT_BYTES
    ) {
        require(file.isFile && file.canRead() && file.length() > 0L) {
            "$label tidak ditemukan, kosong, atau tidak dapat dibaca"
        }
        require(file.length() <= maximumBytes) {
            "$label terlalu besar (${formatBytes(file.length())}); batas maksimum ${formatBytes(maximumBytes)}"
        }
    }

    fun requirePdf(file: File, label: String = "Berkas PDF") {
        requireReadableFile(file, label)
        val prefix = ByteArray(minOf(1024L, file.length()).toInt())
        val read = FileInputStream(file).use { it.read(prefix) }
        require(read >= 5 && indexOfPdfHeader(prefix, read) >= 0) {
            "$label tidak memiliki header PDF yang valid"
        }
    }

    fun requireDistinct(input: File, output: File) {
        require(input.canonicalFile != output.canonicalFile) {
            "Berkas keluaran tidak boleh menimpa berkas sumber"
        }
    }

    suspend fun <T> writeAtomically(
        output: File,
        minimumBytes: Long = 1L,
        block: suspend (temporaryFile: File) -> T
    ): T {
        require(minimumBytes > 0L) { "Ukuran minimum keluaran harus lebih dari nol" }
        val parent = output.parentFile ?: throw IllegalArgumentException("Direktori keluaran tidak valid")
        require(parent.exists() || parent.mkdirs()) { "Direktori keluaran tidak dapat dibuat" }
        require(parent.isDirectory && parent.canWrite()) { "Direktori keluaran tidak dapat ditulis" }

        val suffix = output.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".tmp"
        val temporaryPrefix = ".${output.nameWithoutExtension.ifBlank { "out" }}_"
        val temporary = File.createTempFile(temporaryPrefix, suffix, parent)
        var backup: File? = null
        var preserveBackupForRecovery = false
        var publishingNewOutput = false
        try {
            val result = block(temporary)
            require(temporary.isFile && temporary.length() >= minimumBytes) {
                "Proses tidak menghasilkan berkas keluaran yang valid"
            }

            if (output.exists()) {
                val backupCandidate = File.createTempFile("${temporaryPrefix}backup_", suffix, parent)
                try {
                    output.inputStream().use { input ->
                        java.io.FileOutputStream(backupCandidate).use { destination ->
                            input.copyTo(destination)
                            destination.fd.sync()
                        }
                    }
                    require(backupCandidate.length() == output.length()) {
                        "Pencadangan berkas keluaran lama tidak lengkap"
                    }
                    // Publish the backup only after a complete copy. A failed copy must
                    // leave the still-intact original output untouched during rollback.
                    backup = backupCandidate
                } catch (error: Throwable) {
                    backupCandidate.delete()
                    throw error
                }
                require(output.delete()) { "Berkas keluaran lama tidak dapat diganti" }
            }
            publishingNewOutput = backup == null
            if (!temporary.renameTo(output)) {
                temporary.inputStream().use { input ->
                    java.io.FileOutputStream(output).use { destination ->
                        input.copyTo(destination)
                        destination.fd.sync()
                    }
                }
                require(output.length() == temporary.length()) { "Penyalinan hasil keluaran tidak lengkap" }
                temporary.delete()
            }
            backup?.delete()
            return result
        } catch (error: Throwable) {
            temporary.delete()
            backup?.let { savedOutput ->
                try {
                    if (output.exists()) {
                        require(output.delete()) { "Keluaran parsial tidak dapat dihapus saat rollback" }
                    }
                    if (!savedOutput.renameTo(output)) {
                        savedOutput.inputStream().use { input ->
                            java.io.FileOutputStream(output).use { destination ->
                                input.copyTo(destination)
                                destination.fd.sync()
                            }
                        }
                        require(output.length() == savedOutput.length()) {
                            "Pemulihan berkas keluaran lama tidak lengkap"
                        }
                    }
                } catch (restoreError: Throwable) {
                    // Keep the complete backup on disk if rollback itself fails. Deleting it in
                    // finally would turn a recoverable storage error into permanent data loss.
                    preserveBackupForRecovery = true
                    error.addSuppressed(
                        IllegalStateException(
                            "Rollback gagal; cadangan keluaran dipertahankan di ${savedOutput.absolutePath}",
                            restoreError
                        )
                    )
                }
            }
            if (backup == null && publishingNewOutput && output.exists() && !output.delete()) {
                error.addSuppressed(
                    IllegalStateException(
                        "Keluaran parsial tidak dapat dihapus; periksa ${output.absolutePath}"
                    )
                )
            }
            throw error
        } finally {
            if (!preserveBackupForRecovery) backup?.delete()
        }
    }

    fun sanitizeFileName(rawName: String, fallback: String): String {
        val leafName = File(rawName).name
        val sanitized = leafName.replace(invalidFileNameCharacters, "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(120)
        return sanitized.ifBlank { fallback }
    }

    fun uniqueFile(directory: File, baseName: String, extension: String): File {
        require(directory.exists() || directory.mkdirs()) { "Direktori keluaran tidak dapat dibuat" }
        val safeBase = sanitizeFileName(baseName, "dokumen").ifBlank { "dokumen" }
        val safeExtension = extension.trimStart('.').lowercase(Locale.US)
        require(safeExtension.matches(Regex("[a-z0-9]{1,12}"))) { "Ekstensi keluaran tidak valid" }
        var candidate = File(directory, "$safeBase.$safeExtension")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(directory, "${safeBase}_$counter.$safeExtension")
            counter++
        }
        return candidate
    }

    fun uniqueDirectory(parent: File, baseName: String): File {
        require(parent.exists() || parent.mkdirs()) { "Direktori keluaran tidak dapat dibuat" }
        val safeBase = sanitizeFileName(baseName, "hasil").ifBlank { "hasil" }
        var candidate = File(parent, safeBase)
        var counter = 2
        while (candidate.exists()) {
            candidate = File(parent, "${safeBase}_$counter")
            counter++
        }
        require(candidate.mkdir()) { "Direktori hasil tidak dapat dibuat" }
        return candidate
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun indexOfPdfHeader(buffer: ByteArray, length: Int): Int {
        val header = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte())
        for (start in 0..(length - header.size)) {
            var matches = true
            for (offset in header.indices) {
                if (buffer[start + offset] != header[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }
}
