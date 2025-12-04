package dev.lockedfog.streamllm.core.memory

import dev.lockedfog.streamllm.core.ChatMessage

/**
 * 记忆持久化存储接口。
 *
 * 定义了将对话历史和 System Prompt 持久化到外部存储（如数据库、文件、云端）的标准契约。
 * 所有方法均为挂起函数 (Suspend Function)，以支持异步 I/O 操作，避免阻塞主线程。
 * 开发者可以通过实现此接口来接入自定义的存储后端（如 Room, SqlDelight, DataStore 等）。
 */
interface MemoryStorage {

    /**
     * 获取指定记忆体的 System Prompt。
     *
     * @param memoryId 记忆体 ID。
     * @return System Prompt 字符串，如果不存在则返回 null。
     */
    suspend fun getSystemPrompt(memoryId: String): String?

    /**
     * 更新或设置指定记忆体的 System Prompt。
     *
     * @param memoryId 记忆体 ID。
     * @param prompt 新的 System Prompt 内容。
     */
    suspend fun setSystemPrompt(memoryId: String, prompt: String)

    /**
     * 获取指定记忆体的消息历史。
     *
     * @param memoryId 记忆体 ID。
     * @param limit 获取最近的消息数量。-1 表示获取全部，>0 表示获取最近的 N 条。
     * @return 消息列表，按时间顺序排列（旧 -> 新）。
     */
    suspend fun getMessages(memoryId: String, limit: Int = -1): List<ChatMessage>

    /**
     * 向指定记忆体追加一条消息。
     *
     * 此方法通常用于增量写入。
     *
     * @param memoryId 记忆体 ID。
     * @param message 要追加的消息对象。
     */
    suspend fun addMessage(memoryId: String, message: ChatMessage)

    /**
     * 保存记忆体的完整上下文（System Prompt + 所有消息）。
     *
     * 此方法通常用于缓存驱逐 (Eviction) 时，将内存中的最终状态全量同步到数据库，
     * 或者是为了确保数据一致性而进行的覆盖更新。
     *
     * @param memoryId 记忆体 ID。
     * @param systemPrompt 当前的 System Prompt。
     * @param messages 完整的消息列表。
     */
    suspend fun saveFullContext(memoryId: String, systemPrompt: String?, messages: List<ChatMessage>)

    /**
     * 清空指定记忆体的所有对话消息（但保留 System Prompt）。
     *
     * @param memoryId 记忆体 ID。
     */
    suspend fun clearMessages(memoryId: String)

    /**
     * 彻底删除指定记忆体（包括 System Prompt 和所有消息）。
     *
     * @param memoryId 记忆体 ID。
     */
    suspend fun deleteMemory(memoryId: String)
}
