package dev.lockedfog.streamllm.provider

import dev.lockedfog.streamllm.core.GenerationOptions
import kotlinx.coroutines.flow.Flow

interface LlmProvider {
    suspend fun chat(
        prompt: String,
        options: GenerationOptions? = null,
        onToken: ((String) -> Unit)?= null
    ): String

    fun stream(
        prompt: String,
        options: GenerationOptions? = null
    ): Flow<String>
}