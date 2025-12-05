package dev.lockedfog.streamllm.provider.openai

import dev.lockedfog.streamllm.core.ChatContent
import dev.lockedfog.streamllm.core.ChatRole
import dev.lockedfog.streamllm.core.Tool
import dev.lockedfog.streamllm.core.ToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    /** 可用工具列表 */
    val tools: List<Tool>? = null,
    /** 工具选择策略 */
    @SerialName("tool_choice") val toolChoice: JsonElement? = null
)

/**
 * OpenAI 消息对象。
 */
@Serializable
data class OpenAiMessage(
    /** 角色 */
    val role: ChatRole,
    /** 内容 */
    val content: ChatContent? = null,
    /** 名称（可选） */
    val name: String? = null,
    /** 工具调用（可选） */
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /** 工具调用ID（可选，Tool Role 必须） */
    @SerialName("tool_call_id") val toolCallId: String? = null
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
    data class StreamChoice(
        val delta: Delta,
        /** 停止原因 */
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
    )
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
