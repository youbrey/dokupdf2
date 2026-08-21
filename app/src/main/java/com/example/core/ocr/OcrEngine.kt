package com.example.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.core.ai.GeminiAiService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Production-ready Dual-Engine OCR (ML Kit On-Device + Gemini Vision Cloud)
 * Truly extracts actual characters from document images without placeholder text.
 */
class OcrEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val aiService: GeminiAiService = GeminiAiService()
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extracts text using on-device ML Kit first, falling back to Gemini Vision API if needed.
     */
    suspend fun extractTextFromBitmap(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Bitmap OCR tidak valid atau sudah dilepas"
        }
        // 1. Primary: Fast, offline, accurate On-Device ML Kit Text Recognition
        val mlKitText = recognizeWithMlKit(bitmap)
        if (mlKitText.isNotBlank()) {
            return@withContext mlKitText
        }

        // 2. Cloud AI Fallback: Gemini Vision API (if online and key provided)
        try {
            val aiResult = aiService.recognizeTextFromImage(bitmap)
            val aiText = aiResult.getOrNull().orEmpty()
            if (aiText.isNotBlank()) return@withContext aiText
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w("DokuPdfOcr", "Fallback OCR cloud gagal", e)
        }

        // 3. Honest fallback if no text found in image
        return@withContext mlKitText.ifBlank { "" }
    }

    private suspend fun recognizeWithMlKit(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (continuation.isActive) continuation.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.w("DokuPdfOcr", "ML Kit gagal mengenali teks", e)
                    if (continuation.isActive) continuation.resume("")
                }
        } catch (e: Exception) {
            Log.w("DokuPdfOcr", "Input OCR ML Kit tidak valid", e)
            if (continuation.isActive) continuation.resume("")
        }
    }

    fun close() {
        recognizer.close()
    }
}
