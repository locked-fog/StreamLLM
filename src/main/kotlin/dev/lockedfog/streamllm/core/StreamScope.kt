package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.utils.JsonSanitizer
import java.lang.IllegalStateException

class StreamScope {
    val memory = MemoryManager()

    suspend fun String.ask(
        promptTemplate: String = "",
        options: GenerationOptions? = null,
        onToken: ((String) -> Unit)? = null
    ): String {
        val provider = StreamLLM.defaultProvider ?: throw IllegalStateException("StreamLLM 未初始化")

        // 1. 处理模版
        val currentInput = if (promptTemplate.isBlank()) this else promptTemplate.replace("{{it}}", this)
        val finalPrompt = memory.buildPrompt(currentInput)

        // 2. 调用 Provider
        val response = provider.chat(finalPrompt, options, onToken)

        memory.addMessage("User",currentInput)
        memory.addMessage("LLM",response)

        return response
    }

    suspend fun String.ask(
        promptTemplate: String = "",
        // 展平的参数
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int? = null,
        model: String? = null,
        stop: List<String>? = null,
        // 回调
        onToken: ((String) -> Unit)? = null
    ): String {
        // 自动组装
        val opts = GenerationOptions(
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            modelNameOverride = model,
            stopSequences = stop
        )
        return this.ask(promptTemplate, opts, onToken)
    }

    suspend fun String.stream(
        promptTemplate: String = "",
        // 展平的参数
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int? = null,
        model: String? = null,
        stop: List<String>? = null,
        // 回调
        onToken: ((String) -> Unit) = {token ->
            print(token)
            Thread.sleep(10)
        }
    ): String {
        // 自动组装
        val opts = GenerationOptions(
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            modelNameOverride = model,
            stopSequences = stop
        )
        return this.stream(promptTemplate, opts, onToken)
    }

    suspend fun String.stream(
        promptTemplate: String = "",
        options: GenerationOptions? = null,
        block: (String) -> Unit = {token ->
            print(token)
            Thread.sleep(10)
        }
    ):String {
        return this.ask(options = options, onToken = block)
    }

    suspend inline fun <reified T> String.to(): T{
        val jsonString = JsonSanitizer.sanitize(this)
        return try{
            StreamLLM.json.decodeFromString<T>(jsonString)
        } catch (e: Exception){
            println("Could not parse JSON, original data:\n $this")
            throw e
        }
    }

    suspend inline fun <reified T> String.ask(
        promptTemplate: String = "",
        maxRetries: Int = 3,
        options: GenerationOptions? = null
    ): T {
        // 第一次尝试
        var currentResponse = this.ask(promptTemplate, options)

        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                return currentResponse.to<T>()
            } catch (e: Exception) {
                attempt++
                if (attempt > maxRetries) throw e

                println("⚠️ [自动纠错] 第 $attempt 次重试...")

                // 纠错时，强制使用低温度 (copy 原配置并修改 temperature)
                val fixOptions = (options ?: GenerationOptions()).copy(temperature = 0.1)

                val fixPrompt = "Previous JSON invalid: ${e.message}. Return ONLY JSON.\nOriginal: $currentResponse"

                // 绕过 Memory，直接调用 Provider
                currentResponse = StreamLLM.defaultProvider!!.chat(fixPrompt, fixOptions)
            }
        }
        throw IllegalStateException("Unreachable")
    }

    fun Any.print() {
        println("[StreamLLM] $this")
    }

    fun clearMemory() {
        memory.clear()
    }
}