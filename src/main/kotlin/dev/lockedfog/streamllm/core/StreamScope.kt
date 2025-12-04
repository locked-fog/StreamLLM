package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.utils.HistoryFormatter
import dev.lockedfog.streamllm.utils.JsonSanitizer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException

/**
 * StreamLLM 的 DSL 执行上下文作用域。
 *
 * 提供了用于编排 LLM 对话的核心方法（ask, stream），以及管理记忆和上下文的辅助函数。
 * 通常在 `stream { ... }` 块中使用此类。
 */
class StreamScope {
    @PublishedApi
    internal val logger = LoggerFactory.getLogger(StreamScope::class.java)

    /**
     * 获取最近一次请求的 Token 用量统计。
     *
     * 如果请求支持返回 Usage 信息，该字段会在请求完成后更新。
     */
    var lastUsage: Usage? = null
        private set

    /**
     * 发送 LLM 请求的核心方法。
     *
     * 根据提供的参数构建 Prompt，处理历史记录注入，并调用底层的 Provider 发送请求。
     *
     * @param promptTemplate 提示词模版。支持 `{{it}}` (代表当前字符串内容) 和 `{{history}}` (手动注入历史) 占位符。
     * @param strategy 记忆策略。控制是否读取历史上下文以及是否记录本次对话。默认为 [MemoryStrategy.ReadWrite]。
     * @param historyWindow 历史窗口大小。-1 代表全部，0 代表无，N 代表最近 N 条。默认为 -1。
     * @param system 临时 System Prompt。如果提供，将覆盖当前记忆体的默认设定。
     * @param formatter 自定义历史记录格式化器字符串。仅在 [promptTemplate] 包含 `{{history}}` 时生效。
     * @param options 生成选项 (Temperature, MaxTokens 等)。
     * @param onToken 流式回调。如果提供此参数，请求将以流式 (Streaming) 方式执行，每收到一个 Token 回调一次。
     * 该回调是 suspend 函数，支持在其中执行耗时挂起操作。
     * @return 完整的响应文本。
     */
    suspend fun String.ask(
        promptTemplate: String = "",
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        formatter: String? = null,
        options: GenerationOptions? = null,
        onToken: (suspend (String) -> Unit)? = null
    ): String {
        val provider = StreamLLM.defaultProvider ?: throw IllegalStateException("StreamLLM Not Initialized")

        // 1. 计算策略
        val shouldReadHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.ReadOnly
        val shouldWriteHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.WriteOnly

        if (!shouldReadHistory && promptTemplate.contains("{{history}}")) {
            throw IllegalArgumentException("Conflict: Template contains '{{history}}' but strategy is '$strategy'.")
        }

        // 2. 准备消息
        var finalInput = this
        val messagesToSend = mutableListOf<ChatMessage>()

        // 2.1 模板与手动注入
        if (promptTemplate.isNotBlank()) {
            finalInput = promptTemplate.replace("{{it}}", this)
            if (promptTemplate.contains("{{history}}")) {
                val historyList = StreamLLM.memory.getCurrentHistory(historyWindow, system, includeSystem = false)
                val historyFormatter = if (formatter != null) HistoryFormatter.fromString(formatter) else HistoryFormatter.DEFAULT
                val historyText = historyFormatter.format(historyList)
                finalInput = finalInput.replace("{{history}}", historyText)

                val activeSystem = system ?: StreamLLM.memory.getCurrentHistory(0, null, true)
                    .firstOrNull { it.role == ChatRole.SYSTEM }?.content
                if (!activeSystem.isNullOrBlank()) {
                    messagesToSend.add(ChatMessage(ChatRole.SYSTEM, activeSystem))
                }
            }
        }

        // 2.2 自动注入
        if (shouldReadHistory && !promptTemplate.contains("{{history}}")) {
            val history = StreamLLM.memory.getCurrentHistory(windowSize = historyWindow, tempSystem = system, includeSystem = true)
            messagesToSend.addAll(history)
        } else if (!promptTemplate.contains("{{history}}")) {
            val activeSystem = system ?: StreamLLM.memory.getCurrentHistory(0, null, true)
                .firstOrNull { it.role == ChatRole.SYSTEM }?.content
            if (!activeSystem.isNullOrBlank()) {
                messagesToSend.add(ChatMessage(ChatRole.SYSTEM, activeSystem))
            }
        }

        messagesToSend.add(ChatMessage(ChatRole.USER, finalInput))

        if (shouldWriteHistory) {
            StreamLLM.memory.addMessage(ChatRole.USER, this)
        }

        // 3. 调用 Provider
        var responseContent: String

        if (onToken != null) {
            val sb = StringBuilder()

            // --- 核心实现开始: 自适应批处理 (Adaptive Batching with Skipping) ---

            // 1. 数据流 (Buffer)：使用 StringBuffer (线程安全) 配合 synchronized 块保证原子性
            val streamBuffer = StringBuffer()

            // 2. 锁 (Lock)：控制 Lambda 的访问，确保同一时间只有一个 Lambda 在处理
            val mutex = Mutex()

            // 3. 定义处理函数：从 Stream 中“读取并清除”数据
            val processStream = suspend {
                val chunk = synchronized(streamBuffer) {
                    if (streamBuffer.isNotEmpty()) {
                        val content = streamBuffer.toString()
                        streamBuffer.setLength(0) // 清空流
                        content
                    } else {
                        null
                    }
                }

                // 如果拿到了数据，就调用用户的 Lambda
                if (!chunk.isNullOrEmpty()) {
                    onToken(chunk)
                    sb.append(chunk)
                }
            }

            // 使用 coroutineScope 确保所有子协程完成后才返回
            coroutineScope {
                // 启动网络消费者
                provider.stream(messagesToSend, options).collect { res ->
                    // A. 网络层：快速写入 Buffer
                    if (res.content.isNotEmpty()) {
                        synchronized(streamBuffer) {
                            streamBuffer.append(res.content)
                        }
                    }

                    // B. 尝试触发 Lambda：尝试获取锁 (TryLock)
                    // 如果获取成功，说明当前没有 Lambda 在运行，启动新协程处理
                    // 如果获取失败，说明 Lambda 正忙，直接跳过 (Skip)，让数据积压在 Buffer 中等待下一次处理
                    if (mutex.tryLock()) {
                        launch {
                            try {
                                processStream()
                            } finally {
                                mutex.unlock() // 处理完自动解锁
                            }
                        }
                    }

                    if (res.usage != null) {
                        this@StreamScope.lastUsage = res.usage
                    }
                }

                // --- Flush (处理最后的数据) ---
                // 流结束后，必须等待锁释放，并处理 Buffer 中残留的数据
                mutex.lock() // 挂起等待正在运行的 Lambda 结束
                try {
                    processStream() // 处理剩余数据 (Flush)
                } finally {
                    mutex.unlock()
                }
            }
            // --- 核心实现结束 ---

            responseContent = sb.toString()
        } else {
            // 非流式处理
            val llmResponse = provider.chat(messagesToSend, options)
            responseContent = llmResponse.content
            this@StreamScope.lastUsage = llmResponse.usage
        }

        // 4. 记录历史
        if (shouldWriteHistory) {
            StreamLLM.memory.addMessage(ChatRole.ASSISTANT, responseContent)
        }

        return responseContent
    }

