package dev.lockedfog.streamllm.provider.openai

import dev.lockedfog.streamllm.core.ChatRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI Chat Completion API 请求体。
 */
@Serializable
data class OpenAiChatRequest(
    /** 模型名称 (如 "gpt-3.5-turbo") */
    val model: String,
    /** 消息列表 */
    val messages: List<OpenAiMessage>,
    /** 是否流式传输 */
    val stream: Boolean = false,
    /** 采样温度 */
    val temperature: Double? = null,
    /** 核采样概率 */
    @SerialName("top_p") val topP: Double? = null,
    /** 最大 Token 数 */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** 停止序列 */
    val stop: List<String>? = null,
    // 可选：某些厂商可能需要显式配置以返回 usage
    // @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

/**
 * OpenAI 消息对象。
 */
@Serializable
data class OpenAiMessage(
    /** 角色 */
    val role: ChatRole,
    /** 内容 */
    val content: String
)

// --- 响应包 (Response - 非流式) ---

/**
 * OpenAI 非流式响应体。
 */
@Serializable
data class OpenAiChatResponse(
    /** 生成选项列表 */
    val choices: List<Choice>,
    /** Token 用量统计 */
    val usage: OpenAiUsage? = null
) {
    @Serializable
    data class Choice(val message: OpenAiMessage)
}

// --- 响应包 (Chunk - 流式) ---

/**
 * OpenAI 流式响应块 (Chunk)。
 *
 * SSE 推送的每一行数据对应的 JSON 结构。
 */
@Serializable
data class OpenAiStreamChunk(
    val choices: List<StreamChoice>? = null,
    /** Token 用量 (通常只出现在最后一个块) */
    val usage: OpenAiUsage? = null,
    /** 错误信息 (如果发生错误) */
    val error: OpenAiError? = null
) {
    @Serializable
    data class StreamChoice(val delta: Delta)

    @Serializable
    data class Delta(val content: String? = null)
}

// --- OpenAI 格式的 Usage ---

/**
 * OpenAI 格式的 Token 用量统计。
 */
@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

// --- 错误对象 ---

/**
 * OpenAI API 错误对象。
 */
@Serializable
data class OpenAiError(
    val message: String,
    val type: String? = null,
    val code: String? = null
)