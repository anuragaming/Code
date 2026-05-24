package com.example.api

import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun generateContent(
        prompt: String,
        customUrl: String?,
        customKey: String?,
        customModel: String?,
        systemInstruction: String? = null
    ): String = withContext(Dispatchers.IO) {
        // Fallbacks
        val finalApiKey = if (!customKey.isNullOrEmpty()) customKey else BuildConfig.GEMINI_API_KEY
        val finalModel = if (!customModel.isNullOrEmpty()) customModel else "gemini-3.5-flash"
        val isCustomApi = !customUrl.isNullOrEmpty()

        val url: String
        val jsonPayload: String

        if (!isCustomApi) {
            // Standard Gemini API
            url = "https://generativelanguage.googleapis.com/v1beta/models/$finalModel:generateContent?key=$finalApiKey"
            
            val payloadObj = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                }
                put("contents", contentsArray)

                if (systemInstruction != null) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemInstruction) })
                        })
                    })
                }
            }
            jsonPayload = payloadObj.toString()
        } else {
            // Custom OpenRouter/OpenAI compatible API
            url = customUrl!!
            val payloadObj = JSONObject().apply {
                put("model", finalModel)
                
                val messagesArray = JSONArray().apply {
                    if (systemInstruction != null) {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemInstruction)
                        })
                    }
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
                put("messages", messagesArray)
                put("temperature", 0.7)
            }
            jsonPayload = payloadObj.toString()
        }

        try {
            val requestBuilder = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))

            if (isCustomApi && !finalApiKey.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $finalApiKey")
            }

            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                Log.d(TAG, "Response Code: ${response.code}, Body: $bodyString")

                if (!response.isSuccessful) {
                    return@withContext "API Request Failed (Code ${response.code}): ${bodyString ?: "Empty"}"
                }

                if (bodyString.isNullOrEmpty()) {
                    return@withContext "Empty response received from server."
                }

                // Parse response
                val json = JSONObject(bodyString)
                if (!isCustomApi) {
                    // Parse Gemini structure: candidates[0].content.parts[0].text
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val content = candidate.optJSONObject("content")
                        if (content != null) {
                            val parts = content.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                return@withContext parts.getJSONObject(0).optString("text", "No text in candidates")
                            }
                        }
                    }
                    "Could not extract answer from standard Gemini response. Raw: $bodyString"
                } else {
                    // Parse OpenAI structure: choices[0].message.content
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val message = choice.optJSONObject("message")
                        if (message != null) {
                            return@withContext message.optString("content", "No content in choices")
                        }
                    }
                    "Could not extract answer from custom API response. Raw: $bodyString"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "API call threw exception: ", e)
            "Error: ${e.message ?: "Connection Timeout"}"
        }
    }
}
