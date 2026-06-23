package com.example.data

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream

// --- Moshi request/response models ---

data class InlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

data class Part(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: InlineData? = null
)

data class Content(
    @Json(name = "parts") val parts: List<Part>
)

data class GenerationConfig(
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "topP") val topP: Float? = null,
    @Json(name = "topK") val topK: Int? = null,
    @Json(name = "responseMimeType") val responseMimeType: String? = null
)

data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null
)

data class PartResponse(
    @Json(name = "text") val text: String? = null
)

data class ContentResponse(
    @Json(name = "parts") val parts: List<PartResponse>? = null
)

data class Candidate(
    @Json(name = "content") val content: ContentResponse? = null
)

data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>? = null
)

// --- Retrofit Api declarations ---

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

class GeminiService {

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Resize slightly to reduce token cost and payload size (faster OCR)
        val maxDim = 1024
        val ratio = width.toFloat() / height.toFloat()
        val (newW, newH) = if (width > height) {
            val w = minOf(width, maxDim)
            w to (w / ratio).toInt()
        } else {
            val h = minOf(height, maxDim)
            (h * ratio).toInt() to h
        }
        val resized = Bitmap.createScaledBitmap(this, newW, newH, true)
        resized.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Extracts text from a Bitmap image using Gemini OCR.
     */
    suspend fun performOcr(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Error: Gemini API Key is missing. Please configure it in the AI Studio Secrets panel."
        }
        
        val base64Image = bitmap.toBase64()
        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = "You are an expert high-fidelity document OCR system. Extract and return ALL text visible in this scanned document page accurately. Preserve block layouts, structures, tables and text formatting. Do not include any chat commentary or conversational filler. Output only raw extracted text."),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfig(temperature = 0.1f)
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: "No text found on page."
        } catch (e: Exception) {
            "OCR Error: ${e.localizedMessage ?: e.message}"
        }
    }

    /**
     * Summarizes the text content.
     */
    suspend fun summarizeDocument(text: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Error: API Key is missing."
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = "Synthesize a professional, concise executive summary of the following document text. Highlight key tables, dates, values, and action items in bullet points:\n\n$text")
                    )
                )
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: "Failed to generate summary."
        } catch (e: Exception) {
            "Summary Error: ${e.localizedMessage ?: e.message}"
        }
    }

    /**
     * Translates the given text into the target language.
     */
    suspend fun translateText(text: String, targetLanguage: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Error: API Key is missing."
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = "Translate the following scanned document text into $targetLanguage. Do not add any explanation or conversation. Return only the clean translated text:\n\n$text")
                    )
                )
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: "Translation failed."
        } catch (e: Exception) {
            "Translation Error: ${e.localizedMessage ?: e.message}"
        }
    }

    /**
     * Intelligently renames the file based on the extracted text contents.
     */
    suspend fun suggestSmartName(text: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "DocScan_${System.currentTimeMillis()}"
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = "Given this extracted text from a document, suggest a highly concise, professional, snake_case or PascalCase naming scheme for the document file. Extract vendor names, document type (e.g. Invoice, Receipt, Note), or dates if found. Output ONLY the file name without any extension, quotes, or conversational phrases. Max 4 words. Example output: 'Google_Invoice_June2026':\n\n$text")
                    )
                )
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val name = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
            if (name.isNullOrBlank() || name.startsWith("Error") || name.length > 50) {
                "DocScan_${System.currentTimeMillis()}"
            } else {
                name.replace(Regex("[^a-zA-Z0-9_-]"), "")
            }
        } catch (e: Exception) {
            "DocScan_${System.currentTimeMillis()}"
        }
    }
}
