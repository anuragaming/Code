package com.example.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object GeminiClient {

    /**
     * Validates that the URL is non-empty and uses `https://` (or localhost `http://` for local dev).
     */
    fun validateSecureUrl(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Base URL cannot be empty."))
        }
        val lower = trimmed.lowercase()
        val isLocal = lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1") || lower.startsWith("http://10.0.2.2")
        if (!lower.startsWith("https://") && !isLocal) {
            return Result.failure(
                IllegalArgumentException("Insecure or invalid URL '$trimmed'. Please use an https:// endpoint (e.g. https://motapis.com/v1).")
            )
        }
        return Result.success(trimmed.trimEnd('/'))
    }

    /**
     * Normalizes base URL into the exact chat/messages/generateContent endpoint without duplicating `/v1/v1`.
     *
     * Examples for `OPENAI_COMPATIBLE`:
     * - `https://motapis.com` -> `https://motapis.com/v1/chat/completions`
     * - `https://motapis.com/v1` -> `https://motapis.com/v1/chat/completions`
     * - `https://motapis.com/v1/chat/completions` -> `https://motapis.com/v1/chat/completions`
     */
    fun buildChatEndpointUrl(config: AiProviderConfig): String {
        val cleanBase = config.baseUrl.trim().trimEnd('/')
        val model = config.modelId.trim().ifEmpty { config.protocol.defaultModelId }

        return when (config.protocol) {
            ApiProtocol.OPENAI_COMPATIBLE -> {
                when {
                    cleanBase.endsWith("/v1/chat/completions", ignoreCase = true) -> cleanBase
                    cleanBase.endsWith("/chat/completions", ignoreCase = true) -> cleanBase
                    cleanBase.endsWith("/v1", ignoreCase = true) -> "$cleanBase/chat/completions"
                    else -> "$cleanBase/v1/chat/completions"
                }
            }

            ApiProtocol.ANTHROPIC_MESSAGES -> {
                when {
                    cleanBase.endsWith("/v1/messages", ignoreCase = true) -> cleanBase
                    cleanBase.endsWith("/messages", ignoreCase = true) -> cleanBase
                    cleanBase.endsWith("/v1", ignoreCase = true) -> "$cleanBase/messages"
                    else -> "$cleanBase/v1/messages"
                }
            }

            ApiProtocol.GOOGLE_GEMINI -> {
                val base = if (cleanBase.isEmpty()) ApiProtocol.GOOGLE_GEMINI.defaultBaseUrl else cleanBase
                val normalizedBase = when {
                    base.contains(":generateContent") -> return "$base?key=${config.apiKey.trim()}"
                    base.endsWith("/models", ignoreCase = true) -> base
                    base.endsWith("/v1beta", ignoreCase = true) || base.endsWith("/v1", ignoreCase = true) -> "$base/models"
                    else -> "$base/v1beta/models"
                }
                "$normalizedBase/$model:generateContent?key=${config.apiKey.trim()}"
            }
        }
    }

    /**
     * Normalizes base URL into the `/v1/models` endpoint for model discovery.
     */
    fun buildModelsEndpointUrl(config: AiProviderConfig): String {
        val cleanBase = config.baseUrl.trim().trimEnd('/')
        return when (config.protocol) {
            ApiProtocol.GOOGLE_GEMINI -> {
                val base = if (cleanBase.isEmpty()) ApiProtocol.GOOGLE_GEMINI.defaultBaseUrl else cleanBase
                val modelsBase = when {
                    base.endsWith("/models", ignoreCase = true) -> base
                    base.endsWith("/v1beta", ignoreCase = true) || base.endsWith("/v1", ignoreCase = true) -> "$base/models"
                    else -> "$base/v1beta/models"
                }
                "$modelsBase?key=${config.apiKey.trim()}"
            }

            ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.ANTHROPIC_MESSAGES -> {
                val stripped = cleanBase
                    .removeSuffix("/chat/completions")
                    .removeSuffix("/messages")
                    .trimEnd('/')
                when {
                    stripped.endsWith("/v1/models", ignoreCase = true) -> stripped
                    stripped.endsWith("/models", ignoreCase = true) -> stripped
                    stripped.endsWith("/v1", ignoreCase = true) -> "$stripped/models"
                    else -> "$stripped/v1/models"
                }
            }
        }
    }

    /**
     * Builds the protocol-specific JSON request body.
     */
    fun buildRequestPayload(
        config: AiProviderConfig,
        prompt: String,
        systemInstruction: String,
        stream: Boolean
    ): String {
        val model = config.modelId.trim().ifEmpty { config.protocol.defaultModelId }
        return when (config.protocol) {
            ApiProtocol.OPENAI_COMPATIBLE -> {
                JSONObject().apply {
                    put("model", model)
                    put("temperature", config.temperature.toDouble())
                    put("max_tokens", config.maxOutputTokens)
                    put("stream", stream)
                    val messages = JSONArray().apply {
                        if (systemInstruction.isNotBlank()) {
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
                    put("messages", messages)
                }.toString()
            }

            ApiProtocol.ANTHROPIC_MESSAGES -> {
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", config.maxOutputTokens)
                    put("temperature", config.temperature.toDouble())
                    put("stream", stream)
                    if (systemInstruction.isNotBlank()) {
                        put("system", systemInstruction)
                    }
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    }
                    put("messages", messages)
                }.toString()
            }

            ApiProtocol.GOOGLE_GEMINI -> {
                JSONObject().apply {
                    if (systemInstruction.isNotBlank()) {
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", systemInstruction))
                            })
                        })
                    }
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", prompt))
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", config.temperature.toDouble())
                        put("maxOutputTokens", config.maxOutputTokens)
                    })
                }.toString()
            }
        }
    }

    /**
     * Parses a non-streaming JSON response across OpenAI/MotAPI, Anthropic, and Gemini formats.
     */
    fun parseNonStreamingResponse(rawJson: String, protocol: ApiProtocol): Result<String> {
        return try {
            val json = JSONObject(rawJson)
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message")?.ifBlank { null }
                    ?: json.optString("error")
                return Result.failure(IllegalStateException("API Error: $errMsg"))
            }

            when (protocol) {
                ApiProtocol.OPENAI_COMPATIBLE -> {
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val first = choices.getJSONObject(0)
                        val messageObj = first.optJSONObject("message")
                        if (messageObj != null) {
                            val contentVal = messageObj.opt("content")
                            when (contentVal) {
                                is String -> if (contentVal.isNotEmpty()) return Result.success(contentVal)
                                is JSONArray -> {
                                    val sb = StringBuilder()
                                    for (i in 0 until contentVal.length()) {
                                        val part = contentVal.optJSONObject(i)
                                        if (part != null && part.optString("type", "text") == "text") {
                                            sb.append(part.optString("text", ""))
                                        }
                                    }
                                    if (sb.isNotEmpty()) return Result.success(sb.toString())
                                }
                            }
                        }
                        val directText = first.optString("text", "")
                        if (directText.isNotEmpty()) return Result.success(directText)
                    }
                    // Fallback check if endpoint returned Anthropic-style content array
                    val contentArr = json.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until contentArr.length()) {
                            val item = contentArr.optJSONObject(i)
                            sb.append(item?.optString("text", "").orEmpty())
                        }
                        if (sb.isNotEmpty()) return Result.success(sb.toString())
                    }
                    Result.failure(IllegalStateException("Unexpected OpenAI/MotAPI response structure: ${rawJson.take(240)}"))
                }

                ApiProtocol.ANTHROPIC_MESSAGES -> {
                    val contentArr = json.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until contentArr.length()) {
                            val block = contentArr.optJSONObject(i)
                            if (block != null && block.optString("type", "text") == "text") {
                                sb.append(block.optString("text", ""))
                            }
                        }
                        if (sb.isNotEmpty()) return Result.success(sb.toString())
                    }
                    // Fallback if proxy converted to OpenAI choices
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val msg = choices.getJSONObject(0).optJSONObject("message")?.optString("content")
                        if (!msg.isNullOrEmpty()) return Result.success(msg)
                    }
                    Result.failure(IllegalStateException("Empty Anthropic response content: ${rawJson.take(240)}"))
                }

                ApiProtocol.GOOGLE_GEMINI -> {
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val sb = StringBuilder()
                            for (i in 0 until parts.length()) {
                                sb.append(parts.getJSONObject(i).optString("text", ""))
                            }
                            return Result.success(sb.toString())
                        }
                    }
                    Result.failure(IllegalStateException("No candidates returned from Gemini API: ${rawJson.take(240)}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Malformed JSON response: ${e.message}. Preview: ${rawJson.take(180)}"))
        }
    }

    /**
     * Formats clear, actionable HTTP error diagnostics without silently falling back to another provider.
     */
    fun formatHttpError(
        httpCode: Int,
        rawErrorBody: String,
        providerName: String,
        modelId: String
    ): String {
        val extractedDetail = try {
            val json = JSONObject(rawErrorBody)
            val errObj = json.optJSONObject("error")
            errObj?.optString("message")?.ifBlank { null }
                ?: json.optString("message").ifBlank { null }
                ?: rawErrorBody.take(250)
        } catch (_: Exception) {
            rawErrorBody.take(250).ifBlank { "No response body" }
        }

        val statusLabel = when (httpCode) {
            400 -> "400 Bad Request (Check request format or parameters)"
            401 -> "401 Unauthorized (Invalid or missing API key for $providerName)"
            403 -> "403 Forbidden (Access denied for key or model '$modelId')"
            404 -> "404 Not Found (Endpoint path or model '$modelId' not found on $providerName)"
            429 -> "429 Rate Limit / Quota Exceeded on $providerName"
            500, 502, 503, 504 -> "$httpCode Server Error on $providerName"
            else -> "HTTP $httpCode Error"
        }
        return "[$providerName] $statusLabel: $extractedDetail"
    }

    /**
     * Executes a single non-streaming request using [AiProviderConfig].
     * Never silently falls back to Gemini when a custom API is configured.
     */
    suspend fun generateWithConfig(
        config: AiProviderConfig,
        prompt: String,
        systemInstruction: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("[${config.providerName}] Missing API Key. Open AI Settings to configure your API key.")
            )
        }
        val urlValidation = validateSecureUrl(config.baseUrl)
        if (urlValidation.isFailure) {
            return@withContext Result.failure(urlValidation.exceptionOrNull()!!)
        }

        val endpointUrl = buildChatEndpointUrl(config)
        val timeoutMs = (config.timeoutSeconds.coerceIn(5, 300)) * 1000
        var connection: HttpURLConnection? = null

        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                applyAuthHeaders(this, config)
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
            }

            val payload = buildRequestPayload(
                config = config,
                prompt = prompt,
                systemInstruction = systemInstruction,
                stream = false
            )

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                parseNonStreamingResponse(responseBody, config.protocol)
            } else {
                val errorBody = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                }.orEmpty()
                Result.failure(
                    IllegalStateException(
                        formatHttpError(responseCode, errorBody, config.providerName, config.modelId)
                    )
                )
            }
        } catch (e: SocketTimeoutException) {
            Result.failure(IllegalStateException("[${config.providerName}] Request timed out after ${config.timeoutSeconds}s ($endpointUrl)."))
        } catch (e: UnknownHostException) {
            Result.failure(IllegalStateException("[${config.providerName}] Unknown host / DNS lookup failed for '$endpointUrl'. Check internet connection or Base URL."))
        } catch (e: SSLException) {
            Result.failure(IllegalStateException("[${config.providerName}] SSL/TLS handshake error: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("[${config.providerName}] Network error: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Streams AI responses using Server-Sent Events (`SSE`) for OpenAI/MotAPI and Anthropic,
     * or falls back to single-response emission when `streamingEnabled == false` or protocol is Gemini.
     */
    fun streamWithConfig(
        config: AiProviderConfig,
        prompt: String,
        systemInstruction: String
    ): Flow<AiStreamEvent> = flow {
        if (config.apiKey.isBlank()) {
            emit(
                AiStreamEvent.Error(
                    "[${config.providerName}] Missing API Key. Please configure your API key in Settings."
                )
            )
            return@flow
        }

        val urlValidation = validateSecureUrl(config.baseUrl)
        if (urlValidation.isFailure) {
            emit(AiStreamEvent.Error(urlValidation.exceptionOrNull()?.message ?: "Invalid Base URL"))
            return@flow
        }

        if (!config.streamingEnabled || config.protocol == ApiProtocol.GOOGLE_GEMINI) {
            val result = generateWithConfig(config, prompt, systemInstruction)
            result.fold(
                onSuccess = { fullText ->
                    emit(AiStreamEvent.Delta(fullText, fullText))
                    emit(AiStreamEvent.Complete(fullText))
                },
                onFailure = { err ->
                    emit(AiStreamEvent.Error(err.message ?: "Request failed"))
                }
            )
            return@flow
        }

        val endpointUrl = buildChatEndpointUrl(config)
        val timeoutMs = (config.timeoutSeconds.coerceIn(5, 300)) * 1000
        var connection: HttpURLConnection? = null

        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream, application/json")
                setRequestProperty("Cache-Control", "no-cache")
                applyAuthHeaders(this, config)
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
            }

            val payload = buildRequestPayload(
                config = config,
                prompt = prompt,
                systemInstruction = systemInstruction,
                stream = true
            )

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                }.orEmpty()
                emit(
                    AiStreamEvent.Error(
                        formatHttpError(responseCode, errorBody, config.providerName, config.modelId),
                        responseCode
                    )
                )
                return@flow
            }

            val contentType = connection.contentType.orEmpty().lowercase()
            val accumulated = StringBuilder()

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val rawLines = mutableListOf<String>()
                var sawSseDataLine = false
                var line: String? = reader.readLine()

                while (line != null) {
                    currentCoroutineContext().ensureActive()
                    val trimmed = line.trim()
                    rawLines.add(line)

                    if (trimmed.startsWith("data:")) {
                        sawSseDataLine = true
                        val dataPayload = trimmed.removePrefix("data:").trim()
                        if (dataPayload == "[DONE]") {
                            break
                        }
                        if (dataPayload.isNotEmpty()) {
                            val chunk = extractSseDeltaChunk(dataPayload, config.protocol)
                            if (!chunk.isNullOrEmpty()) {
                                accumulated.append(chunk)
                                emit(AiStreamEvent.Delta(chunk, accumulated.toString()))
                            }
                        }
                    }
                    line = reader.readLine()
                }

                // If server ignored `stream: true` and returned standard JSON body instead of SSE
                if (!sawSseDataLine && !contentType.contains("event-stream")) {
                    val fullRaw = rawLines.joinToString("\n")
                    val parsed = parseNonStreamingResponse(fullRaw, config.protocol)
                    parsed.fold(
                        onSuccess = { text ->
                            accumulated.append(text)
                            emit(AiStreamEvent.Delta(text, text))
                        },
                        onFailure = { err ->
                            emit(AiStreamEvent.Error(err.message ?: "Failed to parse response"))
                            return@flow
                        }
                    )
                }
            }

            if (accumulated.isEmpty()) {
                emit(AiStreamEvent.Error("[${config.providerName}] Stream completed with empty content."))
            } else {
                emit(AiStreamEvent.Complete(accumulated.toString()))
            }
        } catch (e: SocketTimeoutException) {
            emit(AiStreamEvent.Error("[${config.providerName}] Streaming timed out after ${config.timeoutSeconds}s."))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emit(AiStreamEvent.Error("[${config.providerName}] Stream interrupted: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Extracts text delta from a single SSE `data:` JSON line for OpenAI/MotAPI or Anthropic streams.
     */
    fun extractSseDeltaChunk(dataJson: String, protocol: ApiProtocol): String? {
        return try {
            val json = JSONObject(dataJson)
            when (protocol) {
                ApiProtocol.OPENAI_COMPATIBLE -> {
                    val choices = json.optJSONArray("choices") ?: return null
                    if (choices.length() == 0) return null
                    val first = choices.getJSONObject(0)
                    val delta = first.optJSONObject("delta")
                    if (delta != null && delta.has("content") && !delta.isNull("content")) {
                        return delta.optString("content", "")
                    }
                    val message = first.optJSONObject("message")
                    if (message != null && message.has("content")) {
                        return message.optString("content", "")
                    }
                    null
                }

                ApiProtocol.ANTHROPIC_MESSAGES -> {
                    val type = json.optString("type", "")
                    if (type == "content_block_delta") {
                        val delta = json.optJSONObject("delta")
                        return delta?.optString("text", "")
                    }
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        return choices.getJSONObject(0).optJSONObject("delta")?.optString("content", "")
                    }
                    null
                }

                ApiProtocol.GOOGLE_GEMINI -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches the available models from `/v1/models` (or Gemini `/models`) and checks if `claude-sonnet-5-5` is present.
     */
    suspend fun fetchModels(config: AiProviderConfig): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val endpointUrl = buildModelsEndpointUrl(config)
        if (config.apiKey.isBlank()) {
            return@withContext ModelDiscoveryResult(
                success = false,
                models = emptyList(),
                hasClaudeSonnet55 = false,
                endpointUrl = endpointUrl,
                statusMessage = "Missing API Key for ${config.providerName}."
            )
        }
        val urlValidation = validateSecureUrl(config.baseUrl)
        if (urlValidation.isFailure) {
            return@withContext ModelDiscoveryResult(
                success = false,
                models = emptyList(),
                hasClaudeSonnet55 = false,
                endpointUrl = endpointUrl,
                statusMessage = urlValidation.exceptionOrNull()?.message ?: "Invalid Base URL"
            )
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                applyAuthHeaders(this, config)
                connectTimeout = 20_000
                readTimeout = 20_000
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val models = parseModelsList(body)
                val hasSonnet55 = models.any { it.equals("claude-sonnet-5-5", ignoreCase = true) }
                val summary = buildString {
                    append("Discovered ${models.size} model(s) from ${config.providerName}.")
                    if (hasSonnet55) {
                        append(" Verified: 'claude-sonnet-5-5' is available!")
                    }
                }
                ModelDiscoveryResult(
                    success = true,
                    models = models,
                    hasClaudeSonnet55 = hasSonnet55,
                    endpointUrl = endpointUrl,
                    statusMessage = summary
                )
            } else {
                val errorBody = connection.errorStream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
                }.orEmpty()
                ModelDiscoveryResult(
                    success = false,
                    models = emptyList(),
                    hasClaudeSonnet55 = false,
                    endpointUrl = endpointUrl,
                    statusMessage = formatHttpError(code, errorBody, config.providerName, config.modelId)
                )
            }
        } catch (e: Exception) {
            ModelDiscoveryResult(
                success = false,
                models = emptyList(),
                hasClaudeSonnet55 = false,
                endpointUrl = endpointUrl,
                statusMessage = "[${config.providerName}] Failed to fetch models: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    fun parseModelsList(rawJson: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val json = JSONObject(rawJson)
            val dataArr = json.optJSONArray("data") ?: json.optJSONArray("models")
            if (dataArr != null) {
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i)
                    val id = item?.optString("id")?.ifBlank { null }
                        ?: item?.optString("name")?.removePrefix("models/")
                    if (!id.isNullOrBlank()) {
                        result.add(id)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.distinct()
    }

    /**
     * Tests the connection to the configured provider and measures round-trip latency in ms.
     */
    suspend fun testConnection(config: AiProviderConfig): ConnectionTestResult = withContext(Dispatchers.IO) {
        val endpointUrl = buildChatEndpointUrl(config)
        if (config.apiKey.isBlank()) {
            return@withContext ConnectionTestResult(
                success = false,
                httpStatusCode = 0,
                latencyMs = 0L,
                endpointUrl = endpointUrl,
                message = "API Key is empty. Enter a valid key for ${config.providerName}."
            )
        }
        val urlValidation = validateSecureUrl(config.baseUrl)
        if (urlValidation.isFailure) {
            return@withContext ConnectionTestResult(
                success = false,
                httpStatusCode = 0,
                latencyMs = 0L,
                endpointUrl = endpointUrl,
                message = urlValidation.exceptionOrNull()?.message ?: "Invalid Base URL"
            )
        }

        val startTime = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            val testConfig = config.copy(maxOutputTokens = 32, streamingEnabled = false)
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                applyAuthHeaders(this, testConfig)
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
            }

            val payload = buildRequestPayload(
                config = testConfig,
                prompt = "Reply with OK.",
                systemInstruction = "You are a connectivity test responder. Reply briefly.",
                stream = false
            )

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val status = connection.responseCode
            val latency = System.currentTimeMillis() - startTime
            if (status in 200..299) {
                val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val parsed = parseNonStreamingResponse(body, testConfig.protocol)
                val preview = parsed.getOrDefault(body.take(120)).trim()
                ConnectionTestResult(
                    success = true,
                    httpStatusCode = status,
                    latencyMs = latency,
                    endpointUrl = endpointUrl,
                    message = "Connected to ${config.providerName} (${config.modelId}) — HTTP $status in ${latency}ms",
                    responsePreview = preview.take(160)
                )
            } else {
                val errBody = connection.errorStream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
                }.orEmpty()
                ConnectionTestResult(
                    success = false,
                    httpStatusCode = status,
                    latencyMs = latency,
                    endpointUrl = endpointUrl,
                    message = formatHttpError(status, errBody, config.providerName, config.modelId),
                    responsePreview = errBody.take(200)
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ConnectionTestResult(
                success = false,
                httpStatusCode = -1,
                latencyMs = latency,
                endpointUrl = endpointUrl,
                message = "[${config.providerName}] Connection failed after ${latency}ms: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Backwards-compatible entry point that delegates to [generateWithConfig].
     */
    suspend fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String,
        provider: String = "OPENAI_COMPATIBLE",
        baseUrl: String = "https://motapis.com/v1"
    ): String {
        val protocol = when (provider.uppercase()) {
            "GEMINI", "GOOGLE_GEMINI" -> ApiProtocol.GOOGLE_GEMINI
            "ANTHROPIC", "ANTHROPIC_MESSAGES" -> ApiProtocol.ANTHROPIC_MESSAGES
            else -> ApiProtocol.OPENAI_COMPATIBLE
        }
        val config = AiProviderConfig(
            providerName = if (baseUrl.contains("motapis", ignoreCase = true)) "MotAPI" else provider,
            protocol = protocol,
            baseUrl = baseUrl.ifBlank { protocol.defaultBaseUrl },
            apiKey = apiKey,
            modelId = model.ifBlank { protocol.defaultModelId },
            streamingEnabled = false
        )
        return generateWithConfig(config, prompt, systemInstruction)
            .getOrElse { err -> "Error: ${err.message}" }
    }

    suspend fun generateContent(
        prompt: String,
        customUrl: String,
        customKey: String,
        customModel: String,
        systemInstruction: String
    ): String {
        val cleanUrl = customUrl.trim().ifEmpty { "https://motapis.com/v1" }
        val protocol = when {
            cleanUrl.contains("generativelanguage.googleapis.com", ignoreCase = true) -> ApiProtocol.GOOGLE_GEMINI
            cleanUrl.contains("anthropic.com", ignoreCase = true) -> ApiProtocol.ANTHROPIC_MESSAGES
            else -> ApiProtocol.OPENAI_COMPATIBLE
        }
        val config = AiProviderConfig(
            providerName = if (cleanUrl.contains("motapis", ignoreCase = true)) "MotAPI" else protocol.displayName,
            protocol = protocol,
            baseUrl = cleanUrl,
            apiKey = customKey.trim(),
            modelId = customModel.trim().ifEmpty { protocol.defaultModelId },
            streamingEnabled = false
        )
        return generateWithConfig(config, prompt, systemInstruction)
            .getOrElse { err -> "Error: ${err.message}" }
    }

    private fun applyAuthHeaders(connection: HttpURLConnection, config: AiProviderConfig) {
        val cleanKey = config.apiKey.trim()
        when (config.protocol) {
            ApiProtocol.OPENAI_COMPATIBLE -> {
                connection.setRequestProperty("Authorization", "Bearer $cleanKey")
            }
            ApiProtocol.ANTHROPIC_MESSAGES -> {
                connection.setRequestProperty("x-api-key", cleanKey)
                connection.setRequestProperty("Authorization", "Bearer $cleanKey")
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            }
            ApiProtocol.GOOGLE_GEMINI -> {
                connection.setRequestProperty("x-goog-api-key", cleanKey)
            }
        }
    }
}
