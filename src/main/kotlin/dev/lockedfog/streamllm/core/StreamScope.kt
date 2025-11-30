@file:Suppress("unused")

package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.utils.HistoryFormatter
import dev.lockedfog.streamllm.utils.JsonSanitizer
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException

class StreamScope {
    @PublishedApi
    internal val logger = LoggerFactory.getLogger(StreamScope::class.java)

    suspend fun String.ask(
        promptTemplate: String = "",
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1, // -1: All, 0: None, N: Last N
        system: String? = null,  // 临时 System Prompt 覆盖
        formatter: String? = null, // 手动格式化配置
        options: GenerationOptions? = null,
        onToken: ((String) -> Unit)? = null
    ): String {
        val provider = StreamLLM.defaultProvider ?: throw IllegalStateException("StreamLLM Not Initialized")

        // 1. 计算是否需要读取历史
        val shouldReadHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.ReadOnly
        val shouldWriteHistory = strategy == MemoryStrategy.ReadWrite || strategy == MemoryStrategy.WriteOnly

        // 2. 冲突检测
        if (!shouldReadHistory && promptTemplate.contains("{{history}}")) {
            throw IllegalArgumentException("Conflict: Template contains '{{history}}' but strategy is '$strategy' (which ignores history).")
        }

        // 3. 准备消息列表 (Messages to Send)
        var finalInput = this
        val messagesToSend = mutableListOf<ChatMessage>()

        // 3.1 处理模版
        if (promptTemplate.isNotBlank()) {
            finalInput = promptTemplate.replace("{{it}}", this)

            // 3.2 手动 History 注入 (Manual Mode)
            if (promptTemplate.contains("{{history}}")) {
                // 获取历史 (不包含 System, 因为我们通常希望 System 保持独立，或者由 Formatter 决定)
                // 这里的策略是：Manual Mode 下，{{history}} 仅代表对话流。System Prompt 单独作为消息发送。
                val historyList = StreamLLM.memory.getCurrentHistory(historyWindow, system, includeSystem = false)

                // 解析 Formatter
                val historyFormatter = if (formatter != null) {
                    HistoryFormatter.fromString(formatter)
                } else {
                    HistoryFormatter.DEFAULT
                }

                val historyText = historyFormatter.format(historyList)
                finalInput = finalInput.replace("{{history}}", historyText)

                // 组装消息: [System(如有), User(finalInput)]
                // 注意：这里我们需要单独获取 System Prompt
                val activeSystem = system ?: StreamLLM.memory.getCurrentHistory(0, null, true).firstOrNull { it.role == "system" }?.content
                if (!activeSystem.isNullOrBlank()) {
                    messagesToSend.add(ChatMessage("system", activeSystem))
                }
            }
        }

        // 3.3 自动 History 注入 (Auto Mode)
        // 条件：需要读历史 && 模版中没有手动指定 {{history}}
        if (shouldReadHistory && !promptTemplate.contains("{{history}}")) {
            // 获取完整历史 (包含 System)
            val history = StreamLLM.memory.getCurrentHistory(windowSize = historyWindow, tempSystem = system, includeSystem = true)
            messagesToSend.addAll(history)
        } else if (!promptTemplate.contains("{{history}}")) {
            // 不读历史 (Stateless/WriteOnly)，但可能有 System Prompt
            val activeSystem = system ?: StreamLLM.memory.getCurrentHistory(0, null, true).firstOrNull { it.role == "system" }?.content
            if (!activeSystem.isNullOrBlank()) {
                messagesToSend.add(ChatMessage("system", activeSystem))
            }
        }

        // 4. 添加当前用户消息
        // 注意：如果是 Manual Mode，历史已经变成了文本 merge 进了 finalInput，所以这里只添加一条 User 消息是正确的。
        messagesToSend.add(ChatMessage("user", finalInput))

        // 5. 记忆：记录 User 原始输入 (避免记录被模版污染的内容，保持记忆纯净)
        if (shouldWriteHistory) {
            StreamLLM.memory.addMessage("user", this)
        }

        // 6. 调用 Provider
        val response = provider.chat(messagesToSend, options, onToken)

        // 7. 记忆：记录 AI 回复
        if (shouldWriteHistory) {
            StreamLLM.memory.addMessage("assistant", response)
        }

        return response
    }

    // --- 重载方法适配 (Forwarding calls) ---

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
        onToken: ((String) -> Unit)? = null
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
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int? = null,
        model: String? = null,
        stop: List<String>? = null,
        onToken: ((String) -> Unit) = { token ->
            print(token)
            Thread.sleep(10)
        }
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
        block: (String) -> Unit = { token ->
            print(token)
            Thread.sleep(10)
        }
    ): String {
        return this.ask(promptTemplate, strategy, historyWindow, system, formatter, options, block)
    }

    inline fun <reified T> String.to(): T {
        val jsonString = JsonSanitizer.sanitize(this)
        return try {
            StreamLLM.json.decodeFromString<T>(jsonString)
        } catch (e: Exception) {
            logger.error("Could not parse JSON. Original data:\n{}", this, e)
            throw e
        }
    }

    suspend inline fun <reified T> String.ask(
        promptTemplate: String = "",
        strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
        historyWindow: Int = -1,
        system: String? = null,
        formatter: String? = null,
        maxRetries: Int = 3,
        options: GenerationOptions? = null
    ): T {
        // 第一次尝试
        var currentResponse = this.ask(promptTemplate, strategy, historyWindow, system, formatter, options)

        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                return currentResponse.to<T>()
            } catch (e: Exception) {
                attempt++
                if (attempt > maxRetries) throw e

                logger.warn("⚠️ [Auto-Fix] Retrying {}/{}...", attempt, maxRetries)

                // 纠错时，强制使用低温度
                val fixOptions = (options ?: GenerationOptions()).copy(temperature = 0.1)

                // 纠错 Prompt: 需要把上次的错误 JSON 和错误信息带上
                // 注意：纠错通常不应该依赖之前的对话历史，或者应该把之前的 JSON 作为 Context。
                // 简单起见，这里作为一个无状态的单独请求
                val fixPrompt = "Previous JSON invalid: ${e.message}. Return ONLY JSON.\nOriginal: $currentResponse"

                // 调用 Provider (直接构造 List)
                // 这里的纠错逻辑是独立的，不应该污染主记忆
                val messages = listOf(ChatMessage("user", fixPrompt))

                currentResponse = StreamLLM.defaultProvider!!.chat(messages, fixOptions)
            }
        }
        throw IllegalStateException("Unreachable")
    }

    fun Any.print() {
        logger.info("[StreamLLM] {}", this)
    }

    fun clearMemory() {
        StreamLLM.memory.clear()
    }

    // --- 记忆管理 DSL ---

    fun newMemory(name: String, system: String? = null) {
        StreamLLM.memory.createMemory(name, system)
        StreamLLM.memory.switchMemory(name)
    }

    fun switchMemory(name: String) {
        StreamLLM.memory.switchMemory(name)
    }

    fun deleteMemory(name: String) {
        StreamLLM.memory.deleteMemory(name)
    }

    fun setSystemPrompt(name: String, prompt: String) {
        StreamLLM.memory.updateSystemPrompt(name = name, prompt = prompt)
    }
}