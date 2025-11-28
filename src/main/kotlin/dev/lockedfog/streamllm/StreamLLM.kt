package dev.lockedfog.streamllm

import dev.lockedfog.streamllm.provider.LangChainAdapter
import dev.lockedfog.streamllm.provider.LlmProvider
import kotlinx.serialization.json.Json
import java.time.Duration

object StreamLLM {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    var defaultProvider: LlmProvider? = null

    fun init(provider: LlmProvider) {
        this.defaultProvider = provider
    }

    fun init(baseUrl: String, apiKey: String, modelName: String, timeoutSeconds: Long = 60) {
        val adapter = LangChainAdapter(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModelName = modelName,
            defaultTimeout = Duration.ofSeconds(timeoutSeconds)
        )

        this.init(adapter)
    }
}