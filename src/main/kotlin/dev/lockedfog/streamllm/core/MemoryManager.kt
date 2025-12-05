package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.core.memory.LruMemoryCache
import dev.lockedfog.streamllm.core.memory.MemoryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

// --- 核心数据模型 ---

/**
 * 通用对话消息模型。
 *
 * 表示一条对话记录，包含角色和内容。
 *
 * @property role 消息发送者的角色 (See also [ChatRole])。
 * @property content 消息的内容（支持多模态）。
 * @property name (可选) 消息发送者的名称（如工具函数名）。
 * @property toolCalls (可选) Assistant 角色生成的工具调用请求列表。
 * @property toolCallId (可选) Tool 角色回复时对应的 Call ID。
 */
data class ChatMessage(
    val role: ChatRole,
    val content: ChatContent,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
) {
    /**
     * 辅助构造函数，兼容旧代码
     *
     * @param role 消息发送者的角色 [ChatRole]
     * @param text 消息内容
     */
    constructor(role: ChatRole, text: String) : this(
        role = role,
        content = ChatContent.Text(text)
    )
}

/**
 * 记忆策略枚举。
 *
 * 用于控制单次对话请求如何与记忆管理器交互（读取历史上下文/写入新对话）。
 */
@Suppress("unused")
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
 * 采用了 LRU 缓存 + 异步持久化的策略。
 */
