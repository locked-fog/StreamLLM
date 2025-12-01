package dev.lockedfog.streamllm.core

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// --- 核心数据模型 ---

/**
 * 通用对话消息模型。
 *
 * 表示一条对话记录，包含角色和内容。
 *
 * @property role 消息发送者的角色 (User, Assistant, System)。
 * @property content 消息的文本内容。
 */
data class ChatMessage(val role: ChatRole, val content: String)

/**
 * 记忆策略枚举。
 *
 * 用于控制单次对话请求如何与记忆管理器交互（读取历史上下文/写入新对话）。
 */
enum class MemoryStrategy {
    /**
     * (默认) 读写模式。
     * 读取现有历史作为上下文发送给模型，并将本次的用户输入和 AI 回复写入记忆。
     */
    ReadWrite,

    /**
     * 只读模式。
     * 读取现有历史作为上下文，但**不**记录本次对话。
     * 适用于基于历史进行总结、提取信息等不应污染对话流的任务。
     */
    ReadOnly,

    /**
     * 只写模式。
     * **不**读取历史上下文（相当于开启新话题），但将本次对话写入记忆。
     * 适用于希望开启新话题但保留存档的场景。
     */
    WriteOnly,

    /**
     * 无状态模式。
     * 既不读取历史，也不记录本次对话。纯粹的一次性调用。
     */
    Stateless
}

// --- 记忆管理器实现 ---

/**
 * 记忆管理器。
 *
 * 负责管理应用中的对话历史。支持多记忆体（Memory Context）切换，
 * 允许针对不同的用户、会话或任务维护独立的上下文。
 *
 * 注意：目前基于内存存储 (ConcurrentHashMap)，应用重启后数据会丢失。
 */
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

    /**
     * 创建一个新的记忆体。
     *
     * 如果指定名称的记忆体已存在，且提供了新的 [systemPrompt]，则更新该记忆体的 System Prompt。
     *
     * @param name 记忆体的唯一标识名称。
     * @param systemPrompt (可选) 该记忆体的默认 System Prompt (人设/指令)。
     */
    fun createMemory(name: String, systemPrompt: String? = null) {
        memories.computeIfAbsent(name) {
            MemoryContext(systemPrompt = systemPrompt)
        }
        // 如果已存在，且提供了新的 systemPrompt，则更新
        if (systemPrompt != null) {
            memories[name]?.systemPrompt = systemPrompt
        }
    }

    /**
     * 切换当前活动的记忆体。
     *
     * 切换后，后续的 `ask` 或 `stream` 调用将默认使用该记忆体的历史记录。
     *
     * @param name 目标记忆体的名称。
     * @throws IllegalArgumentException 如果指定的记忆体不存在。
     */
    fun switchMemory(name: String) {
        if (!memories.containsKey(name)) {
            throw IllegalArgumentException("Memory '$name' does not exist. Please call newMemory() first.")
        }
        currentMemoryId = name
    }

    /**
     * 删除指定的记忆体。
     *
     * @param name 要删除的记忆体名称。
     * @throws IllegalStateException 如果尝试删除当前正在使用的记忆体。
     */
    fun deleteMemory(name: String) {
        if (name == currentMemoryId) {
            throw IllegalStateException("Cannot delete the currently active memory ('$name'). Please switch first.")
        }
        memories.remove(name)
    }

    /**
     * 更新指定记忆体的 System Prompt。
     *
     * @param name 记忆体名称。
     * @param prompt 新的 System Prompt。
     */
    fun updateSystemPrompt(name: String, prompt: String?) {
        memories[name]?.systemPrompt = prompt
    }

    // --- 消息操作接口 (针对当前记忆体) ---

    /**
     * 向当前活动的记忆体中添加一条消息。
     *
     * @param role 角色。
     * @param content 内容。
     */
    fun addMessage(role: ChatRole, content: String) {
        memories[currentMemoryId]?.messages?.add(ChatMessage(role, content))
    }

    /**
     * 获取当前活动的对话历史记录。
     *
     * @param windowSize 历史窗口大小。
     * - -1: 返回全部历史。
     * - 0: 不返回对话历史 (仅返回 System Prompt，如果 includeSystem 为 true)。
     * - N (>0): 返回最近的 N 条对话记录。
     * @param tempSystem (可选) 临时 System Prompt。如果提供，将覆盖记忆体原有的 System Prompt。
     * @param includeSystem 是否在返回列表中包含 System Message。
     * @return 构造好的消息列表。
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
            listOf(ChatMessage(ChatRole.SYSTEM, activeSystem)) + slicedHistory
        } else {
            slicedHistory
        }
    }

    /**
     * 清空当前活动记忆体中的所有对话历史（保留 System Prompt）。
     */
    fun clear() {
        memories[currentMemoryId]?.messages?.clear()
    }
}