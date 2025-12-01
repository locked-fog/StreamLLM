package dev.lockedfog.streamllm.provider

import dev.lockedfog.streamllm.core.ChatMessage
import dev.lockedfog.streamllm.core.GenerationOptions
import dev.lockedfog.streamllm.core.LlmResponse
import kotlinx.coroutines.flow.Flow

/**
 * LLM 服务提供者接口。
 *
 * 定义了与大语言模型进行交互的标准契约。所有具体的模型实现（如 OpenAI, SiliconFlow, DeepSeek 等）
 * 都需要实现此接口。
 *
 * 该接口继承自 [AutoCloseable]，以便在不再需要时释放底层资源（如 HTTP 连接池）。
 */
interface LlmProvider : AutoCloseable {
    /**
     * 发送一次性对话请求，并等待完整响应。
     *
     * 这是一个挂起函数，会阻塞协程直到服务器返回完整的回复。
     *
     * @param messages 对话历史消息列表。
     * @param options (可选) 生成选项，如温度、最大 Token 数等。
     * @return [LlmResponse] 对象，包含生成的文本内容和 Token 用量统计。
     * @throws dev.lockedfog.streamllm.core.LlmException 当发生 API 错误或网络错误时抛出。
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        options: GenerationOptions? = null
    ): LlmResponse

    /**
     * 发送流式对话请求，返回响应流。
     *
     * 该方法立即返回一个 [Flow]，服务器生成的 Token 会通过该流逐个推送。
     *
     * Flow 中的 [LlmResponse] 对象可能包含以下情况：
     * 1. 仅包含 content 片段 (content 不为空，usage 为 null)。
     * 2. 仅包含 usage 统计 (content 为空，usage 不为 null) —— 通常在流的末尾。
     *
     * @param messages 对话历史消息列表。
     * @param options (可选) 生成选项。
     * @return 发送 [LlmResponse] 的冷流 (Cold Flow)。
     */
    fun stream(
        messages: List<ChatMessage>,
        options: GenerationOptions? = null
    ): Flow<LlmResponse>

    /**
     * 释放该 Provider 占用的资源。
     *
     * 默认实现为空。子类应重写此方法以关闭 HTTP 客户端或数据库连接。
     */
    override fun close() {
        // Default no-op
    }
}