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
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles PDF encryption (Lock) and decryption (Unlock)
 * Production-grade AES-CBC-256 with per-file cryptographically secure IV
 */
class PdfSecurity(private val context: Context) {

    private val AES_CBC_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private val AES_ECB_TRANSFORMATION = "AES/ECB/PKCS5Padding"
    private val HEADER_MAGIC_V2 = "DOKUPDF_ENCRYPTED_V2:"
    private val HEADER_MAGIC_V1 = "DOKUPDF_ENCRYPTED_V1:"
    private val IV_SIZE = 16

    /**
     * Locks a PDF file with AES-256-CBC encryption and unique random IV
     */
    suspend fun lockPdf(
        sourcePdf: File,
        outputPdf: File,
        password: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (password.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Kata sandi tidak boleh kosong"))
            }

            val key = generateKey(password)
            val ivBytes = ByteArray(IV_SIZE).apply {
                SecureRandom().nextBytes(this)
            }
            val ivSpec = IvParameterSpec(ivBytes)

            val cipher = Cipher.getInstance(AES_CBC_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)

            val rawBytes = FileInputStream(sourcePdf).use { it.readBytes() }
            val encryptedBytes = cipher.doFinal(rawBytes)

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { fos ->
                // Write Header Magic V2
                fos.write(HEADER_MAGIC_V2.toByteArray(Charsets.UTF_8))
                // Write IV (16 bytes)
                fos.write(ivBytes)
                // Write Encrypted Payload
                fos.write(encryptedBytes)
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unlocks a password-protected PDF (Supports V2 CBC with IV and V1 ECB legacy)
     */
    suspend fun unlockPdf(
        sourcePdf: File,
        outputPdf: File,
        password: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (password.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Kata sandi tidak boleh kosong"))
            }

            val bytes = FileInputStream(sourcePdf).use { it.readBytes() }
            val headerV2Bytes = HEADER_MAGIC_V2.toByteArray(Charsets.UTF_8)
            val headerV1Bytes = HEADER_MAGIC_V1.toByteArray(Charsets.UTF_8)

            val key = generateKey(password)

            val decryptedData: ByteArray = if (bytes.size >= headerV2Bytes.size && bytes.take(headerV2Bytes.size).toByteArray().contentEquals(headerV2Bytes)) {
                // V2: Header (21 bytes) + IV (16 bytes) + Ciphertext
                val offset = headerV2Bytes.size
                val ivBytes = bytes.copyOfRange(offset, offset + IV_SIZE)
                val cipherBytes = bytes.copyOfRange(offset + IV_SIZE, bytes.size)

                val cipher = Cipher.getInstance(AES_CBC_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ivBytes))
                cipher.doFinal(cipherBytes)
            } else if (bytes.size >= headerV1Bytes.size && bytes.take(headerV1Bytes.size).toByteArray().contentEquals(headerV1Bytes)) {
                // V1 Legacy: Header + Ciphertext
                val offset = headerV1Bytes.size
                val cipherBytes = bytes.copyOfRange(offset, bytes.size)

                val cipher = Cipher.getInstance(AES_ECB_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key)
                cipher.doFinal(cipherBytes)
            } else {
                // Direct Raw Decryption
                val cipher = Cipher.getInstance(AES_CBC_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key)
                cipher.doFinal(bytes)
            }

            outputPdf.parentFile?.mkdirs()
            FileOutputStream(outputPdf).use { fos ->
                fos.write(decryptedData)
            }
            Result.success(outputPdf)
        } catch (e: Exception) {
            Result.failure(Exception("Kata sandi salah atau berkas tidak dapat didekripsi: ${e.message}"))
        }
    }

    suspend fun isPdfLocked(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            FileInputStream(file).use { fis ->
                val maxHeaderLen = maxOf(HEADER_MAGIC_V1.length, HEADER_MAGIC_V2.length)
                val buffer = ByteArray(maxHeaderLen)
                val read = fis.read(buffer)
                if (read > 0) {
                    val headerStr = String(buffer, 0, read, Charsets.UTF_8)
                    headerStr.startsWith(HEADER_MAGIC_V2) || headerStr.startsWith(HEADER_MAGIC_V1)
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun generateKey(password: String): SecretKeySpec {
        val sha = MessageDigest.getInstance("SHA-256")
        val keyBytes = sha.digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES") // 256-bit AES
    }
}
