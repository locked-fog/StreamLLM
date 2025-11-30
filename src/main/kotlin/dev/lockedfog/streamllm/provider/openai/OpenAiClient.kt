package dev.lockedfog.streamllm.provider.openai

import dev.lockedfog.streamllm.core.ChatMessage
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
import org.slf4j.LoggerFactory
import java.time.Duration

class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultModel: String,
    timeout: Duration = Duration.ofSeconds(60)
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(OpenAiClient::class.java)

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
    private fun createRequest(messages: List<ChatMessage>, stream: Boolean, options: GenerationOptions?): OpenAiChatRequest {
        // 将通用的 ChatMessage 转换为 OpenAiMessage
        val openAiMessages = messages.map { OpenAiMessage(it.role, it.content) }

        return OpenAiChatRequest(
            model = options?.modelNameOverride ?: defaultModel,
            messages = openAiMessages,
            stream = stream,
            temperature = options?.temperature,
            topP = options?.topP,
            maxTokens = options?.maxTokens,
            stop = options?.stopSequences
        )
    }

    // --- 实现 Chat (非流式) ---
    override suspend fun chat(
        messages: List<ChatMessage>,
        options: GenerationOptions?,
        onToken: ((String) -> Unit)?
    ): String {
        // 如果有回调，自动切换到流式模式
        if (onToken != null) {
            val sb = StringBuilder()
            stream(messages, options).collect { token ->
                onToken(token)
                sb.append(token)
            }
            return sb.toString()
        }

        // 普通请求
        val requestBody = createRequest(messages, stream = false, options)

        val response = client.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Chat Request Failed [{}]: {}", response.status, errorBody)
            throw IllegalStateException("Chat request failed: ${response.status} - $errorBody")
        }

        val chatResponse = response.body<OpenAiChatResponse>()
        return chatResponse.choices.firstOrNull()?.message?.content ?: ""
    }

    // --- 实现 Stream (流式) ---
    override fun stream(messages: List<ChatMessage>, options: GenerationOptions?): Flow<String> = flow {
        val requestBody = createRequest(messages, stream = true, options)

        try {
            client.preparePost("$baseUrl/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                header("Accept", "text/event-stream")
                header("Cache-Control", "no-cache")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { httpResponse ->
                // 1. 检查 HTTP 状态码
                if (!httpResponse.status.isSuccess()) {
                    val errorBody = httpResponse.bodyAsText()
                    logger.error("❌ Stream API Error [{}]: {}", httpResponse.status, errorBody)
                    throw IllegalStateException("Stream request failed: ${httpResponse.status} - $errorBody")
                }

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

                            // 2. 检查是否有业务错误
                            if (chunk.error != null) {
                                logger.error("⚠️ Stream API Error: {}", chunk.error.message)
                                throw IllegalStateException("Stream API Error: ${chunk.error.message}")
                            }

                            // 3. 正常提取内容
                            val content = chunk.choices?.firstOrNull()?.delta?.content
                            if (!content.isNullOrEmpty()) {
                                emit(content) // 发送给 Flow
                            }
                        } catch (e: Exception) {
                            logger.debug("⚠️ JSON Parse Warning: {} | Data: {}", e.message, data)
                        }
                    }
                    // 兼容非 SSE 格式的错误返回
                    else if (line.trim().startsWith("{") && line.contains("\"error\"")) {
                        logger.error("❌ Raw JSON Error in stream: {}", line)
                        throw IllegalStateException("Raw JSON Error: $line")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("🚨 Stream Request Exception: {}", e.message)
            throw e
        }
    }
}