package dev.lockedfog.streamllm.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String
)

// --- 响应包 (Response - 非流式) ---
@Serializable
data class OpenAiChatResponse(
    val choices: List<Choice>
) {
    @Serializable
    data class Choice(val message: OpenAiMessage)
}

// --- 响应包 (Chunk - 流式) ---
@Serializable
data class OpenAiStreamChunk(
    val choices: List<StreamChoice>
) {
    @Serializable
    data class StreamChoice(val delta: Delta)

    @Serializable
    data class Delta(val content: String? = null) // 流式返回可能没有 content
}