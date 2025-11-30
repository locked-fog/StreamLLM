package dev.lockedfog.streamllm.core

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// --- 核心数据模型 (Shared Models) ---

/**
 * 通用对话消息模型
 */
data class ChatMessage(val role: String, val content: String)

/**
 * 记忆策略枚举
 */
enum class MemoryStrategy {
    ReadWrite, // (默认) 读取历史上下文，并将本次对话写入记忆
    ReadOnly,  // 仅读取历史作为上下文，不记录本次对话
    WriteOnly, // 不读取历史上下文，但记录本次对话 (相当于新话题存档)
    Stateless  // 既不读也不写 (纯粹的无状态调用)
}

// --- 记忆管理器实现 ---

class MemoryManager {

    // 内部类：记忆上下文
    private data class MemoryContext(
        var systemPrompt: String? = null,
        val messages: MutableList<ChatMessage> = Collections.synchronizedList(mutableListOf())
    )

    // 多记忆体存储：Name -> Context
    private val memories = ConcurrentHashMap<String, MemoryContext>()

    @Volatile
    private var currentMemoryId = "default"

    init {
        // 初始化默认记忆体
        createMemory("default")
    }

    // --- 记忆管理接口 ---

    fun createMemory(name: String, systemPrompt: String? = null) {
        memories.computeIfAbsent(name) {
            MemoryContext(systemPrompt = systemPrompt)
        }
        // 如果已存在，且提供了新的 systemPrompt，则更新
        if (systemPrompt != null) {
            memories[name]?.systemPrompt = systemPrompt
        }
    }

    fun switchMemory(name: String) {
        if (!memories.containsKey(name)) {
            throw IllegalArgumentException("Memory '$name' does not exist. Please call newMemory() first.")
        }
        currentMemoryId = name
    }

    fun deleteMemory(name: String) {
        if (name == currentMemoryId) {
            throw IllegalStateException("Cannot delete the currently active memory ('$name'). Please switch first.")
        }
        memories.remove(name)
    }

    fun updateSystemPrompt(name: String, prompt: String?) {
        memories[name]?.systemPrompt = prompt
    }

    // --- 消息操作接口 (针对当前记忆体) ---

    fun addMessage(role: String, content: String) {
        memories[currentMemoryId]?.messages?.add(ChatMessage(role, content))
    }

    /**
     * 获取当前历史记录
     * @param windowSize 窗口大小。-1 代表全部，0 代表无历史，N 代表最近 N 条对话
     * @param tempSystem 若不为空，则使用此 System Prompt 覆盖记忆体原本的 System Prompt
     * @param includeSystem 是否在返回列表中包含 System Message。如果为 false，只返回对话历史。
     */
    fun getCurrentHistory(
        windowSize: Int = -1,
        tempSystem: String? = null,
        includeSystem: Boolean = true
    ): List<ChatMessage> {
        val context = memories[currentMemoryId] ?: return emptyList()

        // 1. 处理窗口切片 (只针对 User/Assistant 对话历史)
        val rawHistory = context.messages
        val slicedHistory = when {
            windowSize < 0 -> rawHistory.toList() // 全部
            windowSize == 0 -> emptyList()        // 无历史
            else -> rawHistory.takeLast(windowSize) // 最近 N 条
        }

        if (!includeSystem) {
            return slicedHistory
        }

        // 2. 处理 System Prompt (临时覆盖 > 记忆体自带)
        val activeSystem = tempSystem ?: context.systemPrompt

        return if (!activeSystem.isNullOrBlank()) {
            listOf(ChatMessage("system", activeSystem)) + slicedHistory
        } else {
            slicedHistory
        }
    }

    fun clear() {
        memories[currentMemoryId]?.messages?.clear()
    }
}