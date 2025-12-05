package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.provider.LlmProvider
import dev.lockedfog.streamllm.utils.HistoryFormatter
import dev.lockedfog.streamllm.utils.JsonSanitizer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException

/**
 * StreamLLM 的 DSL 执行上下文作用域。
 *
 * 提供了用于编排 LLM 对话的核心方法（ask, stream），管理记忆，以及注册和执行工具（Tool Calling）。
 * 通常在 `stream { ... }` 块中使用此类。
 *
 * @property maxToolRounds 最大工具递归轮数。防止模型陷入无限调用工具的死循环。默认为 5。
 */
class StreamScope(
    private val maxToolRounds: Int = 5
) {
    @PublishedApi
    internal val logger = LoggerFactory.getLogger(StreamScope::class.java)

    /**
     * 获取最近一次请求的 Token 用量统计。
     * 如果请求支持返回 Usage 信息，该字段会在请求完成后更新。
     */
    var lastUsage: Usage? = null
        private set

    // --- 工具调用相关状态 ---

    // 已注册的工具定义 (发送给 LLM 用)
    private val registeredTools = mutableListOf<Tool>()

    // 工具执行逻辑映射 (函数名 -> 执行Lambda)
    private val toolExecutors = mutableMapOf<String, suspend (String) -> String>()

    /**
     * 注册一个工具 (Function Tool)。
     *
     * 注册后，该工具的信息会被添加到后续的 `ask` 或 `stream` 请求中。
     * 当模型决定调用此工具时，会自动执行 [executor] 并将结果反馈给模型。
     *
     * @param name 工具名称 (只能包含字母、数字、下划线, e.g., "get_weather")。
     * @param description 工具描述，告诉模型这个工具是做什么的。
     * @param parametersJson Schema 定义 (JSON String)，描述参数结构。建议使用 Kotlinx Serialization 生成或手动编写。
     * @param executor 工具的具体执行逻辑。接收 JSON 格式的参数字符串，返回 String 结果。
     */
    @Suppress("unused")
    fun registerTool(
        name: String,
        description: String? = null,
        parametersJson: String = "{}",
        executor: suspend (String) -> String
    ) {
        val paramsElement = try {
            StreamLLM.json.decodeFromString<JsonElement>(parametersJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON schema for tool '$name'", e)
        }

        val functionDef = FunctionDefinition(
            name = name,
            description = description,
            parameters = paramsElement
        )
        val tool = Tool(type = "function", function = functionDef)

        registeredTools.add(tool)
        toolExecutors[name] = executor
    }

    /**
     * 发送 LLM 请求的核心方法。
     *
     * 支持自动化的工具调用循环 (Re-Act Loop)：
     * 1. 发送 Prompt。
     * 2. 如果模型返回 Tool Calls，自动执行对应 Kotlin 函数。
     * 3. 将执行结果作为 Tool Message 存入记忆。
     * 4. 携带结果再次请求模型，直到模型输出最终文本。
     *
     * @param promptTemplate 提示词模版。支持 `{{it}}` (当前字符串) 和 `{{history}}` 占位符。
     * @param strategy 记忆策略。默认为 [MemoryStrategy.ReadWrite]。
     * @param historyWindow 历史窗口大小。-1 代表全部。
     * @param system 临时 System Prompt。
     * @param formatter 自定义历史记录格式化器。
     * @param options 生成选项 (Temperature, MaxTokens 等)。
     * @param onToken 流式回调。如果提供此参数，请求将以流式 (Streaming) 方式执行。
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
        // 1. 初始化消息与策略
        val (messages, shouldWriteHistory) = prepareContext(
            input = this,
            promptTemplate = promptTemplate,
            strategy = strategy,
            historyWindow = historyWindow,
            system = system,
            formatter = formatter
        )

        // 2. 合并 Options (注入已注册的 Tools)
        val effectiveOptions = mergeOptionsWithTools(options)

        // 3. 进入执行循环
        return executeLoop(
            currentMessages = messages,
            options = effectiveOptions,
            shouldWriteHistory = shouldWriteHistory,
            onToken = onToken
        )
    }

    /**
     * 内部执行循环：处理 "Chat -> Tool -> Chat" 的递归流程。
     */
    private suspend fun executeLoop(
        currentMessages: MutableList<ChatMessage>,
        options: GenerationOptions?,
        shouldWriteHistory: Boolean,
        onToken: (suspend (String) -> Unit)?
    ): String {
        val provider = StreamLLM.defaultProvider ?: throw IllegalStateException("StreamLLM Not Initialized")
        var round = 0
        var finalContent = ""

        while (round <= maxToolRounds) {
            // A. 流式处理
            if (onToken != null) {
                // 流式模式下的聚合与执行
                val (content, toolCalls) = executeStreamRequest(provider, currentMessages, options, onToken)
                finalContent = content

                // 如果没有工具调用，说明是最终回复，结束循环
                if (toolCalls.isEmpty()) break

                val assistantMsg = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = ChatContent.Text(content), // <--- 关键修复：保留生成的文本
                    toolCalls = toolCalls
                )
                currentMessages.add(assistantMsg)

                // 写入记忆
                if (shouldWriteHistory) {
                     StreamLLM.memory.addMessage(
                         role = ChatRole.ASSISTANT,
                         content = assistantMsg.content,
                         toolCalls = toolCalls)
                }

                // 处理工具调用 (流式聚合后的结果)
                handleToolCalls(toolCalls, currentMessages, shouldWriteHistory)
            }
            // B. 非流式处理
            else {
                val response = provider.chat(currentMessages.toList(), options)
                this.lastUsage = response.usage
                finalContent = response.content

                // 记录 Assistant 回复 (可能包含 content 和 tool_calls)
                val assistantMsg = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = ChatContent.Text(response.content), // 即使是空串也要放
                    toolCalls = response.toolCalls
                )
                currentMessages.add(assistantMsg)
                if (shouldWriteHistory) {
                    StreamLLM.memory.addMessage(assistantMsg.role,assistantMsg.content) // 需适配完整对象存储
                }

                // 检查是否需要执行工具
                if (response.toolCalls.isNullOrEmpty()) {
                    break // 正常结束
                }

                // 执行工具并追加结果到 currentMessages
                handleToolCalls(response.toolCalls, currentMessages, shouldWriteHistory)
            }

            round++
        }

        if (round > maxToolRounds) {
            logger.warn("Max tool rounds ($maxToolRounds) exceeded. Stopping execution.")
        }

        return finalContent
    }

    /**
     * 执行单次流式请求，并负责聚合 Content 和 ToolCalls。
     */
    private suspend fun executeStreamRequest(
        provider: LlmProvider,
        messages: List<ChatMessage>,
        options: GenerationOptions?,
        onToken: suspend (String) -> Unit
    ): Pair<String, List<ToolCall>> {
        val sb = StringBuilder()
        // 用于聚合分片的 ToolCalls: Index -> ToolCallBuilder
        val toolCallAccumulator = mutableMapOf<Int, ToolCallBuilder>()

        // 1. 数据流 (Buffer) + 2. 锁 (Lock) -> 实现背压与跳过 (Adaptive Batching)
        val streamBuffer = StringBuffer()
        val mutex = Mutex()

        val processStream = suspend {
            val chunk = synchronized(streamBuffer) {
                if (streamBuffer.isNotEmpty()) {
                    val content = streamBuffer.toString()
                    streamBuffer.setLength(0)
                    content
                } else null
            }
            if (!chunk.isNullOrEmpty()) {
                onToken(chunk)
                sb.append(chunk)
            }
        }

        coroutineScope {
            provider.stream(messages.toList(), options)
                .onCompletion {
                    // 流结束，强制 Flush 剩余文本
                    mutex.lock()
                    try { processStream() } finally { mutex.unlock() }
                }
                .collect { res ->
                    // A. 处理文本内容
                    if (res.content.isNotEmpty()) {
                        synchronized(streamBuffer) {
                            streamBuffer.append(res.content)
                        }
                        // 尝试触发回调 (Skip 机制)
                        if (mutex.tryLock()) {
                            launch {
                                try { processStream() } finally { mutex.unlock() }
                            }
                        }
                    }

                    // B. 聚合工具调用 (Tool Calls 也是流式的)
                    if (!res.toolCalls.isNullOrEmpty()) {
                        res.toolCalls.forEachIndexed { index, fragment ->
                            val builder = toolCallAccumulator.getOrPut(index) { ToolCallBuilder() }
                            // 累加各个字段
                            if (fragment.id.isNotEmpty()) builder.id = fragment.id
                            if (fragment.type.isNotEmpty()) builder.type = fragment.type
                            if (fragment.function.name.isNotEmpty()) builder.name = fragment.function.name
                            if (fragment.function.arguments.isNotEmpty()) builder
                                .arguments
                                .append(fragment
                                    .function
                                    .arguments)
                        }
                    }

                    if (res.usage != null) {
                        this@StreamScope.lastUsage = res.usage
                    }
                }
        }

        // 构建最终的 ToolCall 列表
        val finalToolCalls = toolCallAccumulator.values.map { it.build() }

        return sb.toString() to finalToolCalls
    }

    /**
     * 处理工具调用逻辑：执行函数 -> 记录结果 -> 更新消息列表。
     */
    private suspend fun handleToolCalls(
        toolCalls: List<ToolCall>,
        currentMessages: MutableList<ChatMessage>,
        shouldWriteHistory: Boolean
    ) {
        // 2. 并行/串行执行所有工具
        toolCalls.forEach { call ->
            val functionName = call.function.name
            val argsJson = call.function.arguments

            logger.info("Executing Tool: $functionName with args: $argsJson")

            val result = try {
                val executor = toolExecutors[functionName]
                    ?: throw IllegalStateException("Tool '$functionName' not registered.")
                executor(argsJson)
            } catch (e: Exception) {
                logger.error("Tool execution failed", e)
                "Error executing tool '$functionName': ${e.message}"
            }

            // 3. 构造 Tool Message
            val toolMsg = ChatMessage(
                role = ChatRole.TOOL,
                content = ChatContent.Text(result),
                toolCallId = call.id,
                name = functionName
            )
            currentMessages.add(toolMsg)

            if (shouldWriteHistory) {
                StreamLLM.memory.addMessage(
                    role = ChatRole.TOOL,
                    content = toolMsg.content,
                    toolCallId = call.id,
                    name = functionName
                )
            }
        }
    }

    // --- 辅助方法 ---

    private fun ToolCallBuilder.build(): ToolCall {
        return ToolCall(
            id = this.id ?: "",
            type = this.type ?: "function",
            function = FunctionCall(
                name = this.name ?: "",
                arguments = this.arguments.toString()
            )
        )
    }

    // 内部 Builder 类，用于聚合流式片段
    private class ToolCallBuilder {
        var id: String? = null
        var type: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private suspend fun prepareContext(
        input: String,
        promptTemplate: String,
        strategy: MemoryStrategy,
        historyWindow: Int,
        system: String?,
        formatter: String?
    ): Pair<MutableList<ChatMessage>, Boolean> {
        val shouldReadHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.ReadOnly
        val shouldWriteHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.WriteOnly

        if (!shouldReadHistory && promptTemplate.contains("{{history}}")) {
            throw IllegalArgumentException("Conflict: Template contains '{{history}}' but strategy is '$strategy'.")
        }

        var finalInput = input
        val messagesToSend = mutableListOf<ChatMessage>()

        // 模板与手动注入逻辑
        if (promptTemplate.isNotBlank()) {
            finalInput = promptTemplate.replace("{{it}}", input)
            if (promptTemplate.contains("{{history}}")) {
                val historyList = StreamLLM.memory.getCurrentHistory(historyWindow, system, includeSystem = false)
                val historyFormatter = if (formatter != null) HistoryFormatter.fromString(formatter) else HistoryFormatter.DEFAULT
                val historyText = historyFormatter.format(historyList)
                finalInput = finalInput.replace("{{history}}", historyText)

                val memorySystem = StreamLLM.memory.getCurrentHistory(0, null, true)
                    .firstOrNull { it.role == ChatRole.SYSTEM }?.content
                val activeSystemContent = if (system != null) ChatContent.Text(system) else memorySystem

                if (activeSystemContent != null && activeSystemContent.hasContent()) {
                    messagesToSend.add(ChatMessage(ChatRole.SYSTEM, activeSystemContent))
                }
            }
        }

        // 自动注入逻辑
        if (shouldReadHistory && !promptTemplate.contains("{{history}}")) {
            val history = StreamLLM.memory.getCurrentHistory(windowSize = historyWindow, tempSystem = system, includeSystem = true)
            messagesToSend.addAll(history)
        } else if (!promptTemplate.contains("{{history}}")) {
            // 即使不读历史，也要尝试带上 System Prompt
            val memorySystem = StreamLLM.memory.getCurrentHistory(0, null, true)
                .firstOrNull { it.role == ChatRole.SYSTEM }?.content
            val activeSystemContent = if (system != null) ChatContent.Text(system) else memorySystem

            if (activeSystemContent != null && activeSystemContent.hasContent()) {
                messagesToSend.add(ChatMessage(ChatRole.SYSTEM, activeSystemContent))
            }
        }

        messagesToSend.add(ChatMessage(ChatRole.USER, finalInput))

        if (shouldWriteHistory) {
            StreamLLM.memory.addMessage(ChatRole.USER, input)
        }

        return messagesToSend to shouldWriteHistory
    }

    private fun mergeOptionsWithTools(original: GenerationOptions?): GenerationOptions {
        val base = original ?: GenerationOptions()
        // 如果当前有注册工具，则将其加入 options
        return if (registeredTools.isNotEmpty()) {
            // 注意：这里我们假设 options.tools 是用户手动传的，我们做合并
            val combinedTools = (base.tools.orEmpty() + registeredTools).distinctBy { it.function.name }
            base.copy(tools = combinedTools)
        } else {
            base
        }
    }

    private fun ChatContent.hasContent(): Boolean {
        return when (this) {
            is ChatContent.Text -> this.text.isNotBlank()
            is ChatContent.Parts -> this.parts.isNotEmpty()
        }
    }

    // --- 重载方法适配 (保持 API 兼容) ---

    @Suppress("unused")
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

    @Suppress("unused")
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
     * 发送多模态消息 (非流式)。
     *
     * [v0.4.0 New] 允许直接发送图片、视频等复杂内容。
     * 注意：多模态请求不支持 Prompt Template 替换。
     */
    suspend fun ChatContent.ask(
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        options: GenerationOptions? = null,
        onToken: (suspend (String) -> Unit)? = null
    ): String {
        val (messages, shouldWriteHistory) = prepareContext(
            input = this,
            strategy = strategy,
            historyWindow = historyWindow,
            system = system
        )

        val effectiveOptions = mergeOptionsWithTools(options)

        return executeLoop(
            currentMessages = messages,
            options = effectiveOptions,
            shouldWriteHistory = shouldWriteHistory,
            onToken = onToken
        )
    }

    suspend fun ChatContent.stream(
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        options: GenerationOptions? = null,
        block: suspend (String) -> Unit
    ): String {
        return this.ask(strategy, historyWindow, system, options) { token ->
            block(token)
        }
    }

    private suspend fun prepareContext(
        input: ChatContent,
        strategy: MemoryStrategy,
        historyWindow: Int,
        system: String?
    ): Pair<MutableList<ChatMessage>, Boolean> {
        val shouldReadHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.ReadOnly
        val shouldWriteHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.WriteOnly

        val messagesToSend = mutableListOf<ChatMessage>()

        // 1. 自动注入历史
        if (shouldReadHistory) {
            val history = StreamLLM.memory.getCurrentHistory(windowSize = historyWindow, tempSystem = system, includeSystem = true)
            messagesToSend.addAll(history)
        } else {
            // 即使不读历史，也要尝试带上 System Prompt
            val memorySystem = StreamLLM.memory.getCurrentHistory(0, null, true)
                .firstOrNull { it.role == ChatRole.SYSTEM }?.content
            val activeSystemContent = if (system != null) ChatContent.Text(system) else memorySystem

            if (activeSystemContent != null && activeSystemContent.hasContent()) {
                messagesToSend.add(ChatMessage(ChatRole.SYSTEM, activeSystemContent))
            }
        }

        // 2. 添加当前多模态消息
        messagesToSend.add(ChatMessage(ChatRole.USER, input))

        if (shouldWriteHistory) {
            StreamLLM.memory.addMessage(ChatRole.USER, input)
        }

        return messagesToSend to shouldWriteHistory
    }

    /**
     * 将字符串直接反序列化为指定类型的对象。
     */
    inline fun <reified T> String.to(): T {
        val jsonString = JsonSanitizer.sanitize(this)
        return try {
            StreamLLM.json.decodeFromString<T>(jsonString)
        } catch (e: Exception) {
            logger.error("Could not parse JSON. Original data:\n{}", this, e)
            throw e
        }
    }

    /**
     * 结构化数据提取方法 (带自动重试)。
     */
    @Suppress("unused")
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
                if (e is SerializationException || e is IllegalArgumentException) {
                    attempt++
                    if (attempt > maxRetries) throw e
                    logger.warn("[Auto-Fix] Retrying {}/{} due to JSON error: {}", attempt, maxRetries, e.message)
                    val fixOptions = (options ?: GenerationOptions()).copy(temperature = 0.1)
                    val fixPrompt = "Previous JSON invalid: ${e.message}. Return ONLY JSON. Original content: $currentResponse"
                    val messages = listOf(ChatMessage(ChatRole.USER, fixPrompt))
                    currentResponse = StreamLLM.defaultProvider!!.chat(messages, fixOptions).content
                } else {
                    throw e
                }
            }
        }
        throw IllegalStateException("Unreachable")
    }

    // --- 记忆管理 DSL (保持不变) ---

    @Suppress("unused")
    suspend fun clearMemory() { StreamLLM.memory.clear() }

    @Suppress("unused")
    suspend fun newMemory(name: String, system: String? = null) {
        StreamLLM.memory.createMemory(name, system)
        StreamLLM.memory.switchMemory(name)
    }

    @Suppress("unused")
    suspend fun switchMemory(name: String) { StreamLLM.memory.switchMemory(name) }

    @Suppress("unused")
    suspend fun deleteMemory(name: String) { StreamLLM.memory.deleteMemory(name) }

    @Suppress("unused")
    suspend fun setSystemPrompt(name: String, prompt: String) {
        StreamLLM.memory.updateSystemPrompt(name = name, prompt = prompt)
    }
}
