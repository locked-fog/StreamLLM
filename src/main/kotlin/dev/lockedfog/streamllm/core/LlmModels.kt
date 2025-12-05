package dev.lockedfog.streamllm.core

import kotlinx.serialization.Serializable

/**
 * 通用的 LLM 响应封装对象。
 *
 * 用于在 Provider 和上层调用之间传递数据，解耦了具体 API 厂商的 JSON 结构。
 *
 * @property content 模型生成的文本内容。在流式传输中，这可能是一个片段。
 * @property usage Token 用量统计信息。通常只在完整响应或流式响应的最后一个包中存在，其他情况可能为 null。
 * @property reasoningContent (DeepSeek R1/Siliconflow) 模型生成的思维链/推理内容。
 * @property toolCalls (Tool Calling) 模型生成的工具调用请求。
 */
data class LlmResponse(
    val content: String,
    val usage: Usage? = null,
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall>? = null
)

/**
 * Token 用量统计数据。
 *
 * @property promptTokens 提示词 (Input) 消耗的 Token 数。
 * @property completionTokens 生成内容 (Output) 消耗的 Token 数。
 * @property totalTokens 总 Token 数 (Prompt + Completion)。
 */
@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)