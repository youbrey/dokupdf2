package com.example.core.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service for Gemini AI document features: OCR, Translate, Letter Generation, Spell Check, Audio/Dictation
 */
class GeminiAiService {

    private fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Optical Character Recognition via Gemini Vision
     */
    suspend fun recognizeTextFromImage(bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API Key Gemini belum dikonfigurasi"))
        }

        try {
            val base64Image = bitmapToBase64(bitmap)
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Ekstrak dan transkripsikan seluruh teks yang ada pada gambar dokumen ini dengan sangat akurat dan terstruktur tanpa menambah kata pengantar.")
                            })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
            }

            val response = executePost(endpoint, jsonBody.toString())
            val text = parseGeminiText(response)
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * AI Auto Generate Letter (Surat resmi, lamaran, izin, perjanjian, dll)
     */
    suspend fun generateLetter(
        letterType: String,
        senderName: String,
        recipientName: String,
        purpose: String,
        additionalDetails: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.success(generateOfflineLetterTemplate(letterType, senderName, recipientName, purpose, additionalDetails))
        }

        try {
            val prompt = """
                Buatkan surat formal profesional dalam Bahasa Indonesia:
                - Jenis Surat: $letterType
                - Nama Pengirim: $senderName
                - Nama Penerima: $recipientName
                - Tujuan/Perihal: $purpose
                - Rincian Tambahan: $additionalDetails
                
                Tuliskan surat lengkap dengan kop/kepala tanggal, salam pembuka, paragraf isi terstruktur, salam penutup, dan tempat tanda tangan.
            """.trimIndent()

            val text = callTextModel(apiKey, prompt)
            Result.success(text)
        } catch (e: Exception) {
            Result.success(generateOfflineLetterTemplate(letterType, senderName, recipientName, purpose, additionalDetails))
        }
    }

    /**
     * Document Translation
     */
    suspend fun translateText(
        text: String,
        targetLanguage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Perlu koneksi internet dan API Key untuk penerjemahan AI"))
        }

        try {
            val prompt = "Terjemahkan teks dokumen berikut ini ke bahasa $targetLanguage dengan tetap mempertahankan format dan gaya formal:\n\n$text"
            val translated = callTextModel(apiKey, prompt)
            Result.success(translated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Spell & Grammar Checker
     */
    suspend fun checkSpellingAndGrammar(
        documentText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Pemeriksa ejaan AI memerlukan API Key"))
        }

        try {
            val prompt = """
                Periksa ejaan, tanda baca, dan tata bahasa teks berikut.
                Berikan daftar koreksi yang ditemukan beserta versi teks yang sudah diperbaiki secara rapi:
                
                $documentText
            """.trimIndent()

            val result = callTextModel(apiKey, prompt)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Document Summary
     */
    suspend fun summarizeDocument(text: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Ringkasan dokumen memerlukan API Key"))
        }
        try {
            val prompt = "Buat ringkasan poin-poin penting (executive summary) dari dokumen berikut:\n\n$text"
            val summary = callTextModel(apiKey, prompt)
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun callTextModel(apiKey: String, prompt: String): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }
        val response = executePost(endpoint, jsonBody.toString())
        return parseGeminiText(response)
    }

    private fun executePost(endpoint: String, body: String): String {
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 30000

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (code !in 200..299) {
            throw Exception("Gemini API Error ($code): $response")
        }

        return response
    }

    private fun parseGeminiText(jsonResponse: String): String {
        val root = JSONObject(jsonResponse)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            sb.append(part.optString("text", ""))
        }
        return sb.toString()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Compress to JPEG with reasonable dimensions
        var bmp = bitmap
        if (bitmap.width > 1600 || bitmap.height > 1600) {
            val scale = 1600f / Math.max(bitmap.width, bitmap.height)
            bmp = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        }
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun generateOfflineLetterTemplate(
        letterType: String,
        senderName: String,
        recipientName: String,
        purpose: String,
        details: String
    ): String {
        val date = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale("id")).format(java.util.Date())
        return """
            SURAT ${letterType.uppercase()}
            
            Tanggal: $date
            Kepada Yth.
            $recipientName
            Di Tempat
            
            Dengan hormat,
            
            Sehubungan dengan perihal $purpose, saya yang bertanda tangan di bawah ini:
            Nama: $senderName
            
            Bermaksud untuk menyampaikan bahwa $details.
            
            Demikian surat ini saya sampaikan, atas perhatian dan kerjasamanya saya ucapkan terima kasih.
            
            
            Hormat saya,
            
            
            
            ($senderName)
        """.trimIndent()
    }
}
