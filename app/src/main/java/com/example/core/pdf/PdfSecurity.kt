package com.example.core.pdf

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated DokuPDF container encryption.
 *
 * The encrypted output is deliberately a `.dokupdf` container, not a PDF: encrypted bytes are
 * not readable by normal PDF viewers. V3 uses PBKDF2-HMAC-SHA256 and AES-256-GCM, which detects
 * wrong passwords and tampering. V1/V2 files remain decryptable for backwards compatibility.
 */
class PdfSecurity(@Suppress("UNUSED_PARAMETER") context: Context) {

    private companion object {
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_CBC_TRANSFORMATION = "AES/CBC/PKCS5Padding"
        const val AES_ECB_TRANSFORMATION = "AES/ECB/PKCS5Padding"
        const val HEADER_MAGIC_V3 = "DOKUPDF_ENCRYPTED_V3:"
        const val HEADER_MAGIC_V2 = "DOKUPDF_ENCRYPTED_V2:"
        const val HEADER_MAGIC_V1 = "DOKUPDF_ENCRYPTED_V1:"
        const val SALT_SIZE = 16
        const val GCM_IV_SIZE = 12
        const val CBC_IV_SIZE = 16
        const val PBKDF2_ITERATIONS = 150_000
        const val GCM_TAG_BITS = 128
    }

    suspend fun lockPdf(sourcePdf: File, outputFile: File, password: String): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                require(password.length >= 8) { "Kata sandi baru harus terdiri dari minimal 8 karakter" }
                require(password.length <= 256) { "Kata sandi maksimal 256 karakter" }
                PdfFileUtils.requirePdf(sourcePdf)
                PdfFileUtils.requireDistinct(sourcePdf, outputFile)

                val random = SecureRandom()
                val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
                val iv = ByteArray(GCM_IV_SIZE).also(random::nextBytes)
                val header = HEADER_MAGIC_V3.toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, deriveV3Key(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(header)

                PdfFileUtils.writeAtomically(outputFile, minimumBytes = (header.size + SALT_SIZE + GCM_IV_SIZE + 16).toLong()) { temporary ->
                    FileInputStream(sourcePdf).use { input ->
                        FileOutputStream(temporary).use { output ->
                            output.write(header)
                            output.write(salt)
                            output.write(iv)
                            transformStream(input, output, cipher)
                        }
                    }
                }
                Result.success(outputFile)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (oom: OutOfMemoryError) {
                Result.failure(IllegalStateException("Memori tidak cukup untuk mengenkripsi dokumen", oom))
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    suspend fun unlockPdf(sourceFile: File, outputPdf: File, password: String): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                require(password.isNotBlank()) { "Kata sandi tidak boleh kosong" }
                require(password.length <= 256) { "Kata sandi maksimal 256 karakter" }
                PdfFileUtils.requireReadableFile(sourceFile, "Kontainer DokuPDF", PdfFileUtils.MAX_PDF_INPUT_BYTES)
                PdfFileUtils.requireDistinct(sourceFile, outputPdf)
                val header = detectHeader(sourceFile)

                PdfFileUtils.writeAtomically(outputPdf, minimumBytes = 5L) { temporary ->
                    BufferedInputStream(FileInputStream(sourceFile)).use { input ->
                        input.skipExactly(header.size.toLong())
                        val cipher = when {
                            header.contentEquals(HEADER_MAGIC_V3.toByteArray(Charsets.UTF_8)) -> {
                                val salt = input.readExactly(SALT_SIZE)
                                val iv = input.readExactly(GCM_IV_SIZE)
                                Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                                    init(Cipher.DECRYPT_MODE, deriveV3Key(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
                                    updateAAD(header)
                                }
                            }
                            header.contentEquals(HEADER_MAGIC_V2.toByteArray(Charsets.UTF_8)) -> {
                                val iv = input.readExactly(CBC_IV_SIZE)
                                Cipher.getInstance(AES_CBC_TRANSFORMATION).apply {
                                    init(Cipher.DECRYPT_MODE, deriveLegacyKey(password), IvParameterSpec(iv))
                                }
                            }
                            else -> Cipher.getInstance(AES_ECB_TRANSFORMATION).apply {
                                init(Cipher.DECRYPT_MODE, deriveLegacyKey(password))
                            }
                        }
                        FileOutputStream(temporary).use { output -> transformStream(input, output, cipher) }
                    }
                    PdfFileUtils.requirePdf(temporary, "Hasil dekripsi")
                }
                Result.success(outputPdf)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (oom: OutOfMemoryError) {
                Result.failure(IllegalStateException("Memori tidak cukup untuk mendekripsi dokumen", oom))
            } catch (error: Exception) {
                Result.failure(
                    IllegalArgumentException(
                        "Kata sandi salah, berkas berubah, atau format enkripsi tidak didukung",
                        error
                    )
                )
            }
        }

    suspend fun isPdfLocked(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            FileInputStream(file).use { input ->
                val maxHeaderLength = maxOf(HEADER_MAGIC_V1.length, HEADER_MAGIC_V2.length, HEADER_MAGIC_V3.length)
                val buffer = ByteArray(maxHeaderLength)
                val read = input.read(buffer)
                if (read <= 0) return@use false
                val header = String(buffer, 0, read, Charsets.UTF_8)
                header.startsWith(HEADER_MAGIC_V3) ||
                    header.startsWith(HEADER_MAGIC_V2) ||
                    header.startsWith(HEADER_MAGIC_V1)
            }
        }.getOrDefault(false)
    }

    private fun detectHeader(file: File): ByteArray {
        val headerV3 = HEADER_MAGIC_V3.toByteArray(Charsets.UTF_8)
        val headerV2 = HEADER_MAGIC_V2.toByteArray(Charsets.UTF_8)
        val headerV1 = HEADER_MAGIC_V1.toByteArray(Charsets.UTF_8)

        val maximumLength = maxOf(headerV1.size, headerV2.size, headerV3.size)
        val prefix = FileInputStream(file).use { input ->
            ByteArray(maximumLength).also { buffer ->
                val read = input.read(buffer)
                require(read > 0) { "Kontainer DokuPDF kosong" }
            }
        }
        return when {
            prefix.hasPrefix(headerV3) -> headerV3
            prefix.hasPrefix(headerV2) -> headerV2
            prefix.hasPrefix(headerV1) -> headerV1
            else -> throw IllegalArgumentException("Berkas bukan kontainer DokuPDF terenkripsi")
        }
    }

    private fun deriveV3Key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun deriveLegacyKey(password: String): SecretKeySpec {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    private fun transformStream(input: java.io.InputStream, output: java.io.OutputStream, cipher: Cipher) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            val transformed = cipher.update(buffer, 0, read)
            if (transformed != null && transformed.isNotEmpty()) output.write(transformed)
        }
        val finalBytes = cipher.doFinal()
        if (finalBytes.isNotEmpty()) output.write(finalBytes)
    }

    private fun java.io.InputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(result, offset, length - offset)
            require(read > 0) { "Kontainer DokuPDF terpotong" }
            offset += read
        }
        return result
    }

    private fun java.io.InputStream.skipExactly(length: Long) {
        var remaining = length
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                require(read() >= 0) { "Kontainer DokuPDF terpotong" }
                remaining--
            }
        }
    }
}
