package dev.lockedfog.streamllm.provider

import dev.lockedfog.streamllm.core.ChatMessage
import dev.lockedfog.streamllm.core.GenerationOptions
import kotlinx.coroutines.flow.Flow

interface LlmProvider {
    suspend fun chat(
        messages: List<ChatMessage>,
        options: GenerationOptions? = null,
        onToken: ((String) -> Unit)?= null
    ): String

    fun stream(
        messages: List<ChatMessage>,
        options: GenerationOptions? = null
    ): Flow<String>
}