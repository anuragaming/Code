package com.example.api

enum class ApiProtocol(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModelId: String
) {
    OPENAI_COMPATIBLE(
        id = "OPENAI_COMPATIBLE",
        displayName = "OpenAI-Compatible (/v1/chat/completions)",
        defaultBaseUrl = "https://motapis.com/v1",
        defaultModelId = "claude-sonnet-5-5"
    ),
    ANTHROPIC_MESSAGES(
        id = "ANTHROPIC_MESSAGES",
        displayName = "Anthropic Messages (/v1/messages)",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModelId = "claude-sonnet-5-5"
    ),
    GOOGLE_GEMINI(
        id = "GOOGLE_GEMINI",
        displayName = "Google Gemini (generateContent)",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModelId = "gemini-2.5-flash"
    );

    companion object {
        fun fromId(id: String?): ApiProtocol {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: OPENAI_COMPATIBLE
        }
    }
}

data class AiProviderConfig(
    val providerName: String = "MotAPI",
    val protocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE,
    val baseUrl: String = "https://motapis.com/v1",
    val apiKey: String = "",
    val modelId: String = "claude-sonnet-5-5",
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 4096,
    val timeoutSeconds: Int = 60,
    val streamingEnabled: Boolean = true
) {
    companion object {
        fun motApiPreset(apiKey: String = ""): AiProviderConfig = AiProviderConfig(
            providerName = "MotAPI",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://motapis.com/v1",
            apiKey = apiKey,
            modelId = "claude-sonnet-5-5",
            temperature = 0.2f,
            maxOutputTokens = 4096,
            timeoutSeconds = 60,
            streamingEnabled = true
        )

        fun geminiPreset(apiKey: String = ""): AiProviderConfig = AiProviderConfig(
            providerName = "Google Gemini",
            protocol = ApiProtocol.GOOGLE_GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = apiKey,
            modelId = "gemini-2.5-flash",
            temperature = 0.2f,
            maxOutputTokens = 4096,
            timeoutSeconds = 60,
            streamingEnabled = false
        )

        fun anthropicPreset(apiKey: String = ""): AiProviderConfig = AiProviderConfig(
            providerName = "Anthropic",
            protocol = ApiProtocol.ANTHROPIC_MESSAGES,
            baseUrl = "https://api.anthropic.com/v1",
            apiKey = apiKey,
            modelId = "claude-sonnet-5-5",
            temperature = 0.2f,
            maxOutputTokens = 4096,
            timeoutSeconds = 60,
            streamingEnabled = true
        )
    }
}

data class ConnectionTestResult(
    val success: Boolean,
    val httpStatusCode: Int,
    val latencyMs: Long,
    val endpointUrl: String,
    val message: String,
    val responsePreview: String = ""
)

data class ModelDiscoveryResult(
    val success: Boolean,
    val models: List<String>,
    val hasClaudeSonnet55: Boolean,
    val endpointUrl: String,
    val statusMessage: String
)

sealed class AiStreamEvent {
    data class Delta(val chunk: String, val accumulated: String) : AiStreamEvent()
    data class Complete(val fullText: String) : AiStreamEvent()
    data class Error(val errorMessage: String, val httpCode: Int = -1) : AiStreamEvent()
}
