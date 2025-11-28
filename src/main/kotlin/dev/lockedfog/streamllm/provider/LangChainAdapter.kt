package dev.lockedfog.streamllm.provider

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.StreamingResponseHandler
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.output.Response
import dev.lockedfog.streamllm.core.GenerationOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Duration

class LangChainAdapter(
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultModelName: String,
    private val defaultTimeout: Duration = Duration.ofSeconds(60)
) : LlmProvider {

    private fun buildBlockingModel(options: GenerationOptions?): OpenAiChatModel {
        return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(options?.modelNameOverride ?: defaultModelName)
            .timeout(defaultTimeout)
            .apply {
                options?.temperature?.let { temperature(it)}
                options?.topP?.let{ topP(it)}
                options?.maxTokens?.let{maxTokens(it)}
                options?.frequencyPenalty?.let { frequencyPenalty(it)}
                options?.presencePenalty?.let { presencePenalty(it)}
                options?.stopSequences?.let { stop(it)}
            }
            .build()
    }

    private fun buildStreamingModel(options: GenerationOptions?): OpenAiStreamingChatModel {
        return OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(options?.modelNameOverride ?: defaultModelName)
            .timeout(defaultTimeout)
            .apply {
                options?.temperature?.let { temperature(it)}
                options?.topP?.let{ topP(it)}
                options?.maxTokens?.let{maxTokens(it)}
                options?.frequencyPenalty?.let { frequencyPenalty(it)}
                options?.presencePenalty?.let { presencePenalty(it)}
                options?.stopSequences?.let { stop(it)}
            }
            .build()
    }

    override suspend fun chat(
        prompt: String,
        options: GenerationOptions?,
        onToken: ((String) -> Unit)?
    ): String {
        // 1. 如果没有 onToken，走同步阻塞模式
        if (onToken == null) {
            val model = buildBlockingModel(options)
            return model.generate(prompt)
        }

        // 2. 如果有 onToken，走流式模式
        val sb = StringBuilder()
        stream(prompt, options).collect { token ->
            onToken(token)
            sb.append(token)
        }
        return sb.toString()
    }

    override fun stream(
        prompt: String,
        options: GenerationOptions?
    ): Flow<String> = callbackFlow {
        val model = buildStreamingModel(options)

        model.generate(prompt, object : StreamingResponseHandler<AiMessage> {
            override fun onNext(token: String) {
                trySend(token)
            }
            override fun onComplete(response: Response<AiMessage>) {
                close()
            }
            override fun onError(error: Throwable) {
                close(error)
            }
        })
        awaitClose { }
    }
}