    // --- 重载方法适配 ---

    /**
     * [ask] 的重载版本，将 [GenerationOptions] 的参数展开，方便调用。
     *
     * @see ask
     */
    suspend fun String.ask(
        promptTemplate: String = "",
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        formatter: String? = null,
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int? = null,
        model: String? = null,
        stop: List<String>? = null,
        onToken: (suspend (String) -> Unit)? = null
    ): String {
        val opts = GenerationOptions(
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            modelNameOverride = model,
            stopSequences = stop
        )
        return this.ask(promptTemplate, strategy, historyWindow, system, formatter, opts, onToken)
    }

    /**
     * 发送流式请求的快捷方法。
     *
     * 等同于调用 [ask] 并传入 [onToken] 回调。
     *
     * @param onToken 接收生成的每个 Token 的回调函数。
     * @return 完整的响应文本。
     */
    suspend fun String.stream(
        promptTemplate: String = "",
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        formatter: String? = null,
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int? = null,
        model: String? = null,
        stop: List<String>? = null,
        onToken: suspend (String) -> Unit = { }
    ): String {
        val opts = GenerationOptions(
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            modelNameOverride = model,
            stopSequences = stop
        )
        return this.ask(promptTemplate, strategy, historyWindow, system, formatter, opts, onToken)
    }

