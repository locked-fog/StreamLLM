package dev.lockedfog.streamllm.core.memory

import dev.lockedfog.streamllm.core.ChatMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于内存的 [MemoryStorage] 默认实现。
 *
 * 使用 [ConcurrentHashMap] 存储数据，线程安全。
 * 注意：此实现不具备持久化能力，应用重启后数据会丢失。仅作为 SDK 的默认配置或测试使用。
 */
class InMemoryStorage : MemoryStorage {

    /**
     * 内部存储结构，模拟数据库表的一行记录。
     */
    private data class StorageEntry(
        var systemPrompt: String? = null,
        val messages: MutableList<ChatMessage> = mutableListOf()
    )

    // 使用 ConcurrentHashMap 保证基础的线程安全
    private val data = ConcurrentHashMap<String, StorageEntry>()

    private fun getEntry(id: String): StorageEntry {
        return data.computeIfAbsent(id) { StorageEntry() }
    }

    override suspend fun getSystemPrompt(memoryId: String): String? {
        return data[memoryId]?.systemPrompt
    }

    override suspend fun setSystemPrompt(memoryId: String, prompt: String) {
        getEntry(memoryId).systemPrompt = prompt
    }

    override suspend fun getMessages(memoryId: String, limit: Int): List<ChatMessage> {
        val entry = data[memoryId] ?: return emptyList()
        val allMessages = entry.messages

        if (data[memoryId] == null || data[memoryId]?.messages?.isEmpty() == true) return emptyList()

        // 返回副本以防止外部修改影响内部状态
        return if (limit < 0 || limit >= allMessages.size) {
            ArrayList(allMessages)
        } else {
            ArrayList(allMessages.takeLast(limit))
        }
    }

    override suspend fun addMessage(memoryId: String, message: ChatMessage) {
        getEntry(memoryId).messages.add(message)
    }

    override suspend fun saveFullContext(
        memoryId: String,
        systemPrompt: String?,
        messages: List<ChatMessage>
    ) {
        val entry = getEntry(memoryId)
        entry.systemPrompt = systemPrompt
        // 模拟全量覆盖
        entry.messages.clear()
        entry.messages.addAll(messages)
    }

    override suspend fun clearMessages(memoryId: String) {
        data[memoryId]?.messages?.clear()
    }

    override suspend fun deleteMemory(memoryId: String) {
        data.remove(memoryId)
    }
}