class MemoryManager(
    private val storage: MemoryStorage,
    maxMemoryCount: Int,
    private val persistenceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val logger = LoggerFactory.getLogger(MemoryManager::class.java)
    // 内部类：记忆上下文
    internal data class MemoryContext(
        var systemPrompt: String? = null,
        val messages: MutableList<ChatMessage> = Collections.synchronizedList(mutableListOf())
    )


    // L1 Cache: 互斥锁保护的 LRU 缓存
    private val cacheLock = Mutex()
    private val cache = LruMemoryCache<MemoryContext>(maxMemoryCount) { evictedId, evictedContext ->
        // 驱逐回调：启动协程执行兜底持久化
        persistenceScope.launch {
            try {
                storage.saveFullContext(evictedId, evictedContext.systemPrompt, evictedContext.messages)
            } catch(e: CancellationException) {
                throw e
            }catch (e: IOException) {
                // 2. I/O 异常：这是持久化层最常见的错误（如磁盘满、文件锁、网络断开）
                // 我们可以记录更具体的日志信息
                logger.error("[StreamLLM] I/O error while persisting evicted memory '{}': {}", evictedId, e.message)
            }catch (e: Exception) {
                @Suppress("TooGenericExceptionCaught")
                logger.error("[StreamLLM] Failed to persist evicted memory '{}'",evictedId,e)
            }
        }
    }
    // 预加载任务注册表 (防止缓存击穿)
    private val loadingTasks = ConcurrentHashMap<String, Deferred<Unit>>()

    @Volatile
    private var currentMemoryId = "default"

    // --- 记忆管理接口 ---
    /**
    +     * 预加载指定的记忆体到缓存中。
    +     * 建议在切换会话前（如列表点击事件）调用。
    +     */
    suspend fun preLoad(memoryId: String) {
        val needsLoad = cacheLock.withLock { !cache.containsKey(memoryId) }
        if (needsLoad) {
            getOrStartLoadingTask(memoryId).join()
        }
    }

    /**
     * 创建一个新的记忆体。
     *
     * 如果指定名称的记忆体已存在，且提供了新的 [systemPrompt]，则更新该记忆体的 System Prompt。
     *
     * @param name 记忆体的唯一标识名称。
     * @param systemPrompt (可选) 该记忆体的默认 System Prompt (人设/指令)。
     */
    suspend fun createMemory(name: String, systemPrompt: String? = null) {
        cacheLock.withLock {
            val context = cache.computeIfAbsent(name) { MemoryContext() }
            if (systemPrompt != null) {
                context.systemPrompt = systemPrompt
            }
        }
        // 异步持久化
        persistenceScope.launch {
            if (systemPrompt != null) {
                storage.setSystemPrompt(name, systemPrompt)
            }
        }
    }

    /**
     * 获取或启动加载任务 (Singleflight 模式)。
     */
    private fun getOrStartLoadingTask(id: String): Deferred<Unit> {
        return loadingTasks.computeIfAbsent(id) {
            persistenceScope.async {
                try {
                    val sys = storage.getSystemPrompt(id)
                    val msgs = storage.getMessages(id)
                    val context = MemoryContext(sys, Collections.synchronizedList(msgs.toMutableList()))

                    cacheLock.withLock {
                        cache[id] = context
                    }
                } finally {
                    loadingTasks.remove(id)
                }
            }
        }
    }


    /**
     * 切换当前活动的记忆体。
     * @param name 目标记忆体的名称。
     *
     */
    suspend fun switchMemory(name: String) {
        // 1. 尝试从缓存获取 (无需加载)
        val inCache = cacheLock.withLock { cache.containsKey(name) }
        if (inCache) {
            currentMemoryId = name
            return
        }
        // 2. 等待加载任务 (复用 preLoad 逻辑)
        getOrStartLoadingTask(name).await()
        currentMemoryId = name
    }

    /**
     * 删除指定的记忆体。
     *
     * @param name 要删除的记忆体名称。
     * @throws IllegalStateException 如果尝试删除当前正在使用的记忆体。
     */
    suspend fun deleteMemory(name: String) {
        if (name == currentMemoryId) {
            throw IllegalStateException("Cannot delete the currently active memory ('$name'). Please switch first.")
        }
        cacheLock.withLock {
            cache.remove(name)
        }
        persistenceScope.launch {
            storage.deleteMemory(name)
        }
    }

    /**
     * 更新指定记忆体的 System Prompt。
     *
     * @param name 记忆体名称。
     * @param prompt 新的 System Prompt。
     */
    suspend fun updateSystemPrompt(name: String, prompt: String?) {
        cacheLock.withLock {
            cache[name]?.systemPrompt = prompt
        }
        persistenceScope.launch {
            if (prompt != null) {
                storage.setSystemPrompt(name, prompt)
            }
        }
    }

    // --- 消息操作接口 (针对当前记忆体) ---

    /**
     * 向当前活动的记忆体中添加一条消息。
     *
     * @param role 角色。
     * @param content 内容。
     */
    suspend fun addMessage(role: ChatRole, content: String) {
        val message = ChatMessage(role, content)

        // 1. Write-Through: 先写内存
        cacheLock.withLock {
            val context = cache.computeIfAbsent(currentMemoryId) { MemoryContext() }
            context.messages.add(message)
        }

        // 2. Async Persist: 后台写库
        persistenceScope.launch {
            storage.addMessage(currentMemoryId, message)
        }
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
    suspend fun getCurrentHistory(
        windowSize: Int = -1,
        tempSystem: String? = null,
        includeSystem: Boolean = true
    ): List<ChatMessage> {
        // 必须加锁读取，因为 accessOrder LRU 会修改链表
        val context = cacheLock.withLock { cache[currentMemoryId] }

        // 1. 处理窗口切片 (只针对 User/Assistant 对话历史)
        // 注意：需在锁外操作副本或确保线程安全，这里 messages 是 synchronizedList，所以还可以
        val rawHistory = context?.messages ?: emptyList()
        val slicedHistory = when {
            windowSize < 0 -> rawHistory.toList() // 全部
            windowSize == 0 -> emptyList()        // 无历史
            else -> rawHistory.takeLast(windowSize) // 最近 N 条
        }

        if (!includeSystem) {
            return slicedHistory
        }

        // 2. 处理 System Prompt (临时覆盖 > 记忆体自带)
        val activeSystem = tempSystem ?: context?.systemPrompt

        return if (!activeSystem.isNullOrBlank()) {
            listOf(ChatMessage(ChatRole.SYSTEM, activeSystem)) + slicedHistory
        } else {
            slicedHistory
        }
    }

    /**
     * 清空当前活动记忆体中的所有对话历史（保留 System Prompt）。
     */
    suspend fun clear() {
        cacheLock.withLock {
            cache[currentMemoryId]?.messages?.clear()
        }
        persistenceScope.launch {
            storage.clearMessages(currentMemoryId)
        }
    }
}