    /**
     * 发送流式请求的重载方法，接受挂起函数作为回调。
     */
    suspend fun String.stream(
        promptTemplate: String = "",
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        formatter: String? = null,
        options: GenerationOptions? = null,
        block: suspend (String) -> Unit = { }
    ): String {
        return this.ask(promptTemplate, strategy, historyWindow, system, formatter, options) { token ->
            block(token)
        }
    }

    /**
     * 将字符串直接反序列化为指定类型的对象。
     *
     * 内部会先使用 [JsonSanitizer] 清洗字符串（移除 Markdown 标记等）。
     */
    suspend inline fun <reified T> String.to(): T {
        val jsonString = JsonSanitizer.sanitize(this)
        return try {
            StreamLLM.json.decodeFromString<T>(jsonString)
        } catch (e: Exception) {
            logger.error("Could not parse JSON. Original data:\n{}", this, e)
            throw e
        }
    }

    /**
     * 结构化数据提取方法。
     *
     * 发送请求并将结果自动解析为类型 [T]。
     * 包含自动纠错机制：如果 JSON 解析失败，会自动将错误信息反馈给模型进行重试。
     *
     * @param maxRetries 最大自动重试次数。默认为 3。
     * @return 解析后的对象 [T]。
     * @throws IllegalStateException 如果重试多次后仍然失败。
     */
    suspend inline fun <reified T> String.ask(
        promptTemplate: String = "",
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        formatter: String? = null,
        maxRetries: Int = 3,
        options: GenerationOptions? = null
    ): T {
        var currentResponse = this.ask(promptTemplate, strategy, historyWindow, system, formatter, options)

        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                return currentResponse.to<T>()
            } catch (e: Exception) {
                // 仅针对序列化错误重试，网络错误/鉴权错误等 LlmException 直接抛出
                if (e is SerializationException || e is IllegalArgumentException) {
                    attempt++
                    if (attempt > maxRetries) throw e

                    logger.warn("[Auto-Fix] Retrying {}/{} due to JSON error: {}", attempt, maxRetries, e.message)

                    val fixOptions = (options ?: GenerationOptions()).copy(temperature = 0.1)
                    val fixPrompt = "Previous JSON invalid: ${e.message}. Return ONLY JSON. Original content: $currentResponse"
                    val messages = listOf(ChatMessage(ChatRole.USER, fixPrompt))

                    // 获取修正结果
                    currentResponse = StreamLLM.defaultProvider!!.chat(messages, fixOptions).content
                } else {
                    throw e
                }
            }
        }
        throw IllegalStateException("Unreachable")
    }

    /**
     * 打印日志的辅助方法。
     */
    fun Any.print() {
        logger.info("[StreamLLM] {}", this)
    }

    /**
     * 清空当前活动记忆体的快捷方法。
     */
    suspend fun clearMemory() {
        StreamLLM.memory.clear()
    }

    // --- 记忆管理 DSL ---

    /**
     * 创建并切换到新的记忆体。
     *
     * @param name 记忆体名称。
     * @param system (可选) 该记忆体的 System Prompt。
     */
    suspend fun newMemory(name: String, system: String? = null) {
        StreamLLM.memory.createMemory(name, system)
        StreamLLM.memory.switchMemory(name)
    }

    /**
     * 切换到已存在的记忆体。
     *
     * @param name 记忆体名称。
     */
    suspend fun switchMemory(name: String) {
        StreamLLM.memory.switchMemory(name)
    }

    /**
     * 删除指定的记忆体。
     *
     * @param name 记忆体名称。
     */
    suspend fun deleteMemory(name: String) {
        StreamLLM.memory.deleteMemory(name)
    }

    /**
     * 更新指定记忆体的 System Prompt。
     *
     * @param name 记忆体名称。
     * @param prompt 新的 System Prompt。
     */
    suspend fun setSystemPrompt(name: String, prompt: String) {
        StreamLLM.memory.updateSystemPrompt(name = name, prompt = prompt)
    }
}