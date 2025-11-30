package dev.lockedfog.streamllm.provider.openai

import dev.lockedfog.streamllm.core.GenerationOptions
import dev.lockedfog.streamllm.provider.LlmProvider
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.time.Duration

class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultModel: String,
    timeout: Duration = Duration.ofSeconds(60)
) : LlmProvider {

    // 配置 Ktor Client
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            })
        }
        engine {
            config {
                connectTimeout(timeout)
                readTimeout(timeout)
                writeTimeout(timeout)
            }
        }
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    // --- 统一构建请求体 ---
    private fun createRequest(prompt: String, stream: Boolean, options: GenerationOptions?): OpenAiChatRequest {
        return OpenAiChatRequest(
            model = options?.modelNameOverride ?: defaultModel,
            messages = listOf(OpenAiMessage("user", prompt)), // 这里简化了，实际应该对接 MemoryManager
            stream = stream,
            temperature = options?.temperature,
            topP = options?.topP,
            maxTokens = options?.maxTokens,
            stop = options?.stopSequences
        )
    }

    // --- 实现 Chat (非流式) ---
    override suspend fun chat(
        prompt: String,
        options: GenerationOptions?,
        onToken: ((String) -> Unit)?
    ): String {
        // 如果有回调，自动切换到流式模式
        if (onToken != null) {
            val sb = StringBuilder()
            stream(prompt, options).collect { token ->
                onToken(token)
                sb.append(token)
            }
            return sb.toString()
        }

        // 普通请求
        val requestBody = createRequest(prompt, stream = false, options)

        val response = client.post("$baseUrl/chat/completions") { // 注意：有些 base url 可能自带 /chat/completions，需要适配
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body<OpenAiChatResponse>()

        return response.choices.firstOrNull()?.message?.content ?: ""
    }

    // --- 实现 Stream (流式 - 核心难点) ---
    override fun stream(prompt: String, options: GenerationOptions?): Flow<String> = flow {
        val requestBody = createRequest(prompt, stream = true, options)

        client.preparePost("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            header("Accept", "text/event-stream")
            header("Cache-Control", "no-cache")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.execute { httpResponse ->
            val channel: ByteReadChannel = httpResponse.bodyAsChannel()

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

                // SSE 格式解析: "data: {JSON}"
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break // 结束标志
                    if (data.isBlank()) continue

                    try {
                        val chunk = jsonParser.decodeFromString<OpenAiStreamChunk>(data)
                        val content = chunk.choices.firstOrNull()?.delta?.content
                        if (!content.isNullOrEmpty()) {
                            emit(content) // 发送给 Flow
                        }
                    } catch (e: Exception) {
                        // 忽略解析错误（比如 keep-alive 包）
                    }
                }
            }
        }
    }
}