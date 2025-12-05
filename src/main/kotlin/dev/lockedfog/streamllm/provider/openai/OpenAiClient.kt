package dev.lockedfog.streamllm.provider.openai

import dev.lockedfog.streamllm.core.*
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

/**
 * 基于 OpenAI API 标准协议的 [LlmProvider] 实现。
 *
 * 兼容所有支持 OpenAI 接口格式的厂商，如 OpenAI 官方、DeepSeek、SiliconFlow (硅基流动)、Moonshot 等。
 * 使用 Ktor HTTP Client 进行网络通信。
 *
 * @param baseUrl API 基础地址 (例如 "https://api.openai.com/v1" 或 "https://api.siliconflow.cn/v1")。会自动处理尾部斜杠。
 * @param apiKey API 鉴权密钥。
 * @param defaultModel 默认使用的模型名称 (例如 "gpt-4o", "deepseek-ai/DeepSeek-V3")。
 * @param timeout 请求超时时间。默认为 60 秒。
 * @param httpClient (可选) 外部注入的 Ktor [HttpClient]。如果提供，OpenAiClient 将复用该 Client 且不会在 close() 时关闭它。
 */
class OpenAiClient(
    baseUrl: String,
    private val apiKey: String,
    private val defaultModel: String,
    timeout: Duration = Duration.ofSeconds(60),
    httpClient: HttpClient? = null
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(OpenAiClient::class.java)

    // 标记是否负责管理 httpClient 的生命周期
    private val manageLifecycle = httpClient == null

    // 如果未提供外部 Client，则内部创建一个基于 OkHttp 的 Client
    private val client = httpClient ?: HttpClient(OkHttp) {
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

    // 预处理 Endpoint，防止双斜杠问题
    private val chatEndpoint = "${baseUrl.trimEnd('/')}/chat/completions"

    /**
     * 关闭 HTTP 客户端，释放资源。
     *
     * 仅当 HttpClient 是由 OpenAiClient 内部创建时，才会真正执行关闭操作。
     */
    override fun close() {
        if (manageLifecycle) {
            client.close()
            logger.debug("OpenAiClient resources released.")
        }
    }

    private fun createRequest(messages: List<ChatMessage>, stream: Boolean, options: GenerationOptions?): OpenAiChatRequest {
        val openAiMessages = messages.map {
            OpenAiMessage(
                role = it.role,
                content = it.content,
                name = it.name,
                toolCalls = it.toolCalls,
                toolCallId = it.toolCallId
            )
        }
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

    // --- 统一异常处理 ---
    private suspend fun handleHttpError(response: HttpResponse) {
        val errorBody = response.bodyAsText()
        logger.error("API Error [{}]: {}", response.status, errorBody)

        when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                throw AuthenticationException("Authentication failed: ${response.status} - $errorBody")

            HttpStatusCode.TooManyRequests ->
                throw RateLimitException("Rate limit exceeded: $errorBody")

            HttpStatusCode.BadRequest ->
                throw InvalidRequestException("Invalid request: $errorBody")

            HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable ->
                throw ServerException("Server error: ${response.status}")

            else ->
                throw UnknownLlmException("Unknown API error: ${response.status} - $errorBody")
        }
    }

    // --- 转换 Usage ---
    private fun OpenAiUsage.toUsage(): Usage {
        return Usage(
            promptTokens = this.promptTokens,
            completionTokens = this.completionTokens,
            totalTokens = this.totalTokens
        )
    }

    // --- 实现 Chat ---
    override suspend fun chat(
        messages: List<ChatMessage>,
        options: GenerationOptions?
    ): LlmResponse {
        val requestBody = createRequest(messages, stream = false, options)

        val response = client.post(chatEndpoint) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            handleHttpError(response)
        }

        val chatResponse = response.body<OpenAiChatResponse>()
        val choice = chatResponse.choices.firstOrNull()

        val contentString = when(val rawContent = choice?.message?.content){
            is ChatContent.Text -> rawContent.text
            is ChatContent.Parts -> rawContent.parts
                .filterIsInstance<ContentPart.TextPart>()
                .joinToString("") { it.text }
            null -> ""
        }
        val usage = chatResponse.usage?.toUsage()

        return LlmResponse(
            content = contentString,
            usage = usage,
            toolCalls = choice?.message?.toolCalls
        )
    }

    // --- 实现 Stream ---
    override fun stream(messages: List<ChatMessage>, options: GenerationOptions?): Flow<LlmResponse> = flow {
        val requestBody = createRequest(messages, stream = true, options)

        try {
            client.preparePost(chatEndpoint) {
                header("Authorization", "Bearer $apiKey")
                header("Accept", "text/event-stream")
                header("Cache-Control", "no-cache")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    handleHttpError(httpResponse)
                }

                val channel: ByteReadChannel = httpResponse.bodyAsChannel()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break

                    // 处理 SSE 数据行: "data: {JSON}"
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        if (data.isBlank()) continue

                        try {
                            val chunk = jsonParser.decodeFromString<OpenAiStreamChunk>(data)

                            if (chunk.error != null) {
                                throw ServerException("Stream API Error: ${chunk.error.message}")
                            }

                            // 1. 发送内容
                            val delta = chunk.choices?.firstOrNull()?.delta
                            if (delta != null) {
                                val content = delta.content ?: ""
                                val reasoning = delta.reasoningContent
                                val toolCalls = delta.toolCalls

                                if (content.isNotEmpty() || reasoning != null || toolCalls != null){
                                    emit(LlmResponse(
                                        content = content,
                                        usage = null,
                                        reasoningContent = reasoning,
                                        toolCalls = toolCalls
                                    ))
                                }
                            }


                            // 2. 发送 Usage (通常在最后一个 Chunk)
                            if (chunk.usage != null) {
                                emit(LlmResponse(content = "", usage = chunk.usage.toUsage()))
                            }

                        } catch (e: Exception) {
                            if (e is LlmException) throw e // 重新抛出已知的业务异常
                            logger.debug("JSON Parse Warning: {} | Data: {}", e.message, data)
                        }
                    } else if (line.trim().startsWith("{") && line.contains("\"error\"")) {
                        // 处理非 SSE 格式的错误 (部分厂商在发生错误时会直接返回 JSON 而非 Event Stream)
                        logger.error("Raw JSON Error in stream: {}", line)
                        throw UnknownLlmException("Raw JSON Error: $line")
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is LlmException) {
                logger.error("Stream Request Exception: {}", e.message)
            }
            throw e
        }
    }
}