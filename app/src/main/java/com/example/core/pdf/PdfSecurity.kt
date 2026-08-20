package com.example.core.pdf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
            runCatching {
                require(password.isNotBlank()) { "Kata sandi tidak boleh kosong" }
                require(sourcePdf.isFile && sourcePdf.length() > 0L) { "Berkas sumber tidak valid" }
                val rawBytes = FileInputStream(sourcePdf).use { it.readBytes() }
                require(isPdf(rawBytes)) { "Berkas sumber bukan PDF yang valid" }

                val random = SecureRandom()
                val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
                val iv = ByteArray(GCM_IV_SIZE).also(random::nextBytes)
                val header = HEADER_MAGIC_V3.toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, deriveV3Key(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(header)
                val encrypted = cipher.doFinal(rawBytes)

                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { output ->
                    output.write(header)
                    output.write(salt)
                    output.write(iv)
                    output.write(encrypted)
                }
                outputFile
            }
        }

    suspend fun unlockPdf(sourceFile: File, outputPdf: File, password: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(password.isNotBlank()) { "Kata sandi tidak boleh kosong" }
                require(sourceFile.isFile && sourceFile.length() > 0L) { "Berkas sumber tidak valid" }
                val bytes = FileInputStream(sourceFile).use { it.readBytes() }
                val decrypted = decryptContainer(bytes, password)
                require(isPdf(decrypted)) { "Kata sandi salah atau isi hasil dekripsi bukan PDF" }

                outputPdf.parentFile?.mkdirs()
                FileOutputStream(outputPdf).use { it.write(decrypted) }
                outputPdf
            }.recoverCatching { error ->
                throw IllegalArgumentException(
                    "Kata sandi salah, berkas berubah, atau format enkripsi tidak didukung",
                    error
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

    private fun decryptContainer(bytes: ByteArray, password: String): ByteArray {
        val headerV3 = HEADER_MAGIC_V3.toByteArray(Charsets.UTF_8)
        val headerV2 = HEADER_MAGIC_V2.toByteArray(Charsets.UTF_8)
        val headerV1 = HEADER_MAGIC_V1.toByteArray(Charsets.UTF_8)

        return when {
            bytes.startsWith(headerV3) -> {
                val metadataEnd = headerV3.size + SALT_SIZE + GCM_IV_SIZE
                require(bytes.size > metadataEnd + 16) { "Kontainer V3 terpotong" }
                val salt = bytes.copyOfRange(headerV3.size, headerV3.size + SALT_SIZE)
                val iv = bytes.copyOfRange(headerV3.size + SALT_SIZE, metadataEnd)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, deriveV3Key(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(headerV3)
                cipher.doFinal(bytes, metadataEnd, bytes.size - metadataEnd)
            }
            bytes.startsWith(headerV2) -> {
                val payloadOffset = headerV2.size + CBC_IV_SIZE
                require(bytes.size > payloadOffset) { "Kontainer V2 terpotong" }
                val iv = bytes.copyOfRange(headerV2.size, payloadOffset)
                val cipher = Cipher.getInstance(AES_CBC_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, deriveLegacyKey(password), IvParameterSpec(iv))
                cipher.doFinal(bytes, payloadOffset, bytes.size - payloadOffset)
            }
            bytes.startsWith(headerV1) -> {
                require(bytes.size > headerV1.size) { "Kontainer V1 terpotong" }
                val cipher = Cipher.getInstance(AES_ECB_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, deriveLegacyKey(password))
                cipher.doFinal(bytes, headerV1.size, bytes.size - headerV1.size)
            }
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

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[4] == '-'.code.toByte()
}
