package dev.lockedfog.streamllm

import dev.lockedfog.streamllm.core.MemoryManager
import dev.lockedfog.streamllm.provider.LlmProvider
import dev.lockedfog.streamllm.provider.openai.OpenAiClient
import kotlinx.serialization.json.Json
import java.time.Duration

object StreamLLM {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    val memory = MemoryManager()

    @Volatile
    var defaultProvider: LlmProvider? = null

    fun init(provider: LlmProvider) {
        this.defaultProvider = provider
    }

    fun init(baseUrl: String, apiKey: String, modelName: String, timeoutSeconds: Long = 60) {
        val adapter = OpenAiClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = modelName,
            timeout = Duration.ofSeconds(timeoutSeconds)
        )

        this.init(adapter)
    }
}